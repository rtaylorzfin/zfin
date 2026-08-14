#!/bin/bash
#
# Snapshot every table's row count and a content hash to a single CSV.
#
# This is the lightweight sibling of dump_tables_deterministic.sh: instead of
# dumping full table contents, it records one row per table:
#
#   schema_name,table_name,row_count,content_hash
#
# content_hash is md5(string_agg(md5(row::text) ORDER BY md5(row::text))), so it
# is stable across runs against the same data and changes if any row's contents
# change even when the row count does not. Rows are ordered by their own hash
# rather than by a key, so the result is deterministic regardless of primary key
# and works for any column types (unlike ordering by columns, which fails for
# types with no btree ordering operator, e.g. json/xml, in a PK-less table).
#
# Take one snapshot before a load and one after, then diff / join the two CSVs
# (see compare_table_summaries.sql) for a per-table before/after summary.
#
# Env:
#   DBNAME    target database (required)
#   PGBINDIR  path to psql (optional, defaults to PATH)
#   PGHOST    host (optional, defaults to libpq default / PGHOST in environment)
#   OUTFILE   output CSV path (default: ./table_summary.csv)
#   SCHEMAS   comma-separated schema allowlist (default: all user schemas)
#   EXCLUDE   regex of fully-qualified table names to skip (default: none)
#
# Example:
#   DBNAME=zfin OUTFILE=/tmp/before.csv ./snapshot_table_summary.sh

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
PSQL="${PGBINDIR:+$PGBINDIR/}psql"
OUTFILE="${OUTFILE:-./table_summary.csv}"
SCHEMAS="${SCHEMAS:-}"
EXCLUDE="${EXCLUDE:-}"

mkdir -p "$(dirname "$OUTFILE")"

# Build schema filter clause for the table-listing query.
if [[ -n "$SCHEMAS" ]]; then
    schema_in=$(echo "$SCHEMAS" | sed "s/[^,]*/'&'/g")
    schema_filter="AND n.nspname IN ($schema_in)"
else
    schema_filter="AND n.nspname NOT IN ('pg_catalog','information_schema')
                   AND n.nspname NOT LIKE 'pg_%'"
fi

# List all base tables we should summarize.
tables=()
while IFS= read -r line; do
    tables+=("$line")
done < <(
    "$PSQL" -d "$DBNAME" -At -F$'\t' <<SQL
SELECT n.nspname, c.relname
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind = 'r'
  $schema_filter
ORDER BY 1, 2;
SQL
)

echo "Snapshotting ${#tables[@]} tables to $OUTFILE"
echo "schema_name,table_name,row_count,content_hash" > "$OUTFILE"

for row in "${tables[@]}"; do
    schema="${row%%$'\t'*}"
    table="${row##*$'\t'}"
    fqn="${schema}.${table}"

    if [[ -n "$EXCLUDE" && "$fqn" =~ $EXCLUDE ]]; then
        echo "skip   $fqn"
        continue
    fi

    # count + content hash in one pass. Each row is hashed, then the per-row
    # hashes are concatenated in hash order (deterministic, key-agnostic, works
    # for any column type) and hashed again. string_agg over zero rows is NULL,
    # coalesced to '' so empty tables get a stable empty hash.
    IFS=$'\t' read -r count hash < <(
        "$PSQL" -d "$DBNAME" -At -F$'\t' -v ON_ERROR_STOP=1 -c \
            "SELECT count(*), coalesce(md5(string_agg(h, '' ORDER BY h)), '') FROM (SELECT md5(t::text) AS h FROM ${schema}.${table} t) s"
    )

    echo "snap   $fqn  (rows=$count)"
    echo "${schema},${table},${count},${hash}" >> "$OUTFILE"
done

echo "done. ${#tables[@]} tables in $OUTFILE"
