#!/bin/bash
#
# Compare two unload files table by table: restore BEFORE, snapshot every table
# (row count, content hash, real hash), restore AFTER, snapshot again, and write
# a per-table before/after comparison.
#
# Destructive: drops and recreates DBNAME twice, leaving the AFTER dump loaded.
# For a series of consecutive days, use docker/compare-loads-series.sh instead.
#
# Usage:
#   DBNAME=<db> compare_loads.sh <before unload> <after unload> [out dir]
#
# Out dir (default ./db-load-comparison) gets before.csv, after.csv and
# table_load_comparison.csv. Set HASHES=1 to also keep per-row hash files
# (before/, after/ in the out dir; ~5 GB each) so the comparison includes
# rows added/removed, not just row counts and content hashes.
#
# Env: DBNAME (required), PGBINDIR, HASHES, EXCLUDE (passed to the snapshot)

set -euo pipefail

: "${DBNAME:?DBNAME must be set}"
BEFORE="${1:?usage: DBNAME=<db> compare_loads.sh <before unload> <after unload> [out dir]}"
AFTER="${2:?usage: DBNAME=<db> compare_loads.sh <before unload> <after unload> [out dir]}"
OUTDIR="${3:-db-load-comparison}"
HASHES="${HASHES:-0}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for f in "$BEFORE" "$AFTER"; do
    [[ -f "$f" ]] || { echo "ERROR: no such unload file: $f" >&2; exit 1; }
done
mkdir -p "$OUTDIR"

snap() {   # <label>
    local hash_env=()
    if [[ "$HASHES" == 1 ]]; then
        rm -rf "$OUTDIR/$1"
        hash_env=(HASHDIR="$OUTDIR/$1")
    fi
    env DBNAME="$DBNAME" OUTFILE="$OUTDIR/$1.csv" "${hash_env[@]}" bash "$HERE/snapshot_table_summary.sh"
}

echo "== compareLoads: BEFORE = $BEFORE"
bash "$HERE/restore_unload.sh" "$DBNAME" "$BEFORE"
snap before

echo "== compareLoads: AFTER = $AFTER"
bash "$HERE/restore_unload.sh" "$DBNAME" "$AFTER"
snap after

echo "== compareLoads: comparing snapshots"
churn_env=()
[[ "$HASHES" == 1 ]] && churn_env=(BEFORE_HASHDIR="$OUTDIR/before" AFTER_HASHDIR="$OUTDIR/after")
env DBNAME="$DBNAME" BEFORE="$OUTDIR/before.csv" AFTER="$OUTDIR/after.csv" \
    OUT="$OUTDIR/table_load_comparison.csv" "${churn_env[@]}" bash "$HERE/compare_table_summaries.sh"

echo "compareLoads complete. Summary CSV: $OUTDIR/table_load_comparison.csv"
