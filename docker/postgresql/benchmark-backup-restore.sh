#!/bin/bash
# Benchmark: pg_dump/pg_restore vs pgBackRest
# Run inside the db container: docker compose exec --user postgres db bash /path/to/benchmark-backup-restore.sh
#
# Prerequisites:
#   - Database 'zfindb' exists and has data
#   - pgBackRest stanza already created:
#       pgbackrest --stanza=zfindb stanza-create

set -e

DBNAME=zfindb
DUMP_FILE=/tmp/zfindb_benchmark.bak
PG_RESTORE_JOBS=8
PGBACKREST_PROCS=4

echo "============================================"
echo "Database backup/restore benchmark"
echo "============================================"
echo ""

# --- pg_dump ---
echo "=== pg_dump -Fc (custom format, compressed) ==="
time pg_dump -Fc -d "$DBNAME" -f "$DUMP_FILE" 2>&1
echo "Dump size: $(du -h $DUMP_FILE | cut -f1)"
echo ""

# --- pgBackRest full backup ---
echo "=== pgBackRest full backup (lz4, $PGBACKREST_PROCS processes) ==="
time pgbackrest --stanza=zfindb --type=full backup 2>&1
echo ""

# --- pgBackRest incremental backup ---
echo "=== pgBackRest incremental backup ==="
time pgbackrest --stanza=zfindb --type=incr backup 2>&1
echo ""

# --- Show backup sizes ---
echo "=== pgBackRest backup info ==="
pgbackrest --stanza=zfindb info
echo ""

# --- pg_restore timing (into a test database) ---
echo "=== pg_restore -j $PG_RESTORE_JOBS (parallel restore) ==="
dropdb --if-exists "${DBNAME}_benchmark_test"
createdb "${DBNAME}_benchmark_test"
time pg_restore -j "$PG_RESTORE_JOBS" -d "${DBNAME}_benchmark_test" "$DUMP_FILE" 2>&1 || true
dropdb --if-exists "${DBNAME}_benchmark_test"
echo ""

# Note: pgBackRest restore requires stopping postgres, so we only print instructions
echo "=== pgBackRest restore (manual — requires stopping postgres) ==="
echo "To test full restore:"
echo "  pg_ctl stop -D /var/lib/postgresql/18/docker"
echo "  rm -rf /var/lib/postgresql/18/docker/*"
echo "  pgbackrest --stanza=zfindb --process-max=$PGBACKREST_PROCS restore"
echo "  pg_ctl start -D /var/lib/postgresql/18/docker"
echo ""
echo "To test delta restore (much faster for refreshes):"
echo "  pg_ctl stop -D /var/lib/postgresql/18/docker"
echo "  pgbackrest --stanza=zfindb --process-max=$PGBACKREST_PROCS --delta restore"
echo "  pg_ctl start -D /var/lib/postgresql/18/docker"
echo ""

# Cleanup
rm -f "$DUMP_FILE"
echo "Done."
