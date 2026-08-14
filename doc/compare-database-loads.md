# Comparing two database loads (before/after table summary)

Given two dated DB unload files, load each, snapshot every table, and produce a
per-table before/after summary: row count before, row count after, delta, and a
`status` of `unchanged` / `changed` / `added` / `removed`. `changed` means the
row count was equal but the table's **content hash** differed, so content-only
changes still surface.

## Pieces

| File | Role |
|------|------|
| `server_apps/DB_maintenance/postgres/snapshot_table_summary.sh` | Snapshot one live DB: writes `schema,table,row_count,content_hash` CSV (one row per table). |
| `server_apps/DB_maintenance/postgres/compare_table_summaries.sh` | Join a before + after snapshot into the comparison CSV and print a summary. |
| `build.gradle` → `compareLoads` | Orchestrates: load before → snapshot → load after → snapshot → compare. |
| `server_apps/jenkins/jobs/Compare-Database-Loads/config.xml` | Jenkins job; selects the two backup files the same way `Load-Database` does. |

## Running it

### Via Jenkins (normal path)

1. Run **Compare-Database-Loads** with `LIST_ONLY=true` to print the available
   dated backups under `$DB_UNLOADS_PATH`.
2. Re-run with `BEFORE_PATH` and `AFTER_PATH` set to two of those paths.
3. The comparison CSVs are archived as build artifacts
   (`build/db-load-comparison/*.csv`); the changed/added/removed tables and
   per-status totals are printed to the console.

### Via gradle directly

```bash
gradle compareLoads \
  -Dbefore=/opt/zfin/unloads/db/2026.07.01.1/zfin.bak \
  -Dafter=/opt/zfin/unloads/db/2026.08.14.1/zfin.bak
```

**Destructive:** this drops and recreates the database twice (once per load) and
leaves the *after* dump loaded when it finishes. Run it against a throwaway / QA
database, not one you care about.

### Snapshot / compare by hand

The two building blocks also run standalone against any live DB:

```bash
DBNAME=zfin OUTFILE=/tmp/before.csv \
  server_apps/DB_maintenance/postgres/snapshot_table_summary.sh
# ...load the other dump...
DBNAME=zfin OUTFILE=/tmp/after.csv \
  server_apps/DB_maintenance/postgres/snapshot_table_summary.sh

DBNAME=zfin BEFORE=/tmp/before.csv AFTER=/tmp/after.csv \
  OUT=/tmp/table_load_comparison.csv \
  server_apps/DB_maintenance/postgres/compare_table_summaries.sh
```

`snapshot_table_summary.sh` honors `SCHEMAS` (comma-separated allowlist) and
`EXCLUDE` (regex of `schema.table` names to skip), same as its sibling
`dump_tables_deterministic.sh`.

## Known limitation: ID-churning loads

The content hash is computed over the full row (`md5(row::text)`), which includes
`zdb_id` and date/audit columns. A load that drops old rows and reloads
equivalent data with fresh IDs will therefore show as `changed` even though the
data is semantically the same.

**Future improvement:** per-table "meaningful-column" hashing that excludes
id/date metadata, following the pattern already used by
`lib/DB_functions/mgte_hash_full.sql` / `mgte_hash_min.sql`. That would let churn
tables be compared on their stable content instead of on regenerated IDs.

## Future TODO

- **Quantify row churn.** The `changed` status is a boolean: it says a table's
  contents differ but not by how much. A table that dropped 5,000 rows and
  inserted 5,000 different ones shows `delta = 0` and is indistinguishable here
  from an unchanged table (and from one that swapped a single row). Report
  per-table `rows_added` / `rows_removed` — the rows whose per-row hash is in the
  after snapshot but not the before, and vice versa — so churn is measured
  independent of the net row-count delta. This implies snapshotting per-row
  hashes (or diffing them on the fly for tables flagged `changed`) rather than
  only the single aggregate hash. Real loads make this concrete: a 2026.07.05 →
  2026.07.06 comparison flagged many warehouse / fast-search tables as `changed`
  with `delta = 0` because they are fully rebuilt with regenerated IDs each load.

- **Move out of `build.gradle`.** Move the `compareLoads` orchestration into the
  `zfin-utils` command so it lives with the other operational tooling rather than
  the build.
