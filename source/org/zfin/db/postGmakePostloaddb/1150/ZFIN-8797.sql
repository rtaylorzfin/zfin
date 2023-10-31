--liquibase formatted sql
--changeset rtaylor:ZFIN-8797.sql

INSERT INTO external_note_type (extntype_name)
VALUES ('dblink');

UPDATE external_note
 SET extnote_note_type = 'dblink'
 WHERE extnote_source_zdb_id = 'ZDB-PUB-230615-71'
  AND extnote_note_type IS NULL;