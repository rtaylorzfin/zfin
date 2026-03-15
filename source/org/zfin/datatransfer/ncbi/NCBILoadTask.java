package org.zfin.datatransfer.ncbi;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.zfin.datatransfer.ncbi.load.AccessionWriter;
import org.zfin.datatransfer.ncbi.load.MarkerAssemblyUpdater;
import org.zfin.datatransfer.ncbi.load.NcbiDbLinkLoader;
import org.zfin.datatransfer.ncbi.load.NcbiDeletePreparer;
import org.zfin.datatransfer.ncbi.load.NcbiDiffComputer.CurrentDbLink;
import org.zfin.datatransfer.webservice.NCBIEfetch;
import org.zfin.datatransfer.ncbi.matching.EnsemblSupplementMatcher;
import org.zfin.datatransfer.ncbi.matching.MatchResult;
import org.zfin.datatransfer.ncbi.matching.RnaAccessionMatcher;
import org.zfin.datatransfer.ncbi.matching.VegaLegacyHandler;
import org.zfin.datatransfer.ncbi.report.NcbiLoadReportGenerator;
import org.zfin.datatransfer.ncbi.report.NcbiLoadStatistics;
import org.zfin.datatransfer.ncbi.service.NcbiGene2AccessionService;
import org.zfin.datatransfer.ncbi.service.NcbiRefSeqCatalogService;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.properties.ZfinPropertiesEnum;
import org.zfin.uniprot.task.NcbiGeneInfoService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.zfin.framework.HibernateUtil.currentSession;
import static org.zfin.util.DateUtil.nowToString;


/**
 * Run the NCBI Gene Load Task to pull in relevant accessions from NCBI
 * Including RefSeq, NCBI Gene IDs, GenBank, etc.
 *
 * By default, it will save files to the directory specified in the ZfinPropertiesEnum.NCBI_RELEASE_ARCHIVE_DIR
 * This can be overridden by setting the NCBI_DOWNLOAD_DIRECTORY environment variable
 *
 * It will download files from https://ftp.ncbi.nlm.nih.gov/ by default. This can be overridden by setting the
 * NCBI_URL_BASE environment variable. It expects to find files relative to that URL at:
 *
 *   gene/DATA/gene2accession.gz
 *   gene/DATA/ARCHIVE/gene2vega.gz
 *   gene/DATA/GENE_INFO/Non-mammalian_vertebrates/Danio_rerio.gene_info.gz
 *   refseq/release/release-catalog/RefSeq-release{{releaseNum}}.catalog.gz
 *   refseq/release/RELEASE_NUMBER
 *
 */
@Log4j2
public class NCBILoadTask extends AbstractScriptWrapper {

    public static final String NCBI_DOWNLOAD_DIRECTORY_BASE = ZfinPropertiesEnum.NCBI_RELEASE_ARCHIVE_DIR.value();

    public static void main(String[] args) throws IOException {
        NCBILoadTask task = new NCBILoadTask();
        task.run();
    }

    public NCBILoadTask() {
        initAll();
    }

    public void run() throws IOException {
        log.info("Starting NCBI Load Task");

        // A: Download files (or use existing if already present)
        File downloadDir = getDownloadDirectory();
        NCBIReleaseFileSet fileSet;
        if (hasExistingFiles(downloadDir)) {
            log.info("Using existing files in {}", downloadDir);
            fileSet = buildFileSetFromExisting(downloadDir);
        } else {
            NCBIReleaseFetcher fetcher = new NCBIReleaseFetcher();
            fileSet = fetcher.downloadLatestReleaseFileSet(downloadDir);
        }

        // B: Load external resource tables
        Session session = currentSession();
        Transaction tx = session.beginTransaction();
        try {
            log.info("Loading gene2accession into external_resource table...");
            NcbiGene2AccessionService.loadIntoPersistentTable(session, fileSet);

            log.info("Loading RefSeq catalog into external_resource table...");
            NcbiRefSeqCatalogService.loadIntoPersistentTable(session, fileSet);

            log.info("Loading gene_info into external_resource table...");
            File geneInfoFile;
            if (fileSet.getZfGeneInfo() != null && fileSet.getZfGeneInfo().exists()) {
                // downloadAndExtract handles both downloading and decompressing .gz files
                geneInfoFile = NcbiGeneInfoService.downloadAndExtract(
                        fileSet.getZfGeneInfo().toURI().toString());
            } else {
                geneInfoFile = NcbiGeneInfoService.downloadAndExtract(
                        NcbiGeneInfoService.resolveInputFileUrl(null));
            }
            NcbiGeneInfoService.loadNcbiFileIntoPersistentTable(session, geneInfoFile);

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw new RuntimeException("Failed to load external resource tables", e);
        }

        boolean incremental = "true".equalsIgnoreCase(System.getenv("NCBI_INCREMENTAL_LOAD"));
        log.info("Load mode: {}", incremental ? "INCREMENTAL" : "DROP-AND-RELOAD");

        // C: Capture before state + prepare deletes
        session = currentSession();
        tx = session.beginTransaction();
        NcbiLoadStatistics beforeStats;
        Map<String, String> toDelete;
        Map<String, CurrentDbLink> currentState = null;
        try {
            beforeStats = NcbiLoadStatistics.capture(session, "before");
            NcbiLoadStatistics.captureAllStateToCsv(session, new File(getDownloadDirectory(), "before_load.csv"));

            NcbiDeletePreparer deletePreparer = new NcbiDeletePreparer(session);
            if (incremental) {
                currentState = deletePreparer.prepareWithMetadata();
                toDelete = deletePreparer.getToDelete();
            } else {
                toDelete = deletePreparer.prepare();
            }
            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw new RuntimeException("Failed during delete preparation", e);
        }

        // Fetch "not in current release" gene IDs (from local file or NCBI API)
        List<String> notInCurrentRelease = fetchNotInCurrentReleaseGeneIds(downloadDir);

        // D: Match genes, E: Build records, F: Delete + load, G: Assembly updates
        session = currentSession();
        tx = session.beginTransaction();
        MatchResult matches;
        try {
            // Create temp table with toDelete IDs for use by matchers and loader
            createToDeleteTempTable(session, toDelete);

            RnaAccessionMatcher rnaMatcher = new RnaAccessionMatcher(session);
            matches = rnaMatcher.match();

            EnsemblSupplementMatcher supplementMatcher = new EnsemblSupplementMatcher(session);
            matches = supplementMatcher.augment(matches);

            VegaLegacyHandler vegaHandler = new VegaLegacyHandler(session);
            matches = vegaHandler.reintroduce(matches);

            log.info("Match summary: {} RNA, {} Ensembl supplement, {} Vega legacy",
                    matches.getConfirmed().size(),
                    matches.getSupplement().size(),
                    matches.getLegacyVega().size());

            // E: Build load records
            AccessionWriter accWriter = new AccessionWriter(session, matches, toDelete);
            NCBIOutputFileToLoad recordsToLoad = accWriter.buildLoadRecords();

            // F: Execute load (incremental or drop-and-reload)
            NcbiDbLinkLoader loader = new NcbiDbLinkLoader(session);
            if (incremental) {
                loader.incrementalLoad(currentState, recordsToLoad, notInCurrentRelease);
            } else {
                loader.deleteAndLoad(toDelete, recordsToLoad, notInCurrentRelease);
            }

            // G: Assembly updates
            MarkerAssemblyUpdater assemblyUpdater = new MarkerAssemblyUpdater(session);
            assemblyUpdater.update();

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw new RuntimeException("Failed during matching/loading", e);
        }

        // H: Capture after state + generate report
        session = currentSession();
        tx = session.beginTransaction();
        try {
            NcbiLoadStatistics afterStats = NcbiLoadStatistics.capture(session, "after");
            NcbiLoadStatistics.captureAllStateToCsv(session, new File(getDownloadDirectory(), "after_load.csv"));

            NcbiLoadReportGenerator reportGenerator = new NcbiLoadReportGenerator(
                    beforeStats, afterStats, matches, getDownloadDirectory());
            reportGenerator.generate();

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            log.error("Failed to generate report (load was successful)", e);
        }

        log.info("Finished NCBI Load Task");
    }

    private boolean hasExistingFiles(File dir) {
        return new File(dir, "gene2accession.gz").exists()
                && new File(dir, "RefSeqCatalog.gz").exists();
    }

    private NCBIReleaseFileSet buildFileSetFromExisting(File dir) {
        NCBIReleaseFileSet fileSet = new NCBIReleaseFileSet();
        fileSet.setGene2accession(new File(dir, "gene2accession.gz"));
        fileSet.setRefSeqCatalog(new File(dir, "RefSeqCatalog.gz"));
        File geneInfo = new File(dir, "zf_gene_info.gz");
        if (!geneInfo.exists()) {
            geneInfo = new File(dir, "Danio_rerio.gene_info.gz");
        }
        fileSet.setZfGeneInfo(geneInfo);
        File gene2vega = new File(dir, "gene2vega.gz");
        if (gene2vega.exists()) {
            fileSet.setGene2vega(gene2vega);
        }
        return fileSet;
    }

    /**
     * Create a temp table containing the dblink ZDB IDs to be deleted.
     * Used by matchers and loader to exclude these records via JOINs instead of large IN clauses.
     * The table name is 'tmp_dblinks_to_delete' — referenced by RnaAccessionMatcher,
     * EnsemblSupplementMatcher, and NcbiDbLinkLoader.
     */
    private void createToDeleteTempTable(Session session, Map<String, String> toDelete) {
        session.createNativeQuery("DROP TABLE IF EXISTS tmp_dblinks_to_delete").executeUpdate();
        session.createNativeQuery("""
            CREATE TEMP TABLE tmp_dblinks_to_delete (
                dblink_zdb_id text NOT NULL PRIMARY KEY
            )
            """).executeUpdate();

        if (toDelete.isEmpty()) return;

        List<String> idList = new java.util.ArrayList<>(toDelete.keySet());
        for (int i = 0; i < idList.size(); i += 1000) {
            List<String> batch = idList.subList(i, Math.min(i + 1000, idList.size()));
            StringBuilder sb = new StringBuilder("INSERT INTO tmp_dblinks_to_delete VALUES ");
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("('").append(batch.get(j).replace("'", "''")).append("')");
            }
            session.createNativeQuery(sb.toString()).executeUpdate();
        }
        log.info("Loaded {} toDelete IDs into temp table", toDelete.size());
    }

    /**
     * Fetch "not in current release" gene IDs. Uses the local file if present
     * (tests and pre-fetched runs), otherwise calls the NCBI E-utilities API.
     */
    private List<String> fetchNotInCurrentReleaseGeneIds(File downloadDir) {
        File localFile = new File(downloadDir, "notInCurrentReleaseGeneIDs.unl");
        if (localFile.exists()) {
            try {
                List<String> ids = Files.readAllLines(localFile.toPath()).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                log.info("Read {} 'not in current release' gene IDs from {}", ids.size(), localFile);
                return ids;
            } catch (IOException e) {
                log.warn("Failed to read {}, falling back to API", localFile, e);
            }
        }

        try {
            log.info("Fetching gene IDs not in current annotation release set...");
            List<String> ids = NCBIEfetch.fetchGeneIDsNotInCurrentAnnotationReleaseSet();
            log.info("Fetched {} gene IDs not in current annotation release", ids.size());
            // Write to file for reproducibility
            try {
                Files.writeString(localFile.toPath(), String.join("\n", ids));
            } catch (IOException e) {
                log.warn("Failed to write not-in-current-release file", e);
            }
            return ids;
        } catch (Exception e) {
            log.warn("Failed to fetch 'not in current release' gene IDs, proceeding without", e);
            return Collections.emptyList();
        }
    }

    private File getDownloadDirectory() {
        File downloadDirectory = new File(NCBI_DOWNLOAD_DIRECTORY_BASE, nowToString("yyyy-MM-dd"));

        if (System.getenv("NCBI_DOWNLOAD_DIRECTORY") != null) {
            downloadDirectory = new File(System.getenv("NCBI_DOWNLOAD_DIRECTORY"));
        }

        if (!downloadDirectory.exists()) {
            downloadDirectory.mkdirs();
        }
        return downloadDirectory;
    }
}
