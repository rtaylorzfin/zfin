#!/bin/bash
#
# Backward daily-sweep driver for before/after table comparisons.
#
# Steps backward through a series of dated DB unloads, one at a time, and for
# each consecutive pair produces an exact churn comparison report -- reusing the
# committed snapshot_table_summary.sh + compare_table_summaries.sh + `gradle
# loaddb`. Each dump is loaded exactly once: the snapshot of day D is the
# "before" of the (D -> D+1) report and, one step later, the "after" of the
# (D-1 -> D) report (the "relabel before as after" step). Finally it derives an
# "unchanged since" summary from the accumulated per-date snapshots.
#
# It spans host + container by necessity: the dumps live on a host-side (often
# read-only sshfs) mount, but `gradle loaddb` only works inside the running
# container. So this runs on the host and `docker exec`s the DB-side steps.
#
# Disk discipline: only one ~1.3 GB dump is staged locally at a time (deleted
# right after loading), and only two days of per-row hash dirs (~3 GB each) are
# kept at once. A free-space floor aborts before the disk can fill.
#
# Resumable: each day's snapshot + hash dir are promoted atomically (written to
# .tmp/.partial, renamed on success), and a re-run skips any day whose snapshot
# already exists and any report already written. So an interrupted sweep -- or a
# later run with more --days -- continues without re-loading finished days. The
# tiny per-date snapshot CSVs are kept permanently; only the big hash dirs are
# pruned as the window slides.
#
# Usage:
#   compare-loads-series.sh [options]
#
# Options (env var fallback in parentheses; CLI overrides env overrides default):
#   -m, --mount DIR         dir of dated unload dirs        (CELL_MOUNT; ~/remotes/cell)
#   -d, --days N            number of reports = days back   (DAYS/REPORTS; 10)
#   -s, --start DATE        newest date-dir to start from   (START; newest on mount)
#       --dates "A B C"     explicit newest->oldest list, overrides --days/--start (DATES)
#   -c, --container NAME    container running gradle/db      (CONTAINER; dazed-jenkins-1)
#   -l, --local-unloads DIR host stage dir, bind-mounted     (LOCAL_UNLOADS)
#       --ctr-unloads DIR   container view of that stage dir (CTR_UNLOADS; /opt/zfin/unloads/db)
#       --min-free-gb N     abort if free space drops below  (MIN_FREE_GB; 8)
#   -o, --outrel DIR        output dir relative to repo root (OUTREL; build/db-load-comparison/series)
#   -h, --help              show this help
#
# Examples:
#   compare-loads-series.sh --days 10
#   compare-loads-series.sh --mount /mnt/cell --start 2026.08.13.1 --days 5
#   compare-loads-series.sh --dates "2026.08.13.1 2026.08.12.1 2026.08.11.1"

set -euo pipefail

# Defaults (env overrides these; CLI args override env, parsed below).
CELL_MOUNT="${CELL_MOUNT:-$HOME/remotes/cell}"
LOCAL_UNLOADS="${LOCAL_UNLOADS:-/research/zunloads/databases/zfindb}"
CTR_UNLOADS="${CTR_UNLOADS:-/opt/zfin/unloads/db}"
CONTAINER="${CONTAINER:-dazed-jenkins-1}"
DAYS="${DAYS:-${REPORTS:-10}}"
START="${START:-}"
DATES="${DATES:-}"
MIN_FREE_GB="${MIN_FREE_GB:-8}"
OUTREL="${OUTREL:-build/db-load-comparison/series}"

usage() { sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -m|--mount)         CELL_MOUNT="$2"; shift 2;;
        -d|--days|--reports) DAYS="$2"; shift 2;;
        -s|--start)         START="$2"; shift 2;;
        --dates)            DATES="$2"; shift 2;;
        -c|--container)     CONTAINER="$2"; shift 2;;
        -l|--local-unloads) LOCAL_UNLOADS="$2"; shift 2;;
        --ctr-unloads)      CTR_UNLOADS="$2"; shift 2;;
        --min-free-gb)      MIN_FREE_GB="$2"; shift 2;;
        -o|--outrel)        OUTREL="$2"; shift 2;;
        -h|--help)          usage; exit 0;;
        *) echo "unknown option: $1 (try --help)" >&2; exit 2;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_HOST="$(cd "$SCRIPT_DIR/.." && pwd)"
SNAP="server_apps/DB_maintenance/postgres/snapshot_table_summary.sh"
CMP="server_apps/DB_maintenance/postgres/compare_table_summaries.sh"
US="server_apps/DB_maintenance/postgres/unchanged_since.sh"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
free_gb() { df -BG --output=avail / | tail -1 | tr -dc '0-9'; }
require_disk() {
    local f; f=$(free_gb)
    [[ "$f" -ge "$MIN_FREE_GB" ]] || die "only ${f}G free (< ${MIN_FREE_GB}G floor); aborting"
}
# Run a command from $SOURCEROOT in the container with a login shell.
ctr() { docker exec "$CONTAINER" bash -lc "cd \$SOURCEROOT && $*"; }

# Kill any lingering background step if the sweep is interrupted.
trap 'jobs -p | xargs -r kill 2>/dev/null' EXIT INT TERM

# Run a command in the background (output silenced) while printing an
# elapsed-time heartbeat every 30s, so long silent steps (copy, load) show they
# are alive. Returns the command's exit status.
with_heartbeat() {
    local label="$1"; shift
    "$@" >/dev/null 2>&1 &
    local pid=$! start=$SECONDS
    while kill -0 "$pid" 2>/dev/null; do
        sleep 30
        kill -0 "$pid" 2>/dev/null && log "    ...$label — $((SECONDS - start))s elapsed"
    done
    wait "$pid"
}

have_snapshot() { ctr "test -s $OUTREL/snapshots/$1.csv" 2>/dev/null; }
have_hashes()   { ctr "test -d $OUTREL/hashes/$1"       2>/dev/null; }
have_report()   { ctr "test -s $OUTREL/reports/$1.csv"  2>/dev/null; }

# Stage a dump, load it, and snapshot (+per-row hashes). Snapshot CSV and hash
# dir are promoted atomically so an interrupted run never leaves them looking
# complete; the snapshot CSV is promoted last, as the "day done" sentinel.
load_and_snapshot() {
    local date="$1" bak bakname size
    require_disk
    local baks=("$CELL_MOUNT/$date"/*.bak)     # glob, not `ls | head` (avoids SIGPIPE)
    bak="${baks[0]}"
    [[ -f "$bak" ]] || die "no .bak in $CELL_MOUNT/$date"
    bakname=$(basename "$bak"); size=$(du -h "$bak" | cut -f1)

    log "[$date] copying dump $bakname ($size) from mount..."
    mkdir -p "$LOCAL_UNLOADS/$date"
    with_heartbeat "copying $date" cp -f "$bak" "$LOCAL_UNLOADS/$date/$bakname" \
        || die "copy failed for $date"

    log "[$date] loading into DB — drop + pg_restore (~15 min)..."
    with_heartbeat "loading $date" \
        docker exec "$CONTAINER" bash -lc "cd \$SOURCEROOT && gradle loaddb -Dunload=$CTR_UNLOADS/$date/$bakname" \
        || die "loaddb failed for $date"

    log "[$date] freeing staged dump, snapshotting tables + per-row hashes (~15 min)..."
    rm -rf "${LOCAL_UNLOADS:?}/$date"          # 1.3 GB back before we write hashes
    # Stream the snapshot's per-table output: show every 50th table and the
    # summary lines, so the pane ticks along while ~520 tables are hashed.
    ctr "rm -rf $OUTREL/snapshots/$date.csv.tmp $OUTREL/hashes/$date.partial \
      && DBNAME=\$DBNAME OUTFILE=$OUTREL/snapshots/$date.csv.tmp HASHDIR=$OUTREL/hashes/$date.partial bash $SNAP" \
      | awk '/^snap /{c++; if (c%50==0){printf "    ...%d tables hashed\n", c; fflush()}; next}
             /^Snapshotting|^done\./{print "    "$0; fflush()}'
    # Promote atomically only after the snapshot succeeded (snapshot CSV last).
    ctr "rm -rf $OUTREL/hashes/$date && mv $OUTREL/hashes/$date.partial $OUTREL/hashes/$date \
      && mv $OUTREL/snapshots/$date.csv.tmp $OUTREL/snapshots/$date.csv"
}

# ---- resolve the newest->oldest date-dir sequence ---------------------------
if [[ -n "$DATES" ]]; then
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
    dates=("${all[@]:$start_idx:$((DAYS + 1))}")   # N reports need N+1 snapshots
fi
[[ "${#dates[@]}" -ge 2 ]] || die "need at least 2 dumps to make a report (got ${#dates[@]})"

ctr "mkdir -p $OUTREL/snapshots $OUTREL/hashes $OUTREL/reports"

log "sweep: ${#dates[@]} dumps -> $((${#dates[@]} - 1)) reports"
log "  dates (newest->oldest): ${dates[*]}"
log "  mount=$CELL_MOUNT  stage=$LOCAL_UNLOADS  container=$CONTAINER"
log "  output=$REPO_HOST/$OUTREL"

prev=""                                        # newer day's snapshot = the "after"
for date in "${dates[@]}"; do
    if have_snapshot "$date"; then
        log "=== $date : snapshot present, skipping load ==="
    else
        load_and_snapshot "$date"
    fi

    if [[ -n "$prev" ]]; then
        rpt="${date}_to_${prev}"
        if have_report "$rpt"; then
            log "$date : report present, skipping compare ($rpt)"
        else
            # Churn needs both days' per-row hashes; regenerate any that were
            # pruned by an earlier run (snapshot exists but hashes are gone).
            have_hashes "$date" || { log "$date : hashes pruned, reloading to regenerate"; load_and_snapshot "$date"; }
            have_hashes "$prev" || { log "$prev : hashes pruned, reloading to regenerate"; load_and_snapshot "$prev"; }
            log "[$date → $prev] comparing (churn) -> reports/$rpt.csv"
            ctr "DBNAME=\$DBNAME BEFORE=$OUTREL/snapshots/$date.csv AFTER=$OUTREL/snapshots/$prev.csv \
                 BEFORE_HASHDIR=$OUTREL/hashes/$date AFTER_HASHDIR=$OUTREL/hashes/$prev \
                 OUT=$OUTREL/reports/$rpt.csv.tmp bash $CMP \
                 && mv $OUTREL/reports/$rpt.csv.tmp $OUTREL/reports/$rpt.csv" | sed 's/^/    /'
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

# ---- unchanged-since summary over ALL accumulated snapshots -----------------
ctr "bash $US $OUTREL/snapshots $OUTREL/unchanged_since.csv" | sed 's/^/  /'

log "done."
log "  reports:          $REPO_HOST/$OUTREL/reports/"
log "  index:            $REPO_HOST/$OUTREL/index.csv"
log "  unchanged-since:  $REPO_HOST/$OUTREL/unchanged_since.csv"
ctr "cat $OUTREL/index.csv" | sed 's/^/  /'
