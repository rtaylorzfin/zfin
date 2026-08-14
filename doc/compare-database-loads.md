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
| `docker/compare-loads-series.sh` | Backward daily-sweep driver: steps through many dated unloads and produces a churn report for each consecutive pair. |
| `server_apps/DB_maintenance/postgres/unchanged_since.sh` | Post-run: from the accumulated snapshots, reports how far back each table has been unchanged. |

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

## Exact row churn (rows_added / rows_removed)

`status = changed` only says a table's contents differ, not by how much: a table
that dropped 5,000 rows and inserted 5,000 different ones has `delta = 0` and, on
counts alone, is indistinguishable from an untouched table (or from one that
swapped a single row). To measure it exactly, run the snapshot with `HASHDIR`
set and pass both hash dirs to the comparator:

```bash
DBNAME=zfin OUTFILE=/tmp/before.csv HASHDIR=/tmp/before.hashes \
  server_apps/DB_maintenance/postgres/snapshot_table_summary.sh
# ...load the other dump...
DBNAME=zfin OUTFILE=/tmp/after.csv HASHDIR=/tmp/after.hashes \
  server_apps/DB_maintenance/postgres/snapshot_table_summary.sh

DBNAME=zfin BEFORE=/tmp/before.csv AFTER=/tmp/after.csv \
  BEFORE_HASHDIR=/tmp/before.hashes AFTER_HASHDIR=/tmp/after.hashes \
  OUT=/tmp/table_load_comparison.csv \
  server_apps/DB_maintenance/postgres/compare_table_summaries.sh
```

With `HASHDIR`, the snapshot also writes one sorted per-row-hash file per table
(`<HASHDIR>/<schema>.<table>.md5`); the comparator computes `rows_added` /
`rows_removed` for each `changed` table by a sorted-merge (`comm`) of the two
files — exact down to a single row. The report gains `rows_added` / `rows_removed`
columns and is ordered by total churn. Cost: ~33 bytes per row on disk (~3 GB for
a full ZFIN DB per snapshot), so it needs disk headroom the plain counts mode
does not. `compareLoads` (the single-pair gradle task) runs counts-only; the
daily-sweep harness (below) turns churn on.

## Daily sweep across many loads (`docker/compare-loads-series.sh`)

To compare a run of consecutive daily unloads (e.g. "how did each day change over
the last 10 days?"), the sweep driver steps **backward** through the dated dumps
and produces one churn report per consecutive pair. Each dump is loaded exactly
once: the snapshot of day D is the "before" of the (D → D+1) report and, one step
later, the "after" of the (D-1 → D) report.

It is host-side because the dumps usually live on a (read-only, sshfs) mount while
`gradle loaddb` only works inside the container, so it stages each dump locally,
`docker exec`s the load/snapshot/compare, and cleans up. Only one dump (~1.3 GB)
and two days of per-row hashes (~3 GB each) are on disk at a time, with a
free-space floor that aborts before filling the disk.

```bash
# 3-day trial (2 reports), newest dumps on the mount:
docker/compare-loads-series.sh --days 2
# full 10-report sweep:
docker/compare-loads-series.sh --days 10
# start from a specific date, or give an explicit newest->oldest list:
docker/compare-loads-series.sh --mount /mnt/cell --start 2026.08.13.1 --days 5
docker/compare-loads-series.sh --dates "2026.08.13.1 2026.08.12.1 2026.08.11.1"
docker/compare-loads-series.sh --help    # all flags
```

Config is via CLI flags (`--mount`, `--days`, `--start`, `--dates`, `--container`,
`--local-unloads`, `--ctr-unloads`, `--min-free-gb`, `--outrel`), each with an env
fallback; defaults suit the local `dazed` stack. Budget ~25–30 min per day (load +
hashing snapshot).

**Resumable.** Each day's snapshot CSV and hash dir are promoted atomically
(written to `.tmp`/`.partial`, renamed on success), and a re-run skips any day
whose snapshot already exists and any report already written. So an interrupted
sweep — or a later run with a larger `--days` — continues without re-loading
finished days; it picks up at the first missing day, regenerating a pruned hash
dir only if the boundary report still needs it.

Output lands under `build/db-load-comparison/series/`:
- `snapshots/<date>.csv` — per-date table summaries; **kept permanently** (tiny),
  they are the durable state the resume and the unchanged-since analysis build on.
- `reports/<older>_to_<newer>.csv` — per-pair churn reports.
- `index.csv` — per-day `changed/added/removed` counts and total rows_added/removed.
- `unchanged_since.csv` — see below.

### How far back has each table been unchanged? (`unchanged_since.sh`)

After the sweep (and standalone, any time), `unchanged_since.sh <snapshots_dir>`
walks the accumulated snapshots newest→oldest and reports, per table, the oldest
date its content still matches today:

```
blast_hit: unchanged since = 2026.08.01.1     # an older snapshot differs -> pinned
blast_hit: unchanged since <= 2026.08.03.1    # matched to the oldest snapshot -> lower bound
```

Output columns: `schema_name,table_name,latest_row_count,unchanged_since,bound`
(`bound` = `exact` for `=`, `at_least` for `<=`). Because it reads the retained
snapshots rather than the per-pair reports, it spans **every** snapshot you have
across all past sweeps — so `<=` bounds tighten into exact `=` dates as your
history deepens, with nothing rewritten.

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

- **Move out of `build.gradle`.** Move the `compareLoads` orchestration into the
  `zfin-utils` command so it lives with the other operational tooling rather than
  the build. The daily-sweep driver (`docker/compare-loads-series.sh`) could then
  call that instead of shelling into the container.
