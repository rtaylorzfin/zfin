package org.zfin.datatransfer.ncbi.report;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

/**
 * Captures before/after db_link counts per accession type for the NCBI load report.
 * Call capture() before and after the load to get comparable snapshots.
 */
@Log4j2
@Getter
public class NcbiLoadStatistics {

    private final String label; // "before" or "after"

    // db_link record counts per accession type
    private int ncbiGeneIdCount;
    private int refSeqRnaCount;
    private int refPeptCount;
    private int refSeqDnaCount;
    private int genBankRnaCount;
    private int genPeptCount;
    private int genBankDnaCount;

    // gene-level counts
    private int genesWithRefSeq;
    private int genesWithGenBank;
    private int genesWithoutNcbiGeneId;
    private int genesRefSeqRna;
    private int genesRefSeqPept;

    private NcbiLoadStatistics(String label) {
        this.label = label;
    }

    /**
     * Capture a snapshot of current db_link counts.
     */
    public static NcbiLoadStatistics capture(Session session, String label) {
        NcbiLoadStatistics stats = new NcbiLoadStatistics(label);

        stats.ncbiGeneIdCount = countDbLinks(session, FDCONT_NCBI_GENE_ID);
        stats.refSeqRnaCount = countDbLinks(session, FDCONT_REFSEQ_RNA);
        stats.refPeptCount = countDbLinks(session, FDCONT_REFPEPT);
        stats.refSeqDnaCount = countDbLinks(session, FDCONT_REFSEQ_DNA);
        stats.genBankRnaCount = countDbLinks(session, FDCONT_GENBANK_RNA);
        stats.genPeptCount = countDbLinks(session, FDCONT_GENPEPT);
        stats.genBankDnaCount = countDbLinks(session, FDCONT_GENBANK_DNA);

        stats.genesWithRefSeq = countGenesWithFdbcont(session,
                FDCONT_REFSEQ_RNA, FDCONT_REFPEPT, FDCONT_REFSEQ_DNA);
        stats.genesWithGenBank = countGenesWithFdbcont(session,
                FDCONT_GENBANK_RNA, FDCONT_GENPEPT, FDCONT_GENBANK_DNA);
        stats.genesRefSeqRna = countGenesWithFdbcont(session, FDCONT_REFSEQ_RNA);
        stats.genesRefSeqPept = countGenesWithFdbcont(session, FDCONT_REFPEPT);
        stats.genesWithoutNcbiGeneId = countGenesWithoutNcbiGeneId(session);

        log.info("Captured {} statistics: {} NCBI Gene IDs, {} RefSeq RNA, {} GenBank RNA",
                label, stats.ncbiGeneIdCount, stats.refSeqRnaCount, stats.genBankRnaCount);
        return stats;
    }

    /**
     * Count distinct accessions for gene/RNAG db_links of a given fdbcont type.
     */
    private static int countDbLinks(Session session, String fdbcont) {
        String sql = """
            SELECT COUNT(DISTINCT dblink_acc_num)
            FROM db_link
            WHERE dblink_fdbcont_zdb_id = :fdbcont
              AND (dblink_linked_recid LIKE 'ZDB-GENE%' OR dblink_linked_recid LIKE '%RNAG%')
            """;
        Number count = (Number) session.createNativeQuery(sql)
                .setParameter("fdbcont", fdbcont)
                .uniqueResult();
        return count != null ? count.intValue() : 0;
    }

    /**
     * Count distinct genes that have db_links of any of the given fdbcont types.
     */
    private static int countGenesWithFdbcont(Session session, String... fdbconts) {
        String sql = """
            SELECT COUNT(DISTINCT dblink_linked_recid)
            FROM db_link
            WHERE dblink_fdbcont_zdb_id IN (:fdbconts)
              AND (dblink_linked_recid LIKE 'ZDB-GENE%' OR dblink_linked_recid LIKE '%RNAG%')
            """;
        Number count = (Number) session.createNativeQuery(sql)
                .setParameterList("fdbconts", java.util.List.of(fdbconts))
                .uniqueResult();
        return count != null ? count.intValue() : 0;
    }

    /**
     * Count genes that do NOT have an NCBI Gene ID link.
     */
    private static int countGenesWithoutNcbiGeneId(Session session) {
        String sql = """
            SELECT COUNT(DISTINCT mrkr_zdb_id)
            FROM marker
            WHERE mrkr_type = 'GENE'
              AND NOT EXISTS (
                  SELECT 1 FROM db_link
                  WHERE dblink_linked_recid = mrkr_zdb_id
                    AND dblink_fdbcont_zdb_id = :fdbcont
              )
            """;
        Number count = (Number) session.createNativeQuery(sql)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .uniqueResult();
        return count != null ? count.intValue() : 0;
    }

    /**
     * Capture all state to separate CSV files: db_links, annotation status, and assemblies.
     * Splitting these avoids amplification where a single gene-level change (e.g. marker_annotation_status)
     * shows up as N changes (one per db_link row for that gene).
     *
     * @param baseFile The base file path — e.g. "before_load" produces before_load_dblinks.csv, etc.
     */
    public static void captureAllStateToCsv(Session session, File baseFile) {
        String baseName = baseFile.getName().replaceFirst("\\.csv$", "");
        File dir = baseFile.getParentFile();

        captureDbLinksToCsv(session, new File(dir, baseName + "_dblinks.csv"));
        captureAnnotationStatusToCsv(session, new File(dir, baseName + "_annotation.csv"));
        captureAssemblyToCsv(session, new File(dir, baseName + "_assembly.csv"));
    }

    /**
     * Write db_link + record_attribution state to CSV. One row per db_link.
     */
    @SuppressWarnings("unchecked")
    public static void captureDbLinksToCsv(Session session, File outputFile) {
        String sql = """
            SELECT d.dblink_linked_recid, d.dblink_acc_num, d.dblink_info, d.dblink_zdb_id,
                   d.dblink_acc_num_display, d.dblink_length, d.dblink_fdbcont_zdb_id,
                   string_agg(DISTINCT r.recattrib_source_zdb_id, '|' ORDER BY r.recattrib_source_zdb_id) AS recattrib_source_zdb_id
            FROM db_link d
                LEFT JOIN record_attribution r ON d.dblink_zdb_id = r.recattrib_data_zdb_id
            WHERE (d.dblink_linked_recid LIKE 'ZDB-GENE%' OR d.dblink_linked_recid LIKE '%RNAG%')
            GROUP BY d.dblink_linked_recid, d.dblink_acc_num, d.dblink_info, d.dblink_zdb_id,
                     d.dblink_acc_num_display, d.dblink_length, d.dblink_fdbcont_zdb_id
            ORDER BY d.dblink_linked_recid, d.dblink_acc_num
            """;

        writeCsvFromQuery(session, sql,
                "dblink_linked_recid,dblink_acc_num,dblink_info,dblink_zdb_id,dblink_acc_num_display,dblink_length,dblink_fdbcont_zdb_id,recattrib_source_zdb_id",
                outputFile);
    }

    /**
     * Write marker_annotation_status to CSV. One row per gene.
     */
    @SuppressWarnings("unchecked")
    public static void captureAnnotationStatusToCsv(Session session, File outputFile) {
        String sql = """
            SELECT mas_mrkr_zdb_id, vt_name AS marker_annotation_status
            FROM marker_annotation_status
                LEFT JOIN vocabulary_term ON mas_vt_pk_id = vt_id
            ORDER BY mas_mrkr_zdb_id
            """;

        writeCsvFromQuery(session, sql,
                "mrkr_zdb_id,marker_annotation_status",
                outputFile);
    }

    /**
     * Write marker_assembly to CSV. One row per gene+assembly.
     */
    @SuppressWarnings("unchecked")
    public static void captureAssemblyToCsv(Session session, File outputFile) {
        String sql = """
            SELECT ma_mrkr_zdb_id, a_name AS assembly_name
            FROM marker_assembly
                JOIN assembly ON ma_a_pk_id = a_pk_id
            ORDER BY ma_mrkr_zdb_id, a_name
            """;

        writeCsvFromQuery(session, sql,
                "mrkr_zdb_id,assembly_name",
                outputFile);
    }

    private static void writeCsvFromQuery(Session session, String sql, String header, File outputFile) {
        List<Object[]> rows = session.createNativeQuery(sql).list();

        try (PrintWriter writer = new PrintWriter(outputFile)) {
            writer.println(header);
            for (Object[] row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(",");
                    Object val = row[i];
                    if (val != null) {
                        String s = val.toString();
                        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                            sb.append("\"").append(s.replace("\"", "\"\"")).append("\"");
                        } else {
                            sb.append(s);
                        }
                    }
                }
                writer.println(sb);
            }
        } catch (IOException e) {
            log.error("Failed to write state CSV to {}", outputFile, e);
        }
    }

    /**
     * Return a summary map of category → count for use in reporting.
     */
    public Map<String, Integer> toSummaryMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("NCBI Gene ID", ncbiGeneIdCount);
        map.put("RefSeq RNA", refSeqRnaCount);
        map.put("RefPept", refPeptCount);
        map.put("RefSeq DNA", refSeqDnaCount);
        map.put("GenBank RNA", genBankRnaCount);
        map.put("GenPept", genPeptCount);
        map.put("GenBank DNA", genBankDnaCount);
        return map;
    }

    /**
     * Return a gene-level summary map.
     */
    public Map<String, Integer> toGeneSummaryMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("with RefSeq", genesWithRefSeq);
        map.put("with RefSeq NM", genesRefSeqRna);
        map.put("with RefSeq NP", genesRefSeqPept);
        map.put("with GenBank", genesWithGenBank);
        map.put("without NCBI Gene ID", genesWithoutNcbiGeneId);
        return map;
    }
}
