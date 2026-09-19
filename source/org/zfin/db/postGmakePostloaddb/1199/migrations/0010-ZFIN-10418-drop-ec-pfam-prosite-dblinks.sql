--liquibase formatted sql

-- ZFIN-10418: EC, Pfam, and PROSITE dblinks were audited and found to have no
-- consumer outside the UniProt secondary load itself (see dead-code-evidence.txt
-- on the ticket) -- InterPro and PDB are the only non-GO dblink families with
-- live consumers (gene-page protein domains, PdbLinkController). The load no
-- longer refreshes these three families as of this ticket; this migration
-- removes the existing rows so they don't sit around stale and unmaintained.
--
-- Targets exactly the reference-database rows the load used when creating
-- these dblinks (formerly SecondaryTermLoadService.EC_REFERENCE_DATABASE_ID /
-- PFAM_REFERENCE_DATABASE_ID / PROSITE_REFERENCE_DATABASE_ID, removed by this
-- same ticket), so this cannot touch dblinks from any other source.
--
-- Mirrors what SequenceRepository.removeDBLinks does at runtime: clear
-- record_attribution and zdb_active_data for each dblink before deleting the
-- db_link row itself.

--changeset rtaylor:ZFIN-10418
DELETE FROM record_attribution
WHERE recattrib_data_zdb_id IN (
    SELECT dblink_zdb_id FROM db_link
    WHERE dblink_fdbcont_zdb_id IN ('ZDB-FDBCONT-040412-49', 'ZDB-FDBCONT-040412-50', 'ZDB-FDBCONT-040412-51')
);

DELETE FROM zdb_active_data
WHERE zactvd_zdb_id IN (
    SELECT dblink_zdb_id FROM db_link
    WHERE dblink_fdbcont_zdb_id IN ('ZDB-FDBCONT-040412-49', 'ZDB-FDBCONT-040412-50', 'ZDB-FDBCONT-040412-51')
);

DELETE FROM db_link
WHERE dblink_fdbcont_zdb_id IN ('ZDB-FDBCONT-040412-49', 'ZDB-FDBCONT-040412-50', 'ZDB-FDBCONT-040412-51');
