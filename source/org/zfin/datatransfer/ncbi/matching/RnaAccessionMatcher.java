package org.zfin.datatransfer.ncbi.matching;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.util.*;

/**
 * Performs reciprocal 1:1 matching between ZFIN genes and NCBI genes via shared RNA accessions.
 *
 * Uses data from:
 * - db_link (ZFIN side: genes → GenBank RNA accessions)
 * - external_resource.ncbi_gene2accession (NCBI side: genes → RNA accessions)
 *
 * The algorithm:
 * 1. Build ZFIN-side maps: which genes have which RNA accessions
 * 2. Build NCBI-side maps: which genes have which RNA accessions
 * 3. Filter out "problematic" accessions (those supporting multiple genes on either side)
 * 4. Filter out genes that have ANY problematic accession (conservative)
 * 5. One-way ZFIN→NCBI: for each clean ZFIN gene, find which NCBI genes its accessions point to
 * 6. One-way NCBI→ZFIN: mirror direction
 * 7. Reciprocal check: only keep pairs where both directions agree on 1:1
 *
 * Expects a temp table 'tmp_dblinks_to_delete' to exist with dblink ZDB IDs to exclude.
 */
@Log4j2
public class RnaAccessionMatcher {

    private final Session session;

    public RnaAccessionMatcher(Session session) {
        this.session = session;
    }

    /**
     * Run the full reciprocal matching pipeline.
     */
    public MatchResult match() {
        MatchResult result = new MatchResult();

        // ZFIN side: gene → [accessions], accession → [genes]
        Map<String, Set<String>> zfinGeneToAccs = buildZfinGeneToAccessions();
        Map<String, Set<String>> zfinAccToGenes = invertMultiMap(zfinGeneToAccs);

        // NCBI side: gene → [accessions], accession → [genes]
        Map<String, Set<String>> ncbiGeneToAccs = buildNcbiGeneToAccessions();
        Map<String, Set<String>> ncbiAccToGenes = invertMultiMap(ncbiGeneToAccs);

        // Find accessions supporting >1 gene on each side
        Set<String> zfinProblematicAccs = keysWithMultipleValues(zfinAccToGenes);
        Set<String> ncbiProblematicAccs = keysWithMultipleValues(ncbiAccToGenes);

        // Find genes that have ANY problematic accession (conservative filter)
        Set<String> zfinProblematicGenes = genesWithAnyProblematicAccession(zfinGeneToAccs, zfinProblematicAccs);
        Set<String> ncbiProblematicGenes = genesWithAnyProblematicAccession(ncbiGeneToAccs, ncbiProblematicAccs);

        // Build clean accession→single gene maps (excluding problematic)
        Map<String, String> zfinAccToSingleGene = buildCleanAccToGene(zfinAccToGenes, zfinProblematicAccs);
        Map<String, String> ncbiAccToSingleGene = buildCleanAccToGene(ncbiAccToGenes, ncbiProblematicAccs);

        // One-way ZFIN→NCBI
        Map<String, String> oneToOneZfinToNcbi = new HashMap<>();
        Map<String, Map<String, String>> oneToNZfinToNcbi = new HashMap<>();
        oneWayMapping(zfinGeneToAccs, zfinProblematicGenes,
                ncbiAccToSingleGene, ncbiProblematicAccs,
                oneToOneZfinToNcbi, oneToNZfinToNcbi);

        // One-way NCBI→ZFIN
        Map<String, String> oneToOneNcbiToZfin = new HashMap<>();
        Map<String, Map<String, String>> oneToNNcbiToZfin = new HashMap<>();
        oneWayMapping(ncbiGeneToAccs, ncbiProblematicGenes,
                zfinAccToSingleGene, zfinProblematicAccs,
                oneToOneNcbiToZfin, oneToNNcbiToZfin);

        // Reciprocal check
        for (Map.Entry<String, String> entry : oneToOneZfinToNcbi.entrySet()) {
            String zfinGene = entry.getKey();
            String ncbiGene = entry.getValue();
            if (ncbiGene != null && zfinGene.equals(oneToOneNcbiToZfin.get(ncbiGene))) {
                result.getConfirmed().put(zfinGene, ncbiGene);
            }
        }

        // Capture 1:N conflicts
        result.getOneToN().putAll(oneToNZfinToNcbi);
        result.getNToOne().putAll(oneToNNcbiToZfin);

        log.info("RNA matching: {} reciprocal 1:1 matches, {} 1:N ZFIN conflicts, {} N:1 NCBI conflicts",
                result.getConfirmed().size(), result.getOneToN().size(), result.getNToOne().size());

        return result;
    }

    /**
     * Build map of ZFIN gene ID → set of RNA accessions (unversioned).
     * Mirrors the logic of prepareNCBIgeneLoad.sql's genes_supported_by_rna view
     * and the ZFIN-side initialization in NCBIDirectPort.
     *
     * Excludes db_links in the tmp_dblinks_to_delete temp table.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> buildZfinGeneToAccessions() {
        // Direct gene → GenBank RNA links (excluding those marked for delete via temp table)
        String sql = """
            SELECT d.dblink_linked_recid, d.dblink_acc_num
            FROM db_link d
            WHERE d.dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-37'
              AND (d.dblink_linked_recid LIKE 'ZDB-GENE%' OR d.dblink_linked_recid LIKE '%RNAG%')
              AND NOT EXISTS (SELECT 1 FROM marker WHERE mrkr_abbrev LIKE 'WITHDRAWN%' AND d.dblink_linked_recid = mrkr_zdb_id)
              AND NOT EXISTS (SELECT 1 FROM tmp_dblinks_to_delete td WHERE td.dblink_zdb_id = d.dblink_zdb_id)
            """;

        List<Object[]> directRows = session.createNativeQuery(sql).list();
        Map<String, Set<String>> geneToAccs = new HashMap<>();
        for (Object[] row : directRows) {
            String gene = (String) row[0];
            String acc = (String) row[1];
            geneToAccs.computeIfAbsent(gene, k -> new HashSet<>()).add(acc);
        }

        // Also include "gene encodes small segment" relationships
        String smallSegSql = """
            SELECT mrel_mrkr_1_zdb_id, d.dblink_acc_num
            FROM marker_relationship
            JOIN db_link d ON d.dblink_linked_recid = mrel_mrkr_2_zdb_id
            WHERE mrel_type = 'gene encodes small segment'
              AND d.dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-040412-37'
              AND NOT EXISTS (SELECT 1 FROM marker WHERE mrkr_abbrev LIKE 'WITHDRAWN%' AND mrel_mrkr_1_zdb_id = mrkr_zdb_id)
              AND NOT EXISTS (SELECT 1 FROM tmp_dblinks_to_delete td WHERE td.dblink_zdb_id = d.dblink_zdb_id)
            """;

        List<Object[]> ssRows = session.createNativeQuery(smallSegSql).list();
        for (Object[] row : ssRows) {
            String gene = (String) row[0];
            String acc = (String) row[1];
            geneToAccs.computeIfAbsent(gene, k -> new HashSet<>()).add(acc);
        }

        return geneToAccs;
    }

    /**
     * Build map of NCBI gene ID → set of RNA accessions (unversioned) from external_resource table.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> buildNcbiGeneToAccessions() {
        String sql = """
            SELECT gene_id, rna_accession
            FROM external_resource.ncbi_gene2accession
            WHERE rna_accession IS NOT NULL
            """;

        List<Object[]> rows = session.createNativeQuery(sql).list();
        Map<String, Set<String>> geneToAccs = new HashMap<>();
        for (Object[] row : rows) {
            String geneId = (String) row[0];
            String rnaAcc = (String) row[1];
            geneToAccs.computeIfAbsent(geneId, k -> new HashSet<>()).add(rnaAcc);
        }
        return geneToAccs;
    }

    /**
     * One-way mapping: for each "source" gene (that's not problematic), find which
     * "target" genes its accessions point to via the clean target acc→gene map.
     *
     * Results classified as:
     * - 1:1 if all accessions point to the same target gene
     * - 1:N if accessions point to different target genes
     */
    private void oneWayMapping(
            Map<String, Set<String>> sourceGeneToAccs,
            Set<String> sourceProblematicGenes,
            Map<String, String> targetAccToSingleGene,
            Set<String> targetProblematicAccs,
            Map<String, String> oneToOne,
            Map<String, Map<String, String>> oneToN) {

        for (Map.Entry<String, Set<String>> entry : sourceGeneToAccs.entrySet()) {
            String sourceGene = entry.getKey();
            Set<String> accs = entry.getValue();

            if (sourceProblematicGenes.contains(sourceGene)) {
                continue;
            }

            Map<String, String> targetGeneToAcc = new HashMap<>();
            for (String acc : accs) {
                if (targetProblematicAccs.contains(acc)) {
                    continue;
                }
                String targetGene = targetAccToSingleGene.get(acc);
                if (targetGene != null) {
                    targetGeneToAcc.put(targetGene, acc);
                }
            }

            if (targetGeneToAcc.size() == 1) {
                oneToOne.put(sourceGene, targetGeneToAcc.keySet().iterator().next());
            } else if (targetGeneToAcc.size() > 1) {
                oneToN.put(sourceGene, targetGeneToAcc);
            }
        }
    }

    // ---- Utility methods ----

    private static <K, V> Map<V, Set<K>> invertMultiMap(Map<K, Set<V>> map) {
        Map<V, Set<K>> inverted = new HashMap<>();
        for (Map.Entry<K, Set<V>> entry : map.entrySet()) {
            for (V value : entry.getValue()) {
                inverted.computeIfAbsent(value, k -> new HashSet<>()).add(entry.getKey());
            }
        }
        return inverted;
    }

    private static <K> Set<K> keysWithMultipleValues(Map<K, Set<String>> map) {
        Set<K> result = new HashSet<>();
        for (Map.Entry<K, Set<String>> entry : map.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private static Set<String> genesWithAnyProblematicAccession(
            Map<String, Set<String>> geneToAccs, Set<String> problematicAccs) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : geneToAccs.entrySet()) {
            for (String acc : entry.getValue()) {
                if (problematicAccs.contains(acc)) {
                    result.add(entry.getKey());
                    break;
                }
            }
        }
        return result;
    }

    private static Map<String, String> buildCleanAccToGene(
            Map<String, Set<String>> accToGenes, Set<String> problematicAccs) {
        Map<String, String> clean = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : accToGenes.entrySet()) {
            if (entry.getValue().size() == 1 && !problematicAccs.contains(entry.getKey())) {
                clean.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        return clean;
    }
}
