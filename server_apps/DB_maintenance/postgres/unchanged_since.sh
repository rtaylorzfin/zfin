#!/bin/bash
#
# Post-run analysis over the accumulated per-date snapshot CSVs (from
# snapshot_table_summary.sh, one file per date: schema,table,row_count,
# content_hash): for each table, how far back has it been byte-for-byte
# unchanged?
#
# Walking the snapshots newest -> oldest, a table's content_hash matches the
# newest snapshot's hash for a contiguous run of recent dates. The oldest date
# in that run is when its current content was established:
#
#   blast_hit: unchanged since = 2026.08.01.1   (older snapshot differs -> the
#                                                change is pinned in-window)
#   blast_hit: unchanged since <= 2026.08.03.1  (matched all the way to the
#                                                oldest snapshot -> lower bound)
#
# Because this reads the (tiny, permanently kept) snapshot files rather than the
# per-pair reports, it spans every snapshot you have -- across multiple sweeps --
# so `<=` bounds tighten into exact `=` dates as your history deepens, with
# nothing rewritten.
#
# Usage:
#   unchanged_since.sh <snapshots_dir> [out_csv]
# Output CSV columns: schema_name,table_name,latest_row_count,unchanged_since,bound
#   bound = exact     (= date; an older snapshot differs, change seen)
#         | at_least  (<= date; matched to the oldest snapshot, lower bound)

set -euo pipefail

SNAP_DIR="${1:?usage: unchanged_since.sh <snapshots_dir> [out_csv]}"
OUT="${2:-$SNAP_DIR/../unchanged_since.csv}"

# Snapshot files newest-first (filenames are <date>.csv, date = YYYY.MM.DD.N).
mapfile -t files < <(
    for f in "$SNAP_DIR"/*.csv; do
        [[ -e "$f" ]] || continue
        d=$(basename "$f" .csv); printf '%s\t%s\n' "$d" "$f"
    done | sort -r | cut -f2-
)
[[ "${#files[@]}" -ge 1 ]] || { echo "no <date>.csv snapshots in $SNAP_DIR" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"

date_of() { local b; b=$(basename "$1" .csv); echo "$b"; }

declare -A NEWHASH LATEST SINCE RESOLVED BOUND
oldest_date=""

# Seed from the newest snapshot: every current table starts "unchanged since"
# the newest date, matching itself.
newest_date=$(date_of "${files[0]}")
while IFS=, read -r schema table rc hash; do
    key="$schema,$table"
    NEWHASH["$key"]="$hash"; LATEST["$key"]="$rc"; SINCE["$key"]="$newest_date"
done < <(tail -n +2 "${files[0]}")

# Walk older snapshots; extend each table's stable run back until its hash
# diverges (or the table is absent), which pins the change date.
for ((i = 1; i < ${#files[@]}; i++)); do
    d=$(date_of "${files[$i]}")
    oldest_date="$d"
    declare -A THIS=()
    while IFS=, read -r schema table rc hash; do
        THIS["$schema,$table"]="$hash"
    done < <(tail -n +2 "${files[$i]}")

    for key in "${!NEWHASH[@]}"; do
        [[ -n "${RESOLVED[$key]:-}" ]] && continue
        if [[ -n "${THIS[$key]+x}" && "${THIS[$key]}" == "${NEWHASH[$key]}" ]]; then
            SINCE["$key"]="$d"                     # still matching; run extends back
        else
            RESOLVED["$key"]=1; BOUND["$key"]="exact"   # diverged between $d and SINCE
        fi
    done
    unset THIS
done

# Anything never resolved matched all the way to the oldest snapshot -> bound.
{
    echo "schema_name,table_name,latest_row_count,unchanged_since,bound"
    for key in "${!NEWHASH[@]}"; do
        b="${BOUND[$key]:-at_least}"
        echo "$key,${LATEST[$key]},${SINCE[$key]},$b"
    done | sort -t, -k4,4 -k1,1 -k2,2
} > "$OUT"

# ---- console summary --------------------------------------------------------
total=$(($(wc -l < "$OUT") - 1))
echo ""
echo "=== unchanged-since: $total tables across ${#files[@]} snapshots ($oldest_date .. $newest_date) -> $OUT ==="
awk -F, 'NR>1{c[$5]++} END{
    printf "  exact    (= date, change seen in window):     %d\n", c["exact"]
    printf "  at_least (<= oldest, stable across window):   %d\n", c["at_least"]
}' "$OUT"
echo ""
echo "  longest-stable tables (unchanged since the earliest dates):"
# Limit inside awk (not `| head`) so a closed pipe can't SIGPIPE under pipefail.
awk -F, 'NR>1 && ++n<=20 { op=($5=="at_least")?"<=":"="; printf "    %-46s unchanged since %s %s\n", $1"."$2, op, $4 }' "$OUT"
