#!/bin/bash
#
# Drill down into ONE table's churn between two DB unloads, separating real
# content changes from surrogate-key / load-timestamp churn.
#
# The sweep's per-pair report counts churn over the whole row, so a table that is
# rebuilt each load with a regenerated serial PK (and/or a load-date column) shows
# ~100% churn even when its meaningful content barely moved. This tool recomputes
# the churn over the table's MEANINGFUL columns only -- everything except:
#   - date / time / timestamp typed columns, and
#   - the single-column integer primary key (a surrogate serial),
# plus anything you name with --exclude. ZFIN's ZDB IDs are stable persistent
# identifiers, so they are kept; only the regenerated surrogate/date churn is
# stripped, revealing the real add/remove counts.
#
# It never does a full `loaddb`: `pg_restore -t` pulls just the one table out of
# each dump into a throwaway scratch database, side by side, then a set-difference
# (EXCEPT ALL over a per-row hash) counts real vs raw adds/removes.
#
# Usage:
#   drill-table-churn.sh --table <schema.table> --before <A> --after <B> [options]
#     <A>,<B>  a dated unload dir name (e.g. 2026.08.12.1) resolved on the mount,
#              or a direct path to a .bak file.
#
# Options (env fallback in parens):
#   --table S.T          schema-qualified table (required)
#   --before A           older unload (date-dir or .bak path) (required)
#   --after  B           newer unload (date-dir or .bak path) (required)
#   --exclude c1,c2      extra columns to treat as churn (beyond the auto set)
#   --mount DIR          dir of dated unload dirs        (CELL_MOUNT; ~/remotes/cell)
#   --local-unloads DIR  host stage dir, bind-mounted     (LOCAL_UNLOADS)
#   --ctr-unloads DIR    container view of that stage dir (CTR_UNLOADS; /opt/zfin/unloads/db)
#   --container NAME     container running the db         (CONTAINER; dazed-jenkins-1)
#   --keep-scratch       don't drop the scratch DB (for inspection)
#   -h, --help
#
# Example:
#   docker/drill-table-churn.sh --table ui.publication_expression_display \
#     --before 2026.08.12.1 --after 2026.08.13.1

set -euo pipefail

CELL_MOUNT="${CELL_MOUNT:-$HOME/remotes/cell}"
LOCAL_UNLOADS="${LOCAL_UNLOADS:-/research/zunloads/databases/zfindb}"
CTR_UNLOADS="${CTR_UNLOADS:-/opt/zfin/unloads/db}"
CONTAINER="${CONTAINER:-dazed-jenkins-1}"
SCRATCH="drilldown_scratch"
TABLE=""; BEFORE=""; AFTER=""; EXCLUDE=""; KEEP=0

usage() { sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --table)         TABLE="$2"; shift 2;;
        --before)        BEFORE="$2"; shift 2;;
        --after)         AFTER="$2"; shift 2;;
        --exclude)       EXCLUDE="$2"; shift 2;;
        --mount)         CELL_MOUNT="$2"; shift 2;;
        --local-unloads) LOCAL_UNLOADS="$2"; shift 2;;
        --ctr-unloads)   CTR_UNLOADS="$2"; shift 2;;
        --container)     CONTAINER="$2"; shift 2;;
        --keep-scratch)  KEEP=1; shift;;
        -h|--help)       usage; exit 0;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2;;
    esac
done

[[ -n "$TABLE" && -n "$BEFORE" && -n "$AFTER" ]] || { echo "need --table, --before, --after (try --help)" >&2; exit 2; }
schema="${TABLE%%.*}"; table="${TABLE#*.}"
[[ "$schema" != "$table" ]] || { echo "--table must be schema.table" >&2; exit 2; }

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
ctr() { docker exec "$CONTAINER" bash -lc "cd \$SOURCEROOT && $*"; }
# Run SQL (on stdin) in the container; strip the login-shell "Loading properties"
# banner and container stderr so callers get clean, parseable output.
ctr_sql() { docker exec -i "$CONTAINER" bash -lc "psql -d ${1:?} -At -F'|' -v ON_ERROR_STOP=1" 2>/dev/null | grep -v 'Loading properties' || true; }

cleanup() {
    [[ "$KEEP" -eq 1 ]] || ctr "dropdb --if-exists $SCRATCH" >/dev/null 2>&1 || true
    rm -rf "${LOCAL_UNLOADS:?}/_drill" 2>/dev/null || true
}
trap cleanup EXIT

# Resolve a --before/--after spec to a container-visible staged .bak path.
stage() {   # $1 = spec, $2 = name(before|after) -> echoes container path
    local spec="$1" name="$2" bak
    if [[ "$spec" == *.bak ]]; then bak="$spec"; else
        local b=("$CELL_MOUNT/$spec"/*.bak); bak="${b[0]}"
    fi
    [[ -f "$bak" ]] || die "no dump found for '$spec'"
    mkdir -p "$LOCAL_UNLOADS/_drill"
    log "staging $name dump $(basename "$bak") ($(du -h "$bak" | cut -f1))..." >&2
    cp -f "$bak" "$LOCAL_UNLOADS/_drill/$name.bak"
    echo "$CTR_UNLOADS/_drill/$name.bak"
}

# ---- introspect columns / types / PK from the live zfindb -------------------
log "introspecting $schema.$table from \$DBNAME..."
declare -A COLTYPE; allcols=()
while IFS='|' read -r name typ; do
    [[ -z "$name" ]] && continue
    allcols+=("$name"); COLTYPE["$name"]="$typ"
done < <(ctr_sql "\$DBNAME" <<SQL
SELECT a.attname, format_type(a.atttypid, a.atttypmod)
FROM pg_attribute a
WHERE a.attrelid = '$schema.$table'::regclass AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY a.attnum;
SQL
)
[[ "${#allcols[@]}" -gt 0 ]] || die "table $schema.$table not found in the live DB (needed for its column layout)"

pkcols=()
while IFS= read -r name; do [[ -n "$name" ]] && pkcols+=("$name"); done < <(ctr_sql "\$DBNAME" <<SQL
SELECT a.attname
FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
WHERE i.indrelid = '$schema.$table'::regclass AND i.indisprimary
ORDER BY a.attnum;
SQL
)

# ---- decide the churn (excluded) columns ------------------------------------
# Auto-exclude: date/time-typed columns, and integer/bigint surrogate keys. In
# ZFIN real identifiers are text ZDB IDs, so a bigint column named `id` / `*_id`
# is a regenerated surrogate serial (whether or not it is the PK) -- exclude it;
# keep the *_zdb_id (text) columns, which are stable.
declare -A EXSET
for name in "${allcols[@]}"; do
    t="${COLTYPE[$name]}"
    if [[ "$t" =~ ^(date|time|timestamp) ]]; then EXSET["$name"]=1; continue; fi
    if [[ "$t" =~ ^(integer|bigint|smallint) && "$name" =~ (^id$|_id$) ]]; then EXSET["$name"]=1; continue; fi
done
if [[ "${#pkcols[@]}" -eq 1 ]]; then                                          # sole integer PK, any name
    pk="${pkcols[0]}"
    [[ "${COLTYPE[$pk]}" =~ ^(integer|bigint|smallint) ]] && EXSET["$pk"]=1
fi
if [[ -n "$EXCLUDE" ]]; then
    IFS=',' read -ra ux <<< "$EXCLUDE"
    for u in "${ux[@]}"; do u="${u// /}"; [[ -n "$u" ]] && EXSET["$u"]=1; done
fi

meaningful=()
for name in "${allcols[@]}"; do [[ -n "${EXSET[$name]:-}" ]] || meaningful+=("$name"); done
[[ "${#meaningful[@]}" -gt 0 ]] || die "every column was excluded; nothing meaningful to compare"

mlist=""; for c in "${meaningful[@]}"; do mlist+="\"$c\","; done; mlist="${mlist%,}"
excluded_list="$(printf '%s ' "${!EXSET[@]}" | sort | tr '\n' ' ')"; excluded_list="${excluded_list%% }"

# ---- stage the two dumps and pg_restore just this table into a scratch DB ---
before_ctr=$(stage "$BEFORE" before)
after_ctr=$(stage "$AFTER" after)

log "restoring $schema.$table from both dumps into scratch DB '$SCRATCH'..."
ctr "dropdb --if-exists $SCRATCH >/dev/null 2>&1; createdb $SCRATCH"
ctr "psql -d $SCRATCH -qc 'CREATE SCHEMA IF NOT EXISTS \"$schema\"'"
ctr "pg_restore -d $SCRATCH -n '$schema' -t '$table' --no-owner --no-privileges '$before_ctr' 2>/dev/null || true"
ctr "psql -d $SCRATCH -qc 'ALTER TABLE \"$schema\".\"$table\" RENAME TO \"${table}__before\"'" \
    || die "restore of the BEFORE table failed (table absent in $BEFORE, or a type it depends on wasn't restorable)"
ctr "pg_restore -d $SCRATCH -n '$schema' -t '$table' --no-owner --no-privileges '$after_ctr' 2>/dev/null || true"
ctr "psql -d $SCRATCH -qtc 'SELECT 1 FROM \"$schema\".\"$table\" LIMIT 1' >/dev/null 2>&1 || psql -d $SCRATCH -qc 'SELECT count(*) FROM \"$schema\".\"$table\"' >/dev/null" \
    || die "restore of the AFTER table failed"

# ---- compare: raw (whole row) vs meaningful (excluded cols dropped) ----------
log "comparing (raw vs meaningful)..."
read -r raw_add raw_rem real_add real_rem after_n before_n < <(ctr_sql "$SCRATCH" <<SQL | tr '|' ' '
WITH
a_raw AS (SELECT md5(t::text) h FROM "$schema"."$table" t),
b_raw AS (SELECT md5(t::text) h FROM "$schema"."${table}__before" t),
a_m   AS (SELECT md5(ROW($mlist)::text) h FROM "$schema"."$table"),
b_m   AS (SELECT md5(ROW($mlist)::text) h FROM "$schema"."${table}__before")
SELECT
  (SELECT count(*) FROM (SELECT h FROM a_raw EXCEPT ALL SELECT h FROM b_raw) x),
  (SELECT count(*) FROM (SELECT h FROM b_raw EXCEPT ALL SELECT h FROM a_raw) x),
  (SELECT count(*) FROM (SELECT h FROM a_m   EXCEPT ALL SELECT h FROM b_m)   x),
  (SELECT count(*) FROM (SELECT h FROM b_m   EXCEPT ALL SELECT h FROM a_m)   x),
  (SELECT count(*) FROM a_raw),
  (SELECT count(*) FROM b_raw);
SQL
)

# ---- report -----------------------------------------------------------------
echo ""
echo "=== $schema.$table   ($BEFORE -> $AFTER) ==="
echo "  rows: before $before_n -> after $after_n   (delta $((after_n - before_n)))"
echo "  columns: ${#allcols[@]}   meaningful: ${#meaningful[@]}"
echo "  excluded as churn: ${excluded_list:-none}"
echo "  raw churn (whole row):     +$raw_add / -$raw_rem"
echo "  REAL churn (meaningful):   +$real_add / -$real_rem"
echo ""
