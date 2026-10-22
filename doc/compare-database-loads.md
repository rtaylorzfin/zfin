# Comparing two database loads (before/after table summary)

Given two dated DB unload files, load each, snapshot every table, and produce a
per-table before/after summary: row count before, row count after, delta, and a
`status` of `unchanged` / `changed` / `added` / `removed`. `changed` means the
row count was equal but the table's **content hash** differed, so content-only
changes still surface.

## Pieces

| File | Role |
|------|------|
| `server_apps/DB_maintenance/postgres/snapshot_table_summary.sh` | Snapshot one live DB: writes `schema,table,row_count,content_hash,excluded_cols,real_hash` CSV (one row per table). |
| `server_apps/DB_maintenance/postgres/churn_columns.sh` + `churn_columns.tsv` | Decides which columns are churn (surrogate ids, timestamps) vs meaningful, per table. Shared by the snapshot's `real_hash` and `drill-table-churn.sh`. |
| `server_apps/DB_maintenance/postgres/compare_table_summaries.sh` | Join a before + after snapshot into the comparison CSV and print a summary. |
| `server_apps/DB_maintenance/postgres/compare_loads.sh` | Orchestrates: load before → snapshot → load after → snapshot → compare. `gradle compareLoads` is a thin wrapper around it. |
| `server_apps/DB_maintenance/postgres/restore_unload.sh` | Drop, recreate and restore one DB from an unload file (shared by `compare_loads.sh` and `gradle loadDatabase`). Only terminates sessions on that DB; fails if the closing `vacuum (analyze)` fails. |
| `server_apps/jenkins/jobs/Compare-Database-Loads/config.xml` | Jenkins job; selects the two backup files the same way `Load-Database` does. |
| `docker/compare-loads-series.sh` | Backward daily-sweep driver: steps through many dated unloads and produces a churn report for each consecutive pair. |
| `server_apps/DB_maintenance/postgres/unchanged_since.sh` | Post-run: from the accumulated snapshots, reports how far back each table has been unchanged. |
| `docker/drill-table-churn.sh` | Drill into ONE table between two loads: real (meaningful-column) churn vs raw churn. |
| `server_apps/DB_maintenance/postgres/daily_table_churn.sh` | Daily job: read-only snapshot of the live DB, compared with the previous day. Jenkins: `Table-Churn-Report_d`. |
| `server_apps/DB_maintenance/postgres/churn_dashboard.py` | Builds the dashboard (`index.html`) and today's unusual changes (`anomalies.html` / `.txt`) from a reports dir. |
| `server_apps/DB_maintenance/postgres/build_churn_index.sh` | Summarizes a reports dir into `index.csv` (shared by the sweep and the daily job). |

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

### Without gradle

```bash
DBNAME=zfin_scratch server_apps/DB_maintenance/postgres/compare_loads.sh \
  /opt/zfin/unloads/db/2026.07.01.1/zfin.bak \
  /opt/zfin/unloads/db/2026.08.14.1/zfin.bak \
  /tmp/db-load-comparison
```

Add `HASHES=1` to keep per-row hash files (~5 GB per side) so the comparison
also reports rows added/removed, raw and real.

**Destructive:** either way, this drops and recreates the database twice (once per load) and
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
# catch up an existing history: every dump newer than its newest snapshot
docker/compare-loads-series.sh --forward --outrel /mnt/research/zunloads/table_churn/dazed
docker/compare-loads-series.sh --help    # all flags
```

`--forward` keeps the newest day's hash files, so the next `--forward` run loads only the new
dumps; it exits 0 when there is nothing new. Config is via CLI flags (`--mount`, `--days`,
`--start`, `--dates`, `--forward`, `--container`,
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

### Drilling into one table: real churn vs surrogate/metadata churn

The sweep reports carry real churn per table (see below). Before that existed,
the sweep counted churn over the whole row only, so a table rebuilt each load with a
regenerated surrogate serial key and/or a load timestamp shows ~100% churn even
when its meaningful content barely moved. `drill-table-churn.sh` recomputes the
churn for a single table over its **meaningful columns only**, using the same
rules as the sweep's real hash (`churn_columns.sh`, see "Real churn" below), plus
any `--exclude` you add. Use it for a pair of dumps outside the sweep, or to try
out an exclusion before putting it in `churn_columns.tsv`.

```bash
docker/drill-table-churn.sh --table ui.publication_expression_display \
  --before 2026.08.12.1 --after 2026.08.13.1
```

It never runs `loaddb`: `pg_restore -t` pulls just that one table from each dump
into a throwaway scratch DB, and a set-difference (`EXCEPT ALL` over a per-row
hash) counts raw vs meaningful adds/removes. Example output shows the difference
starkly — a fast-search/warehouse table whose entire daily churn is noise:

```
=== ui.publication_expression_display   (2026.07.05.1 -> 2026.07.06.1) ===
  excluded as churn: ped_id created_at
  raw churn (whole row):   +475753 / -475753
  REAL churn (meaningful): +0 / -0
```

Give a dated unload dir (resolved on the mount) or a direct `.bak` path for
`--before`/`--after`. The table's column layout and PK are read from the live DB,
so the table must exist there. Each run copies both dumps again (~4 min per table).

## Real (meaningful-content) churn

Many tables are rebuilt each load with fresh serial ids and load timestamps, so
whole-row hashing shows them as ~100% churn even when their content is the same.
Every snapshot therefore also records a **real hash** over the table's meaningful
columns, and every report carries real churn next to the raw churn:

- **Which columns are left out** is decided by `churn_columns.sh` (one set of rules,
  shared with `drill-table-churn.sh`): date/time-typed columns; integer columns
  named `id` / `*_id` (ZFIN's real identifiers are text ZDB IDs); a sole integer
  primary key; plus per-table overrides in `churn_columns.tsv`. Add an `include`
  line there for an integer `*_id` that is meaningful (e.g. an FK into a table
  whose ids are stable), or an `exclude` line for churn the rules miss:
  ```
  public.genotype_figure_fast_search  include  gffs_psg_id,gffs_pg_id
  ```
  Check what a table resolves to with
  `DBNAME=zfindb server_apps/DB_maintenance/postgres/churn_columns.sh <schema.table>`.
- **Snapshot:** `excluded_cols` (`;`-separated) and `real_hash` columns. The real
  hash (and, in `HASHDIR` mode, a `<schema>.<table>.real.md5` per-row file) is
  computed only for tables with at least one excluded column; for the rest,
  `real_hash` equals `content_hash` at no extra cost.
- **Churn report** columns: `…, rows_added, rows_removed, real_rows_added,
  real_rows_removed, excluded_cols, status`, ordered by real churn. The sweep's
  `index.csv` gains `real_rows_added_total` / `real_rows_removed_total`.
- **Counts-only report** (`compareLoads`) gains `real_status`
  (`changed` / `unchanged` / `n/a`).
- **When real churn is blank / `n/a`:** one snapshot predates `real_hash` (the
  August 2026 series), or the two snapshots left out different columns because the
  rules or `churn_columns.tsv` changed in between. It is not guessed in either case.

Example (`ui.term_phenotype_display`, 2026.08.12.1 → 08.13.1): raw
+227,096 / −226,915, real **+217 / −36**, excluded `tpd_id;created_at`. It matches
`drill-table-churn.sh` on the same pair.

Real churn still drops integer FK columns into tables whose ids churn, so a row
re-pointed to a different parent is not detected; see `TODO.txt` ("re-pointed
links").

## Daily job (`Table-Churn-Report_d`)

Runs after every successful `Unload-Database_d` (a downstream `BuildTrigger`). It
snapshots the **live** database with `daily_table_churn.sh` and compares it with
the previous day's snapshot. It never restores or loads anything, so it is safe to
run against production.

**Read-only.** The job connects as a read-only role and refuses to run if the role
can write (superuser, `pg_write_all_data`, or write access to `zdb_active_data`).
The snapshot itself also runs with `default_transaction_read_only=on`. The compare
step uses TEMP tables, which a read-only transaction does not allow, so it runs
without that setting and only touches its own temp tables. One-time setup, as a DB
admin (PostgreSQL 14+):

```sql
CREATE ROLE zfin_churn_ro LOGIN PASSWORD '...';
GRANT pg_read_all_data TO zfin_churn_ro;
```

and put the password in the Jenkins user's `~/.pgpass`. The role name is the job's
`CHURN_PGUSER` parameter.

**Storage** is split so the big files can live on cheaper disk:

| Parameter | Holds | Size | Kept |
|---|---|---|---|
| `CHURN_DATA_DIR` (default `/research/zunloads/table_churn/$INSTANCE`) | `snapshots/`, `reports/`, `index.csv`, `unchanged_since.csv` | ~100 KB/day snapshot + a few hundred KB/day of reports | forever |
| `CHURN_HASH_DIR` (default `$CHURN_DATA_DIR/hashes`) | per-row hash files | ~5 GB/day | newest day only (tomorrow's "before") |

While the snapshot runs, two days of hash files exist at once (~10 GB), so the job
checks for `MIN_FREE_GB` (12) free in `CHURN_HASH_DIR` first.

**Behavior worth knowing:**
- Each day is named like its unload dir (`YYYY.MM.DD.N`, the newest under
  `/research/zunloads/databases/$INSTANCE`). Re-running a day reuses its snapshot
  (`FORCE=1` re-takes it).
- The snapshot sees production a few minutes after the dump; a change made in
  between shows up in the next day's report.
- `external_resource.*` is skipped (`EXCLUDE`), matching the unload, which leaves
  out that schema's data.
- If the previous day's hash files are missing (first run, a skipped day), that
  day's report is counts-only; full churn resumes the next day.
- The build archives the newest report, `index.csv`, `unchanged_since.csv` and the
  dashboard, publishes the dashboard as **Table Churn Dashboard**, and puts today's
  unusual changes at the top of the email.

### Dashboard and unusual changes

`churn_dashboard.py` (Python 3, standard library only; no JavaScript or external
resources) writes `dashboard/index.html`: daily real vs raw totals, a heatmap of real
churn by table and day (share of the table that changed; hover for numbers; raw-only
days hatched; a bar marks missing days), and a row-count trend per table over every
snapshot kept.

A change is **unusual** when a table's real churn is at least `FACTOR` (5) times its
own median over the previous `BASELINE` (28) days and at least `MIN_ROWS` (1,000)
rows above it, or when a table appears or disappears (ignoring `*_old_<timestamp>`
leftovers, `IGNORE_TABLES`). Judging each table against its own history keeps daily
rebuilds from drowning out a real spike. Replayed on the 30-day sweep it flags every
Friday NCBI load, the 09.29 `db_link` swap and the `term_phenotype_display`
array-order days.

The page also lists every table **added or removed** during the period (with a count
of the ignored `*_old_*` leftovers), and gives each heatmap table its **average real
churn per day** over the period.

Below the heatmap (which shows the top 40), a collapsed **All tables** section lists
every table with its row count, how many days it changed, its total real churn and the
last day it changed; tables with no change in the period come last, with how long the
whole table has been identical (from `unchanged_since.csv`). For a full-year page:
`FROM=<first day> churn_dashboard.py <data_dir> <data_dir>/dashboard-year`.

A **Row counts by date** section links `row_counts.csv` (written next to `index.html`):
every table's row count at every snapshot on disk, tables down, snapshot dates across
(like the heatmap). For the page's own period the same grid can be opened on the page
(cells that differ from the snapshot to their left are highlighted); above 40,000 cells, e.g. a full year, it is
CSV-only.

**What it reads:** only the stored CSVs: `reports/*.csv` (the per-pair churn reports)
for churn, and `snapshots/*.csv` for row counts. It never touches the database or the
hash files, so it can be rebuilt any time for any period those CSVs cover. The daily
job keeps both forever.

**A report for any period:** `FROM` / `TO` (day labels) pick the days shown; reports
before `FROM` still feed each table's "usual" baseline. `TO` alone shows the page as it
looked that day (`AS_OF` is an alias).

```bash
FROM=2026.09.14.1 TO=2026.10.13.1 python3 server_apps/DB_maintenance/postgres/churn_dashboard.py \
  build/db-load-comparison/series /tmp/churn-dashboard
```

For a period with no reports yet (before the daily job started, say), make them first
with the sweep, e.g. `docker/compare-loads-series.sh --start 2026.07.31.1 --days 30`,
then point the dashboard at its output dir.

Run it by hand (e.g. on a dev box, with `REQUIRE_READONLY_ROLE=0` only when
testing as a writable role):

```bash
DBNAME=zfindb PGUSER=zfin_churn_ro CHURN_DATA_DIR=/research/zunloads/table_churn/zfinprod \
  CHURN_HASH_DIR=/scratch/table_churn_hashes \
  server_apps/DB_maintenance/postgres/daily_table_churn.sh
```

## Future TODO

- **Sweep reuse.** `compareLoads` now lives in `compare_loads.sh` /
  `restore_unload.sh`. The daily-sweep driver (`docker/compare-loads-series.sh`)
  still has its own load step; it could call `restore_unload.sh` instead.
