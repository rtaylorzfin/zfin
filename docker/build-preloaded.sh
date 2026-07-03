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
#   --slim           Before capture, empty jobs-only tables + compact WAL for a leaner
#                    image. NOTE: mutates the source stack's DB, but only tables proven
#                    NOT read by the webapp (reloadable), and WAL auto-regrows. See
#                    workbench/db-slim-candidates.md.
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
SLIM=0

# Tables proven jobs-only (NOT read by the webapp) -> safe to empty for a lean
# image. Evidence (ORM + call-site trace): workbench/db-slim-candidates.md.
SLIM_TABLES="gff3 gff3_ncbi gff3_ncbi_attribute expression_search_anatomy_generated feature_stats_old"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --tag)     TAG="$2"; shift 2 ;;
    --slim)    SLIM=1; shift ;;
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

if [[ "$SLIM" == 1 && "$STOP" != 1 ]]; then
  echo "!! --slim needs the db stopped; don't combine with --no-stop" >&2
  exit 2
fi

# Empty jobs-only tables and shed stale WAL so the captured snapshot is lean.
# Safe by design: the truncated tables are not read by the webapp
# (workbench/db-slim-candidates.md) and are reloadable. WAL is collapsed with
# pg_resetwal (a single CHECKPOINT won't -- PostgreSQL's segment-retention
# estimate decays only ~2%/checkpoint). pg_resetwal is safe ONLY after a clean
# shutdown, which we guarantee by stopping the throwaway server first. Runs only
# while the real db container is stopped (single writer on the volume).
slim_pg() {
  local img="$REGISTRY/zfin-db:$ZFIN_RELEASE" name="preloaded-slim-$$" i ok=0
  echo ">> [slim] throwaway postgres on $PG_VOL: TRUNCATE jobs-only tables"
  docker run -d --name "$name" \
    -e POSTGRES_HOST_AUTH_METHOD=trust \
    -v "$PG_VOL":/var/lib/postgresql \
    "$img" >/dev/null
  for i in $(seq 1 60); do
    docker exec "$name" pg_isready -U postgres -d zfindb >/dev/null 2>&1 && { ok=1; break; }
    sleep 1
  done
  [[ "$ok" == 1 ]] || { echo "!! [slim] postgres not ready" >&2; docker logs --tail 30 "$name" >&2; docker rm -f "$name" >/dev/null; exit 1; }
  docker exec -i "$name" psql -U postgres -d zfindb -v ON_ERROR_STOP=1 <<SQL
DO \$\$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[$(printf "'%s'," $SLIM_TABLES | sed 's/,$//')]
  LOOP
    IF to_regclass(t) IS NOT NULL THEN
      EXECUTE format('TRUNCATE TABLE %I CASCADE', t);
      RAISE NOTICE 'slimmed %', t;
    END IF;
  END LOOP;
END \$\$;
SQL
  docker stop "$name" >/dev/null   # clean shutdown: required precondition for pg_resetwal
  docker rm "$name" >/dev/null
  echo ">> [slim] pg_resetwal to shed stale WAL segments (safe after the clean shutdown above)"
  docker run --rm -u postgres -v "$PG_VOL":/var/lib/postgresql --entrypoint bash "$img" \
    -c 'pg_resetwal -f "$PGDATA"'
  echo ">> [slim] emptied: $SLIM_TABLES; WAL reset"
}

# Quiesce Postgres/Solr so the on-disk state we tar is clean, not mid-write.
if [[ "$STOP" == 1 ]]; then
  echo ">> stopping db + solr in project '$PROJECT' for a consistent capture"
  docker compose -p "$PROJECT" stop db solr
fi

if [[ "$SLIM" == 1 ]]; then
  slim_pg
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
