#!/bin/bash
#
# Resolve, per table, which columns are "churn" (excluded from the meaningful-
# content hash) and which are meaningful. This is the single source of truth for
# the exclusion rules, shared by snapshot_table_summary.sh (the real_hash) and
# docker/drill-table-churn.sh, so the two always agree.
#
# A column is excluded when any auto rule matches and it is not rescued by an
# `include` in the registry:
#   - date / time / timestamp typed columns (load or audit timestamps);
#   - integer/bigint/smallint columns named `id` or `*_id` -- ZFIN's real
#     identifiers are text ZDB IDs, so an integer *_id is a regenerated surrogate
#     serial (or a reference to one);
#   - a single-column integer primary key, whatever its name;
#   - anything listed as `exclude` for the table in the registry.
#
# Registry: churn_columns.tsv next to this script (override with REGISTRY). One
# directive per line, whitespace separated, # comments allowed:
#   <schema.table>  exclude  col1,col2   # also treat these as churn
#   <schema.table>  include  col1        # keep these even if an auto rule matches
#
# Output (stdout), one line per table, tab separated, columns in attnum order:
#   schema|table|excluded_cols|meaningful_cols
# where both column lists are ';'-separated bare names (excluded_cols may be
# empty). '|' rather than a tab so an empty field survives `IFS=... read`; ';'
# because the excluded list is stored inside CSV snapshot files.
#
# Env:
#   DBNAME    database to introspect (required)
#   PGBINDIR  path to psql (optional, defaults to PATH)
#   SCHEMAS   comma-separated schema allowlist (default: all user schemas)
#   REGISTRY  path to the registry file (default: churn_columns.tsv beside this)
#
# Args: optional schema.table names to restrict the output to.
#
# Example:
#   DBNAME=zfindb ./churn_columns.sh ui.term_phenotype_display public.marker

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
PSQL="${PGBINDIR:+$PGBINDIR/}psql"
SCHEMAS="${SCHEMAS:-}"
REGISTRY="${REGISTRY:-$(dirname "${BASH_SOURCE[0]}")/churn_columns.tsv}"

if [[ -n "$SCHEMAS" ]]; then
    schema_filter="AND n.nspname IN ($(echo "$SCHEMAS" | sed "s/[^,]*/'&'/g"))"
else
    schema_filter="AND n.nspname NOT IN ('pg_catalog','information_schema')
                   AND n.nspname NOT LIKE 'pg_%'"
fi
table_filter=""
if [[ $# -gt 0 ]]; then
    table_filter="AND n.nspname || '.' || c.relname IN ($(printf "'%s'," "$@" | sed 's/,$//'))"
fi

[[ -f "$REGISTRY" ]] || REGISTRY=/dev/null

# One row per column: schema, table, column, type, is-sole-key-of-the-PK.
sql="
SELECT n.nspname, c.relname, a.attname, format_type(a.atttypid, a.atttypmod),
       EXISTS (SELECT 1 FROM pg_index i
               WHERE i.indrelid = c.oid AND i.indisprimary
                 AND i.indnkeyatts = 1 AND i.indkey[0] = a.attnum)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE c.relkind = 'r'
  $schema_filter
  $table_filter
ORDER BY n.nspname, c.relname, a.attnum;"

"$PSQL" -d "$DBNAME" -At -F$'\t' -v ON_ERROR_STOP=1 -c "$sql" | awk -F'\t' -v OFS='|' '
    # Registry first (NR==FNR): directives keyed by "schema.table|column".
    NR == FNR {
        sub(/#.*/, ""); sub(/^[ \t]+/, "")
        if (split($0, f, /[ \t]+/) < 3) next
        n = split(f[3], c, ",")
        for (i = 1; i <= n; i++) if (c[i] != "") reg[f[1] "|" c[i]] = f[2]
        next
    }
    function flush() {
        if (cur != "") print cs, ct, excl, mean
        excl = ""; mean = ""
    }
    {
        key = $1 "." $2
        if (key != cur) { flush(); cur = key; cs = $1; ct = $2 }
        name = $3; typ = $4; solepk = ($5 == "t")
        isint = (typ ~ /^(integer|bigint|smallint)/)
        ex = (typ ~ /^(date|time|timestamp)/) || (isint && name ~ /(^id$|_id$)/) || (isint && solepk)
        d = reg[key "|" name]
        if (d == "exclude") ex = 1
        if (d == "include") ex = 0
        if (ex) excl = excl (excl == "" ? "" : ";") name
        else    mean = mean (mean == "" ? "" : ";") name
    }
    END { flush() }
' "$REGISTRY" -
