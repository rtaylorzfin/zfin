#!/bin/bash
#
# Drill down into one or more tables' churn between two DB unloads, separating
# real content changes from surrogate-key / load-timestamp churn. The two dumps
# are staged once and every table is restored from them, so drilling several
# tables costs one copy of each dump rather than one per table.
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
# It never does a full `loaddb`: each table (definition, data and its PK/unique
# constraints, picked from the dump's TOC) is restored from each dump into a
# throwaway scratch database, side by side, then a set-difference (EXCEPT ALL over
# a per-row hash) counts real vs raw adds/removes. The churn columns are resolved
# from the AFTER dump's copy of the table, so the live DB is not consulted and a
# column added or dropped since the dumps does not matter.
#
# Usage:
#   drill-table-churn.sh --table <schema.table>[,<schema.table>...] --before <A> --after <B> [options]
#     <A>,<B>  a dated unload dir name (e.g. 2026.08.12.1) resolved on the mount,
#              or a direct path to a .bak file.
#
# Options (env fallback in parens):
#   --table S.T[,S.T]    schema-qualified table(s); repeat or comma-separate (required)
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
#   docker/drill-table-churn.sh --table ui.publication_expression_display,public.marker \
#     --before 2026.08.12.1 --after 2026.08.13.1

set -euo pipefail

CELL_MOUNT="${CELL_MOUNT:-$HOME/remotes/cell}"
LOCAL_UNLOADS="${LOCAL_UNLOADS:-/research/zunloads/databases/zfindb}"
CTR_UNLOADS="${CTR_UNLOADS:-/opt/zfin/unloads/db}"
CONTAINER="${CONTAINER:-dazed-jenkins-1}"
SCRATCH="drilldown_scratch"
TABLES=(); BEFORE=""; AFTER=""; EXCLUDE=""; KEEP=0

usage() { sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --table)         IFS=',' read -ra _t <<< "$2"; TABLES+=("${_t[@]}"); shift 2;;
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

[[ "${#TABLES[@]}" -gt 0 && -n "$BEFORE" && -n "$AFTER" ]] || { echo "need --table, --before, --after (try --help)" >&2; exit 2; }
for t in "${TABLES[@]}"; do
    [[ "${t%%.*}" != "${t#*.}" ]] || { echo "--table must be schema.table (got '$t')" >&2; exit 2; }
done

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
# Fail fast when the dump mount is missing or has dropped: a dead sshfs mount
# errors or hangs on access, which otherwise surfaces mid-run as a stall.
check_mount() {
    timeout 15 ls "$CELL_MOUNT" >/dev/null 2>&1 \
        || die "cannot read $CELL_MOUNT (missing, or the sshfs mount dropped?); remount it or pass --mount <dir>"
}
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
        check_mount
        local b=("$CELL_MOUNT/$spec"/*.bak); bak="${b[0]}"
    fi
    [[ -f "$bak" ]] || die "no dump found for '$spec'"
    mkdir -p "$LOCAL_UNLOADS/_drill"
    log "staging $name dump $(basename "$bak") ($(du -h "$bak" | cut -f1))..." >&2
    cp -f "$bak" "$LOCAL_UNLOADS/_drill/$name.bak"
    docker exec "$CONTAINER" pg_restore -l "$CTR_UNLOADS/_drill/$name.bak" > "$LOCAL_UNLOADS/_drill/$name.toc"
    echo "$CTR_UNLOADS/_drill/$name.bak"
}

# Restore one table from a staged dump into the scratch DB: its definition, data,
# and PK/unique constraints (so churn_columns.sh's sole-int-PK rule sees the PK),
# picked from the dump's TOC. `pg_restore -t` alone leaves the constraints out.
# Column defaults, other indexes and FKs are skipped (not needed, and FKs would
# need the referenced tables). Returns 1 if the dump has no data for the table.
restore_table() {   # $1 = before|after, $2 = staged dump (container path), $3 = schema, $4 = table
    local name="$1" dump="$2" schema="$3" table="$4" list="$LOCAL_UNLOADS/_drill/$1.$3.$4.list"
    awk -v want=" $schema $table " '
        { line = $0; sub(/^[0-9]+; [0-9]+ [0-9]+ /, "", line) }
        index(line, "TABLE" want) == 1 || index(line, "TABLE DATA" want) == 1 || index(line, "CONSTRAINT" want) == 1
    ' "$LOCAL_UNLOADS/_drill/$name.toc" > "$list"
    grep -q " TABLE DATA $schema $table " "$list" || return 1
    ctr "pg_restore -d $SCRATCH -L '$CTR_UNLOADS/_drill/$name.$schema.$table.list' --no-owner --no-privileges '$dump' 2>/dev/null || true"
}

# ---- stage the two dumps once; every table is restored from them ------------
before_ctr=$(stage "$BEFORE" before)
after_ctr=$(stage "$AFTER" after)
ctr "dropdb --if-exists $SCRATCH >/dev/null 2>&1; createdb $SCRATCH"

warn() { echo "SKIP: $*" >&2; }

# One table: resolve its churn columns, restore it from both dumps into the
# scratch DB, compare raw vs meaningful churn, report. A table that cannot be
# drilled is skipped with a warning so the others still run.
drill_one() {
    local schema="${1%%.*}" table="${1#*.}"
    local resolved rexcl rmean excluded mcols meaningful allcols mlist excluded_list u ux name
    local raw_add raw_rem real_add real_rem after_n before_n
    log "restoring $schema.$table from both dumps into scratch DB '$SCRATCH'..."
    ctr "psql -d $SCRATCH -qc 'CREATE SCHEMA IF NOT EXISTS \"$schema\"'"
    restore_table before "$before_ctr" "$schema" "$table" \
        || { warn "$schema.$table has no data in $BEFORE"; return 0; }
    ctr "psql -d $SCRATCH -qc 'ALTER TABLE \"$schema\".\"$table\" RENAME TO \"${table}__before\"'" \
        || { warn "restore of $schema.$table from BEFORE failed (a type it needs wasn't restorable?)"; return 0; }
    # Free the PK/unique index names for the AFTER copy (index names are schema-wide).
    ctr_sql "$SCRATCH" <<SQL >/dev/null
DO \$\$ DECLARE c text; BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = '"$schema"."${table}__before"'::regclass AND contype IN ('p','u','x') LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', '"$schema"."${table}__before"', c);
  END LOOP; END \$\$;
SQL
    restore_table after "$after_ctr" "$schema" "$table" \
        || { warn "$schema.$table has no data in $AFTER"; return 0; }
    ctr "psql -d $SCRATCH -qc 'SELECT count(*) FROM \"$schema\".\"$table\"' >/dev/null" \
        || { warn "restore of $schema.$table from AFTER failed"; return 0; }

    # ---- resolve churn vs meaningful columns (shared rules, see churn_columns.sh) -
    # From the AFTER dump's copy of the table, with the same rules and
    # churn_columns.tsv overrides the sweep's real_hash uses; --exclude adds more.
    resolved=$(ctr "DBNAME=$SCRATCH bash server_apps/DB_maintenance/postgres/churn_columns.sh $schema.$table" 2>/dev/null \
        | grep -v 'Loading properties' || true)
    [[ -n "$resolved" ]] || { warn "could not resolve the columns of $schema.$table"; return 0; }
    IFS='|' read -r _ _ rexcl rmean <<< "$resolved"

    local -A EXSET
    IFS=';' read -ra excluded <<< "$rexcl"
    for u in "${excluded[@]}"; do [[ -n "$u" ]] && EXSET["$u"]=1; done
    if [[ -n "$EXCLUDE" ]]; then
        IFS=',' read -ra ux <<< "$EXCLUDE"
        for u in "${ux[@]}"; do u="${u// /}"; [[ -n "$u" ]] && EXSET["$u"]=1; done
    fi

    # Columns only in one of the two copies (added/dropped between the dumps)
    # cannot be compared row by row; compare the shared ones and say so.
    local -A BEFORECOLS; local onlyone=()
    while read -r name; do [[ -n "$name" ]] && BEFORECOLS["$name"]=1; done < <(ctr_sql "$SCRATCH" <<SQL
SELECT attname FROM pg_attribute WHERE attrelid = '"$schema"."${table}__before"'::regclass AND attnum > 0 AND NOT attisdropped;
SQL
    )
    IFS=';' read -ra mcols <<< "$rmean"
    meaningful=()
    for name in "${mcols[@]}"; do
        [[ -n "${EXSET[$name]:-}" ]] && continue
        if [[ -n "${BEFORECOLS[$name]:-}" ]]; then meaningful+=("$name"); else onlyone+=("$name"); fi
    done
    allcols=("${excluded[@]}" "${mcols[@]}")
    [[ "${#meaningful[@]}" -gt 0 ]] || { warn "every column of $schema.$table was excluded; nothing meaningful to compare"; return 0; }

    mlist=""; for c in "${meaningful[@]}"; do mlist+="\"$c\","; done; mlist="${mlist%,}"
    excluded_list="$(printf '%s ' "${!EXSET[@]}" | sort | tr '\n' ' ')"; excluded_list="${excluded_list%% }"

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
    [[ "${#onlyone[@]}" -eq 0 ]] || echo "  not compared (not in the BEFORE copy): ${onlyone[*]}  -- raw churn counts them"
    echo "  raw churn (whole row):     +$raw_add / -$raw_rem"
    echo "  REAL churn (meaningful):   +$real_add / -$real_rem"
    echo ""
}

for t in "${TABLES[@]}"; do drill_one "$t"; done
