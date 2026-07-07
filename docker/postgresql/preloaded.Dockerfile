# Preloaded DB image: the stock zfin-db image (schema/config only) with a
# pre-loaded PGDATA baked in, so a container starts with a fully populated
# `zfindb` and NO getdb/loaddb/liquibase step on the dev critical path.
#
# The tarball is the full contents of the `pg_data` volume (i.e. everything
# under /var/lib/postgresql, including PG18's version-subdir layout), captured
# from an already-loaded dev stack by build-preloaded.groovy. We tar the whole
# volume root so we never need to know the exact PGDATA subpath.
#
# postgres:18 declares VOLUME /var/lib/postgresql, so at runtime Docker seeds a
# fresh (anonymous or per-project) volume by COPYING this baked content -- giving
# every feature stack its own fully independent DB with no shared state and no ZFS
# needed. NOT copy-on-write: it's a full ~32G copy per feature (the image is ~98%
# PGDATA; only the ~0.7G stock base layers are shared). `down -v` discards it.
ARG ZFIN_RELEASE=main
FROM ghcr.io/zfin/zfin-db:${ZFIN_RELEASE}

# ADD auto-extracts a local tar into the destination. The tarball is captured
# straight from the postgres data volume, so file ownership (the postgres
# uid/gid) is already correct and ADD preserves it. We deliberately DO NOT
# `RUN chown -R` afterward: that rewrites every file into a second multi-GB
# layer, which doubled the on-disk image size (~34GB observed on first build).
ARG PGDATA_TARBALL=pgdata.tgz
ADD ${PGDATA_TARBALL} /var/lib/postgresql/
