package org.zfin.datatransfer.ncbi.load;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad.LoadFileRow;

import java.util.*;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

/**
 * Handles the core delete/insert cycle for the NCBI gene load.
 * Replaces loadNCBIgeneAccs.sql.
 *
 * Steps:
 * 1. Preserve "not in current release" gene IDs that aren't being replaced or conflicting
 * 2. Delete from reference_protein for records being deleted
 * 3. Delete from zdb_active_data (cascade deletes db_link + record_attribution)
 * 4. Remove load-pub attributions from manually curated GenBank accessions
 * 5. Resolve conflicts with manually-curated NCBI Gene IDs
 * 6. Insert new db_link records
 * 7. Insert record_attribution entries
 * 8. Post-load many-to-many cleanup
 * 9. Update marker_annotation_status
 */
@Log4j2
public class NcbiDbLinkLoader {

    private final Session session;

    public NcbiDbLinkLoader(Session session) {
        this.session = session;
    }

    /**
     * Execute the full delete + load cycle.
     *
     * @param toDelete Map of dblink_zdb_id → accession for records to delete
     * @param recordsToLoad Records to insert
     * @param notInCurrentRelease NCBI gene IDs not in current annotation release
     */
    public void deleteAndLoad(Map<String, String> toDelete, NCBIOutputFileToLoad recordsToLoad,
                              List<String> notInCurrentRelease) {

        // Step 1: Preserve certain delete candidates
        Set<String> preserved = preserveNotInCurrentRelease(toDelete, recordsToLoad, notInCurrentRelease);
        Map<String, String> effectiveDelete = new HashMap<>(toDelete);
        preserved.forEach(effectiveDelete::remove);
        log.info("Preserved {} 'not in current release' gene IDs from deletion", preserved.size());

        // Step 2: Delete from reference_protein
        deleteReferenceProteins(effectiveDelete.keySet());

        // Step 3: Delete from zdb_active_data (cascades to db_link)
        deleteActiveData(effectiveDelete.keySet());

        // Step 4: Remove load-pub attributions from manually curated GenBank accessions
        removeLoadPubFromManuallyCurated();

        // Step 5: Resolve conflicts with manually-curated NCBI Gene IDs
        resolveManualCurationConflicts(recordsToLoad);

        // Step 6 + 7: Insert new db_link records and attributions
        insertNewRecords(recordsToLoad);

        // Step 8: Post-load many-to-many cleanup
        cleanupPostLoadManyToMany();

        // Step 9: Update marker_annotation_status
        updateMarkerAnnotationStatus(notInCurrentRelease);
    }

    /**
     * Preserve NCBI Gene IDs that are:
     * 1. Marked for deletion
     * 2. Not in current release
     * 3. NOT being replaced by a new load record
     * 4. NOT involved in N:N conflicts (deferred to Phase 3)
     *
     * Returns set of dblink_zdb_ids to preserve (remove from delete set).
     */
    @SuppressWarnings("unchecked")
    private Set<String> preserveNotInCurrentRelease(Map<String, String> toDelete,
                                                     NCBIOutputFileToLoad recordsToLoad,
                                                     List<String> notInCurrentRelease) {
        Set<String> preserved = new HashSet<>();
        if (toDelete.isEmpty() || notInCurrentRelease.isEmpty()) {
            return preserved;
        }

        // Find genes being loaded as NCBI Gene IDs
        Set<String> genesBeingLoaded = new HashSet<>();
        for (LoadFileRow row : recordsToLoad.getRows()) {
            if (FDCONT_NCBI_GENE_ID.equals(row.fdb())) {
                genesBeingLoaded.add(row.geneID());
            }
        }

        Set<String> notInCurrentSet = new HashSet<>(notInCurrentRelease);

        // Query db_link for delete candidates that have "not in current" NCBI Gene IDs
        String sql = """
            SELECT dblink_zdb_id, dblink_linked_recid, dblink_acc_num
            FROM db_link
            WHERE dblink_zdb_id IN (:deleteIds)
              AND dblink_fdbcont_zdb_id = :fdbcont
              AND dblink_acc_num IN (:notInCurrent)
            """;

        // Batch the notInCurrent set to avoid parameter limit issues
        List<String> notInCurrentList = new ArrayList<>(notInCurrentSet);
        for (int i = 0; i < notInCurrentList.size(); i += 1000) {
            List<String> batch = notInCurrentList.subList(i, Math.min(i + 1000, notInCurrentList.size()));

            List<Object[]> rows = session.createNativeQuery(sql)
                    .setParameterList("deleteIds", toDelete.keySet())
                    .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                    .setParameterList("notInCurrent", batch)
                    .list();

            for (Object[] row : rows) {
                String dblinkZdbId = (String) row[0];
                String geneId = (String) row[1];

                // Only preserve if gene is NOT being replaced by new load
                if (!genesBeingLoaded.contains(geneId)) {
                    preserved.add(dblinkZdbId);
                }
            }
        }

        return preserved;
    }

    /**
     * Delete from reference_protein for records being deleted.
     */
    private void deleteReferenceProteins(Set<String> deleteIds) {
        if (deleteIds.isEmpty()) return;

        int deleted = session.createNativeQuery(
                "DELETE FROM reference_protein WHERE rp_dblink_zdb_id IN (:ids)")
                .setParameterList("ids", deleteIds)
                .executeUpdate();
        log.info("Deleted {} reference_protein records", deleted);
    }

    /**
     * Delete from zdb_active_data, which cascades to db_link and record_attribution.
     */
    private void deleteActiveData(Set<String> deleteIds) {
        if (deleteIds.isEmpty()) return;

        // Batch deletes to avoid oversized IN clauses
        List<String> idList = new ArrayList<>(deleteIds);
        int totalDeleted = 0;
        for (int i = 0; i < idList.size(); i += 1000) {
            List<String> batch = idList.subList(i, Math.min(i + 1000, idList.size()));
            int deleted = session.createNativeQuery(
                    "DELETE FROM zdb_active_data WHERE zactvd_zdb_id IN (:ids)")
                    .setParameterList("ids", batch)
                    .executeUpdate();
            totalDeleted += deleted;
        }
        log.info("Deleted {} zdb_active_data records (cascading to db_link)", totalDeleted);
    }

    /**
     * Remove load-pub attributions from manually curated GenBank accessions.
     * These are records where the RNA load pub was attributed to a GenBank accession
     * that also has manual curation.
     */
    private void removeLoadPubFromManuallyCurated() {
        String sql = """
            DELETE FROM record_attribution
            WHERE recattrib_source_zdb_id = :loadPub
              AND EXISTS (SELECT 1 FROM db_link
                          WHERE recattrib_data_zdb_id = dblink_zdb_id
                            AND (dblink_linked_recid LIKE 'ZDB-GENE%' OR dblink_linked_recid LIKE '%RNAG%')
                            AND dblink_fdbcont_zdb_id IN (:fdbconts))
            """;

        int deleted = session.createNativeQuery(sql)
                .setParameter("loadPub", PUB_MAPPED_BASED_ON_RNA)
                .setParameterList("fdbconts", List.of(FDCONT_GENBANK_RNA, FDCONT_GENPEPT, FDCONT_GENBANK_DNA))
                .executeUpdate();
        log.info("Removed {} load-pub attributions from manually curated GenBank records", deleted);
    }

    /**
     * Resolve conflicts between load records and manually-curated NCBI Gene IDs.
     * If a load record conflicts with an existing manually-curated NCBI Gene ID
     * (same gene, different ID or different gene, same ID), remove the load record.
     */
    @SuppressWarnings("unchecked")
    private void resolveManualCurationConflicts(NCBIOutputFileToLoad recordsToLoad) {
        // Find all NCBI Gene ID load records
        Set<String> loadGenes = new HashSet<>();
        Set<String> loadNcbiIds = new HashSet<>();
        for (LoadFileRow row : recordsToLoad.getRows()) {
            if (FDCONT_NCBI_GENE_ID.equals(row.fdb())) {
                loadGenes.add(row.geneID());
                loadNcbiIds.add(row.accession());
            }
        }

        if (loadGenes.isEmpty()) return;

        // Find existing manually-curated NCBI Gene IDs that conflict
        String sql = """
            SELECT dblink_linked_recid, dblink_acc_num
            FROM db_link
            WHERE dblink_fdbcont_zdb_id = :fdbcont
              AND (dblink_linked_recid IN (:loadGenes) OR dblink_acc_num IN (:loadNcbiIds))
              AND EXISTS (
                  SELECT 1 FROM record_attribution
                  WHERE recattrib_data_zdb_id = dblink_zdb_id
                    AND recattrib_source_zdb_id NOT IN (:loadPubs))
            """;

        List<Object[]> existingRows = session.createNativeQuery(sql)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .setParameterList("loadGenes", loadGenes)
                .setParameterList("loadNcbiIds", loadNcbiIds)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .list();

        // Collect conflicting genes and NCBI IDs
        Set<String> conflictGenes = new HashSet<>();
        Set<String> conflictNcbiIds = new HashSet<>();
        for (Object[] row : existingRows) {
            String existingGene = (String) row[0];
            String existingNcbi = (String) row[1];

            // Check for actual conflicts (same gene different ID or different gene same ID)
            for (LoadFileRow loadRow : recordsToLoad.getRows()) {
                if (!FDCONT_NCBI_GENE_ID.equals(loadRow.fdb())) continue;

                boolean sameGeneDiffId = loadRow.geneID().equals(existingGene) && !loadRow.accession().equals(existingNcbi);
                boolean diffGeneSameId = !loadRow.geneID().equals(existingGene) && loadRow.accession().equals(existingNcbi);

                if (sameGeneDiffId || diffGeneSameId) {
                    conflictGenes.add(loadRow.geneID());
                    conflictNcbiIds.add(loadRow.accession());
                    conflictGenes.add(existingGene);
                    conflictNcbiIds.add(existingNcbi);
                }
            }
        }

        if (!conflictGenes.isEmpty()) {
            // Remove ALL load records for conflicting genes (NCBI Gene ID records only)
            recordsToLoad.removeNcbiGeneIdRecords(conflictGenes, conflictNcbiIds);
            log.info("Removed load records for {} conflicting genes due to manual curation conflicts", conflictGenes.size());
        }
    }

    /**
     * Insert new db_link records and record_attribution entries.
     */
    @SuppressWarnings("unchecked")
    private void insertNewRecords(NCBIOutputFileToLoad recordsToLoad) {
        List<LoadFileRow> rows = recordsToLoad.getRows();
        if (rows.isEmpty()) {
            log.info("No records to insert");
            return;
        }

        // Dedup by (gene, accession, fdbcont) keeping highest priority pub
        Map<String, LoadFileRow> deduped = new LinkedHashMap<>();
        for (LoadFileRow row : rows) {
            String key = row.geneID() + "|" + row.accession() + "|" + row.fdb();
            LoadFileRow existing = deduped.get(key);
            if (existing == null || pubPriority(row.pub()) < pubPriority(existing.pub())) {
                deduped.put(key, row);
            }
        }

        int inserted = 0;
        int attributed = 0;

        for (LoadFileRow row : deduped.values()) {
            // Generate ZDB ID
            String zdbId = (String) session.createNativeQuery(
                    "SELECT get_id_and_insert_active_data('DBLINK')")
                    .uniqueResult();

            // Insert db_link with ON CONFLICT DO NOTHING
            String insertSql = """
                INSERT INTO db_link (dblink_linked_recid, dblink_acc_num, dblink_acc_num_display,
                                     dblink_info, dblink_zdb_id, dblink_length, dblink_fdbcont_zdb_id)
                VALUES (:gene, :acc, :acc, :info, :zdbId, :length, :fdbcont)
                ON CONFLICT (dblink_linked_recid, dblink_acc_num, dblink_fdbcont_zdb_id) DO NOTHING
                """;

            int affected = session.createNativeQuery(insertSql)
                    .setParameter("gene", row.geneID())
                    .setParameter("acc", row.accession())
                    .setParameter("info", "uncurated: NCBI gene load")
                    .setParameter("zdbId", zdbId)
                    .setParameter("length", row.length())
                    .setParameter("fdbcont", row.fdb())
                    .executeUpdate();

            if (affected > 0) {
                inserted++;

                // Insert record_attribution
                session.createNativeQuery(
                        "INSERT INTO record_attribution (recattrib_data_zdb_id, recattrib_source_zdb_id) VALUES (:data, :source)")
                        .setParameter("data", zdbId)
                        .setParameter("source", row.pub())
                        .executeUpdate();
                attributed++;
            } else {
                // Conflict — row already exists, clean up the active data we just created
                session.createNativeQuery(
                        "DELETE FROM zdb_active_data WHERE zactvd_zdb_id = :id")
                        .setParameter("id", zdbId)
                        .executeUpdate();
            }

            // Flush periodically
            if ((inserted + attributed) % 100 == 0) {
                session.flush();
                session.clear();
            }
        }

        session.flush();
        log.info("Inserted {} db_link records with {} attributions (from {} deduped rows)",
                inserted, attributed, deduped.size());
    }

    private int pubPriority(String pub) {
        return switch (pub) {
            case PUB_MAPPED_BASED_ON_RNA -> 1;
            case PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT -> 2;
            case PUB_MAPPED_BASED_ON_VEGA -> 3;
            default -> 4;
        };
    }

    /**
     * Post-load cleanup: find and remove any many-to-many NCBI Gene ID links
     * that survived the load (multiple genes → same NCBI ID, or one gene → multiple NCBI IDs).
     */
    @SuppressWarnings("unchecked")
    private void cleanupPostLoadManyToMany() {
        String sql = """
            SELECT dblink_zdb_id FROM db_link
            WHERE dblink_fdbcont_zdb_id = :fdbcont
              AND dblink_acc_num IN (
                  SELECT dblink_acc_num FROM db_link
                  WHERE dblink_fdbcont_zdb_id = :fdbcont
                  GROUP BY dblink_acc_num HAVING COUNT(dblink_linked_recid) > 1
              )
            UNION
            SELECT dblink_zdb_id FROM db_link
            WHERE dblink_fdbcont_zdb_id = :fdbcont
              AND dblink_linked_recid IN (
                  SELECT dblink_linked_recid FROM db_link
                  WHERE dblink_fdbcont_zdb_id = :fdbcont
                  GROUP BY dblink_linked_recid HAVING COUNT(dblink_acc_num) > 1
              )
            """;

        List<String> manyToManyIds = session.createNativeQuery(sql)
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .list();

        if (!manyToManyIds.isEmpty()) {
            log.warn("Found {} many-to-many NCBI Gene ID links post-load, cleaning up", manyToManyIds.size());
            for (int i = 0; i < manyToManyIds.size(); i += 1000) {
                List<String> batch = manyToManyIds.subList(i, Math.min(i + 1000, manyToManyIds.size()));
                session.createNativeQuery("DELETE FROM zdb_active_data WHERE zactvd_zdb_id IN (:ids)")
                        .setParameterList("ids", batch)
                        .executeUpdate();
            }
        }
    }

    /**
     * Update marker_annotation_status based on NCBI Gene ID matches.
     *
     * Logic:
     * 1. Gene has NCBI Gene ID in "not in current release" → status 13
     * 2. Gene has NCBI Gene ID attributed to load pub → status 12 (Current)
     * 3. Otherwise → no entry (Unknown)
     */
    private void updateMarkerAnnotationStatus(List<String> notInCurrentRelease) {
        // Clear all existing entries
        int cleared = session.createNativeQuery("DELETE FROM marker_annotation_status").executeUpdate();
        log.info("Cleared {} existing marker_annotation_status entries", cleared);

        // Load "not in current release" into a temp table for efficient querying
        session.createNativeQuery("CREATE TEMP TABLE IF NOT EXISTS tmp_not_in_current (ncbi_gene_id varchar(50) NOT NULL)")
                .executeUpdate();
        session.createNativeQuery("DELETE FROM tmp_not_in_current").executeUpdate();

        if (!notInCurrentRelease.isEmpty()) {
            for (int i = 0; i < notInCurrentRelease.size(); i += 1000) {
                List<String> batch = notInCurrentRelease.subList(i, Math.min(i + 1000, notInCurrentRelease.size()));
                StringBuilder sb = new StringBuilder("INSERT INTO tmp_not_in_current VALUES ");
                for (int j = 0; j < batch.size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append("('").append(batch.get(j).replace("'", "''")).append("')");
                }
                session.createNativeQuery(sb.toString()).executeUpdate();
            }
        }

        // Insert marker_annotation_status based on the decision logic
        String insertSql = """
            INSERT INTO marker_annotation_status (mas_mrkr_zdb_id, mas_vt_pk_id)
            WITH eligible_records AS (
                SELECT DISTINCT
                    dl.dblink_linked_recid,
                    CASE
                        WHEN EXISTS (
                            SELECT 1 FROM tmp_not_in_current nic
                            WHERE nic.ncbi_gene_id = dl.dblink_acc_num
                        ) THEN 13
                        WHEN EXISTS (
                            SELECT 1 FROM record_attribution ra
                            WHERE ra.recattrib_data_zdb_id = dl.dblink_zdb_id
                              AND ra.recattrib_source_zdb_id IN (:loadPubs)
                        ) THEN 12
                    END AS annotation_status
                FROM db_link dl
                WHERE dl.dblink_fdbcont_zdb_id = :fdbcont
            )
            SELECT dblink_linked_recid, annotation_status
            FROM eligible_records
            WHERE annotation_status IS NOT NULL
            """;

        int inserted = session.createNativeQuery(insertSql)
                .setParameterList("loadPubs", List.of(PUB_MAPPED_BASED_ON_RNA, PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT))
                .setParameter("fdbcont", FDCONT_NCBI_GENE_ID)
                .executeUpdate();
        log.info("Inserted {} marker_annotation_status entries", inserted);

        // Clean up temp table
        session.createNativeQuery("DROP TABLE IF EXISTS tmp_not_in_current").executeUpdate();
    }

}
