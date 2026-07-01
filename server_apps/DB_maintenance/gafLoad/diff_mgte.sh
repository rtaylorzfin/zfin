#!/bin/bash
#
# Churn-excluded diff between two snapshots produced by snapshot_mgte.sh.
# Loads the two CSVs and writes the genuine removals/additions (logical-level;
# zdb_id and protein_accession churn excluded — see diff_mgte.sql).
#
# Usage:
#   diff_mgte.sh <before.csv> <after.csv> <out_removed.csv> <out_added.csv>
#
# Env: PGHOST (default: db), DBNAME (required).
#
set -euo pipefail

BEFORE="${1:?usage: diff_mgte.sh <before.csv> <after.csv> <out_removed.csv> <out_added.csv>}"
AFTER="${2:?after.csv required}"
OUT_REMOVED="${3:?out_removed.csv required}"
OUT_ADDED="${4:?out_added.csv required}"
DIR="$(cd "$(dirname "$0")" && pwd)"
PSQL="psql -v ON_ERROR_STOP=1 -h ${PGHOST:-db} -d ${DBNAME:?DBNAME must be set}"

# Load the two snapshot CSVs into the staging tables diff_mgte.sql expects.
$PSQL -c "DROP TABLE IF EXISTS tmp_gaf_mgoe_before, tmp_gaf_mgoe_after;
          CREATE TABLE tmp_gaf_mgoe_before (zdb_id text, marker text, term text, source text, evidence text,
                 relation text, created_by text, contributed_by text, protein_acc text, inferred_from text);
          CREATE TABLE tmp_gaf_mgoe_after (LIKE tmp_gaf_mgoe_before INCLUDING ALL);"
$PSQL -c "\copy tmp_gaf_mgoe_before FROM '$BEFORE' CSV HEADER"
$PSQL -c "\copy tmp_gaf_mgoe_after  FROM '$AFTER'  CSV HEADER"

$PSQL -f "$DIR/diff_mgte.sql"
$PSQL -c "\copy (SELECT * FROM tmp_gaf_real_removed ORDER BY publication,gene_zdb_id,go_id) TO STDOUT CSV HEADER" > "$OUT_REMOVED"
$PSQL -c "\copy (SELECT * FROM tmp_gaf_real_added   ORDER BY publication,gene_zdb_id,go_id) TO STDOUT CSV HEADER" > "$OUT_ADDED"
$PSQL -c "DROP TABLE IF EXISTS tmp_gaf_mgoe_before, tmp_gaf_mgoe_after, tmp_gaf_real_removed, tmp_gaf_real_added" >/dev/null

echo "real removed: $(( $(wc -l < "$OUT_REMOVED") - 1 )); real added: $(( $(wc -l < "$OUT_ADDED") - 1 ))"
