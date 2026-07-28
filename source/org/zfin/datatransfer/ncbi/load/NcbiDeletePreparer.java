package org.zfin.datatransfer.ncbi.load;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;
import org.zfin.datatransfer.ncbi.load.NcbiDiffComputer.CurrentDbLink;

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

    /**
     * Query the current database state for all load-owned db_link records (same filtering as prepare()).
     * Returns a map keyed by natural key (gene|accession|fdbcont) → CurrentDbLink with full metadata.
     *
     * Used by the incremental load path to compute the diff against desired state.
     */
    @SuppressWarnings("unchecked")
    public Map<String, CurrentDbLink> prepareWithMetadata() {
        // Run the normal prepare() to identify delete candidates
        prepare();

        // Now query full metadata for those candidates
        // Re-create the temp table since prepare() dropped it
        session.createNativeQuery("DROP TABLE IF EXISTS tmp_ncbi_delete_candidates").executeUpdate();
        session.createNativeQuery("""
            CREATE TEMP TABLE tmp_ncbi_delete_candidates (
                zdb_id text NOT NULL PRIMARY KEY
            )
            """).executeUpdate();

        if (!toDelete.isEmpty()) {
            List<String> idList = new ArrayList<>(toDelete.keySet());
            for (int i = 0; i < idList.size(); i += 1000) {
                List<String> batch = idList.subList(i, Math.min(i + 1000, idList.size()));
                StringBuilder sb = new StringBuilder("INSERT INTO tmp_ncbi_delete_candidates VALUES ");
                for (int j = 0; j < batch.size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append("('").append(batch.get(j).replace("'", "''")).append("')");
                }
                session.createNativeQuery(sb.toString()).executeUpdate();
            }
        }

        // Query full metadata: gene, accession, fdbcont, zdb_id, length, and highest-priority load pub
        List<Object[]> rows = session.createNativeQuery("""
            SELECT dl.dblink_linked_recid,
                   dl.dblink_acc_num,
                   dl.dblink_fdbcont_zdb_id,
                   dl.dblink_zdb_id,
                   dl.dblink_length,
                   (SELECT ra.recattrib_source_zdb_id
                    FROM record_attribution ra
                    WHERE ra.recattrib_data_zdb_id = dl.dblink_zdb_id
                      AND ra.recattrib_source_zdb_id IN (:loadPubs)
                    ORDER BY CASE ra.recattrib_source_zdb_id
                        WHEN :rnaPub THEN 1
                        WHEN :supplementPub THEN 2
                        WHEN :vegaPub THEN 3
                    END
                    LIMIT 1) AS load_pub
            FROM db_link dl
            JOIN tmp_ncbi_delete_candidates ON dl.dblink_zdb_id = zdb_id
            """)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT, PUB_MAPPED_BASED_ON_VEGA))
                .setParameter("rnaPub", PUB_MAPPED_BASED_ON_RNA)
                .setParameter("supplementPub", PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT)
                .setParameter("vegaPub", PUB_MAPPED_BASED_ON_VEGA)
                .list();

        Map<String, CurrentDbLink> currentState = new HashMap<>();
        for (Object[] row : rows) {
            String geneId = (String) row[0];
            String accession = (String) row[1];
            String fdbcont = (String) row[2];
            String zdbId = (String) row[3];
            Integer length = row[4] != null ? ((Number) row[4]).intValue() : null;
            String pub = (String) row[5];

            CurrentDbLink dbLink = new CurrentDbLink(geneId, accession, fdbcont, zdbId, length, pub);
            currentState.put(dbLink.naturalKey(), dbLink);
        }

        session.createNativeQuery("DROP TABLE IF EXISTS tmp_ncbi_delete_candidates").executeUpdate();

        log.info("Prepared metadata for {} load-owned db_link records", currentState.size());
        return currentState;
    }
}
