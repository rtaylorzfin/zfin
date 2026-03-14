package org.zfin.datatransfer.ncbi.load;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections4.BidiMap;
import org.hibernate.Session;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad.LoadFileRow;
import org.zfin.datatransfer.ncbi.matching.MatchResult;

import java.util.*;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

/**
 * Builds the complete set of load records (NCBI Gene IDs + downstream accessions)
 * from the match result and external_resource tables.
 *
 * For each matched gene, writes:
 * 1. NCBI Gene ID record (with appropriate publication attribution)
 * 2. GenBank RNA accessions (from gene2accession, supporting the match)
 * 3. RefSeq RNA accessions (NM_/XM_ from gene2accession)
 * 4. Protein accessions (NP_/XP_ from gene2accession)
 * 5. Genomic DNA accessions (from gene2accession)
 *
 * Lengths come from external_resource.ncbi_refseq_catalog.
 */
@Log4j2
public class AccessionWriter {

    private final Session session;
    private final MatchResult matches;
    private final Map<String, String> toDelete;
    private final boolean legacyBehavior;

    // Track what's already been added to prevent duplicates
    private final Set<String> added = new HashSet<>();

    /**
     * In legacy mode, these 1:1 maps replicate the old code's last-write-wins behavior.
     * Key = accession, Value = gene_id that "owns" it (last one parsed).
     * Accessions shared by multiple genes effectively get assigned to whichever gene
     * was last in the gene2accession file. If that gene isn't matched to a ZFIN gene,
     * the accession is dropped for ALL genes.
     */
    private Map<String, String> legacyRefSeqRnaOwner;
    private Map<String, String> legacyRefPeptOwner;
    private Map<String, String> legacyRefSeqDnaOwner;
    private Map<String, String> legacyGenBankRnaOwner;
    private Map<String, String> legacyGenPeptOwner;
    private Map<String, String> legacyGenBankDnaOwner;

    public AccessionWriter(Session session, MatchResult matches, Map<String, String> toDelete) {
        this.session = session;
        this.matches = matches;
        this.toDelete = toDelete;
        this.legacyBehavior = NcbiDbLinkLoader.isLegacyBehaviorEnabled();
    }

    public NCBIOutputFileToLoad buildLoadRecords() {
        NCBIOutputFileToLoad recordsToLoad = new NCBIOutputFileToLoad();

        // Pre-load sequence lengths from RefSeq catalog
        Map<String, Integer> lengths = loadSequenceLengths();

        // Pre-load existing db_link accession→length for GenBank
        Map<String, Integer> existingLengths = loadExistingDbLinkLengths();
        lengths.putAll(existingLengths);

        // Pre-load set of genomic accessions shared by multiple genes (NC_ chromosomes etc.)
        // These should not be linked to individual genes — old code's 1:1 map naturally dropped them.
        Set<String> sharedGenomicAccessions = loadSharedGenomicAccessions();

        // In legacy mode, build 1:1 maps that replicate old code's last-write-wins behavior
        if (legacyBehavior) {
            buildLegacyOwnerMaps();
            log.info("Legacy mode: built 1:1 owner maps for accession filtering");
        }

        // Write NCBI Gene IDs + downstream accessions for each match type
        writeMatchedGenes(matches.getConfirmed(), PUB_MAPPED_BASED_ON_RNA, recordsToLoad, lengths, sharedGenomicAccessions);
        writeMatchedGenes(matches.getSupplement(), PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT, recordsToLoad, lengths, sharedGenomicAccessions);
        writeMatchedGenes(matches.getLegacyVega(), PUB_MAPPED_BASED_ON_VEGA, recordsToLoad, lengths, sharedGenomicAccessions);

        log.info("Built {} load records for {} genes",
                recordsToLoad.getRowCount(),
                recordsToLoad.isEmpty() ? 0 : "multiple");
        return recordsToLoad;
    }

    /**
     * For each matched gene pair, write the NCBI Gene ID and all downstream accessions.
     */
    private void writeMatchedGenes(BidiMap<String, String> matchMap, String pub,
                                   NCBIOutputFileToLoad records, Map<String, Integer> lengths,
                                   Set<String> sharedGenomicAccessions) {
        for (Map.Entry<String, String> entry : matchMap.entrySet()) {
            String zfinGene = entry.getKey();
            String ncbiGene = entry.getValue();

            // NCBI Gene ID record
            records.addRow(new LoadFileRow(zfinGene, ncbiGene, null, FDCONT_NCBI_GENE_ID, pub));

            // Downstream accessions from gene2accession
            writeDownstreamAccessions(zfinGene, ncbiGene, pub, records, lengths, sharedGenomicAccessions);
        }
    }

    /**
     * For a given NCBI gene, look up all accessions in gene2accession and write them.
     */
    @SuppressWarnings("unchecked")
    private void writeDownstreamAccessions(String zfinGene, String ncbiGene, String pub,
                                           NCBIOutputFileToLoad records, Map<String, Integer> lengths,
                                           Set<String> sharedGenomicAccessions) {
        String sql = """
            SELECT rna_accession, protein_accession, genomic_accession,
                   rna_accession_versioned, protein_accession_versioned, genomic_accession_versioned,
                   status
            FROM external_resource.ncbi_gene2accession
            WHERE gene_id = :geneId
            """;

        List<Object[]> rows = session.createNativeQuery(sql)
                .setParameter("geneId", ncbiGene)
                .list();

        for (Object[] row : rows) {
            String rnaAcc = (String) row[0];
            String proteinAcc = (String) row[1];
            String genomicAcc = (String) row[2];
            String status = (String) row[6];
            // Status "-" is stored as null by NcbiGene2AccessionService.dashToNull()
            boolean isGenBank = (status == null);

            // Old code categorizes by status: "-" → GenBank maps, anything else → RefSeq maps.
            // This is mutually exclusive: an accession goes to GenBank OR RefSeq, never both.
            if (isGenBank) {
                // GenBank RNA (status="-", non-RefSeq prefix)
                if (rnaAcc != null && ownsAccession(legacyGenBankRnaOwner, rnaAcc, ncbiGene)) {
                    addIfNew(records, zfinGene, rnaAcc, FDCONT_GENBANK_RNA, pub, lengths);
                }

                // GenPept (status="-", non-RefSeq protein)
                if (proteinAcc != null && ownsAccession(legacyGenPeptOwner, proteinAcc, ncbiGene)) {
                    addIfNew(records, zfinGene, proteinAcc, FDCONT_GENPEPT, pub, lengths);
                }

                // GenBank DNA (status="-", non-RefSeq genomic)
                if (genomicAcc != null && ownsAccession(legacyGenBankDnaOwner, genomicAcc, ncbiGene)) {
                    addIfNew(records, zfinGene, genomicAcc, FDCONT_GENBANK_DNA, pub, lengths);
                }
            } else {
                // RefSeq RNA (NM_, XM_, NR_, XR_)
                if (rnaAcc != null && isRefSeqRna(rnaAcc) && ownsAccession(legacyRefSeqRnaOwner, rnaAcc, ncbiGene)) {
                    addIfNew(records, zfinGene, rnaAcc, FDCONT_REFSEQ_RNA, pub, lengths);
                }

                // RefPept (NP_, XP_)
                if (proteinAcc != null && isRefSeqProtein(proteinAcc) && ownsAccession(legacyRefPeptOwner, proteinAcc, ncbiGene)) {
                    addIfNew(records, zfinGene, proteinAcc, FDCONT_REFPEPT, pub, lengths);
                }

                // RefSeq DNA (NC_, NT_, NW_, etc.) — skip chromosome-level accessions shared by multiple genes
                if (genomicAcc != null && isRefSeqDna(genomicAcc) && !sharedGenomicAccessions.contains(genomicAcc)
                        && ownsAccession(legacyRefSeqDnaOwner, genomicAcc, ncbiGene)) {
                    addIfNew(records, zfinGene, genomicAcc, FDCONT_REFSEQ_DNA, pub, lengths);
                }
            }
        }
    }

    private void addIfNew(NCBIOutputFileToLoad records, String zfinGene, String accession,
                          String fdbcont, String pub, Map<String, Integer> lengths) {
        String key = zfinGene + accession + fdbcont;
        if (added.contains(key)) return;
        added.add(key);

        Integer length = lengths.get(accession);
        records.addRow(new LoadFileRow(zfinGene, accession, length, fdbcont, pub));
    }

    /**
     * Find RefSeq DNA genomic accessions (NC_, NT_, NW_, etc.) that are shared by multiple genes.
     * These are typically chromosome-level accessions and should not be linked to individual genes.
     * The old code used a 1:1 map (last gene wins), effectively dropping multi-gene accessions.
     */
    @SuppressWarnings("unchecked")
    private Set<String> loadSharedGenomicAccessions() {
        String sql = """
            SELECT genomic_accession
            FROM external_resource.ncbi_gene2accession
            WHERE genomic_accession IS NOT NULL
              AND status IS NOT NULL AND status != '-'
            GROUP BY genomic_accession
            HAVING COUNT(DISTINCT gene_id) > 1
            """;

        List<String> rows = session.createNativeQuery(sql).list();
        Set<String> shared = new HashSet<>(rows);
        log.info("Found {} shared genomic accessions to exclude from RefSeq DNA", shared.size());
        return shared;
    }

    /**
     * Load sequence lengths from external_resource.ncbi_refseq_catalog.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> loadSequenceLengths() {
        String sql = """
            SELECT accession, length FROM external_resource.ncbi_refseq_catalog
            WHERE length IS NOT NULL
            """;

        List<Object[]> rows = session.createNativeQuery(sql).list();
        Map<String, Integer> lengths = new HashMap<>();
        for (Object[] row : rows) {
            lengths.put((String) row[0], (Integer) row[1]);
        }
        return lengths;
    }

    /**
     * Load existing db_link lengths for GenBank accessions.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> loadExistingDbLinkLengths() {
        String sql = """
            SELECT dblink_acc_num, dblink_length FROM db_link
            WHERE dblink_length IS NOT NULL
              AND dblink_fdbcont_zdb_id IN (:fdbconts)
              AND (dblink_linked_recid LIKE 'ZDB-GENE%' OR dblink_linked_recid LIKE '%RNAG%')
            """;

        List<Object[]> rows = session.createNativeQuery(sql)
                .setParameterList("fdbconts", List.of(FDCONT_GENBANK_RNA, FDCONT_GENPEPT, FDCONT_GENBANK_DNA))
                .list();

        Map<String, Integer> lengths = new HashMap<>();
        for (Object[] row : rows) {
            lengths.put((String) row[0], (Integer) row[1]);
        }
        return lengths;
    }

    /**
     * In legacy mode, check whether this gene "owns" the accession in the 1:1 map.
     * In non-legacy mode (ownerMap is null), always returns true — all accessions are written.
     */
    private boolean ownsAccession(Map<String, String> ownerMap, String accession, String ncbiGene) {
        if (ownerMap == null) return true; // non-legacy mode
        return ncbiGene.equals(ownerMap.get(accession));
    }

    /**
     * Build 1:1 maps (accession → gene_id) replicating the old code's last-write-wins behavior.
     * The old code iterates gene2accession rows in file order and uses Map.put(), so the last
     * gene_id seen for a given accession wins. We replicate this by using the database row order
     * (which matches file insertion order via the serial primary key).
     */
    @SuppressWarnings("unchecked")
    private void buildLegacyOwnerMaps() {
        legacyRefSeqRnaOwner = new HashMap<>();
        legacyRefPeptOwner = new HashMap<>();
        legacyRefSeqDnaOwner = new HashMap<>();
        legacyGenBankRnaOwner = new HashMap<>();
        legacyGenPeptOwner = new HashMap<>();
        legacyGenBankDnaOwner = new HashMap<>();

        String sql = """
            SELECT gene_id, status, rna_accession, protein_accession, genomic_accession
            FROM external_resource.ncbi_gene2accession
            ORDER BY id
            """;

        List<Object[]> rows = session.createNativeQuery(sql).list();
        for (Object[] row : rows) {
            String geneId = (String) row[0];
            String status = (String) row[1];
            String rnaAcc = (String) row[2];
            String proteinAcc = (String) row[3];
            String genomicAcc = (String) row[4];
            boolean isGenBank = (status == null);

            if (isGenBank) {
                if (rnaAcc != null) legacyGenBankRnaOwner.put(rnaAcc, geneId);
                if (proteinAcc != null) legacyGenPeptOwner.put(proteinAcc, geneId);
                if (genomicAcc != null) legacyGenBankDnaOwner.put(genomicAcc, geneId);
            } else {
                if (rnaAcc != null && isRefSeqRna(rnaAcc)) legacyRefSeqRnaOwner.put(rnaAcc, geneId);
                if (proteinAcc != null && isRefSeqProtein(proteinAcc)) legacyRefPeptOwner.put(proteinAcc, geneId);
                if (genomicAcc != null && isRefSeqDna(genomicAcc)) legacyRefSeqDnaOwner.put(genomicAcc, geneId);
            }
        }

        log.info("Legacy owner maps: RefSeq RNA={}, RefPept={}, RefSeq DNA={}, GenBank RNA={}, GenPept={}, GenBank DNA={}",
                legacyRefSeqRnaOwner.size(), legacyRefPeptOwner.size(), legacyRefSeqDnaOwner.size(),
                legacyGenBankRnaOwner.size(), legacyGenPeptOwner.size(), legacyGenBankDnaOwner.size());
    }

    // ---- Accession type classification ----

    private static boolean isRefSeq(String acc) {
        return acc.startsWith("NM_") || acc.startsWith("NR_") || acc.startsWith("XM_") || acc.startsWith("XR_")
                || acc.startsWith("NP_") || acc.startsWith("XP_")
                || acc.startsWith("NC_") || acc.startsWith("NT_") || acc.startsWith("NW_")
                || acc.startsWith("NG_") || acc.startsWith("AC_");
    }

    private static boolean isRefSeqRna(String acc) {
        return acc.startsWith("NM_") || acc.startsWith("XM_") || acc.startsWith("NR_") || acc.startsWith("XR_");
    }

    private static boolean isRefSeqProtein(String acc) {
        return acc.startsWith("NP_") || acc.startsWith("XP_");
    }

    private static boolean isRefSeqDna(String acc) {
        return acc.startsWith("NC_") || acc.startsWith("NT_") || acc.startsWith("NW_")
                || acc.startsWith("NG_") || acc.startsWith("AC_");
    }
}
