# Surrogate-ID churn in regenerated tables — audit + stabilization plan

_Audit 2026-08-17, off the daily-sweep churn reports (`build/db-load-comparison/series/`)
against the 2026.08.12→08.13 pair. Companion to `doc/compare-database-loads.md` (the
sweep + `drill-table-churn.sh` tooling that surfaced this). Static source review only;
the DB-heavy drill-down/FK confirmation is still to run (see "Open items")._

## Problem

Many tables are fully regenerated each load (rebuild-and-swap, or delete-and-reinsert),
so their **surrogate serial ids get reassigned even when the content is identical**. In
the before/after sweep this reads as ~100% churn: e.g. `pheno_term_fast_search`
+4,264,438 / −4,261,742 (net +2,696) and `ui.publication_expression_display`
+476,974 / −476,706 (net +268). `drill-table-churn.sh` confirmed the pattern on
`ui.publication_expression_display` 07.05→07.06: raw +475,753/−475,753 → **REAL +0/−0**
(the entire daily churn was `ped_id` + `created_at` noise).

Goal: stop regenerating ids for unchanged content, so the sweep (and any downstream
consumer) sees only *real* change.

## Key finding: the fix already exists in-tree (ZFIN-10350)

The three phenotype-mart base tables were **already converted** from rebuild-and-swap to
an **incremental natural-key apply** (delete-gone / update-changed / insert-new), in
`source/org/zfin/db/postGmakePostloaddb/1183/migrations/0030..0060-ZFIN-10350-*` +
`lib/DB_functions/regen_phenotype_mart*.sql`. Their surrogate ids (`psg_id`, `pg_id`) are
now **stable across runs**. This is the pattern to extend; `pheno_term_fast_search`'s own
regen header already carries a `TODO(ZFIN-10350)` to do the same.

## The churn splits into four buckets

### 1. Build artifacts / staging — should not be in the unload at all (cheapest win)
Transient or backup tables the webapp never reads; excluding them from the unload kills
their churn outright *and* shrinks the dumps (ties into the slim-DB / classify-tables
work — see [[preloaded-slim-db]]).
- `xpatfs_old` and the `*_old_<timestamp>_<rand>` tables — leftover previous generations
  from rename-swap rebuilds.
- `*_temp` staging tables (`phenotype_observation_generated_temp`,
  `phenotype_generated_curated_mapping_temp`, `phenotype_source_generated_temp`) —
  TRUNCATE+rebuild inputs to the incremental apply, not swapped in.
- `*_bkup` — already being dropped (`1183/migrations/0050`).

### 2. Already stable — do not touch (ZFIN-10350)
`phenotype_observation_generated` (`psg_id`), `phenotype_source_generated` (`pg_id`),
`phenotype_generated_curated_mapping` (natural key / ctid, no surrogate). Only their
`_temp` siblings churn → bucket 1.

### 3. Junction tables — inherit their parent's churn
No surrogate id of their own; they store the display tables' churning ids. Fixing the
parent fixes these automatically.
- `ui.phenotype_warehouse_association`, `ui.phenotype_zfin_association` → store `tpd_id`
- `ui.chebi_phenotype_warehouse_association` → stores `cpd_id`

### 4. Real targets — still full rebuild / delete+reinsert

| table | surrogate id | rebuild style | id exposed to client/URL? |
|---|---|---|---|
| `ui.term_phenotype_display` | `tpd_id` | delete+reinsert | **YES** — `TermAPIController` `/api/ontology/{termID}/phenotype` (`FishStatistics.java:38`) |
| `ui.chebi_phenotype_display` | `cpd_id` | delete+reinsert | **YES** — `/api/ontology/{termID}/phenotype-chebi` (`ChebiPhenotypeDisplay.java:35`) |
| `ui.publication_expression_display` | `ped_id` | delete+reinsert | annotated `@JsonView`, but the serving path (`FigureViewService.getExpressionTableRows`) builds rows fresh → the churning `ped_id` likely does **not** leak (confirm) |
| `pheno_term_fast_search` | `ptfs_pk_id` | rename-swap (`nextval` restored after CTAS) | no |
| `expression_term_fast_search` | `etfs_pk_id` | double-rename (`xpatfs_old` is its leftover) | no |
| `genotype_figure_fast_search` | `gffs_serial_id` | rename-swap | no |
| `sequence_feature_chromosome_location_generated` | `sfclg_pk_id` | partial delete+insert (`'other map location'`,`'General Load'`) | no |

## Approaches considered

- **A. Content-hash id** (`id = hash(meaningful cols)`): stateless, deterministic, no
  extra state. But an *edited* row gets a *new* id — which **breaks URL/client-exposed
  ids** (bucket 4's `tpd_id`/`cpd_id`) on any content change. OK only for internal,
  unexposed tables as a fallback. 64-bit `hashtextextended` needs a UNIQUE guard; md5/uuid
  is collision-proof but a wider key.
- **B. Persistent content→id mapping table**: reuse serials via a stored hash→id map.
  Works, but it's approach A plus per-table persistent state + upsert logic + the map
  itself to maintain. Only worth it if small reused integers are specifically required.
- **C. Incremental natural-key apply (ZFIN-10350 pattern)** — *recommended*. Keeps compact
  serials and **preserves the id across content edits**, which is exactly what the
  URL-exposed tables need. Cost: define a real natural key per table + rewrite regen as a
  merge. Already proven in-tree.
- Rejected: deterministic `ORDER BY`-assigned serials — one mid-sequence insert reshuffles
  every later id.

## Recommended plan (priority order)

1. **Exclude bucket 1** (`_old`/`_temp`/`_bkup`) from the unload. Cheap; kills that churn +
   shrinks dumps. Coordinate with the slim-DB table classification.
2. **Incremental-apply the exposed display tables** `ui.term_phenotype_display` (`tpd_id`)
   and `ui.chebi_phenotype_display` (`cpd_id`) — highest value because their ids are in
   client URLs; this also stabilizes bucket 3 junction tables.
3. **Incremental-apply the internal fast_search tables**, `pheno_term_fast_search` first
   (TODO already filed), then `expression_term_fast_search`, `genotype_figure_fast_search`,
   `sequence_feature_chromosome_location_generated`.
4. Content-hash id (A) only as a fallback for internal/unexposed tables where the merge is
   disproportionate.

## Design: incremental apply for `ui.term_phenotype_display` (and `chebi_phenotype_display`)

_Written 2026-10-16. Implemented 2026-10-17 (8974e8b62e term, ed15721394 chebi): merge SQL tested
on scratch copies of the 08.12/08.13 tables; an end-to-end indexer run is still to do. Chebi's key is
(fish, experiment, term, has_chebi_in_phenotype), with a NULL term (about 10% of rows) matched via
coalesce._

**How it is built today.** `TermPhenotypeIndexer` (a `UiIndexer`) computes every row in
memory (`inputOutput()`), then `UiIndexer.index()` calls `cleanUiTables()` (deletes
`term_phenotype_display`, `phenotype_zfin_association`, `phenotype_warehouse_association`) and
Hibernate re-inserts everything. `FishStatistics.id` is `@GeneratedValue(IDENTITY)`, so every
row gets a new `tpd_id` every run, and both association tables, which store `tpd_id`, churn
with it.

**Natural key.** `(tpd_term_zdb_id, tpd_fish_zdb_id)`: unique on real data (228,817 keys for
228,817 rows, 10.09 dump).

**Plan.** Keep `inputOutput()` as is; replace "delete all + insert all" for this indexer with
a merge on the natural key:
1. Write the computed rows to a staging table (`ui.term_phenotype_display_stage`, same columns
   without `tpd_id`; a COPY or batched insert, no Hibernate entity graph), and the association
   rows as `(term, fish, gene)` / `(term, fish, warehouse id)`.
2. In one transaction:
   - `UPDATE` rows whose key exists and whose content differs (the meaningful columns:
     counts, single fig/pub, the three `*_search` strings, `tpd_ancestor_term_ids`, which is
     sorted since f18ab5a335). Unchanged rows are not touched, so their `tpd_id` and
     `created_at` stay.
   - `DELETE` rows whose key is gone, plus their association rows.
   - `INSERT` new keys (fresh `tpd_id`).
   - Association tables: map the staged keys to `tpd_id`, then delete pairs that are gone and
     insert new pairs. They have no surrogate id of their own, so unchanged pairs simply stay.
3. Drop the staging tables.

Expected result: `term_phenotype_display` raw churn falls to about its real churn (tens of rows a
day instead of ~229k), and both association tables (~355k and ~233k rows/day today) stop
churning, since `tpd_id` is stable. Same shape for `ChebiPhenotypeIndexer`
(`chebi_phenotype_display.cpd_id` + `chebi_phenotype_warehouse_association`).

**Warehouse ids are stable (checked 2026-10-16).** `pwa_phenotype_warehouse_id` points at
`PhenotypeStatementWarehouse`, i.e. `phenotype_observation_generated.psg_id`, which ZFIN-10350
already stabilized: over 09.13-10.13 (279,573 rows) its raw churn equals its real churn, about
1,371 rows/day, so the warehouse association will stop churning once `tpd_id` is stable. Still to
confirm: nothing else writes these tables between runs (the indexer assumes it owns them).

**Acceptance.** Run the indexer twice on the same data: the second run should change 0 rows. In the
daily sweep, the table's raw churn should drop to about its real churn and stay there.

## Status 2026-10-17

Converted to incremental applies (each tested on scratch restores of two consecutive dumps; an
end-to-end regen run is still to do): `ui.term_phenotype_display` (8974e8b62e),
`ui.chebi_phenotype_display` (ed15721394), `pheno_term_fast_search` (98a31a9576),
`expression_term_fast_search` (d194fbc72d), `genotype_figure_fast_search` full mode (f805b17625).
Not converted: `sequence_feature_chromosome_location_generated`. Its daily rewrite stopped after
09.08, the rest are occasional per-source reloads, and `sfclg_pk_id` is internal.

## Open items / caveats

- **DB-heavy confirmation not yet run** (was deferred to avoid contending with the running
  sweep): `drill-table-churn.sh` across all 15 to quantify real-vs-surrogate churn, and a
  live `pg_constraint` FK-reference check per id. Queue once the sweep frees the DB.
- **`fish_components`** (resolved 2026-10-16): regen is `lib/DB_functions/regen_fish_components.sql`
  (`delete from fish_components` + re-insert). The table predates the migrations (owner `informix`), so
  its DDL is not in the repo; from the 2026.01.14 dump (`pg_restore --schema-only -t fish_components`):
  ```sql
  CREATE TABLE public.fish_components (
      fc_pk_id bigint NOT NULL,          -- DEFAULT nextval('fish_components_fc_pk_id_seq')
      fc_fish_zdb_id text NOT NULL,
      fc_genotype_zdb_id text NOT NULL,
      fc_fish_name text NOT NULL,
      fc_affector_zdb_id text NOT NULL,
      fc_gene_zdb_id text,
      fc_construct_zdb_id text
  );
  ```
  No primary key or index in the dump. `fc_pk_id` is read nowhere: the one Java use
  (`HibernateMutantRepository`, feature page) joins on `fc_fish_zdb_id` / `fc_gene_zdb_id` /
  `fc_affector_zdb_id`. So its churn is harmless and needs no stabilizing; the real hash already
  excludes it. If anything, the column could simply be dropped.
- **`ui.publication_expression_display`**: confirm whether `ped_id` actually reaches any
  client (serving path appears to construct rows fresh, not load the table).
- URL-exposure is the deciding constraint between approach A and C — re-verify per table
  before implementing, don't rely on the id being "internal".
