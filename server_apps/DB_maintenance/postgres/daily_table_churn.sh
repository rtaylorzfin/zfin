#!/bin/bash
#
# Daily table-churn report against a LIVE database, read-only.
#
# Run right after the nightly unload (Jenkins: Table-Churn-Report_d, triggered by
# Unload-Database_d). It snapshots every table of the live DB (row count, content
# hash, real hash, and per-row hash files), compares the snapshot with the
# previous one, and refreshes the index and unchanged-since summaries. Nothing is
# restored or loaded: unlike docker/compare-loads-series.sh, this never writes to
# the database it reads.
#
# Read-only, two ways:
#   - it refuses to run unless the connecting role is read-only: not a superuser,
#     no pg_write_all_data, and no INSERT/UPDATE/DELETE on a sentinel table
#     (set REQUIRE_READONLY_ROLE=0 only for local testing);
#   - the snapshot runs with default_transaction_read_only=on, so even a bug in
#     the snapshot SQL cannot write. (The compare step uses TEMP tables, which a
#     read-only transaction forbids, so it runs without that setting; it only
#     ever touches its own temp tables.)
#
# Storage is split so the big files can live on cheaper disk:
#   CHURN_DATA_DIR  small, kept forever: snapshots/ (~100 KB/day), reports/,
#                   index.csv, unchanged_since.csv, dashboard/ (churn_dashboard.py)
#   CHURN_HASH_DIR  large, transient: per-row hash dirs (~5 GB/day); only the
#                   newest day's dir is kept, as tomorrow's "before"
#
# Each day is labelled like its unload dir (YYYY.MM.DD.N): the newest dir under
# UNLOADS_DIR, or LABEL if given. Re-running a day reuses its snapshot unless
# FORCE=1, and keeps an existing report. If the previous day's hash files are missing (first run, or they were
# pruned), the report is counts-only for that day and full churn resumes tomorrow.
#
# Env:
#   DBNAME                 database to snapshot (required)
#   PGUSER / PGHOST        connection (a read-only role; password via ~/.pgpass)
#   PGBINDIR               path to psql (optional)
#   CHURN_DATA_DIR         small, permanent output dir (required)
#   CHURN_HASH_DIR         large hash dir (default: $CHURN_DATA_DIR/hashes)
#   UNLOADS_DIR            dated unload dirs, for the label (default: /research/zunloads/databases/$INSTANCE)
#   LABEL                  override the day label
#   EXCLUDE                regex of schema.table to skip (default: ^external_resource\. -- the
#                          unload leaves out that schema's data, so skip it here too)
#   MIN_FREE_GB            abort if CHURN_HASH_DIR has less free space (default: 12)
#   REQUIRE_READONLY_ROLE  1 (default) to refuse a role that can write
#   FORCE                  1 to re-snapshot a day that already has a snapshot
#
# Example:
#   DBNAME=zfindb PGUSER=zfin_churn_ro CHURN_DATA_DIR=/research/zunloads/table_churn/zfinprod \
#     CHURN_HASH_DIR=/scratch/table_churn_hashes ./daily_table_churn.sh

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
: "${CHURN_DATA_DIR:?CHURN_DATA_DIR must be set}"
CHURN_HASH_DIR="${CHURN_HASH_DIR:-$CHURN_DATA_DIR/hashes}"
UNLOADS_DIR="${UNLOADS_DIR:-/research/zunloads/databases/${INSTANCE:-}}"
LABEL="${LABEL:-}"
EXCLUDE="${EXCLUDE-^external_resource\.}"
MIN_FREE_GB="${MIN_FREE_GB:-12}"
REQUIRE_READONLY_ROLE="${REQUIRE_READONLY_ROLE:-1}"
FORCE="${FORCE:-0}"
PSQL="${PGBINDIR:+$PGBINDIR/}psql"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SNAPS="$CHURN_DATA_DIR/snapshots"
REPORTS="$CHURN_DATA_DIR/reports"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
q()   { "$PSQL" -d "$DBNAME" -At -v ON_ERROR_STOP=1 -c "$1"; }

mkdir -p "$SNAPS" "$REPORTS" "$CHURN_HASH_DIR"

# ---- refuse a role that can write --------------------------------------------
if [[ "$REQUIRE_READONLY_ROLE" == 1 ]]; then
    read -r who super writer can_write < <(q "
        SELECT current_user, r.rolsuper, pg_has_role(current_user, 'pg_write_all_data', 'MEMBER'),
               has_table_privilege('public.zdb_active_data', 'INSERT, UPDATE, DELETE')
        FROM pg_roles r WHERE r.rolname = current_user" | tr '|' ' ')
    [[ "$super" == f && "$writer" == f && "$can_write" == f ]] \
        || die "role '$who' can write (superuser=$super pg_write_all_data=$writer writes zdb_active_data=$can_write); use a read-only role"
    log "connected as read-only role '$who'"
fi

# ---- day label and previous day -----------------------------------------------
if [[ -z "$LABEL" ]]; then
    LABEL=$(ls -d "$UNLOADS_DIR"/20??.??.??.* 2>/dev/null | xargs -r -n1 basename \
        | grep -E '^20[0-9]{2}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$' | sort | tail -1 || true)
    [[ -n "$LABEL" ]] || LABEL="$(date +%Y.%m.%d).1"
fi
prev=$(ls "$SNAPS" 2>/dev/null | sed -n 's/\.csv$//p' | grep -E '^20[0-9]{2}\.' | sort | awk -v l="$LABEL" '$0 < l' | tail -1 || true)
log "day $LABEL  (previous: ${prev:-none})  data=$CHURN_DATA_DIR  hashes=$CHURN_HASH_DIR"

# ---- snapshot (read-only session), promoted atomically ------------------------
if [[ -s "$SNAPS/$LABEL.csv" && -d "$CHURN_HASH_DIR/$LABEL" && "$FORCE" != 1 ]]; then
    log "snapshot for $LABEL already present, reusing it"
else
    free=$(df -BG --output=avail "$CHURN_HASH_DIR" | tail -1 | tr -dc '0-9')
    [[ "$free" -ge "$MIN_FREE_GB" ]] || die "only ${free}G free in $CHURN_HASH_DIR (< ${MIN_FREE_GB}G)"
    rm -rf "$SNAPS/$LABEL.csv.tmp" "$CHURN_HASH_DIR/$LABEL.partial"
    log "snapshotting $DBNAME..."
    PGOPTIONS="${PGOPTIONS:+$PGOPTIONS }-c default_transaction_read_only=on" \
    DBNAME="$DBNAME" OUTFILE="$SNAPS/$LABEL.csv.tmp" HASHDIR="$CHURN_HASH_DIR/$LABEL.partial" EXCLUDE="$EXCLUDE" \
        bash "$HERE/snapshot_table_summary.sh" | awk '/^snap /{c++; if (c%200==0) printf "  ...%d tables\n", c; next} {print "  "$0}'
    rm -rf "$CHURN_HASH_DIR/$LABEL"
    mv "$CHURN_HASH_DIR/$LABEL.partial" "$CHURN_HASH_DIR/$LABEL"
    mv "$SNAPS/$LABEL.csv.tmp" "$SNAPS/$LABEL.csv"
fi

# ---- compare with the previous day --------------------------------------------
rpt="$REPORTS/${prev}_to_${LABEL}.csv"
if [[ -n "$prev" && -s "$rpt" && "$FORCE" != 1 ]]; then
    # Re-run of a finished day: the previous day's hashes are pruned by now, so
    # re-comparing would replace the full churn report with a counts-only one.
    log "report ${prev}_to_${LABEL} already present, keeping it"
elif [[ -n "$prev" ]]; then
    churn_env=()
    if [[ -d "$CHURN_HASH_DIR/$prev" ]]; then
        churn_env=(BEFORE_HASHDIR="$CHURN_HASH_DIR/$prev" AFTER_HASHDIR="$CHURN_HASH_DIR/$LABEL")
    else
        log "no hash files for $prev; this report is counts-only"
    fi
    log "comparing $prev -> $LABEL"
    env DBNAME="$DBNAME" BEFORE="$SNAPS/$prev.csv" AFTER="$SNAPS/$LABEL.csv" OUT="$rpt.tmp" "${churn_env[@]}" \
        bash "$HERE/compare_table_summaries.sh" | sed 's/^/  /'
    mv "$rpt.tmp" "$rpt"
else
    log "first snapshot; nothing to compare yet"
fi

# ---- summaries; keep only the newest hash dir ---------------------------------
bash "$HERE/build_churn_index.sh" "$REPORTS" "$CHURN_DATA_DIR/index.csv"
bash "$HERE/unchanged_since.sh" "$SNAPS" "$CHURN_DATA_DIR/unchanged_since.csv" | sed 's/^/  /'
for d in "$CHURN_HASH_DIR"/20??.??.??.*; do
    if [[ -d "$d" && "$(basename "$d")" != "$LABEL" ]]; then
        log "pruning hashes $(basename "$d")"; rm -rf "$d"
    fi
done
python3 -I "$HERE/churn_dashboard.py" "$CHURN_DATA_DIR" "$CHURN_DATA_DIR/dashboard" | sed 's/^/  /'
log "done: $CHURN_DATA_DIR/index.csv, $CHURN_DATA_DIR/dashboard/index.html"
