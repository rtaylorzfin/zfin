#!/usr/bin/env bash
# Run Solr's /analysis/field handler for each (fieldType, input) pair so
# two schema variants can be diffed without a reindex. After rebuilding
# the solr image with a new schema and restarting the container (or running
# `ant reload-solr-core` after `docker cp`-ing a config change), the
# analyzers reflect the new config immediately — only token output changes
# here, stored docs are untouched.
#
# Run from inside a docker-compose service (e.g. compile) so
# SOLR=http://solr:8983/solr resolves via the compose network.
#
# Usage:
#   SOLR=http://solr:8983/solr CORE=site_index \
#     ./solr-analyze.sh <inputs-file> <out.json>
#
# Inputs file: one value per line, blank lines and `#` comments ignored.
# Override TYPES env var to change which field types get exercised.
#
# Requires: curl, jq.

set -euo pipefail

IN="${1:?usage: solr-analyze.sh <inputs-file> <out.json>}"
OUT="${2:?usage: solr-analyze.sh <inputs-file> <out.json>}"
SOLR="${SOLR:-http://solr:8983/solr}"
CORE="${CORE:-site_index}"
TYPES="${TYPES:-text edgytext simplified-international-text}"

mkdir -p "$(dirname "$OUT")"

results=()
while IFS= read -r line; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    for ft in $TYPES; do
        resp=$(curl -fsS "$SOLR/$CORE/analysis/field" \
            --data-urlencode "analysis.fieldtype=$ft" \
            --data-urlencode "analysis.fieldvalue=$line" \
            --data-urlencode "analysis.query=$line" \
            --data-urlencode 'wt=json')
        payload=$(jq --sort-keys --arg ft "$ft" --arg input "$line" \
            '{fieldtype: $ft, input: $input, analysis: .analysis}' <<<"$resp")
        results+=("$payload")
    done
done < "$IN"

printf '%s\n' "${results[@]}" | jq -s --sort-keys '.' > "$OUT"

# Companion human-readable summary: just the final token list per row.
flat="${OUT%.json}.tokens.txt"
jq -r '
    .[]
    | "fieldtype=\(.fieldtype)  input=\(.input)"
    , "  index: " + (
        [.analysis.field_types[].index // [] | .[-1]? | (.[]?.text // empty)]
        | join(" | ")
    )
    , "  query: " + (
        [.analysis.field_types[].query // [] | .[-1]? | (.[]?.text // empty)]
        | join(" | ")
    )
    , ""
' "$OUT" > "$flat"

echo "wrote:"
echo "  full json:        $OUT"
echo "  token-only flat:  $flat"
