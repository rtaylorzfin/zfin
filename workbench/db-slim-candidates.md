# Slimming the preloaded dev DB (working analysis)

Goal: shrink the preloaded DB image (`base/` ≈16GB + `pg_wal/` ≈8.8GB = ~24GB
PGDATA on `dazed`) and speed up `getdb`/`loaddb`. Two INDEPENDENT levers:

- **Lever A — WAL (~8.8GB): the biggest, safest win.**
- **Lever B — jobs-only table data (~3.8GB): smaller, needs care.**

TOAST is a non-issue (365MB total). VACUUM FULL not worth it (dead-tuple counts modest).

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

## Recommended mechanism

Add an optional `--slim` mode to `docker/utils/build-preloaded.groovy` that, before capture:
1. compacts WAL (Lever A), and
2. `TRUNCATE`s the jobs-only drop set (Lever B).

Do NOT change `unload_production.sh` — keep the shared prod dump complete; only
the local image is lean. Consider two tags: `…-preloaded` (full) /
`…-preloaded-lean`. Validate the lean image boots and common pages render before
trusting it.

Combined ceiling: ~8.8GB (WAL) + ~3.8GB (tables) ≈ **~12.6GB off a ~24GB PGDATA**.
