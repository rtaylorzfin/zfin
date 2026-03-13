package org.zfin.datatransfer.ncbi.report;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.util.LinkedHashMap;
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
