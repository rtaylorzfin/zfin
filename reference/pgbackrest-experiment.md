# pgBackRest experiment results (2026-04-01)

## Goal

Evaluate pgBackRest as a faster alternative to pg_dump/pg_restore for both
production backups and dev database refreshes.

## Setup

- PostgreSQL 18 running in Docker
- pgBackRest 2.58.0 installed in the db container
- Compression: lz4 level 1 (fastest)
- Parallelism: 4 processes
- WAL archiving enabled via `archive_command`
- Database size: 14.8GB (503 tables in public schema)

### Configuration files

- `docker/postgresql/Dockerfile` — installs pgBackRest
- `docker/postgresql/pgbackrest.conf` — stanza and repo config
- `docker/docker-compose.yml` — WAL archiving params, pgbackrest volume

### Key config details

pgBackRest requires:
- `archive_mode=on` in PostgreSQL
- `archive_command=pgbackrest --stanza=zfindb archive-push %p`
- `wal_level=replica`
- Data directory is `/var/lib/postgresql/18/docker` (not `/var/lib/postgresql/data`)

### Setup gotchas encountered

1. **Data directory path**: PostgreSQL 18 Docker image uses `/var/lib/postgresql/18/docker`,
   not the commonly documented `/var/lib/postgresql/data`.

2. **Permissions**: pgBackRest commands must run as the `postgres` user. The `stanza-create`
   and `backup` commands must use `--user postgres` or `su postgres`. Running as root
   creates files that the postgres-user archive_command can't read.

3. **Directory creation**: `/tmp/pgbackrest`, `/var/lib/pgbackrest`, and `/var/log/pgbackrest`
   must exist and be owned by `postgres` before the first archive-push.

4. **Recovery after restore**: pgBackRest restore puts the cluster in recovery mode.
   It auto-promotes on startup in most cases, but may need `pg_ctl promote` if it
   stays in standby. Check with `SELECT pg_is_in_recovery()`.

## Benchmark results

| Operation | Time | Output size | Notes |
|-----------|------|-------------|-------|
| pg_dump -Fc | 4m 16s | 1.3GB | Single-threaded, zlib compression |
| pgBackRest full backup | 1m 48s | 4.5GB | 4 processes, lz4 |
| pgBackRest incr backup | 2.8s | 478B | Nothing changed since full |
| pgBackRest full restore | 1m 37s | 14.8GB | 4 processes |
| pgBackRest delta restore | **10s** | 23.1GB | Only checksums changed files |
| pg_restore -j 8 | crashed (OOM) | — | Tried restoring into 2nd DB while 1st running |

### Key observations

- **Full backup 2.4x faster** than pg_dump (1:48 vs 4:16)
- **Incremental backup near-instant** when nothing changed (2.8s)
- **Delta restore is the standout**: 10 seconds to refresh a 23GB database when files
  are already close to the backup. This is the dev workflow win.
- **Full restore comparable** to what pg_restore would be (~1:37 vs estimated ~3-4 min)
- **Backup is larger** (4.5GB vs 1.3GB) because pgBackRest is physical — includes indexes,
  WAL, and all table data (can't exclude `external_resource.*` like pg_dump does)

## Trade-offs

### pgBackRest advantages
- Incremental backups (only changed pages)
- Delta restore (only replace changed files) — massive win for dev refreshes
- Better compression speed (lz4 ~3x faster than zlib)
- Parallel backup AND restore
- Built-in backup verification (`pgbackrest verify`)
- Continuous WAL archiving enables point-in-time recovery

### pgBackRest disadvantages
- Physical backup — backs up entire cluster, not per-database
- Cannot exclude specific tables (no `--exclude-table-data` equivalent)
- Requires WAL archiving enabled (small write overhead)
- More complex setup (stanza management, WAL archive, permissions)
- Cannot restore to a different PostgreSQL major version
- Larger backup size since it includes indexes and all data

## Recommendations

1. **Dev database refresh**: pgBackRest delta restore (10s) should replace
   `gradle loaddb` (pg_restore -j 8, several minutes). Requires keeping a
   pgBackRest backup in a Docker volume.

2. **Production backup**: pgBackRest full + daily incremental could replace
   or supplement the current pg_dump workflow. The continuous WAL archiving
   also enables point-in-time recovery, which pg_dump cannot do.

3. **Keep pg_dump for portability**: pg_dump is still needed for creating
   portable database exports (e.g., for sharing with collaborators, loading
   into different PostgreSQL versions, or excluding specific table data).

## Commands reference

```bash
# Initial setup (after container start with fresh volume)
docker compose exec --user postgres db pgbackrest --stanza=zfindb stanza-create

# Full backup
docker compose exec --user postgres db pgbackrest --stanza=zfindb --type=full backup

# Incremental backup
docker compose exec --user postgres db pgbackrest --stanza=zfindb --type=incr backup

# Check backup status
docker compose exec --user postgres db pgbackrest --stanza=zfindb info

# Delta restore (stop container first, then use run with entrypoint override)
docker compose stop db
docker compose run --rm --entrypoint bash db -c '
  chown -R postgres:postgres /var/lib/pgbackrest /var/log/pgbackrest /tmp/pgbackrest
  su postgres -c "pgbackrest --stanza=zfindb --delta --process-max=4 restore"
'
docker compose up -d db
```
