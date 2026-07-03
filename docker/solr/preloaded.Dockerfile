# Preloaded Solr image: the stock zfin-solr image with a pre-populated
# `site_index` core baked in, so a container starts search-ready without the
# ~1-5 min loadsolr restore (let alone the ~36 min full reindex).
#
# The tarball is the full contents of the `solr_var` volume (/var/solr),
# captured from an already-loaded dev stack by build-preloaded.sh. On container
# start the image's sync-config.sh still runs and refreshes conf/, lib/, and
# log4j2.xml from the image templates, but leaves the baked index data/tlog/
# snapshots alone -- so config stays image-authoritative while the index is
# served from the preloaded capture.
ARG ZFIN_RELEASE=main
FROM ghcr.io/zfin/zfin-solr:${ZFIN_RELEASE}

# Ownership (the solr uid/gid) is preserved from the captured volume by ADD, so
# we skip a `RUN chown -R` — that rewrites every file into a second multi-GB
# layer and roughly doubles the image's on-disk size.
USER root
ARG SOLRVAR_TARBALL=solrvar.tgz
ADD ${SOLRVAR_TARBALL} /var/solr/
USER solr
