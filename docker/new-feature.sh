#!/usr/bin/env bash
# Provision an isolated feature dev stack: a git worktree plus its own Compose
# project (own network, volumes, loopback IP, hostname) booted from preloaded
# DB + Solr images. Several feature branches can then run in parallel without
# branch-switching or reloading data. See workbench/feature-lifecycle.md.
#
# What Compose already handles per-project (no work here): the private network,
# per-project volumes, and intra-network DNS (`db`/`solr` resolve to THIS
# project's containers). What this script handles are the HOST-side concerns
# that escape the Docker network -- published ports and the loopback/hostname
# mapping -- plus the worktree + per-feature .env.
#
# Usage:
#   docker/new-feature.sh <name> [--base BRANCH] [--branch NAME]
#                                [--tag TAG] [--ip 127.0.0.X] [--up] [--hosts]
#
#   <name>          Feature id, e.g. ZFIN-9002 -> project "zfin-9002",
#                   worktree "wt-zfin-9002", host "zfin-9002.zfin.test".
#   --base BRANCH   Start point for the new branch (default: main)
#   --branch NAME   Branch to create (default: <name>)
#   --tag TAG       Preloaded image tag (default: $PRELOADED_TAG or "latest")
#   --ip 127.0.0.X  Loopback IP for published ports (default: next free)
#   --up            Bring the stack up after provisioning
#   --hosts         Add "<ip> <host>" to /etc/hosts (uses sudo)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # .../zfin/docker
REPO="$(cd "$HERE/.." && pwd)"
WT_PARENT="$(cd "$REPO/.." && pwd)"

BASE=main
TAG="${PRELOADED_TAG:-latest}"
IP=""
BRANCH=""
DO_UP=0
DO_HOSTS=0
NAME=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)   BASE="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --tag)    TAG="$2"; shift 2 ;;
    --ip)     IP="$2"; shift 2 ;;
    --up)     DO_UP=1; shift ;;
    --hosts)  DO_HOSTS=1; shift ;;
    -*) echo "unknown arg: $1" >&2; exit 2 ;;
    *)  NAME="$1"; shift ;;
  esac
done

[[ -n "$NAME" ]] || { echo "usage: new-feature.sh <name> [opts]" >&2; exit 2; }
[[ -f "$HERE/.env" ]] || { echo "!! $HERE/.env not found (needed as the base env)" >&2; exit 1; }

SLUG="$(echo "$NAME" | tr '[:upper:]' '[:lower:]')"   # Compose projects must be lowercase
PROJECT="$SLUG"
BRANCH="${BRANCH:-$NAME}"
HOST="$SLUG.zfin.test"
WT="$WT_PARENT/wt-$SLUG"

# Allocate the next free 127.0.0.X across existing feature worktrees, unless
# the caller pinned one. This is the host-side bit Compose can't do for us:
# published ports must land on a per-feature IP so stacks don't fight over
# :443/:5432, and so <name>.zfin.test resolves to exactly one stack.
if [[ -z "$IP" ]]; then
  max=1
  for f in "$WT_PARENT"/wt-*/docker/.env; do
    [[ -f "$f" ]] || continue
    o=$(grep -E '^LOOPBACK_IP=' "$f" | tail -1 | cut -d= -f2); o=${o##*.}
    [[ "$o" =~ ^[0-9]+$ ]] && (( o > max )) && max=$o
  done
  IP="127.0.0.$((max+1))"
fi

echo ">> feature=$NAME project=$PROJECT host=$HOST ip=$IP tag=$TAG base=$BASE"

# 1. worktree + branch (separate host path => its own mounted source tree)
if [[ ! -d "$WT" ]]; then
  git -C "$REPO" worktree add "$WT" -b "$BRANCH" "$BASE"
else
  echo ">> worktree $WT already exists, reusing"
fi

# 2. per-feature .env: start from the base env, strip the keys we own, append
#    our overrides. Published ports bind to $IP with standard ports so URLs are
#    clean (https://$HOST, no port suffix).
mkdir -p "$WT/docker"
OWNED='COMPOSE_PROJECT_NAME|DOCKER_SOURCE_ROOTS_PATH|DOCKER_VIRTUAL_HOST|DOCKER_HTTPD_HTTP_PORT|DOCKER_HTTPD_HTTPS_PORT|DOCKER_DB_PORT|DOCKER_SOLR_PORT|DOCKER_JENKINS_HTTP_PORT|DOCKER_TOMCATDEBUG_PORT|ZFIN_DB_IMAGE|ZFIN_SOLR_IMAGE|LOOPBACK_IP'
grep -vE "^($OWNED)=" "$HERE/.env" > "$WT/docker/.env"
cat >> "$WT/docker/.env" <<EOF

# --- added by new-feature.sh for $NAME ---
COMPOSE_PROJECT_NAME=$PROJECT
DOCKER_SOURCE_ROOTS_PATH=$WT
DOCKER_VIRTUAL_HOST=$HOST
LOOPBACK_IP=$IP
DOCKER_HTTPD_HTTP_PORT=$IP:80
DOCKER_HTTPD_HTTPS_PORT=$IP:443
DOCKER_DB_PORT=$IP:5432
DOCKER_SOLR_PORT=$IP:8983
DOCKER_JENKINS_HTTP_PORT=$IP:9499
DOCKER_TOMCATDEBUG_PORT=$IP:5000
ZFIN_DB_IMAGE=ghcr.io/zfin/zfin-db-preloaded:$TAG
ZFIN_SOLR_IMAGE=ghcr.io/zfin/zfin-solr-preloaded:$TAG
EOF

# 3. name resolution. On Linux all of 127.0.0.0/8 is already loopback, so no
#    interface alias is needed -- only a name -> IP mapping. (On macOS you also
#    need: sudo ifconfig lo0 alias $IP.) A dnsmasq *.zfin.test wildcard avoids
#    editing /etc/hosts per feature; --hosts is the low-tech fallback.
if [[ "$DO_HOSTS" == 1 ]]; then
  if ! grep -qE "[[:space:]]$HOST(\$|[[:space:]])" /etc/hosts; then
    echo ">> adding '$IP $HOST' to /etc/hosts (sudo)"
    echo "$IP $HOST" | sudo tee -a /etc/hosts >/dev/null
  else
    echo ">> /etc/hosts already resolves $HOST"
  fi
fi

# 4. Compose command. Use THIS repo's compose files (so the preloaded overlay is
#    found regardless of the worktree's branch), but the worktree's .env + source.
COMPOSE=(docker compose
  --project-name "$PROJECT"
  --env-file "$WT/docker/.env"
  -f "$HERE/docker-compose.yml"
  -f "$HERE/docker-compose.preloaded.yml")

if [[ "$DO_UP" == 1 ]]; then
  echo ">> ${COMPOSE[*]} up -d"
  "${COMPOSE[@]}" up -d
fi

cat <<EOF

>> provisioned $NAME
     worktree : $WT
     branch   : $BRANCH  (off $BASE)
     project  : $PROJECT
     url      : https://$HOST   (-> $IP)
     images   : zfin-{db,solr}-preloaded:$TAG

next:
  # bring up (if you didn't pass --up):
  ${COMPOSE[*]} up -d
  # apply this branch's schema/solr deltas on top of preloaded:
  ${COMPOSE[*]} run --rm compile bash -lc 'gradle liquibasePostBuild'
  # tear down (-v discards this stack's DB/Solr copy):
  ${COMPOSE[*]} down -v
EOF
