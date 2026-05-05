-- ============================================================================
-- Regenerate-Chromosome-Mart_d — runs downstream of Refresh-GBrowse-Tracks_d.
-- ============================================================================
-- The downstream-trigger wiring is in
-- server_apps/jenkins/jobs/Refresh-GBrowse-Tracks_d/config.xml
-- (hudson.tasks.BuildTrigger). The refresh job runs
-- server_apps/data_transfer/Ensembl/updateSequenceFeatureChromosomeLocation.sql,
-- which DELETE+INSERTs the bulk of UCSC, Ensembl, Zfin, and DirectSubmission
-- rows in sequence_feature_chromosome_location_generated. By the time this
-- script starts, the table is already in its post-refresh state, so this
-- script's only remaining work is the pieces the refresh path doesn't cover:
--
--   §E. ENSDARG-fallback EnsemblStartEndLoader rows for genes whose ENSDARG
--       accession has no ENSDART transcript link.
--   §H. zmp gbrowse-track tag (kept belt-and-suspenders for both ZMP pubs;
--       the refresh handles ZDB-PUB-130425-4 — re-tagging it here is a no-op
--       in steady state but acts as a fallback if refresh §H ever skips).
--
-- Section markers §A–§K below correspond to the same-named sections in the
-- refresh script. Sections marked "REDUNDANT — handled by refresh" are
-- commented out here; a follow-up PR will fully delete them.
-- ============================================================================

begin work;

-- ----------------------------------------------------------------------------
-- §A. REDUNDANT — handled by refresh §A
--     Bulk DELETE of the five sources we'd otherwise own here.
-- ----------------------------------------------------------------------------
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_location_source = 'ZfinGbrowseStartEndLoader';
--
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_location_source = 'ZfinGbrowseZv9StartEndLoader';
--
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_location_source = 'DirectSubmission';
--
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_location_source = 'EnsemblStartEndLoader';
--
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_location_source = 'UCSCStartEndLoader';

-- ----------------------------------------------------------------------------
-- §B. ACTIVE — build tmp_gff_start_end / tmp_gene from gff3 + ensdar_mapping.
--     Only tmp_gene.accnum1 is used downstream (by §E's "not in" filter); the
--     start / ender columns and the full structure here mirror refresh §B and
--     would be needed if any of §C / §D / §F were re-activated in this file.
-- ----------------------------------------------------------------------------
create temp table tmp_gff_start_end (accnum varchar(50),chrom varchar(20), gene varchar(50),
       start int,
       ender int
) ;

CREATE INDEX tmp_gff_start_end_acc ON tmp_gff_start_end (accnum);
CREATE INDEX tmp_gff_start_end_chrom ON tmp_gff_start_end (chrom);
CREATE INDEX tmp_gff_start_end_gene ON tmp_gff_start_end (gene);

insert into tmp_gff_start_end (accnum,chrom, gene)
select distinct gff_parent, gff_seqname, ensm_ensdarg_id
 from gff3, ensdar_mapping
 where gff_parent like 'ENSDART%'
and gff_parent = ensm_ensdart_id;

update tmp_gff_start_end
  set start = (select min(gff_start)
      	      	      from gff3
		      where gff_parent = accnum
		      and gff_seqname = chrom);

update tmp_gff_start_end
  set ender = (select max(gff_end)
      	      	      from gff3
		      where gff_parent = accnum
		      and gff_seqname = chrom);

select count(*) from tmp_gff_start_end
 where ender is null;

create index gene_index on tmp_gff_start_end (gene)
;
create index accnum_index on tmp_gff_start_end (accnum)
;
create temp table tmp_gene (accnum1 varchar(50) , chrom1 varchar(20), start int, ender int)
;

insert into tmp_gene (accnum1, chrom1)
 select distinct gene, chrom
  from tmp_gff_start_end;


create index gene2_index on tmp_gene (accnum1)
;
update tmp_gene
 set start = (select min(start)
      	      	      from tmp_gff_start_end
		      where  gene = accnum1
		      and chrom = chrom1);


update tmp_gene
 set ender = (select max(ender)
      	      	      from tmp_gff_start_end
		      where  gene = accnum1
		      and chrom = chrom1);

select count(*) from tmp_gene
 where start is null;

select count(*) from tmp_gene
 where ender is null;

-- ----------------------------------------------------------------------------
-- §C. REDUNDANT — handled by refresh §C
--     UCSC: build tmp_ucsc / tmp_ucsc_all and INSERT 'UCSCStartEndLoader' rows.
--     The UCSC INSERT here was already disabled before this branch
--     (commit 1c246db2, ZFIN-9989) because the refresh path was the canonical
--     writer. The pruneUcscLinks.sh post-step in regenChromosomeMart.sh removes
--     any UCSC rows whose accession isn't in UCSC's danRer11 refGene track.
-- ----------------------------------------------------------------------------
-- create temp table tmp_ucsc (geneId text,
-- 				chrom1 varchar(10),
-- 				accnum1 varchar(50),
-- 				source text,
-- 				fdb_db_pk_id int8)
-- ;
-- -- DISABLE UCSC record creation - comment out the insert
-- -- insert into tmp_ucsc (geneId, chrom1, accnum1, source, fdb_db_pk_id)
-- -- select ... (UCSC logic disabled)
-- ;
--
-- create temp table tmp_ucsc_all (counter int,
-- 			geneId text,
-- 			chrom1 varchar(10),
-- 			source text,
-- 			subsource text,
-- 			dblink_acc_num varchar(50));
-- insert into tmp_ucsc_all (counter, geneId, chrom1, source, subsource, dblink_acc_num)
-- select count(*) as counter, geneId, chrom1, source, source as subsource, dblink_acc_num
--   from db_link, tmp_ucsc
--  where geneId = dblink_linked_Recid
--  and dblink_Acc_num like 'NM%'
--  and exists (select 'x' from foreign_db_contains, foreign_db
--      	    	    	where fdbcont_fdb_db_id = fdb_db_pk_id
-- 			and fdb_db_name = 'RefSeq')
-- group by geneId, chrom1, source, dblink_acc_num
-- ;
-- insert into sequence_feature_chromosome_location_generated (sfclg_data_Zdb_id,
--        	    			       sfclg_chromosome,
-- 					       sfclg_acc_num,
-- 					       sfclg_location_source,
-- 					       sfclg_location_Subsource,
--                                        sfclg_evidence_code
-- 					   )
-- select distinct geneId, chrom1, dblink_acc_num, source, subsource, 'ZDB-TERM-170419-250'
--   from tmp_ucsc_all;

-- ----------------------------------------------------------------------------
-- §D. REDUNDANT — handled by refresh §D
--     EnsemblStartEndLoader main path: insert one row per gene/chromosome
--     using start/ender from §B.
-- ----------------------------------------------------------------------------
-- insert into sequence_feature_chromosome_location_generated (sfclg_data_Zdb_id,
--        	    			       sfclg_chromosome,
-- 					       sfclg_start,
-- 					       sfclg_end,
-- 					       sfclg_acc_num,
-- 					       sfclg_location_source,
-- 					       sfclg_fdb_db_id,sfclg_evidence_code)
-- select distinct dblink_linked_recid,
--        		chrom1,
-- 		start,
-- 		ender,
-- 		accnum1,
-- 		'EnsemblStartEndLoader',
-- 		fdb_db_pk_id, 'ZDB-TERM-170419-250'
--   from db_link, tmp_gene, foreign_db, foreign_db_contains
--   where dblink_Fdbcont_zdb_id = fdbcont_Zdb_id
--   and dblink_acc_num = accnum1
--   and fdb_db_pk_id = fdbcont_fdb_db_id
--  and start is not null
--  and ender is not null
--     -- don't include fdb_db_display_name = 'ExpressionAtlas'
--  and fdb_db_pk_id != 91
-- ;

-- ----------------------------------------------------------------------------
-- §E. ACTIVE — ENSDARG-fallback EnsemblStartEndLoader rows.
--     Warehouse-unique; no equivalent in refresh.sql. Introduced by ZFIN-9989
--     (PR #1619) for genes whose ENSDARG accession has no ENSDART transcript
--     link (so refresh §D's ENSDART path produces nothing for them).
--
--     The NOT EXISTS clause makes this idempotent — subsequent runs skip rows
--     already inserted. uk_sfclg_unique_location + ON CONFLICT doesn't help
--     here because that constraint covers sfclg_assembly and
--     sfclg_location_subsource, both NULL in fallback rows; PostgreSQL treats
--     NULLs in unique indexes as distinct, so the constraint doesn't fire on
--     rerun.
-- ----------------------------------------------------------------------------
insert into sequence_feature_chromosome_location_generated (sfclg_data_Zdb_id,
       	    			       sfclg_chromosome,
				       sfclg_start,
				       sfclg_end,
				       sfclg_acc_num,
				       sfclg_location_source,
				       sfclg_fdb_db_id,sfclg_evidence_code)
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
  -- Only include genes not already processed by the ENSDART logic in refresh §D
  and dblink_linked_recid not in (
    select distinct dblink_linked_recid
    from db_link, tmp_gene, foreign_db_contains
    where dblink_fdbcont_zdb_id = fdbcont_zdb_id
    and dblink_acc_num = accnum1
  )
  -- don't include fdb_db_display_name = 'ExpressionAtlas'
  and fdb_db_pk_id != 91
  -- skip rows we've already inserted (idempotence; see §E header)
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

-- ----------------------------------------------------------------------------
-- §F. REDUNDANT — handled by refresh §F
--     ZfinGbrowseStartEndLoader from tmp_gene joined with zfin_ensembl_gene.
-- ----------------------------------------------------------------------------
-- insert into sequence_feature_chromosome_location_generated (sfclg_data_Zdb_id,
--        	    			       sfclg_chromosome,
-- 					       sfclg_start,
-- 					       sfclg_end,
-- 					       sfclg_acc_num,
-- 					       sfclg_location_source,
-- 					       sfclg_fdb_db_id,sfclg_evidence_code)
-- select dblink_linked_recid,
--        		chrom1,
-- 		start,
-- 		ender,
-- 		accnum1,
-- 		'ZfinGbrowseStartEndLoader',
-- 		fdb_db_pk_id, 'ZDB-TERM-170419-250'
--   from db_link, tmp_gene, foreign_db, foreign_db_contains
--   where dblink_Fdbcont_zdb_id = fdbcont_Zdb_id
--   and dblink_acc_num = accnum1
--   and fdb_db_pk_id = fdbcont_fdb_db_id
--  and start is not null
--  and ender is not null
--  and exists (select 'x' from zfin_ensembl_gene where zeg_gene_zdb_id = dblink_linked_recid)
--  group by dblink_linked_recid, chrom1, start, ender, accnum1, fdb_db_pk_id
-- ;

-- ----------------------------------------------------------------------------
-- §G. REDUNDANT — handled by refresh §G
--     DirectSubmission re-import from sequence_feature_chromosome_location.
-- ----------------------------------------------------------------------------
-- insert into sequence_feature_chromosome_location_generated (
--   sfclg_chromosome, sfclg_data_zdb_id, sfclg_start, sfclg_end, sfclg_location_source, sfclg_location_subsource, sfclg_assembly, sfclg_pub_zdb_id, sfclg_evidence_code)
-- select sfcl_chromosome, sfcl_feature_zdb_id, sfcl_start_position, sfcl_end_position, 'DirectSubmission', '', sfcl_assembly, recattrib_source_zdb_id, sfcl_evidence_code
--   from sequence_feature_chromosome_location
--   left outer join record_attribution on recattrib_data_zdb_id = sfcl_zdb_id;

-- ----------------------------------------------------------------------------
-- §H. ACTIVE — zmp gbrowse-track tag.
--     Refresh §H tags ZDB-PUB-130425-4. We re-tag both ZMP pubs here as
--     belt-and-suspenders: re-tagging ZDB-PUB-130425-4 is a no-op in steady
--     state, but acts as a fallback if refresh §H ever silently skips, and
--     ZDB-PUB-250905-18 is genuinely warehouse-unique work.
-- ----------------------------------------------------------------------------
update sequence_feature_chromosome_location_generated
set sfclg_gbrowse_track = 'zmp'
where sfclg_pub_zdb_id in ('ZDB-PUB-130425-4', 'ZDB-PUB-250905-18');

-- ----------------------------------------------------------------------------
-- §I. REDUNDANT — handled by refresh §I
--     BurgessLin Zv9 insertion features.
-- ----------------------------------------------------------------------------
-- insert into sequence_feature_chromosome_location_generated (sfclg_chromosome, sfclg_data_zdb_id,
--   sfclg_start, sfclg_end, sfclg_location_source, sfclg_location_subsource, sfclg_assembly, sfclg_gbrowse_track, sfclg_evidence_code)
-- select gff3.gff_seqname, feature.feature_zdb_id, gff3.gff_start, gff3.gff_end, 'ZfinGbrowseZv9StartEndLoader', 'BurgessLin', 'Zv9', 'insertion', 'ZDB-TERM-170419-250'
-- from gff3
-- inner join feature on (gff3.gff_id || 'Tg') = feature.feature_abbrev
-- where gff3.gff_source = 'BurgessLin';

-- ----------------------------------------------------------------------------
-- §J. REDUNDANT — handled by refresh §J
--     KnockdownReagentLoader (ZFIN_knockdown_reagent + GRCz12tu variant in
--     refresh; only the non-GRCz12tu variant was here historically).
-- ----------------------------------------------------------------------------
-- insert into sequence_feature_chromosome_location_generated (sfclg_chromosome, sfclg_data_zdb_id,
--   sfclg_start, sfclg_end, sfclg_location_source, sfclg_location_subsource, sfclg_evidence_code)
-- select gff_seqname, gff_name, gff_start, gff_end, 'ZfinGbrowseStartEndLoader', 'KnockdownReagentLoader', 'ZDB-TERM-170419-250'
-- from gff3
-- where gff_source = 'ZFIN_knockdown_reagent';

-- ----------------------------------------------------------------------------
-- §K. REDUNDANT — handled by refresh §K
--     Cleanup deletes for AB / U / 0 chromosomes across the source set.
-- ----------------------------------------------------------------------------
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_chromosome in ('AB','U','0')
--  and sfclg_location_source = 'UCSCStartEndLoader';
--
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_chromosome in ('AB','U','0')
--  and (
--   sfclg_location_source = 'ZfinGbrowseStartEndLoader'
--   OR sfclg_location_source = 'ZfinGbrowseZv9StartEndLoader'
-- );
--
-- delete from sequence_feature_chromosome_location_generated
--  where sfclg_chromosome in ('AB','U','0')
--  and sfclg_location_source = 'EnsemblStartEndLoader';

commit work;
-- commit or rollback is appended externally
--rollback work;commit work;
