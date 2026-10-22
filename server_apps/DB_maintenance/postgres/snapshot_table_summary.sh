#!/bin/bash
#
# Snapshot every table's row count and a content hash to a single CSV.
#
# This is the lightweight sibling of dump_tables_deterministic.sh: instead of
# dumping full table contents, it records one row per table:
#
#   schema_name,table_name,row_count,content_hash,excluded_cols,real_hash
#
# content_hash is md5 of the per-row hashes (md5(row::text)) concatenated in
# hash order. It is stable across runs against the same data and changes if any
# row's contents change even when the row count does not. Rows are ordered by
# their own hash rather than by a key, so the result is deterministic regardless
# of primary key and works for any column types (unlike ordering by columns,
# which fails for types with no btree ordering operator, e.g. json/xml, in a
# PK-less table). Ordering is forced to COLLATE "C" (byte order) so it matches a
# plain `comm` / `LC_ALL=C sort` on the per-row hash files (see HASHDIR below).
#
# real_hash is the same hash computed over the table's MEANINGFUL columns only,
# leaving out the churn columns that churn_columns.sh resolves (date/time columns,
# integer surrogate ids, plus churn_columns.tsv overrides). excluded_cols lists
# them (';'-separated). It is computed only for tables with at least one excluded
# column; for every other table it equals content_hash at no extra cost. A table
# rebuilt with fresh serial ids and load timestamps changes content_hash but keeps
# real_hash when its meaningful content is the same.
#
# Take one snapshot before a load and one after, then diff / join the two CSVs
# (see compare_table_summaries.sh) for a per-table before/after summary.
#
# HASHDIR (optional): if set, additionally write each table's sorted per-row
# hashes to <HASHDIR>/<schema>.<table>.md5 (one md5 per line, C-sorted). This is
# what lets compare_table_summaries.sh compute exact per-table row churn
# (rows_added / rows_removed) between two snapshots. When HASHDIR is set the
# content_hash and row_count are derived from that file, so they are identical to
# what the lean (HASHDIR-unset) path would produce -- the two modes are
# cross-consistent. Cost: ~33 bytes per row on disk (~3 GB for a full ZFIN DB).
# Tables with excluded columns also get <schema>.<table>.real.md5 (the per-row
# real hashes), used for real_rows_added / real_rows_removed.
#
# Env:
#   DBNAME    target database (required)
#   PGBINDIR  path to psql (optional, defaults to PATH)
#   PGHOST    host (optional, defaults to libpq default / PGHOST in environment)
#   OUTFILE   output CSV path (default: ./table_summary.csv)
#   HASHDIR   dir for per-row hash files (optional; enables exact churn)
#   SCHEMAS   comma-separated schema allowlist (default: all user schemas)
#   EXCLUDE   regex of fully-qualified table names to skip (default: none)
#
# Example:
#   DBNAME=zfin OUTFILE=/tmp/before.csv ./snapshot_table_summary.sh
#   DBNAME=zfin OUTFILE=/tmp/before.csv HASHDIR=/tmp/before.hashes ./snapshot_table_summary.sh

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
PSQL="${PGBINDIR:+$PGBINDIR/}psql"
OUTFILE="${OUTFILE:-./table_summary.csv}"
HASHDIR="${HASHDIR:-}"
SCHEMAS="${SCHEMAS:-}"
EXCLUDE="${EXCLUDE:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mkdir -p "$(dirname "$OUTFILE")"
[[ -n "$HASHDIR" ]] && mkdir -p "$HASHDIR"

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

# Excluded / meaningful columns per table (shared rules with drill-table-churn.sh).
declare -A EXCL MEAN
while IFS='|' read -r s t ex me; do
    EXCL["$s.$t"]="$ex"; MEAN["$s.$t"]="$me"
done < <(DBNAME="$DBNAME" SCHEMAS="$SCHEMAS" bash "$SCRIPT_DIR/churn_columns.sh")

# ';'-separated column names -> a quoted SQL ROW(...) expression.
row_expr() {
    local IFS=';' c out=""
    for c in $1; do out+="\"${c//\"/\"\"}\","; done
    echo "ROW(${out%,})"
}

echo "Snapshotting ${#tables[@]} tables to $OUTFILE"
echo "schema_name,table_name,row_count,content_hash,excluded_cols,real_hash" > "$OUTFILE"

for row in "${tables[@]}"; do
    schema="${row%%$'\t'*}"
    table="${row##*$'\t'}"
    fqn="${schema}.${table}"

    if [[ -n "$EXCLUDE" && "$fqn" =~ $EXCLUDE ]]; then
        echo "skip   $fqn"
        continue
    fi

    excl="${EXCL[$fqn]:-}"
    real_expr=""
    [[ -n "$excl" ]] && real_expr="md5($(row_expr "${MEAN[$fqn]}")::text)"

    if [[ -n "$HASHDIR" ]]; then
        # Dump the sorted per-row hashes to a file (enables exact churn), then
        # derive count + content_hash from it. `tr -d '\n'` reproduces the lean
        # path's separator-less concatenation, so the content_hash matches;
        # empty tables get '' the same way. Ordering is COLLATE "C" == byte order.
        hf="$HASHDIR/${schema}.${table}.md5"
        "$PSQL" -d "$DBNAME" -v ON_ERROR_STOP=1 -c \
            "\copy (SELECT md5(t::text) FROM ${schema}.${table} t ORDER BY md5(t::text) COLLATE \"C\") TO '${hf}'"
        count=$(wc -l < "$hf")
        if [[ "$count" -eq 0 ]]; then
            hash=""
        else
            hash=$(tr -d '\n' < "$hf" | md5sum | cut -d' ' -f1)
        fi
        real_hash="$hash"
        if [[ -n "$real_expr" ]]; then
            rf="$HASHDIR/${schema}.${table}.real.md5"
            "$PSQL" -d "$DBNAME" -v ON_ERROR_STOP=1 -c \
                "\copy (SELECT $real_expr FROM ${schema}.${table} ORDER BY $real_expr COLLATE \"C\") TO '${rf}'"
            if [[ "$count" -eq 0 ]]; then
                real_hash=""
            else
                real_hash=$(tr -d '\n' < "$rf" | md5sum | cut -d' ' -f1)
            fi
        fi
    else
        # count + content hash in one pass. Each row is hashed, then the per-row
        # hashes are concatenated in hash order (deterministic, key-agnostic,
        # works for any column type) and hashed again. string_agg over zero rows
        # is NULL, coalesced to '' so empty tables get a stable empty hash.
        # The real hash rides along in the same pass when the table has one.
        IFS=$'\t' read -r count hash real_hash < <(
            "$PSQL" -d "$DBNAME" -At -F$'\t' -v ON_ERROR_STOP=1 -c \
                "SELECT count(*), coalesce(md5(string_agg(h, '' ORDER BY h COLLATE \"C\")), ''),
                        coalesce(md5(string_agg(r, '' ORDER BY r COLLATE \"C\")), '')
                 FROM (SELECT md5(t::text) AS h, ${real_expr:-NULL::text} AS r FROM ${schema}.${table} t) s"
        )
        [[ -n "$real_expr" ]] || real_hash="$hash"
    fi

    echo "snap   $fqn  (rows=$count)"
    echo "${schema},${table},${count},${hash},${excl},${real_hash}" >> "$OUTFILE"
done

echo "done. ${#tables[@]} tables in $OUTFILE"
