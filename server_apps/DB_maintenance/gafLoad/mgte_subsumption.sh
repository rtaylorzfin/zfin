#!/bin/bash
#
# Classify the (gene, GO) pairs lost across a cutover diff (ZFIN-10464).
#
#   mgte_subsumption.sh <outdir>
#
# Reads  <outdir>/mgte_{before,after}_ALL.csv   -- written by mgte_snapshot.sh --all
# Writes <outdir>/mgte_subsumption.xlsx         -- sheets: true_loss / subsumed / specificity_lost
#
# Env: PGHOST, DBNAME, SOURCEROOT.
#
# Run AFTER mgte_csvdiff.sh. Of the pairs that disappeared, which does ZFIN still cover by another
# term on the same gene? The diff cannot say -- it has no ontology awareness.
#
# Needs the --all snapshot pair, not the per-org files: a pair that moved organization is not
# lost, and only the ALL view can see that.
set -euo pipefail

OUT="${1:?usage: mgte_subsumption.sh <outdir>}"
: "${SOURCEROOT:?SOURCEROOT must be set}"
SQL="$SOURCEROOT/server_apps/DB_maintenance/gafLoad"

for f in mgte_before_ALL.csv mgte_after_ALL.csv; do
    [ -s "$OUT/$f" ] || {
        echo "mgte_subsumption.sh: missing or empty $OUT/$f" >&2
        echo "  mgte_snapshot.sh must have been run with --all for both phases." >&2
        exit 1
    }
done

# cd rather than pass paths: \copy does not interpolate psql variables.
cd "$OUT"
psql -v ON_ERROR_STOP=1 -h "$PGHOST" -d "$DBNAME" -f "$SQL/mgte_subsumption.sql"

cd "$SOURCEROOT"
gradle csv2xlsx --args="$OUT/mgte_subsumption.xlsx \
    $OUT/mgte_subsumption_true_loss.csv \
    $OUT/mgte_subsumption_subsumed.csv \
    $OUT/mgte_subsumption_specificity_lost.csv"

# The workbook is the artifact; the CSVs were only its input. Mirrors CSVDIFF_XLSX_ONLY.
rm -f "$OUT"/mgte_subsumption_true_loss.csv \
      "$OUT"/mgte_subsumption_subsumed.csv \
      "$OUT"/mgte_subsumption_specificity_lost.csv

echo "wrote $OUT/mgte_subsumption.xlsx"
