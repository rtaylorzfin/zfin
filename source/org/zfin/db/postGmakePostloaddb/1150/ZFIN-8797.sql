--liquibase formatted sql
--changeset rtaylor:ZFIN-8797.sql

-- fix null values for external notes loaded in past uniprot loads
INSERT INTO external_note_type (extntype_name)
VALUES ('dblink');

UPDATE external_note
 SET extnote_note_type = 'dblink'
 WHERE extnote_source_zdb_id = 'ZDB-PUB-230615-71'
  AND extnote_note_type IS NULL;
-- end fix for external notes

-- add column to uniprot_release table: upr_secondary_load_date
ALTER TABLE uniprot_release ADD COLUMN upr_secondary_load_date timestamp without time zone;