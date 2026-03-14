package org.zfin.datatransfer.ncbi.matching;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.util.List;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.FDCONT_NCBI_GENE_ID;

/**
 * Provides supplementary NCBI gene matches via shared Ensembl IDs.
 *
 * Uses data from:
 * - external_resource.ncbi_danio_rerio_gene_info_ensembl (NCBI side: gene → Ensembl ID)
 * - db_link (ZFIN side: gene → Ensembl ID via ENSDARG fdbcont)
 *
 * Only matches genes that:
 * 1. Were NOT already matched reciprocally via RNA accessions
 * 2. Do NOT already have an NCBI Gene ID in the database (from a non-load source)
 *
 * These are "one-way" matches (NCBI knows about ZFIN via Ensembl, but no RNA reciprocity).
 *
 * Expects a temp table 'tmp_dblinks_to_delete' to exist with dblink ZDB IDs to exclude.
 */
@Log4j2
public class EnsemblSupplementMatcher {

    private final Session session;

    public EnsemblSupplementMatcher(Session session) {
        this.session = session;
    }

    /**
     * Augment the match result with Ensembl-based supplementary matches.
     * Only adds matches for genes not already in confirmed or having existing NCBI Gene IDs.
     */
    @SuppressWarnings("unchecked")
    public MatchResult augment(MatchResult result) {
        // Find NCBI genes that reference Ensembl IDs which match ZFIN gene Ensembl links
        // This mirrors the logic from NcbiMatchThroughEnsemblTask and
        // addReverseMappedGenesFromNCBItoZFINFromSupplementaryLoad in NCBIDirectPort
        String sql = """
            SELECT DISTINCT
                ncbi_ens.ncbi_id,
                zfin_ens.dblink_linked_recid AS zdb_id
            FROM external_resource.ncbi_danio_rerio_gene_info_ensembl ncbi_ens
            JOIN db_link zfin_ens
                ON zfin_ens.dblink_acc_num = ncbi_ens.ensembl_id
                AND zfin_ens.dblink_fdbcont_zdb_id = 'ZDB-FDBCONT-200123-1'
            WHERE zfin_ens.dblink_linked_recid LIKE 'ZDB-GENE%'
            """;

        List<Object[]> rows = session.createNativeQuery(sql).list();

        int added = 0;
        for (Object[] row : rows) {
            String ncbiGene = (String) row[0];
            String zfinGene = (String) row[1];

            // Skip if already matched via RNA
            if (result.getConfirmed().containsKey(zfinGene) || result.getConfirmed().containsValue(ncbiGene)) {
                continue;
            }

            // Skip if already in supplement (another Ensembl ID might have mapped it)
            if (result.getSupplement().containsKey(zfinGene) || result.getSupplement().containsValue(ncbiGene)) {
                continue;
            }

            // Skip if gene already has an NCBI Gene ID from a non-load source
            if (hasExistingNcbiGeneId(zfinGene)) {
                continue;
            }

            result.getSupplement().put(zfinGene, ncbiGene);
            added++;
        }

        log.info("Ensembl supplement matching: {} additional matches", added);
        return result;
    }

    /**
     * Check if a ZFIN gene already has an NCBI Gene ID link in the database
     * (excluding those in the tmp_dblinks_to_delete temp table).
     */
    private boolean hasExistingNcbiGeneId(String zfinGene) {
        String sql = """
            SELECT COUNT(*) FROM db_link d
            WHERE d.dblink_linked_recid = :gene
              AND d.dblink_fdbcont_zdb_id = :fdbcont
              AND NOT EXISTS (SELECT 1 FROM tmp_dblinks_to_delete td WHERE td.dblink_zdb_id = d.dblink_zdb_id)
            """;

        Number count = (Number) session.createNativeQuery(sql)
                .setParameter("gene", zfinGene)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .uniqueResult();
        return count != null && count.intValue() > 0;
    }
}
