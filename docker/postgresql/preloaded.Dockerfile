# Preloaded DB image: the stock zfin-db image (schema/config only) with a
# pre-loaded PGDATA baked in, so a container starts with a fully populated
# `zfindb` and NO getdb/loaddb/liquibase step on the dev critical path.
#
# The tarball is the full contents of the `pg_data` volume (i.e. everything
# under /var/lib/postgresql, including PG18's version-subdir layout), captured
# from an already-loaded dev stack by build-preloaded.sh. We tar the whole
# volume root so we never need to know the exact PGDATA subpath.
#
# postgres:18 declares VOLUME /var/lib/postgresql, so at runtime Docker seeds a
# fresh (anonymous or per-project) volume from this baked content -- giving every
# feature stack its own copy-on-write DB with no shared state and no ZFS needed.
# `docker compose down -v` discards it.
ARG ZFIN_RELEASE
FROM ghcr.io/zfin/zfin-db:${ZFIN_RELEASE}

# ADD auto-extracts a local tar into the destination.
ARG PGDATA_TARBALL=pgdata.tgz
ADD ${PGDATA_TARBALL} /var/lib/postgresql/

# tar already preserves ownership, but re-assert it in case the capture host
# used different uid/gid mapping than the postgres image's `postgres` user.
RUN chown -R postgres:postgres /var/lib/postgresql
