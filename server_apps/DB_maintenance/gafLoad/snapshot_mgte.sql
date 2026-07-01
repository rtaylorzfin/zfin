-- Snapshot GOA marker_go_term_evidence rows into a staging table for a load
-- before/after comparison.
--
-- Captures one row per annotation with the columns needed to diff at the logical
-- (churn-excluded) level: the identity fields plus zdb_id and the aggregated
-- inferred_from (with/from) from inference_group_member. Scoped to the GOA
-- organization, since that is what the GAF-GOA load touches.
--
-- The CSV export is done by the caller (jenkins job) via
--   \copy (SELECT * FROM <stage> ORDER BY zdb_id) TO STDOUT CSV HEADER
-- because psql's \copy does not interpolate :variables. This file only builds
-- the staging table; diff_mgte.sql consumes the before/after tables.
--
-- Usage (run once before the load, once after):
--   psql -v ON_ERROR_STOP=1 -h $PGHOST -d $DBNAME \
--        -v stage=tmp_gaf_mgoe_before -f snapshot_mgte.sql

\set ON_ERROR_STOP on

DROP TABLE IF EXISTS :stage;

CREATE TABLE :stage AS
WITH inf AS (
    SELECT infgrmem_mrkrgoev_zdb_id AS zid,
           string_agg(infgrmem_inferred_from, '|' ORDER BY infgrmem_inferred_from) AS inferred_from
    FROM inference_group_member
    GROUP BY infgrmem_mrkrgoev_zdb_id
)
SELECT e.mrkrgoev_zdb_id                              AS zdb_id,
       e.mrkrgoev_mrkr_zdb_id                         AS marker,
       e.mrkrgoev_term_zdb_id                         AS term,
       e.mrkrgoev_source_zdb_id                       AS source,
       e.mrkrgoev_evidence_code                       AS evidence,
       COALESCE(e.mrkrgoev_relation_term_zdb_id, '-') AS relation,
       e.mrkrgoev_annotation_organization_created_by  AS created_by,
       COALESCE(e.mrkrgoev_contributed_by, '-')       AS contributed_by,
       COALESCE(e.mrkrgoev_protein_accession, '-')    AS protein_acc,
       COALESCE(i.inferred_from, '')                  AS inferred_from
FROM marker_go_term_evidence e
JOIN marker_go_term_evidence_annotation_organization o
      ON o.mrkrgoevas_pk_id = e.mrkrgoev_annotation_organization
     AND o.mrkrgoevas_annotation_organization = 'GOA'
LEFT JOIN inf i ON i.zid = e.mrkrgoev_zdb_id;
