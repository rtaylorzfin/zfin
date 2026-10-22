#!/bin/bash

# Unload the database to $pth/<YYYY.MM.DD.N>/<YYYY.MM.DD.N>.bak, with a pg_dumpall
# of the cluster next to it as $pth/<YYYY.MM.DD.N>.dumpall.
#
# Exits non-zero on any failure, and a failed dump leaves nothing behind: jobs
# that load "the newest unload" must never pick up a partial .bak.

set -uo pipefail

: "${INSTANCE:?INSTANCE must be set}" "${SOURCEROOT:?SOURCEROOT must be set}"
: "${PGBINDIR:?PGBINDIR must be set}" "${DBNAME:?DBNAME must be set}"

fail() { echo "unload_production failed: $*" >&2; exit 1; }

pth=/research/zunloads/databases/${INSTANCE}
mkdir -p "$pth" || fail "cannot create $pth"

"${SOURCEROOT}/server_apps/DB_maintenance/set_unload_timestamp.sh" || fail "set_unload_timestamp.sh"

# First free name for today: YYYY.MM.DD.1, .2, ...
day=$(date +"%Y.%m.%d")
n=1
while [[ -d "$pth/$day.$n" ]]; do n=$((n + 1)); done
dirname=$day.$n

echo "pg_dumpall starting"
if ! "${PGBINDIR}/pg_dumpall" --clean --verbose --no-role-passwords > "$pth/$dirname.dumpall"; then
    rm -f "$pth/$dirname.dumpall"
    fail "pg_dumpall"
fi

mkdir "$pth/$dirname" || fail "cannot create $pth/$dirname"

echo "pg_dump starting"
# Exclude data (but keep structure) for tables in the external_resource schema.
# These tables are large mirrors of external data that get reloaded from their sources.
if ! "${PGBINDIR}/pg_dump" -Fc "${DBNAME}" --exclude-table-data='external_resource.*' -f "$pth/$dirname/$dirname.bak"; then
    rm -rf "${pth:?}/$dirname"
    fail "pg_dump (partial dump removed)"
fi

chgrp -R fishadmin "$pth/$dirname"
chmod -R g+rw "$pth/$dirname"

echo "unload_production completed successfully: $pth/$dirname"
