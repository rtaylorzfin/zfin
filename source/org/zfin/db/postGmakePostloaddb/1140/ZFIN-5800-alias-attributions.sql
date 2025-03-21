--liquibase formatted sql
--changeset rtaylor:ZFIN-5800-alias-attributions

-- This sql will grab all the aliases with their associated publications, then join to the alias table to get
-- the marker ID, then join back again to the record attribution table to find those markers
-- without the direct attribution. ("ra2.recattrib_source_zdb_id IS NULL" in the where clause means no direct attribution)

WITH alias_attributions AS (
    SELECT DISTINCT
        ra1.recattrib_source_zdb_id AS alias_pub,
        ra1.recattrib_data_zdb_id AS alias_id,
        dalias_data_zdb_id AS alias_for,
        ra2.recattrib_source_zdb_id
    FROM
        record_attribution ra1
            JOIN data_alias ON ra1.recattrib_data_zdb_id = dalias_zdb_id
            LEFT JOIN record_attribution ra2 ON dalias_data_zdb_id = ra2.recattrib_data_zdb_id
            AND ra1.recattrib_source_zdb_id = ra2.recattrib_source_zdb_id
    WHERE
            ra1.recattrib_data_zdb_id LIKE 'ZDB-DALIAS%'
      AND ra2.recattrib_source_zdb_id IS NULL -- no attribution for the aliased gene/marker
    )
INSERT INTO record_attribution (recattrib_data_zdb_id, recattrib_source_zdb_id, recattrib_source_type, recattrib_created_at)
SELECT DISTINCT
    alias_attributions.alias_for,
    alias_attributions.alias_pub,
    'standard',
    NOW()
FROM
    alias_attributions;

