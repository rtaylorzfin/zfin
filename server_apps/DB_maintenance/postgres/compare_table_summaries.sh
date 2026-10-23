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
# The count summaries are loaded into TEMP tables via client-side \copy, so this
# reads the CSVs from wherever this script runs (the build host), not the DB
# server, and does not touch the loaded data.
#
# Exact row churn (optional): if BEFORE_HASHDIR and AFTER_HASHDIR are given (the
# per-row hash dirs written by snapshot_table_summary.sh's HASHDIR mode), the
# report gains rows_added / rows_removed columns -- the exact number of rows
# whose content is in one snapshot but not the other, computed by a sorted-merge
# (comm) of the two per-row hash files. This distinguishes a table that is truly
# unchanged from one that dropped N rows and inserted N different rows (which has
# delta 0 but large churn), down to a single row. Without the hash dirs the
# report is counts + a changed flag only.
#
# Real (meaningful-content) churn: snapshots carry excluded_cols + real_hash (see
# snapshot_table_summary.sh), a hash that leaves out surrogate ids and load
# timestamps. The churn report adds real_rows_added / real_rows_removed (from the
# <t>.real.md5 files, or equal to the raw churn when nothing is excluded); the
# counts-only report adds real_status (changed/unchanged). Real churn is left
# blank / 'n/a' when it cannot be compared honestly: a snapshot from before
# real_hash existed, or the two sides excluded different columns (the rules or
# churn_columns.tsv changed between them). excluded_cols is carried into the
# report so you can see what was left out.
#
# Env:
#   DBNAME          database to run the comparison in (required; any live DB)
#   PGBINDIR        path to psql (optional, defaults to PATH)
#   PGHOST          host (optional, defaults to libpq default / PGHOST in env)
#   BEFORE          before snapshot CSV (required)
#   AFTER           after snapshot CSV (required)
#   OUT             comparison CSV to write (default: ./table_load_comparison.csv)
#   BEFORE_HASHDIR  before per-row hash dir (optional; enables exact churn)
#   AFTER_HASHDIR   after per-row hash dir (optional; enables exact churn)
#
# Example:
#   DBNAME=zfin BEFORE=/tmp/b.csv AFTER=/tmp/a.csv OUT=/tmp/cmp.csv \
#     ./compare_table_summaries.sh
#   DBNAME=zfin BEFORE=/tmp/b.csv AFTER=/tmp/a.csv OUT=/tmp/cmp.csv \
#     BEFORE_HASHDIR=/tmp/b.hashes AFTER_HASHDIR=/tmp/a.hashes \
#     ./compare_table_summaries.sh

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
: "${BEFORE:?BEFORE csv must be set}"
: "${AFTER:?AFTER csv must be set}"
PSQL="${PGBINDIR:+$PGBINDIR/}psql"
OUT="${OUT:-./table_load_comparison.csv}"
BEFORE_HASHDIR="${BEFORE_HASHDIR:-}"
AFTER_HASHDIR="${AFTER_HASHDIR:-}"

CHURN=0
if [[ -n "$BEFORE_HASHDIR" && -n "$AFTER_HASHDIR" ]]; then
    CHURN=1
fi

mkdir -p "$(dirname "$OUT")"
base=$(mktemp)
trap 'rm -f "$base"' EXIT

# Snapshots written before real_hash existed have only the first four columns;
# load those with real churn marked unavailable.
snap_cols() {
    if head -1 "$1" | grep -q ',real_hash'; then
        echo "schema_name, table_name, row_count, content_hash, excluded_cols, real_hash"
    else
        echo "schema_name, table_name, row_count, content_hash"
    fi
}
before_cols=$(snap_cols "$BEFORE"); after_cols=$(snap_cols "$AFTER")
before_real=false; [[ "$before_cols" == *real_hash ]] && before_real=true
after_real=false;  [[ "$after_cols"  == *real_hash ]] && after_real=true

# Build the base comparison (counts + status) in psql. Paths are inlined into
# the heredoc by the shell because psql's \copy does not interpolate variables.
"$PSQL" -d "$DBNAME" -v ON_ERROR_STOP=1 >/dev/null <<SQL
\pset pager off

CREATE TEMP TABLE snap_before (schema_name text, table_name text, row_count bigint, content_hash text,
                               excluded_cols text, real_hash text, has_real boolean DEFAULT $before_real);
CREATE TEMP TABLE snap_after  (schema_name text, table_name text, row_count bigint, content_hash text,
                               excluded_cols text, real_hash text, has_real boolean DEFAULT $after_real);

\copy snap_before ($before_cols) FROM '$BEFORE' WITH (FORMAT csv, HEADER true)
\copy snap_after  ($after_cols)  FROM '$AFTER'  WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE comparison AS
SELECT
    coalesce(b.schema_name, a.schema_name) AS schema_name,
    coalesce(b.table_name,  a.table_name)  AS table_name,
    b.row_count                            AS before_count,
    a.row_count                            AS after_count,
    coalesce(a.row_count, 0) - coalesce(b.row_count, 0) AS delta,
    -- the after side's list when the table still exists; NULL (not '') so CSV writes it bare
    nullif(CASE WHEN a.table_name IS NOT NULL THEN a.excluded_cols ELSE b.excluded_cols END, '') AS excluded_cols,
    CASE
        WHEN b.table_name IS NULL OR a.table_name IS NULL THEN 'changed'
        WHEN NOT (b.has_real AND a.has_real)
          OR coalesce(b.excluded_cols, '') <> coalesce(a.excluded_cols, '') THEN 'n/a'
        WHEN coalesce(b.real_hash, '') = coalesce(a.real_hash, '') THEN 'unchanged'
        ELSE 'changed'
    END AS real_status,
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

\copy (SELECT schema_name, table_name, before_count, after_count, delta, real_status, excluded_cols, status FROM comparison ORDER BY schema_name, table_name) TO '$base' WITH (FORMAT csv, HEADER true)
SQL

if [[ "$CHURN" -eq 0 ]]; then
    # Counts-only report: reorder (changed/added/removed first, then |delta|).
    { head -1 "$base"
      tail -n +2 "$base" \
        | awk -F, '{ key=($NF!="unchanged")?1:0; d=($5<0)?-$5:$5; print key"\t"d"\t"$0 }' \
        | sort -t$'\t' -k1,1nr -k2,2nr | cut -f3-; } > "$OUT"
else
    # Churn report: add rows_added / rows_removed via comm of the per-row hash
    # files, then order by total churn (changed/added/removed float to the top).
    # Ordered by real churn where known (raw otherwise), so rebuilt-but-identical
    # tables sink below tables whose content actually moved.
    header="schema_name,table_name,before_count,after_count,delta,rows_added,rows_removed,real_rows_added,real_rows_removed,excluded_cols,status"
    { tail -n +2 "$base" | while IFS=, read -r schema table bcount acount delta real_status excl status; do
        added=""; removed=""; radded=""; rremoved=""
        case "$status" in
            changed)
                bf="$BEFORE_HASHDIR/${schema}.${table}.md5"
                af="$AFTER_HASHDIR/${schema}.${table}.md5"
                if [[ -f "$bf" && -f "$af" ]]; then
                    added=$(LC_ALL=C comm -13 "$bf" "$af" | wc -l)
                    removed=$(LC_ALL=C comm -23 "$bf" "$af" | wc -l)
                fi
                if [[ "$real_status" == unchanged ]]; then
                    radded=0; rremoved=0
                elif [[ "$real_status" == changed && -z "$excl" ]]; then
                    radded="$added"; rremoved="$removed"          # nothing excluded: real == raw
                elif [[ "$real_status" == changed ]]; then
                    bf="$BEFORE_HASHDIR/${schema}.${table}.real.md5"
                    af="$AFTER_HASHDIR/${schema}.${table}.real.md5"
                    if [[ -f "$bf" && -f "$af" ]]; then
                        radded=$(LC_ALL=C comm -13 "$bf" "$af" | wc -l)
                        rremoved=$(LC_ALL=C comm -23 "$bf" "$af" | wc -l)
                    fi
                fi ;;
            added)   added="${acount:-0}"; removed=0; radded="$added"; rremoved=0 ;;
            removed) added=0; removed="${bcount:-0}"; radded=0; rremoved="$removed" ;;
            *)       added=0; removed=0; radded=0; rremoved=0 ;;   # unchanged
        esac
        if [[ -n "$radded" ]]; then total=$(( radded + rremoved )); else total=$(( ${added:-0} + ${removed:-0} )); fi
        printf '%d\t%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
            "$total" "$schema" "$table" "$bcount" "$acount" "$delta" "$added" "$removed" "$radded" "$rremoved" "$excl" "$status"
      done | sort -t$'\t' -k1,1nr | cut -f2-; } > "$base.churn"
    { echo "$header"; cat "$base.churn"; } > "$OUT"
    rm -f "$base.churn"
fi

# ---- console summary (from the final CSV, so churn shows when present) --------
echo ""
echo "=== Totals by status ==="
awk -F, 'NR>1 { c[$NF]++; d[$NF]+=$5 } END { for (s in c) printf "  %-10s %5d tables   net_row_delta %+d\n", s, c[s], d[s] }' "$OUT" \
    | sort

echo ""
if [[ "$CHURN" -eq 1 ]]; then
    echo "=== Top 40 real row churn (raw where real is n/a), changed/added/removed only ==="
    printf '  %-52s %11s %11s %11s %11s %9s  %s\n' table real_added real_removed raw_added raw_removed delta status
    # Limit inside awk (not `| head`) so a closed pipe can't SIGPIPE under pipefail.
    awk -F, 'NR>1 && $NF!="unchanged" && ++n<=40 {
        ra = ($8 == "") ? "n/a" : $8; rr = ($9 == "") ? "n/a" : $9
        printf "  %-52s %11s %11s %11s %11s %+9d  %s\n", $1"."$2, ra, rr, $6, $7, $5, $NF }' "$OUT"
else
    echo "=== Top 40 changed / added / removed tables (by |delta|) ==="
    printf '  %-52s %12s %12s %9s  %-9s  %s\n' table before after delta real status
    awk -F, 'NR>1 && $NF!="unchanged" && ++n<=40 { printf "  %-52s %12s %12s %+9d  %-9s  %s\n", $1"."$2, $3, $4, $5, $6, $NF }' "$OUT"
fi

echo ""
echo "wrote comparison CSV: $OUT"
if [[ "$CHURN" -eq 1 ]]; then
    echo "(with exact raw + real rows_added / rows_removed churn columns)"
fi