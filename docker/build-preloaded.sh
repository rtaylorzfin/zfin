#!/usr/bin/env bash
# Build "preloaded" images from an already-loaded dev stack.
#
# Prereq: a normal dev stack that has been loaded the usual way, e.g.
#   gradle getdb && gradle loaddb && gradle liquibasePreBuild && gradle liquibasePostBuild
#   gradle getsolr && gradle loadsolr
#
# This script captures that stack's pg_data + solr_var volumes and bakes them
# into ghcr.io/zfin/zfin-db-preloaded:<tag> and zfin-solr-preloaded:<tag>, which
# a feature stack can then boot instantly with per-container copy-on-write data
# (no ZFS required). See docker/postgresql/preloaded.Dockerfile and
# docker/solr/preloaded.Dockerfile.
#
# Usage:
#   docker/build-preloaded.sh [--project NAME] [--tag TAG] [--push] [--no-stop] [--keep-tarballs]
#
#   --project NAME   Compose project whose volumes to capture (default: $COMPOSE_PROJECT_NAME or "dazed")
#   --tag TAG        Preloaded image tag (default: YYYY-MM-DD)
#   --push           docker push the built images
#   --no-stop        Don't stop db/solr before capture (only safe if already stopped)
#   --keep-tarballs  Leave the intermediate .tgz files in the build contexts
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Pull COMPOSE_PROJECT_NAME / ZFIN_RELEASE from the docker .env if present.
if [[ -f "$HERE/.env" ]]; then set -a; . "$HERE/.env"; set +a; fi

PROJECT="${COMPOSE_PROJECT_NAME:-dazed}"
TAG="$(date +%Y-%m-%d)"
REGISTRY="ghcr.io/zfin"
PUSH=0
STOP=1
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --tag)     TAG="$2"; shift 2 ;;
    --push)    PUSH=1; shift ;;
    --no-stop) STOP=0; shift ;;
    --keep-tarballs) KEEP=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

: "${ZFIN_RELEASE:?ZFIN_RELEASE must be set (from docker/.env or the environment)}"

PG_VOL="${PROJECT}_pg_data"
SOLR_VOL="${PROJECT}_solr_var"

echo ">> preloaded build: project=$PROJECT release=$ZFIN_RELEASE tag=$TAG"

# Both named volumes must exist, or the capture would silently produce empties.
for v in "$PG_VOL" "$SOLR_VOL"; do
  if ! docker volume inspect "$v" >/dev/null 2>&1; then
    echo "!! volume '$v' not found -- is project '$PROJECT' loaded? (try --project)" >&2
    exit 1
  fi
done

# Quiesce Postgres/Solr so the on-disk state we tar is clean, not mid-write.
if [[ "$STOP" == 1 ]]; then
  echo ">> stopping db + solr in project '$PROJECT' for a consistent capture"
  docker compose -p "$PROJECT" stop db solr
fi

capture() {  # capture <volume> <output-tgz>
  local vol="$1" out="$2"
  echo ">> capturing $vol -> $out"
  docker run --rm \
    -v "$vol":/data:ro \
    -v "$(dirname "$out")":/out \
    alpine tar czf "/out/$(basename "$out")" -C /data .
}

PG_TARBALL="$HERE/postgresql/pgdata.tgz"
SOLR_TARBALL="$HERE/solr/solrvar.tgz"
capture "$PG_VOL" "$PG_TARBALL"
capture "$SOLR_VOL" "$SOLR_TARBALL"

if [[ "$STOP" == 1 ]]; then
  echo ">> restarting db + solr"
  docker compose -p "$PROJECT" start db solr
fi

DB_IMAGE="$REGISTRY/zfin-db-preloaded:$TAG"
SOLR_IMAGE="$REGISTRY/zfin-solr-preloaded:$TAG"

echo ">> building $DB_IMAGE"
docker build \
  --build-arg ZFIN_RELEASE="$ZFIN_RELEASE" \
  --build-arg PGDATA_TARBALL="pgdata.tgz" \
  -f "$HERE/postgresql/preloaded.Dockerfile" \
  -t "$DB_IMAGE" \
  "$HERE/postgresql"

echo ">> building $SOLR_IMAGE"
docker build \
  --build-arg ZFIN_RELEASE="$ZFIN_RELEASE" \
  --build-arg SOLRVAR_TARBALL="solrvar.tgz" \
  -f "$HERE/solr/preloaded.Dockerfile" \
  -t "$SOLR_IMAGE" \
  "$HERE/solr"

if [[ "$KEEP" != 1 ]]; then
  rm -f "$PG_TARBALL" "$SOLR_TARBALL"
fi

if [[ "$PUSH" == 1 ]]; then
  echo ">> pushing"
  docker push "$DB_IMAGE"
  docker push "$SOLR_IMAGE"
fi

echo ">> done:"
echo "     $DB_IMAGE"
echo "     $SOLR_IMAGE"
echo ">> point a feature .env at these via ZFIN_DB_IMAGE / ZFIN_SOLR_IMAGE (see #3, compose override)."
