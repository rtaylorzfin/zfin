package org.zfin.datatransfer.ncbi.load;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;

import java.util.*;

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
     * Uses a temp table to avoid exceeding PostgreSQL's 65535 parameter limit
     * when working with large candidate sets.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> prepare() {
        // Create temp table to hold candidates (avoids massive IN clauses)
        session.createNativeQuery("DROP TABLE IF EXISTS tmp_ncbi_delete_candidates").executeUpdate();
        session.createNativeQuery("""
            CREATE TEMP TABLE tmp_ncbi_delete_candidates (
                zdb_id text NOT NULL PRIMARY KEY
            )
            """).executeUpdate();

        // Step 1: Find all db_link ZDB IDs attributed to load publications
        session.createNativeQuery("""
            INSERT INTO tmp_ncbi_delete_candidates (zdb_id)
            SELECT DISTINCT recattrib_data_zdb_id
            FROM record_attribution
            WHERE recattrib_source_zdb_id IN (:loadPubs)
            """)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .executeUpdate();

        int initialCount = ((Number) session.createNativeQuery(
                "SELECT COUNT(*) FROM tmp_ncbi_delete_candidates").uniqueResult()).intValue();
        log.info("Step 1: {} candidates attributed to load pubs", initialCount);

        // Step 2: Exclude those in expression_experiment2 attributed only to load pub
        int eeRemoved = session.createNativeQuery("""
            DELETE FROM tmp_ncbi_delete_candidates
            WHERE zdb_id IN (
                SELECT DISTINCT xpatex_dblink_zdb_id
                FROM expression_experiment2
                WHERE EXISTS (SELECT 1 FROM record_attribution
                              WHERE xpatex_dblink_zdb_id = recattrib_data_zdb_id
                              AND recattrib_source_zdb_id IN (:loadPubs))
                AND NOT EXISTS (SELECT 1 FROM record_attribution
                               WHERE xpatex_dblink_zdb_id = recattrib_data_zdb_id
                               AND recattrib_source_zdb_id NOT IN (:loadPubs))
            )
            """)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .executeUpdate();
        log.info("Step 2: excluded {} expression_experiment2 records", eeRemoved);

        // Step 3: Exclude those not linked to genes or RNAGs
        int nonGeneRemoved = session.createNativeQuery("""
            DELETE FROM tmp_ncbi_delete_candidates
            WHERE zdb_id IN (
                SELECT dblink_zdb_id FROM db_link
                JOIN tmp_ncbi_delete_candidates ON dblink_zdb_id = zdb_id
                WHERE dblink_linked_recid NOT LIKE 'ZDB-GENE%'
                  AND dblink_linked_recid NOT LIKE '%RNAG%'
            )
            """).executeUpdate();
        log.info("Step 3: excluded {} non-gene/RNAG records", nonGeneRemoved);

        // Step 4: Exclude those attributed to a non-load publication
        int manualRemoved = session.createNativeQuery("""
            DELETE FROM tmp_ncbi_delete_candidates
            WHERE zdb_id IN (
                SELECT DISTINCT recattrib_data_zdb_id
                FROM record_attribution
                JOIN tmp_ncbi_delete_candidates ON recattrib_data_zdb_id = zdb_id
                WHERE recattrib_source_zdb_id NOT IN (:loadPubs)
            )
            """)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .executeUpdate();
        log.info("Step 4: excluded {} manually curated records", manualRemoved);

        // Build toDelete map: dblink_zdb_id → accession
        List<Object[]> rows = session.createNativeQuery("""
            SELECT dblink_zdb_id, dblink_acc_num FROM db_link
            JOIN tmp_ncbi_delete_candidates ON dblink_zdb_id = zdb_id
            """).list();

        for (Object[] row : rows) {
            toDelete.put((String) row[0], (String) row[1]);
        }

        session.createNativeQuery("DROP TABLE IF EXISTS tmp_ncbi_delete_candidates").executeUpdate();

        log.info("Delete preparation: {} db_link records identified for deletion", toDelete.size());
        return toDelete;
    }
}
