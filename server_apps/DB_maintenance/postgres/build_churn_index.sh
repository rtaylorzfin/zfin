#!/bin/bash
#
# Summarize a directory of per-pair churn reports (from compare_table_summaries.sh,
# named <before>_to_<after>.csv) into one index CSV, one line per report:
#
#   report,changed,added,removed,rows_added_total,rows_removed_total,real_rows_added_total,real_rows_removed_total
#
# Real totals use each table's real churn, falling back to raw where real is
# n/a; they are blank for reports made before real churn existed. Shared by the
# backward sweep (docker/compare-loads-series.sh) and the daily job
# (daily_table_churn.sh).
#
# Usage:
#   build_churn_index.sh <reports_dir> <out_csv>

set -euo pipefail

REPORTS="${1:?usage: build_churn_index.sh <reports_dir> <out_csv>}"
OUT="${2:?usage: build_churn_index.sh <reports_dir> <out_csv>}"

{
    echo 'report,changed,added,removed,rows_added_total,rows_removed_total,real_rows_added_total,real_rows_removed_total'
    for r in "$REPORTS"/*.csv; do
        [[ -e "$r" ]] || continue
        awk -F, -v name="$(basename "$r" .csv)" '
            NR>1 { st[$NF]++; if (NF>=8) { add+=$6; rem+=$7 }
                   if (NF>=11) { real=1; radd+=($8=="" ? $6 : $8); rrem+=($9=="" ? $7 : $9) } }
            END { printf "%s,%d,%d,%d,%d,%d,%s,%s\n", name, st["changed"], st["added"], st["removed"], add, rem,
                         real ? radd : "", real ? rrem : "" }
        ' "$r"
    done
} > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
