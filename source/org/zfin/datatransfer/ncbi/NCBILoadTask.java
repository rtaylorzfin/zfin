package org.zfin.datatransfer.ncbi;

import lombok.extern.log4j.Log4j2;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.zfin.datatransfer.ncbi.service.NcbiGene2AccessionService;
import org.zfin.datatransfer.ncbi.service.NcbiRefSeqCatalogService;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.properties.ZfinPropertiesEnum;
import org.zfin.uniprot.task.NcbiGeneInfoService;

import java.io.File;
import java.io.IOException;

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

        // A: Download files
        NCBIReleaseFetcher fetcher = new NCBIReleaseFetcher();
        NCBIReleaseFileSet fileSet = fetcher.downloadLatestReleaseFileSet(getDownloadDirectory());

        // B: Load external resource tables
        Session session = currentSession();
        Transaction tx = session.beginTransaction();
        try {
            log.info("Loading gene2accession into external_resource table...");
            NcbiGene2AccessionService.loadIntoPersistentTable(session, fileSet);

            log.info("Loading RefSeq catalog into external_resource table...");
            NcbiRefSeqCatalogService.loadIntoPersistentTable(session, fileSet);

            log.info("Loading gene_info into external_resource table...");
            File geneInfoFile = NcbiGeneInfoService.downloadAndExtract(
                    NcbiGeneInfoService.resolveInputFileUrl(null));
            NcbiGeneInfoService.loadNcbiFileIntoPersistentTable(session, geneInfoFile);
            geneInfoFile.delete();

            tx.commit();
        } catch (Exception e) {
            tx.rollback();
            throw new RuntimeException("Failed to load external resource tables", e);
        }

        //... more to come (matching, delete/load, reporting)
        log.info("Finished NCBI Load Task");
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
