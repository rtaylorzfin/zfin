#!/usr/bin/env bash
# Dump every doc from a Solr core to one ndjson file, id-sorted, with
# volatile fields removed and keys sorted, so two runs can be diffed
# directly.
#
# Run from inside a docker-compose service (e.g. the compile container)
# so SOLR=http://solr:8983/solr resolves via the compose network.
#
# Usage:
#   SOLR=http://solr:8983/solr CORE=site_index ./solr-dump.sh <outfile.ndjson>
#
# Requires: curl, jq.

set -euo pipefail

OUT="${1:?usage: solr-dump.sh <outfile.ndjson>}"
SOLR="${SOLR:-http://solr:8983/solr}"
CORE="${CORE:-site_index}"
ROWS="${ROWS:-1000}"

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

mark='*'
page=0
total=0
while :; do
    resp=$(curl -fsS "$SOLR/$CORE/select" \
        --data-urlencode 'q=*:*' \
        --data-urlencode "rows=$ROWS" \
        --data-urlencode 'sort=id asc' \
        --data-urlencode "cursorMark=$mark" \
        --data-urlencode 'wt=json')

    next=$(jq -r '.nextCursorMark' <<<"$resp")
    count=$(jq '.response.docs | length' <<<"$resp")

    jq -c --sort-keys '.response.docs[] | del(._version_, .timestamp, ._root_)' \
        <<<"$resp" >> "$OUT"

    total=$((total + count))
    page=$((page + 1))
    if (( page % 25 == 0 )); then
        echo "  page $page  total=$total  cursor=$next"
    fi

    [[ "$next" == "$mark" ]] && break
    mark="$next"
done

echo "done: $total docs written to $OUT"
