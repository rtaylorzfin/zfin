-- Add ENSDARG-fallback EnsemblStartEndLoader rows that the gbrowse-refresh
-- path (Refresh-GBrowse-Tracks_d via updateSequenceFeatureChromosomeLocation.sql)
-- does not cover: genes with an ENSDARG accession but no ENSDART transcript
-- link. Also tag the zmp gbrowse track for the second ZMP pub. Strictly
-- additive on top of the daily refresh — assumes Refresh-GBrowse-Tracks_d
-- has already run today and DELETE+INSERTed the bulk of UCSC, Ensembl, Zfin,
-- and DirectSubmission rows.

begin work;

-- Build tmp_gene: ENSDARG accessions already covered by ENSDART-based
-- coordinates from the gbrowse-refresh path. These genes are excluded from
-- the fallback insert below.
create temp table tmp_gene (accnum1 varchar(50));
create index tmp_gene_accnum1_idx on tmp_gene (accnum1);

insert into tmp_gene (accnum1)
select distinct ensm_ensdarg_id
  from gff3, ensdar_mapping
 where gff_parent like 'ENSDART%'
   and gff_parent = ensm_ensdart_id;

select count(*) as tmp_gene_count from tmp_gene;

-- Add ENSDARG-fallback EnsemblStartEndLoader rows for genes not in tmp_gene.
-- Introduced by ZFIN-9989 (PR #1619) for genes whose ENSDARG accession has no
-- ENSDART transcripts (so the gbrowse-refresh ENSDART path produces nothing
-- for them). The NOT EXISTS clause makes this idempotent — subsequent runs
-- skip rows already inserted. We can't rely on uk_sfclg_unique_location +
-- ON CONFLICT here because that constraint covers sfclg_assembly and
-- sfclg_location_subsource, which we leave NULL; PostgreSQL treats NULLs in
-- unique indexes as distinct, so the constraint doesn't fire on rerun.
insert into sequence_feature_chromosome_location_generated (sfclg_data_zdb_id,
       sfclg_chromosome,
       sfclg_start,
       sfclg_end,
       sfclg_acc_num,
       sfclg_location_source,
       sfclg_fdb_db_id, sfclg_evidence_code)
select distinct dblink_linked_recid,
       gff_seqname,
       gff_start,
       gff_end,
       dblink_acc_num,
       'EnsemblStartEndLoader',
       fdb_db_pk_id, 'ZDB-TERM-170419-250'
  from db_link, foreign_db, foreign_db_contains, gff3
 where dblink_fdbcont_zdb_id = fdbcont_zdb_id
   and dblink_acc_num = gff_id
   and fdb_db_pk_id = fdbcont_fdb_db_id
   and dblink_acc_num like 'ENSDARG%'
   and gff_feature = 'gene'
   and dblink_linked_recid not in (
     select distinct dblink_linked_recid
       from db_link, tmp_gene, foreign_db_contains
      where dblink_fdbcont_zdb_id = fdbcont_zdb_id
        and dblink_acc_num = accnum1
   )
   -- exclude ExpressionAtlas (fdb_db_display_name = 'ExpressionAtlas')
   and fdb_db_pk_id != 91
   and not exists (
     select 1 from sequence_feature_chromosome_location_generated existing
      where existing.sfclg_data_zdb_id = dblink_linked_recid
        and existing.sfclg_location_source = 'EnsemblStartEndLoader'
        and existing.sfclg_acc_num = dblink_acc_num
        and existing.sfclg_chromosome = gff_seqname
        and existing.sfclg_start = gff_start
        and existing.sfclg_end = gff_end
   )
;

-- Tag zmp gbrowse track for the additional ZMP pub. Refresh-GBrowse-Tracks_d
-- only tags ZDB-PUB-130425-4; we cover ZDB-PUB-250905-18 here.
update sequence_feature_chromosome_location_generated
   set sfclg_gbrowse_track = 'zmp'
 where sfclg_pub_zdb_id = 'ZDB-PUB-250905-18'
   and (sfclg_gbrowse_track is null or sfclg_gbrowse_track <> 'zmp');

commit work;
