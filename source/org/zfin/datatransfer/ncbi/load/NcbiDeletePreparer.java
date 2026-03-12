package org.zfin.datatransfer.ncbi.load;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

/**
 * Identifies db_link records that should be deleted before the NCBI load.
 * Replaces the logic in prepareNCBIgeneLoad.sql.
 *
 * Records are candidates for deletion if:
 * 1. They are attributed to one of the load publications (RNA or Ensembl supplement)
 * 2. They are NOT in expression_experiment2 (attributed only to load pub)
 * 3. They are linked to genes or RNAGs
 * 4. They are NOT attributed to any non-load publication
 */
@Log4j2
public class NcbiDeletePreparer {

    private final Session session;

    @Getter
    private Map<String, String> toDelete = new HashMap<>(); // dblink_zdb_id → accession

    public NcbiDeletePreparer(Session session) {
        this.session = session;
    }

    /**
     * Build the toDelete map. Mirrors prepareNCBIgeneLoad.sql logic.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> prepare() {
        // Step 1: Find all db_link ZDB IDs attributed to load publications
        String sql = """
            SELECT DISTINCT recattrib_data_zdb_id
            FROM record_attribution
            WHERE recattrib_source_zdb_id IN (:loadPubs)
            """;

        List<String> preDelete = session.createNativeQuery(sql)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .list();

        // Step 2: Exclude those in expression_experiment2 attributed only to load pub
        String eeOnlyLoadPubSql = """
            SELECT DISTINCT xpatex_dblink_zdb_id
            FROM expression_experiment2
            WHERE EXISTS (SELECT 1 FROM record_attribution
                          WHERE xpatex_dblink_zdb_id = recattrib_data_zdb_id
                          AND recattrib_source_zdb_id IN (:loadPubs))
            AND NOT EXISTS (SELECT 1 FROM record_attribution
                           WHERE xpatex_dblink_zdb_id = recattrib_data_zdb_id
                           AND recattrib_source_zdb_id NOT IN (:loadPubs))
            """;

        List<String> eeExclusions = session.createNativeQuery(eeOnlyLoadPubSql)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .list();

        preDelete.removeAll(eeExclusions);

        // Step 3: Exclude those not linked to genes or RNAGs
        if (!preDelete.isEmpty()) {
            String notGeneOrRnagSql = """
                SELECT dblink_zdb_id FROM db_link
                WHERE dblink_zdb_id IN (:candidates)
                  AND dblink_linked_recid NOT LIKE 'ZDB-GENE%'
                  AND dblink_linked_recid NOT LIKE '%RNAG%'
                """;

            List<String> nonGeneLinks = session.createNativeQuery(notGeneOrRnagSql)
                    .setParameterList("candidates", preDelete)
                    .list();

            preDelete.removeAll(nonGeneLinks);
        }

        // Step 4: Exclude those attributed to a non-load publication
        if (!preDelete.isEmpty()) {
            String nonLoadPubSql = """
                SELECT DISTINCT recattrib_data_zdb_id
                FROM record_attribution
                WHERE recattrib_data_zdb_id IN (:candidates)
                  AND recattrib_source_zdb_id NOT IN (:loadPubs)
                """;

            List<String> nonLoadPubLinks = session.createNativeQuery(nonLoadPubSql)
                    .setParameterList("candidates", preDelete)
                    .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                    .list();

            preDelete.removeAll(nonLoadPubLinks);
        }

        // Build toDelete map: dblink_zdb_id → accession
        if (!preDelete.isEmpty()) {
            String accSql = """
                SELECT dblink_zdb_id, dblink_acc_num FROM db_link
                WHERE dblink_zdb_id IN (:ids)
                """;

            List<Object[]> rows = session.createNativeQuery(accSql)
                    .setParameterList("ids", preDelete)
                    .list();

            for (Object[] row : rows) {
                toDelete.put((String) row[0], (String) row[1]);
            }
        }

        log.info("Delete preparation: {} db_link records identified for deletion", toDelete.size());
        return toDelete;
    }
}
