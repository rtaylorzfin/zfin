#!/bin/bash
#
# Drop, recreate, and restore a database from one unload file (pg_dump custom
# format), then vacuum analyze. Shared by `gradle loadDatabase` and
# compare_loads.sh so both go through exactly the same steps.
#
# Only sessions on DBNAME itself are ended, not every session on the server: the
# server may be serving other databases (e.g. a long churn sweep). The terminate,
# dropdb, createdb and pg_restore steps ignore errors (pg_restore routinely exits
# non-zero on harmless warnings: missing roles, extensions). The final vacuum
# (analyze) does not: stale planner statistics after a reload make downstream
# loads plan seq scans over large tables and run orders of magnitude slower
# (observed: ~1h39m vs ~6m for the UniProt diff load), so a failure there fails
# the script.
#
# Usage:
#   restore_unload.sh <dbname> <unload file>
#
# Env: PGBINDIR  path to the postgres client tools (optional)

DBNAME="${1:?usage: restore_unload.sh <dbname> <unload file>}"
UNLOAD="${2:?usage: restore_unload.sh <dbname> <unload file>}"
BIN="${PGBINDIR:+$PGBINDIR/}"

[[ -f "$UNLOAD" ]] || { echo "ERROR: no such unload file: $UNLOAD" >&2; exit 1; }

echo "Loading $UNLOAD into $DBNAME"
# One summary line ("Ended N connection(s)"); FILTER references `terminated` so the
# volatile call isn't optimized away.
"${BIN}psql" -tAq -v ON_ERROR_STOP=1 -d postgres -v db="$DBNAME" <<'SQL'
set client_min_messages = error;
select 'Ended ' || count(*) filter (where terminated) || ' connection(s)'
  from (select pg_terminate_backend(pid) as terminated
          from pg_stat_activity
         where datname = :'db' and pid <> pg_backend_pid()) as backends;
SQL
"${BIN}dropdb" "$DBNAME"
"${BIN}createdb" "$DBNAME"
"${BIN}pg_restore" -j 8 -d "$DBNAME" "$UNLOAD"
echo 'vacuum (analyze)' | "${BIN}psql" -v ON_ERROR_STOP=1 "$DBNAME" \
    || { echo "ERROR: vacuum (analyze) of $DBNAME failed" >&2; exit 1; }
