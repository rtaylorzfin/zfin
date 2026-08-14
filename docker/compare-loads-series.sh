#!/bin/bash
#
# Backward daily-sweep driver for before/after table comparisons.
#
# Steps backward through a series of dated DB unloads, one at a time, and for
# each consecutive pair produces an exact churn comparison report -- reusing the
# committed snapshot_table_summary.sh + compare_table_summaries.sh + `gradle
# loaddb`. Each dump is loaded exactly once and each snapshot computed once: the
# snapshot of day D is the "before" of the (D -> D+1) report and, one step later,
# the "after" of the (D-1 -> D) report (the "relabel before as after" step).
#
# It spans host + container by necessity: the dumps live on a host-side (often
# read-only sshfs) mount, but `gradle loaddb` only works inside the running
# container. So this runs on the host and `docker exec`s the DB-side steps.
#
# Disk discipline: only one ~1.3 GB dump is staged locally at a time (deleted
# right after loading), and only two days of per-row hash dirs (~3 GB each) are
# kept at once. A free-space floor aborts the run before it can fill the disk.
#
# Env (all optional; defaults suit the local `dazed` stack):
#   CELL_MOUNT      dir of dated unload dirs        (default: ~/remotes/cell)
#   LOCAL_UNLOADS   host stage dir, bind-mounted    (default: /research/zunloads/databases/zfindb)
#   CTR_UNLOADS     container view of LOCAL_UNLOADS  (default: /opt/zfin/unloads/db)
#   CONTAINER       container running gradle/db      (default: dazed-jenkins-1)
#   REPORTS         number of reports to produce     (default: 10)
#   START           newest date-dir to start from    (default: newest on the mount)
#   DATES           explicit newest->oldest date-dir list, overrides REPORTS/START
#   MIN_FREE_GB     abort if free space drops below   (default: 8)
#   OUTREL          output dir, relative to repo root (default: build/db-load-comparison/series)
#
# Example (3-day trial = 2 reports, newest 3 dumps):
#   REPORTS=2 docker/compare-loads-series.sh
#   DATES="2026.08.13.1 2026.08.12.1 2026.08.11.1" docker/compare-loads-series.sh

set -euo pipefail

CELL_MOUNT="${CELL_MOUNT:-$HOME/remotes/cell}"
LOCAL_UNLOADS="${LOCAL_UNLOADS:-/research/zunloads/databases/zfindb}"
CTR_UNLOADS="${CTR_UNLOADS:-/opt/zfin/unloads/db}"
CONTAINER="${CONTAINER:-dazed-jenkins-1}"
REPORTS="${REPORTS:-10}"
START="${START:-}"
MIN_FREE_GB="${MIN_FREE_GB:-8}"
OUTREL="${OUTREL:-build/db-load-comparison/series}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_HOST="$(cd "$SCRIPT_DIR/.." && pwd)"
SNAP="server_apps/DB_maintenance/postgres/snapshot_table_summary.sh"
CMP="server_apps/DB_maintenance/postgres/compare_table_summaries.sh"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

free_gb() { df -BG --output=avail / | tail -1 | tr -dc '0-9'; }
require_disk() {
    local f; f=$(free_gb)
    [[ "$f" -ge "$MIN_FREE_GB" ]] || die "only ${f}G free (< ${MIN_FREE_GB}G floor); aborting before staging next dump"
}

# In-container helper: run a command from $SOURCEROOT with a login shell so the
# env (DBNAME, PGHOST, SOURCEROOT, PATH) is loaded.
ctr() { docker exec "$CONTAINER" bash -lc "cd \$SOURCEROOT && $*"; }

# ---- resolve the newest->oldest date-dir sequence ---------------------------
if [[ -n "${DATES:-}" ]]; then
    read -r -a dates <<< "$DATES"
else
    # Canonical unload dirs only (YYYY.MM.DD.N); excludes .dumpall siblings etc.
    mapfile -t all < <(cd "$CELL_MOUNT" && ls -d 20??.??.??.* 2>/dev/null \
        | grep -E '^20[0-9][0-9]\.[0-9][0-9]\.[0-9][0-9]\.[0-9]+$' | sort -r)
    [[ "${#all[@]}" -gt 0 ]] || die "no dated unload dirs under $CELL_MOUNT"
    start_idx=0
    if [[ -n "$START" ]]; then
        for i in "${!all[@]}"; do [[ "${all[$i]}" == "$START" ]] && start_idx=$i && break; done
    fi
    need=$((REPORTS + 1))                      # N reports need N+1 snapshots
    dates=("${all[@]:$start_idx:$need}")
fi
[[ "${#dates[@]}" -ge 2 ]] || die "need at least 2 dumps to make a report (got ${#dates[@]})"

OUT_H="$REPO_HOST/$OUTREL"
# Output lives under build/, which is owned by the container user, so all writes
# to it happen in-container. The host only stages dumps (to $LOCAL_UNLOADS).
ctr "mkdir -p $OUTREL/snapshots $OUTREL/hashes $OUTREL/reports"

log "sweep: ${#dates[@]} dumps -> $((${#dates[@]} - 1)) reports"
log "  dates (newest->oldest): ${dates[*]}"
log "  mount=$CELL_MOUNT  stage=$LOCAL_UNLOADS  container=$CONTAINER"
log "  output=$OUT_H"

prev=""                                        # newer day's snapshot = the "after"
for date in "${dates[@]}"; do
    snap="$OUTREL/snapshots/$date.csv"
    hdir="$OUTREL/hashes/$date"

    # Resume: skip stage/load/snapshot if this day's snapshot + hashes exist.
    if ctr "test -s $snap && test -d $hdir" 2>/dev/null; then
        log "=== $date : snapshot present, skipping stage/load/snapshot ==="
    else
        require_disk
        bak=$(ls "$CELL_MOUNT/$date"/*.bak 2>/dev/null | head -1) \
            || die "no .bak in $CELL_MOUNT/$date"
        bakname=$(basename "$bak")

        log "=== $date : staging $bakname ($(du -h "$bak" | cut -f1)) ==="
        mkdir -p "$LOCAL_UNLOADS/$date"
        cp -f "$bak" "$LOCAL_UNLOADS/$date/$bakname"

        log "$date : loading (drops + recreates the DB)"
        ctr "gradle loaddb -Dunload=$CTR_UNLOADS/$date/$bakname" >/dev/null

        log "$date : freeing staged dump, snapshotting (with per-row hashes)"
        rm -rf "${LOCAL_UNLOADS:?}/$date"      # 1.3 GB back before we write hashes
        ctr "DBNAME=\$DBNAME OUTFILE=$snap HASHDIR=$hdir bash $SNAP" >/dev/null
    fi

    if [[ -n "$prev" ]]; then
        report="$OUTREL/reports/${date}_to_${prev}.csv"
        if ctr "test -s $report" 2>/dev/null; then
            log "$date : report already present, skipping compare ($report)"
        else
            ctr "test -d $OUTREL/hashes/$prev" 2>/dev/null \
                || die "need hashes for $prev but $OUTREL/hashes/$prev is gone (pruned by an earlier partial run); delete snapshots/$prev.csv to force a reload"
            log "$date : comparing $date -> $prev  ->  $report"
            ctr "DBNAME=\$DBNAME BEFORE=$snap AFTER=$OUTREL/snapshots/$prev.csv \
                 BEFORE_HASHDIR=$hdir AFTER_HASHDIR=$OUTREL/hashes/$prev \
                 OUT=$report bash $CMP" | sed 's/^/    /'
        fi
        ctr "rm -rf $OUTREL/hashes/$prev"      # prev's hashes are done (were the "after")
    fi
    prev="$date"
done
ctr "rm -rf $OUTREL/hashes/$prev"              # last remaining hash dir

# ---- index of all reports (built in-container, where the files live) --------
ctr "
echo 'report,changed,added,removed,rows_added_total,rows_removed_total' > $OUTREL/index.csv
for r in $OUTREL/reports/*.csv; do
    [ -e \"\$r\" ] || continue
    awk -F, -v name=\"\$(basename \"\$r\" .csv)\" '
        NR>1 { st[\$NF]++; if (NF>=8) { add+=\$6; rem+=\$7 } }
        END { printf \"%s,%d,%d,%d,%d,%d\n\", name, st[\"changed\"], st[\"added\"], st[\"removed\"], add, rem }
    ' \"\$r\" >> $OUTREL/index.csv
done
"

log "done. reports under $OUT_H/reports/ ; index:"
ctr "cat $OUTREL/index.csv" | sed 's/^/  /'
