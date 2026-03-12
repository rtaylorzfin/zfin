package org.zfin.datatransfer.ncbi.matching;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.util.List;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

/**
 * Handles preservation of legacy Vega-based NCBI Gene ID links.
 *
 * The Vega database is retired, but some ZFIN genes have NCBI Gene ID links
 * that were originally established through Vega transcript matching.
 * This handler:
 * 1. Captures existing Vega-attributed NCBI Gene ID links before the load deletes them
 * 2. Reintroduces those links after matching, so they survive the load cycle
 * 3. Only reintroduces if the gene has no higher-priority match (RNA or Ensembl)
 */
@Log4j2
public class VegaLegacyHandler {

    private final Session session;

    public VegaLegacyHandler(Session session) {
        this.session = session;
    }

    /**
     * Capture existing Vega-attributed NCBI Gene ID links and add them to the match result.
     * These will be reintroduced during the load phase with lowest priority.
     */
    @SuppressWarnings("unchecked")
    public MatchResult reintroduce(MatchResult result) {
        String sql = """
            SELECT dl.dblink_linked_recid, dl.dblink_acc_num
            FROM db_link dl
            JOIN record_attribution ra ON dl.dblink_zdb_id = ra.recattrib_data_zdb_id
            WHERE dl.dblink_fdbcont_zdb_id = :fdbcont
              AND ra.recattrib_source_zdb_id = :vegaPub
              AND dl.dblink_linked_recid LIKE 'ZDB-GENE%'
            """;

        List<Object[]> rows = session.createNativeQuery(sql)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .setParameter("vegaPub", PUB_MAPPED_BASED_ON_VEGA)
                .list();

        int added = 0;
        for (Object[] row : rows) {
            String zfinGene = (String) row[0];
            String ncbiGene = (String) row[1];

            // Only reintroduce if not already matched by RNA or Ensembl
            if (result.getConfirmed().containsKey(zfinGene) || result.getConfirmed().containsValue(ncbiGene)) {
                continue;
            }
            if (result.getSupplement().containsKey(zfinGene) || result.getSupplement().containsValue(ncbiGene)) {
                continue;
            }
            // Also skip if already captured
            if (result.getLegacyVega().containsKey(zfinGene) || result.getLegacyVega().containsValue(ncbiGene)) {
                continue;
            }

            result.getLegacyVega().put(zfinGene, ncbiGene);
            added++;
        }

        log.info("Vega legacy reintroduction: {} links preserved", added);
        return result;
    }
}
