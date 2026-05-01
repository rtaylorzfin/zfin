#!/usr/bin/env bash
# Dump the indexed term dictionary for each field listed in the field
# list, one TSV per field (term<TAB>freq), sorted by term. Diffable
# across two reindexed cores to surface what the analyzer change
# actually produced.
#
# Reindex required on each side before running this — terms only reflect
# what's in the index, not the live schema.
#
# Run from inside a docker-compose service (e.g. compile) so
# SOLR=http://solr:8983/solr resolves via the compose network.
#
# Usage:
#   SOLR=http://solr:8983/solr CORE=site_index \
#     ./solr-terms.sh <outdir> [field-list-file]
#
# Default field list: ./solr-affected-fields.txt next to this script.
#
# Requires: curl, jq.

set -euo pipefail

OUT="${1:?usage: solr-terms.sh <outdir> [field-list-file]}"
FIELDS="${2:-$(dirname "$0")/solr-affected-fields.txt}"
SOLR="${SOLR:-http://solr:8983/solr}"
CORE="${CORE:-site_index}"
LIMIT="${LIMIT:--1}"

mkdir -p "$OUT"

while IFS= read -r f; do
    [[ -z "$f" || "$f" =~ ^[[:space:]]*# ]] && continue
    resp=$(curl -fsS "$SOLR/$CORE/terms" \
        --data-urlencode "terms.fl=$f" \
        --data-urlencode "terms.limit=$LIMIT" \
        --data-urlencode "terms.sort=index" \
        --data-urlencode 'wt=json')
    count=$(jq --arg f "$f" '(.terms[$f] // []) | length / 2' <<<"$resp")
    jq -r --arg f "$f" '
        .terms[$f] // [] | _nwise(2) | "\(.[0])\t\(.[1])"
    ' <<<"$resp" > "$OUT/$f.tsv"
    echo "  $f: $count terms"
done < "$FIELDS"

echo "done: per-field term lists in $OUT"
