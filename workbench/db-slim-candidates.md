# Slimming the preloaded dev DB (working analysis)

## Why size matters: the preloaded model makes a FULL COPY per feature (not CoW)

The preloaded image bakes the whole PGDATA (and Solr index) into its layers. But
postgres/solr declare `VOLUME`, so on a feature stack's first `up`, Docker **seeds
the per-project named volume (`<proj>_pg_data` / `<proj>_solr_var`) by COPYING** the
image's baked data into it. This is a plain filesystem copy at volume-create time --
**NOT copy-on-write**, and the running DB then uses that copy, not the shared image
layer. So disk cost is roughly:

```
  preloaded images (once)     ~33G db + ~10G solr
  + per feature stack         ~26G db + ~10G solr   (a full, independent copy)
```

Three feature stacks ≈ 100G+. This is why the `--slim` levers below matter (they
shrink every per-feature copy), and it's the trade the preloaded approach made:
instant boot + isolation on plain Docker, paid for in disk. True per-feature CoW
clones would need a CoW filesystem (ZFS/btrfs/reflink) -- see TODO.txt.

## Slimming the image (working analysis)

Goal: shrink the preloaded DB image (`base/` ≈16GB + `pg_wal/` ≈8.8GB = ~24GB
PGDATA on `dazed`) and speed up `getdb`/`loaddb`. Two INDEPENDENT levers:

- **Lever A — WAL (~8.8GB): the biggest, safest win.**
- **Lever B — jobs-only table data (~3.8GB): smaller, needs care.**

TOAST is a non-issue (365MB total). VACUUM FULL not worth it -- **measured**, see below.

## Lever A — drop WAL from the snapshot (~8.8GB)

`pg_wal/` holds **561 × 16MB segments = 8.8GB**, retained because the stack runs
`-c max_wal_size=10GB` (good for load throughput, useless in a frozen snapshot).
Recycled WAL is near-zero data, so it compresses away (small push size) but sits
at full size **uncompressed on disk** — this is most of the disk-vs-content gap.

Fix: compact WAL at capture time. You can't shrink it on the running dev PG
(`ALTER SYSTEM` is overridden by the compose `-c max_wal_size`). Instead, after
`docker compose stop db`, briefly run a throwaway postgres on the same volume
with tiny `max_wal_size`/`min_wal_size` + `CHECKPOINT` + clean stop (recycles WAL
down to ~64–128MB), THEN tar. Keep `max_wal_size=10GB` for normal dev speed.

## Lever B — table data classification (webapp vs jobs-only)

`inbound FKs` = other tables FK'ing into this one (truncate/cascade risk).
Verdict from code trace (ORM mapping + call sites): **WEBAPP** = read on a
request path (keep); **JOBS-ONLY** = only batch/CLI/SQL (safe to empty for a
webapp-focused image).

| Table | Size | FKs | Verdict | Notes / regen |
|---|---|---|---|---|
| gff3_ncbi | 1252 MB | 1* | **JOBS-ONLY** | NCBIGff3Processor / `Load-NCBI-GFF3-File`; webapp reads the derived chrom-location table instead |
| gff3_ncbi_attribute | 1144 MB | 0 | **JOBS-ONLY** | same NCBI GFF3 loader |
| expression_search_anatomy_generated | 931 MB | 0 | **JOBS-ONLY** | no ORM; only read by the Solr DIH at index build (`regenExpressionSearchAnatomy.sql`) |
| gff3 | 292 MB | 0 | **JOBS-ONLY** | GFF3/GBrowse load SQL; JBrowse reads files, not this table |
| feature_stats_old | 175 MB | 0 | **JOBS-ONLY** | stale rename artifact from `regen_feature_term_fast_search.sql`; no Java refs |
| all_term_contains | 1022 MB | 0 | WEBAPP | ontology/expression/phenotype repos read it (OntologyTermController etc.) |
| record_attribution | 939 MB | 0 | WEBAPP | core provenance, heavy |
| zdb_active_data | 886 MB | 81 | WEBAPP | core ID registry, heavy |
| updates | 557 MB | 0 | WEBAPP | history shown via `/updates` + audit DetailsController |
| blast_hit | 554 MB | 0 | WEBAPP | Reno curation UI |
| pheno_term_fast_search | 497 MB | 0 | WEBAPP | HibernateMutantRepository phenotype-by-term |
| snp_download | 379 MB | 0 | WEBAPP | CloneViewController / MarkerNotesController |
| snp_download_attribution | 237 MB | 0 | WEBAPP | same clone/pub SNP paths |
| sequence_feature_chromosome_location_generated | 224 MB | 0 | WEBAPP | GeneView/JBrowse/mapping controllers |
| feature_stats | 126 MB | — | WEBAPP | AO-statistics controllers |

\* `gff3_ncbi`'s 1 inbound FK is from `gff3_ncbi_attribute` (also dropped) — truncate child first or CASCADE.

Also droppable: `external_resource.*` (already excluded from the prod unload).
The `ui.*` schema is NOT fully droppable — `ui.publication_expression_display`
and `ui.term_phenotype_display` are read by webapp API controllers.

### Safe jobs-only drop set (~3.8GB)
`gff3`, `gff3_ncbi`, `gff3_ncbi_attribute`, `expression_search_anatomy_generated`,
`feature_stats_old`.

Caveat: `expression_search_anatomy_generated` is consumed by a **full Solr
reindex**. The preloaded Solr image already has the index baked, so a webapp dev
won't need it — but document `regenExpressionSearchAnatomy.sql` for anyone who
does a from-scratch reindex in their feature stack.

## Lever C -- VACUUM FULL: MEASURED, NOT WORTH IT (2026-08-28)

Spiked against `zfin-db-preloaded:dev` in a throwaway container seeded from the image
(`vacuumdb --full --analyze --jobs=4`), so these are numbers, not estimates:

| | `base/` on disk | indexes | wall clock |
|---|---|---|---|
| before | 14.84 GiB | 5183 MB | -- |
| after `VACUUM FULL` | 14.80 GiB | 5170 MB | 93 s |
| **reclaimed** | **~44 MB (0.3%)** | 13 MB | |

**Why it can't help, and why no future tuning changes that:** bloat is a property of a
long-running *mutable* database -- it accrues from UPDATE/DELETE churn leaving dead tuples
and fragmented pages. This snapshot is born from a bulk `loaddb`/restore, so it is already
compact by construction. The dead-tuple census says the same thing: **17,044 dead rows
against 90.8M live (0.02%)**. There is no bloat to reclaim, so the 15GB is genuine data +
indexes. Don't re-litigate this one without new evidence that the snapshot's provenance
has changed.

One mechanical note if it's ever revisited: VACUUM FULL generates WAL heavily (pg_wal went
0.02 GiB -> ~1 GiB), so it would have to run INSIDE the existing throwaway-postgres window,
*before* `pg_resetwal` -- which discards that WAL anyway.

## Lever B is measured too -- and it is the one that pays (2026-08-28)

Same container, immediately after the VACUUM FULL arm. Truncating exactly the documented
safe jobs-only set (`gff3`, `gff3_ncbi`, `gff3_ncbi_attribute`,
`expression_search_anatomy_generated`, `feature_stats_old`):

| | `base/` on disk | wall clock |
|---|---|---|
| before | 14.78 GiB | -- |
| after TRUNCATE | 11.10 GiB | **0.3 s** |
| **reclaimed** | **3.70 GiB (25%)** | |

So Lever B reclaims **~90x more than VACUUM FULL in 1/300th of the time**, and it matches
the ~3.8GB this doc predicted. It also compounds: the per-feature copy is a full copy, so
25% comes off *every* feature stack's disk AND its seed time (measured: seeding one
feature's `pg_data` volume from the image takes **1m26s**).

## Mechanism (as built)

`docker/utils/build-preloaded.groovy` trims every snapshot before capture:
1. **WAL (Lever A) — ALWAYS, no flag.** Recycled WAL is dead weight in a frozen
   snapshot (near-zero data, full ~16MB/segment on disk), so `pg_resetwal` collapses
   it on every build. Safe — the copy is started fresh in every feature.
2. **Jobs-only tables (Lever B) — `--slim`. NOT IMPLEMENTED YET.** This section described
   the intended design; as of 2026-08-28 `BuildPreloaded.groovy` parses only
   `--project/--tag/--app/--caches/--keep-tarballs`, and the only `[trim]` steps it runs are
   the throwaway-postgres start and `pg_resetwal`. The measurement above says this is the
   highest-value thing left on the list (3.70 GiB, 0.3 s, one TRUNCATE ... CASCADE).

Both run in one throwaway-postgres session while the real db is stopped; the clean
stop is `pg_resetwal`'s required precondition.

Do NOT change `unload_production.sh` — keep the shared prod dump complete; only
the local image is lean. Validate a `--slim` image boots and common pages render
before trusting it.

Combined ceiling: ~8.8GB (WAL, always) + ~3.8GB (tables, `--slim`) ≈ **~12.6GB off a
~24GB PGDATA**. The WAL half is done: PGDATA on the current `dev` image is 14.85 GiB with
pg_wal already down to 0.02 GiB. Lever B's 3.70 GiB is the remaining, measured, unclaimed
half -- it would take the image from ~19.7GB to ~16GB and each feature's copy from ~16GB
to ~12.3GB.
