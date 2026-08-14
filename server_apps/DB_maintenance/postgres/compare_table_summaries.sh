#!/bin/bash
#
# Join two table-summary snapshots (from snapshot_table_summary.sh) into a
# per-table before/after comparison: write a CSV artifact and print a summary.
#
# Each output row is one table with before_count, after_count, delta, and a
# status of:
#   added     - table present only in the after snapshot
#   removed   - table present only in the before snapshot
#   changed   - same table, but row count and/or content hash differ
#   unchanged - identical row count and content hash
#
# The snapshots are loaded into TEMP tables via client-side \copy, so this reads
# the CSVs from wherever this script runs (the build host), not the DB server,
# and does not touch the loaded data.
#
# Env:
#   DBNAME    database to run the comparison in (required; any live DB works)
#   PGBINDIR  path to psql (optional, defaults to PATH)
#   PGHOST    host (optional, defaults to libpq default / PGHOST in environment)
#   BEFORE    before snapshot CSV (required)
#   AFTER     after snapshot CSV (required)
#   OUT       comparison CSV to write (default: ./table_load_comparison.csv)
#
# Example:
#   DBNAME=zfin BEFORE=/tmp/before.csv AFTER=/tmp/after.csv OUT=/tmp/cmp.csv \
#     ./compare_table_summaries.sh

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
: "${BEFORE:?BEFORE csv must be set}"
: "${AFTER:?AFTER csv must be set}"
PSQL="${PGBINDIR:+$PGBINDIR/}psql"
OUT="${OUT:-./table_load_comparison.csv}"

mkdir -p "$(dirname "$OUT")"

# Paths are inlined into the heredoc by the shell because psql's \copy does not
# perform variable interpolation on its arguments.
"$PSQL" -d "$DBNAME" -v ON_ERROR_STOP=1 <<SQL
\pset pager off

CREATE TEMP TABLE snap_before (schema_name text, table_name text, row_count bigint, content_hash text);
CREATE TEMP TABLE snap_after  (schema_name text, table_name text, row_count bigint, content_hash text);

\copy snap_before FROM '$BEFORE' WITH (FORMAT csv, HEADER true)
\copy snap_after  FROM '$AFTER'  WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE comparison AS
SELECT
    coalesce(b.schema_name, a.schema_name) AS schema_name,
    coalesce(b.table_name,  a.table_name)  AS table_name,
    b.row_count                            AS before_count,
    a.row_count                            AS after_count,
    coalesce(a.row_count, 0) - coalesce(b.row_count, 0) AS delta,
    CASE
        WHEN b.table_name IS NULL THEN 'added'
        WHEN a.table_name IS NULL THEN 'removed'
        WHEN b.content_hash IS DISTINCT FROM a.content_hash THEN 'changed'
        ELSE 'unchanged'
    END AS status
FROM snap_before b
FULL JOIN snap_after a
  ON a.schema_name = b.schema_name
 AND a.table_name  = b.table_name;

\copy (SELECT schema_name, table_name, before_count, after_count, delta, status FROM comparison ORDER BY (status <> 'unchanged') DESC, abs(delta) DESC, schema_name, table_name) TO '$OUT' WITH (FORMAT csv, HEADER true)

\echo ''
\echo '=== Changed / added / removed tables ==='
SELECT schema_name || '.' || table_name AS "table",
       before_count, after_count, delta, status
FROM comparison
WHERE status <> 'unchanged'
ORDER BY abs(delta) DESC, schema_name, table_name;

\echo ''
\echo '=== Totals by status ==='
SELECT status,
       count(*)                AS tables,
       coalesce(sum(delta), 0) AS net_row_delta
FROM comparison
GROUP BY status
ORDER BY status;
SQL

echo "wrote comparison CSV: $OUT"
