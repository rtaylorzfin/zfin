package org.zfin.datatransfer.ncbi.load;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.FDCONT_NCBI_GENE_ID;

/**
 * Updates marker_assembly and sequence_feature_chromosome_location_generated
 * after the NCBI gene load. Replaces markerAssemblyUpdate.sql.
 *
 * For genes that received a new NCBI Gene ID and have a matching GFF3 entry:
 * 1. Insert marker_assembly record for GRCz12tu
 * 2. Insert sequence_feature_chromosome_location_generated records (NCBI + ZFIN sources)
 * 3. Insert gene_id into gff3_ncbi_attribute
 *
 * Also handles GRCz11 fallback for genes with Ensembl locations but no GRCz12tu.
 */
@Log4j2
public class MarkerAssemblyUpdater {

    private final Session session;

    public MarkerAssemblyUpdater(Session session) {
        this.session = session;
    }

    public void update() {
        // Create temp table of genes that need assembly updates
        createTempNewGeneTable();

        // Insert marker_assembly for GRCz12tu
        insertMarkerAssembly();

        // Insert sequence_feature_chromosome_location_generated (NCBI source)
        insertChromosomeLocations("NCBILoader");

        // Insert sequence_feature_chromosome_location_generated (ZFIN source)
        insertChromosomeLocations("ZFIN");

        // Insert gene_id into gff3_ncbi_attribute
        insertGff3Attributes();

        // GRCz11 fallback
        insertGrcz11Fallback();

        // Clean up
        session.createNativeQuery("DROP TABLE IF EXISTS tmp_new_gene").executeUpdate();
    }

    /**
     * Identify genes that got a new NCBI Gene ID and should be associated with GRCz12tu
     * but are not yet.
     */
    private void createTempNewGeneTable() {
        session.createNativeQuery("DROP TABLE IF EXISTS tmp_new_gene").executeUpdate();

        String sql = """
            CREATE TEMP TABLE tmp_new_gene AS
            SELECT db.dblink_linked_recid AS gene_zdb_id, db.dblink_acc_num AS accession
            FROM db_link AS db
            WHERE db.dblink_fdbcont_zdb_id = :fdbcont
              AND NOT EXISTS (
                  SELECT 1 FROM sequence_feature_chromosome_location_generated
                  WHERE db.dblink_linked_recid = sfclg_data_zdb_id
                    AND sfclg_assembly = 'GRCz12tu'
              )
              AND EXISTS (
                  SELECT 1 FROM gff3_ncbi
                  WHERE gff_attributes LIKE '%GeneID:' || db.dblink_acc_num || '%'
                    AND gff_feature IN ('gene', 'pseudogene')
              )
              AND NOT EXISTS (
                  SELECT d.dblink_linked_recid FROM db_link AS d
                  WHERE d.dblink_fdbcont_zdb_id = :fdbcont
                    AND d.dblink_linked_recid = db.dblink_linked_recid
                  GROUP BY d.dblink_linked_recid
                  HAVING COUNT(*) > 1
              )
            """;

        session.createNativeQuery(sql)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .executeUpdate();

        Number count = (Number) session.createNativeQuery("SELECT COUNT(*) FROM tmp_new_gene").uniqueResult();
        log.info("Found {} genes needing assembly updates", count);
    }

    private void insertMarkerAssembly() {
        String sql = """
            INSERT INTO marker_assembly (ma_mrkr_zdb_id, ma_a_pk_id)
            SELECT gene_zdb_id, 1
            FROM tmp_new_gene
            ON CONFLICT (ma_mrkr_zdb_id, ma_a_pk_id) DO NOTHING
            """;
        int inserted = session.createNativeQuery(sql).executeUpdate();
        log.info("Inserted {} marker_assembly records for GRCz12tu", inserted);
    }

    private void insertChromosomeLocations(String source) {
        String sql = """
            INSERT INTO sequence_feature_chromosome_location_generated
                (sfclg_data_zdb_id, sfclg_chromosome, sfclg_assembly, sfclg_start, sfclg_end, sfclg_acc_num, sfclg_location_source)
            SELECT gene_zdb_id, gff_seqname, 'GRCz12tu', gff_start, gff_end, accession, :source
            FROM tmp_new_gene, gff3_ncbi, gff3_ncbi_attribute
            WHERE gna_key = 'Dbxref'
              AND (regexp_like(gna_value, '.*GeneID:' || accession || '$')
                   OR regexp_like(gna_value, '.*GeneID:' || accession || ','))
              AND gna_gff_pk_id = gff_pk_id
              AND gff_feature IN ('gene', 'pseudogene')
            """;
        int inserted = session.createNativeQuery(sql)
                .setParameter("source", source)
                .executeUpdate();
        log.info("Inserted {} chromosome location records (source: {})", inserted, source);
    }

    private void insertGff3Attributes() {
        String sql = """
            INSERT INTO gff3_ncbi_attribute (gna_gff_pk_id, gna_key, gna_value)
            SELECT gff_pk_id, 'gene_id', gene_zdb_id
            FROM tmp_new_gene, gff3_ncbi, gff3_ncbi_attribute
            WHERE gna_key = 'Dbxref'
              AND (regexp_like(gna_value, '.*GeneID:' || accession || '$')
                   OR regexp_like(gna_value, '.*GeneID:' || accession || ','))
              AND gna_gff_pk_id = gff_pk_id
              AND gff_feature IN ('gene', 'pseudogene')
            ON CONFLICT (gna_pk_id) DO NOTHING
            """;
        int inserted = session.createNativeQuery(sql).executeUpdate();
        log.info("Inserted {} gff3_ncbi_attribute records", inserted);
    }

    /**
     * For genes with GRCz11 Ensembl locations but no GRCz12tu association,
     * add a GRCz11 marker_assembly entry.
     */
    private void insertGrcz11Fallback() {
        String sql = """
            INSERT INTO marker_assembly
            SELECT DISTINCT gg.sfclg_data_zdb_id, 3
            FROM sequence_feature_chromosome_location_generated AS gg
            WHERE gg.sfclg_assembly = 'GRCz11'
              AND gg.sfclg_location_source = 'ZfinGbrowseStartEndLoader'
              AND gg.sfclg_acc_num LIKE 'ENSDARG%'
              AND NOT EXISTS (
                  SELECT 1 FROM sequence_feature_chromosome_location_generated AS ss
                  WHERE gg.sfclg_data_zdb_id = ss.sfclg_data_zdb_id
                    AND ss.sfclg_assembly = 'GRCz12tu'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM db_link
                  WHERE dblink_linked_recid = gg.sfclg_data_zdb_id
                    AND dblink_acc_num = gg.sfclg_acc_num
                    AND dblink_fdbcont_zdb_id = :fdbcont
              )
              AND NOT EXISTS (
                  SELECT 1 FROM marker_assembly AS g
                  WHERE gg.sfclg_data_zdb_id = g.ma_mrkr_zdb_id
                    AND g.ma_a_pk_id = 3
              )
            ON CONFLICT (ma_mrkr_zdb_id, ma_a_pk_id) DO NOTHING
            """;
        int inserted = session.createNativeQuery(sql)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .executeUpdate();
        log.info("Inserted {} GRCz11 fallback marker_assembly records", inserted);
    }
}
