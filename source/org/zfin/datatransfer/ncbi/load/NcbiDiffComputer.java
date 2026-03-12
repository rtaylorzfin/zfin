package org.zfin.datatransfer.ncbi.load;

import lombok.extern.log4j.Log4j2;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad;
import org.zfin.datatransfer.ncbi.NCBIOutputFileToLoad.LoadFileRow;

import java.util.*;

import static org.zfin.datatransfer.ncbi.NCBIDirectPort.*;

/**
 * Computes the diff between current database state and desired load state.
 * Pure computation — no database access. Designed for easy unit testing.
 *
 * The natural key for db_link uniqueness is (dblink_linked_recid, dblink_acc_num, dblink_fdbcont_zdb_id).
 */
@Log4j2
public class NcbiDiffComputer {

    /**
     * Represents a db_link record currently in the database.
     */
    public record CurrentDbLink(
            String geneId,
            String accession,
            String fdbcont,
            String zdbId,
            Integer length,
            String pub
    ) {
        public String naturalKey() {
            return geneId + "|" + accession + "|" + fdbcont;
        }
    }

    /**
     * An update to apply to an existing db_link record.
     */
    public record DiffUpdate(
            String zdbId,
            String geneId,
            String accession,
            String fdbcont,
            Integer newLength,
            Integer oldLength,
            String newPub,
            String oldPub
    ) {}

    /**
     * A record that matched but didn't need data changes. May still need attribution normalization.
     */
    public record KeptRecord(String zdbId, String desiredPub) {}

    /**
     * The result of the diff computation.
     */
    public record DiffResult(
            List<LoadFileRow> toAdd,
            List<String> toDeleteZdbIds,
            List<DiffUpdate> toUpdate,
            List<KeptRecord> kept
    ) {}

    /**
     * Compute the diff between current database state and the desired load records.
     *
     * @param currentState Map of natural key → CurrentDbLink for all load-owned records in the DB
     * @param recordsToLoad The desired state from AccessionWriter
     * @return DiffResult with adds, deletes, and updates
     */
    public DiffResult computeDiff(Map<String, CurrentDbLink> currentState, NCBIOutputFileToLoad recordsToLoad) {

        // Dedup desired records by natural key, keeping highest priority pub
        Map<String, LoadFileRow> desired = dedup(recordsToLoad.getRows());

        List<LoadFileRow> toAdd = new ArrayList<>();
        List<DiffUpdate> toUpdate = new ArrayList<>();
        List<KeptRecord> keptRecords = new ArrayList<>();
        Set<String> matchedKeys = new HashSet<>();

        for (Map.Entry<String, LoadFileRow> entry : desired.entrySet()) {
            String key = entry.getKey();
            LoadFileRow desiredRow = entry.getValue();
            CurrentDbLink current = currentState.get(key);

            if (current == null) {
                // New record — needs INSERT
                toAdd.add(desiredRow);
            } else {
                matchedKeys.add(key);
                boolean lengthChanged = !Objects.equals(desiredRow.length(), current.length());
                boolean pubChanged = !Objects.equals(desiredRow.pub(), current.pub())
                        && pubPriority(desiredRow.pub()) < pubPriority(current.pub());

                if (lengthChanged || pubChanged) {
                    toUpdate.add(new DiffUpdate(
                            current.zdbId(),
                            current.geneId(),
                            current.accession(),
                            current.fdbcont(),
                            desiredRow.length(),
                            current.length(),
                            desiredRow.pub(),
                            current.pub()
                    ));
                } else {
                    keptRecords.add(new KeptRecord(current.zdbId(), desiredRow.pub()));
                }
            }
        }

        // Records in current state but not in desired state — needs DELETE
        List<String> toDeleteZdbIds = new ArrayList<>();
        for (Map.Entry<String, CurrentDbLink> entry : currentState.entrySet()) {
            if (!matchedKeys.contains(entry.getKey()) && !desired.containsKey(entry.getKey())) {
                toDeleteZdbIds.add(entry.getValue().zdbId());
            }
        }

        log.info("Diff result: {} adds, {} deletes, {} updates, {} kept unchanged",
                toAdd.size(), toDeleteZdbIds.size(), toUpdate.size(), keptRecords.size());

        return new DiffResult(toAdd, toDeleteZdbIds, toUpdate, keptRecords);
    }

    private Map<String, LoadFileRow> dedup(List<LoadFileRow> rows) {
        Map<String, LoadFileRow> deduped = new LinkedHashMap<>();
        for (LoadFileRow row : rows) {
            String key = row.geneID() + "|" + row.accession() + "|" + row.fdb();
            LoadFileRow existing = deduped.get(key);
            if (existing == null || pubPriority(row.pub()) < pubPriority(existing.pub())) {
                deduped.put(key, row);
            }
        }
        return deduped;
    }

    private int pubPriority(String pub) {
        return switch (pub) {
            case PUB_MAPPED_BASED_ON_RNA -> 1;
            case PUB_MAPPED_BASED_ON_NCBI_SUPPLEMENT -> 2;
            case PUB_MAPPED_BASED_ON_VEGA -> 3;
            default -> 4;
        };
    }
}
