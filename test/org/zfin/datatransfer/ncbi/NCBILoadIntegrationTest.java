package org.zfin.datatransfer.ncbi;


import org.hibernate.Session;
import org.junit.Before;
import org.junit.Test;
import org.zfin.AbstractDangerousDatabaseTest;
import org.zfin.framework.HibernateUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;

/**
 * Run tests against a database that only has test data in it.
 * Not to be run on prod or any other important database.
 */
public class NCBILoadIntegrationTest extends AbstractDangerousDatabaseTest {

    public static final String ZF_GENE_INFO_HEADER = "#tax_id\tGeneID\tSymbol\tLocusTag\tSynonyms\tdbXrefs\tchromosome\tmap_location\tdescription\ttype_of_gene\tSymbol_from_nomenclature_authority\tFull_name_from_nomenclature_authority\tNomenclature_status\tOther_designations\tModification_date\tFeature_type\n";
    public static final String GENE_2_ACCESSION_HEADER = "#tax_id\tGeneID\tstatus\tRNA_nucleotide_accession.version\tRNA_nucleotide_gi\tprotein_accession.version\tprotein_gi\tgenomic_nucleotide_accession.version\tgenomic_nucleotide_gi\tstart_position_on_the_genomic_accession\tend_position_on_the_genomic_accession\torientation\tassembly\tmature_peptide_accession.version\tmature_peptide_gi\tSymbol\n";
    private static final String TEST_GENE = "ZDB-GENE-010319-10";
    private static final String TEST_NCBI_ID = "80928";
    private static final String NCBI_FDBCONT = "ZDB-FDBCONT-040412-1";
    private static final String GENBANK_FDBCONT = "ZDB-FDBCONT-040412-37";
    private static final String REFSEQ_CATALOG_ARCHIVE = "/research/zarchive/load_files/NCBI-gene-load-archive/2025-09-23/RefSeqCatalog.gz";

    public static final Boolean DELETE_ON_EXIT = true;

    public Path tempDir;

    @Before
    public void setupTestData() throws IOException {
        //Make sure we are running in the NCBI test environment
        String env = System.getenv("IS_NCBI_LOAD_CONTAINER");
        if (!"true".equals(env)) {
            System.out.println("IS_NCBI_LOAD_CONTAINER environment variable is not set to true. Preventing run to avoid data corruption.");
            System.out.flush();
            throw new RuntimeException("NCBI_LOAD_CONTAINER environment variable is not set. Preventing run to avoid data corruption.");
        }

        tempDir = Files.createTempDirectory("ncbi_test_");
        if (DELETE_ON_EXIT) {
            tempDir.toFile().deleteOnExit();
        }
        createTestFiles();
    }

    /**
     * Test of the simplest case. Start with one gene with no NCBI link. The gene has an RNA sequence
     * that can be be matched to an NCBI record. After the load, there should be one new NCBI Gene ID link
     */
    @Test
    public void testInitialLoadCreatesOneLink() throws IOException {

        fillTestFiles("7955\t80928\t-\tBG985726.1\t14389806\t-\t-\t-\t-\t-\t-\t?\t-\t-\t-\tid:ibd2600",
                "7955\t80928\tid:ibd2600\t-\t-\tZFIN:ZDB-GENE-010319-10|AllianceGenome:ZFIN:ZDB-GENE-010319-10\t5\t-\tid:ibd2600\tprotein-coding\tid:ibd2600\tid:ibd2600\tO\tuncharacterized protein LOC80928\t20250705\t-"
        );

        // Setup: Clear any existing NCBI links for test gene
        Session session = HibernateUtil.currentSession();
        session.beginTransaction();
        deleteAllMarkersAndDBLinks();

        //create database state before the load
        createGene("ZDB-GENE-010319-10", "id:ibd2600");
        createDBLink("ZDB-GENE-010319-10", "BG985726", GENBANK_FDBCONT, "ZDB-PUB-020723-5");

        session.getTransaction().commit();

        // Execute: Run NCBI load
        runNCBILoad();

        // Verify: Check that exactly one link was created
        int linkCount = getNCBILinkCount(TEST_GENE);
        assertEquals("Should create exactly one NCBI link", 1, linkCount);

        // Verify: Check correct NCBI ID was linked
        List<String> ncbiIds = getNCBILinks(TEST_GENE);
        assertEquals("Should have exactly one NCBI ID", 1, ncbiIds.size());
        assertEquals("Should link correct NCBI ID", TEST_NCBI_ID, ncbiIds.get(0));

        // Verify: Check attribution was created
        int attributionCount = getAttributionCount(TEST_GENE);
        assertEquals("Should create exactly one attribution record",1, attributionCount);

        // Check the output files:
        Path beforeLoadFile = tempDir.resolve("before_load.csv");
        Path afterLoadFile = tempDir.resolve("after_load.csv");
        assertEquals(true, Files.exists(beforeLoadFile));
        assertEquals(true, Files.exists(afterLoadFile));
        // before should have 2 lines and after should have 3 (header + 2 data lines)
        try {
            long beforeLines = Files.lines(beforeLoadFile).count();
            long afterLines = Files.lines(afterLoadFile).count();
            assertEquals(2, beforeLines);
            assertEquals(3, afterLines);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read load output files", e);
        }
    }

    @Test
    public void testGeneWithReplacedNCBIGene() throws IOException {

        // Setup: Clear any existing NCBI links for test gene
        Session session = HibernateUtil.currentSession();
        session.beginTransaction();
        deleteAllMarkersAndDBLinks();

        // Create the gene
        /*
        We need to create pre-run state with:
         gene ZDB-GENE-120709-33 linked to Gene:103910949 with attribution to ZDB-PUB-230516-87 (ensembl match)
         The gene has an RNA sequence (GDQQ01002583) attributed manually (ZDB-PUB-030905-2)
         that matches to NCBI Gene: 108183900 according to the download files.

         In a future pass, maybe we can check if the following complication makes any difference:
         // INSERT INTO "public"."db_link" ("dblink_linked_recid", "dblink_acc_num", "dblink_info", "dblink_zdb_id", "dblink_acc_num_display", "dblink_length", "dblink_fdbcont_zdb_id") VALUES ('ZDB-TSCRIPT-120702-150', 'OTTDARG00000036791', 'uncurated 10/24/2012', 'ZDB-DBLINK-121024-585', 'OTTDARG00000036791', NULL, 'ZDB-FDBCONT-040412-14');

         */
        //create database state before the load
        createGene("ZDB-GENE-120709-33", "si:ch211-209j12.2");
        createDBLink("ZDB-GENE-120709-33", "103910949", NCBI_FDBCONT, "ZDB-PUB-230516-87");
        createDBLink("ZDB-GENE-120709-33", "GDQQ01002583", GENBANK_FDBCONT, "ZDB-PUB-030905-2");
        session.getTransaction().commit();

        //create test files
        String zfGeneInfo = "7955\t108183900\tsi:ch211-209j12.2\t-\t-\tZFIN:ZDB-GENE-120709-33|Ensembl:ENSDARG00000099337|AllianceGenome:ZFIN:ZDB-GENE-120709-33\t4\t-\tsi:ch211-209j12.2\tncRNA\tsi:ch211-209j12.2\tsi:ch211-209j12.2\tO\tuncharacterized protein LOC108183900\t20250909\t-";
        String gene2accession = "7955\t108183900\t-\tGDQQ01002583.1\t-\t-\t-\t-\t-\t-\t-\t?\t-\t-\t-\tsi:ch211-209j12.2";
        fillTestFiles(zfGeneInfo, gene2accession);

        // Execute: Run NCBI load
        runNCBILoad();

        // Check the output files:
        Path beforeLoadFile = tempDir.resolve("before_load.csv");
        Path afterLoadFile = tempDir.resolve("after_load.csv");
        assertEquals(true, Files.exists(beforeLoadFile));
        assertEquals(true, Files.exists(afterLoadFile));
        // before should have 2 lines and after should have 3 (header + 2 data lines)
        try {
            long beforeLines = Files.lines(beforeLoadFile).count();
            long afterLines = Files.lines(afterLoadFile).count();
            assertEquals(2, beforeLines);
            assertEquals(3, afterLines);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read load output files", e);
        }
    }

    private void createDBLink(String geneID, String accNum, String fdbcontID, String pubID) {
        String dateYYMMDD = new SimpleDateFormat("yyMMdd").format(new Date());
        String currentTime = "%05d".formatted(System.currentTimeMillis() % 100000);
        String dbLinkID = "ZDB-DBLINK-" + dateYYMMDD + "-" + currentTime;
        String activeDataSql = "INSERT INTO zdb_active_data (zactvd_zdb_id) VALUES (?)";
        HibernateUtil.currentSession().createNativeQuery(activeDataSql)
                .setParameter(1, dbLinkID)
                .executeUpdate();

        String sql = """
                INSERT INTO db_link ("dblink_linked_recid", "dblink_acc_num", "dblink_zdb_id", "dblink_fdbcont_zdb_id")
                VALUES (?, ?, ?, ?)
                """;
        HibernateUtil.currentSession().createNativeQuery(sql)
                .setParameter(1, geneID)
                .setParameter(2, accNum)
                .setParameter(3, dbLinkID)
                .setParameter(4, fdbcontID)
                .executeUpdate();

        //example attribution:
        // INSERT INTO "public"."record_attribution" ("recattrib_pk_id", "recattrib_data_zdb_id", "recattrib_source_zdb_id", "recattrib_source_significance", "recattrib_source_type", "recattrib_created_at", "recattrib_modified_at", "recattrib_modified_count") VALUES (1, 'ZDB-ALT-000209-24', 'ZDB-PUB-030129-1', NULL, 'standard', NULL, NULL, NULL);
        String attribSql = """
                INSERT INTO record_attribution (recattrib_data_zdb_id, recattrib_source_zdb_id, recattrib_source_type)
                VALUES (?, ?, 'standard')
                """;
        HibernateUtil.currentSession().createNativeQuery(attribSql)
                .setParameter(1, dbLinkID)
                .setParameter(2, pubID)
                .executeUpdate();

    }

    private void createGene(String geneID, String geneAbbrev) {
        String activeDataSql = "INSERT INTO zdb_active_data (zactvd_zdb_id) VALUES (?)";
        HibernateUtil.currentSession().createNativeQuery(activeDataSql)
                .setParameter(1, geneID)
                .executeUpdate();

        String sql = """
                INSERT INTO marker ("mrkr_zdb_id", "mrkr_name", "mrkr_abbrev", "mrkr_type", "mrkr_owner") 
                VALUES (?, ?, ?, 'GENE', ?)
                """;
        HibernateUtil.currentSession().createNativeQuery(sql)
                .setParameter(1, geneID)
                .setParameter(2, geneAbbrev)
                .setParameter(3, geneAbbrev)
                .setParameter(4, "ZDB-PERS-990902-1")
                .executeUpdate();
    }


    // Helper methods
    public void createTestFiles() throws IOException {
        System.out.println("Creating test files in: " + tempDir);
        System.out.flush();

        // Create gene2accession.gz
        Files.writeString(tempDir.resolve("gene2accession"), GENE_2_ACCESSION_HEADER);

        // Create zf_gene_info.gz
        Files.writeString(tempDir.resolve("zf_gene_info"), ZF_GENE_INFO_HEADER);

        // Create other required files (empty)
        Files.writeString(tempDir.resolve("gene2vega"), "");
        Files.writeString(tempDir.resolve("seq.fasta"), "");
        Files.writeString(tempDir.resolve("notInCurrentReleaseGeneIDs.unl"), "");
        Files.writeString(tempDir.resolve("RELEASE_NUMBER"), "231");
        Files.writeString(tempDir.resolve("ncbi_matches_through_ensembl.csv"),
            "ncbi_id,zdb_id,ensembl_id,symbol,dblinks,publications,rna_accessions");

        // Compress required files
        gzipFile(tempDir.resolve("gene2accession"));
        gzipFile(tempDir.resolve("zf_gene_info"));
        gzipFile(tempDir.resolve("gene2vega"));

        Files.copy(Path.of(REFSEQ_CATALOG_ARCHIVE), tempDir.resolve("RefSeqCatalog.gz"));

        // Mark as delete on exit
        if (DELETE_ON_EXIT) {
            Files.list(tempDir).forEach(path -> path.toFile().deleteOnExit());
        }
    }

    public void fillTestFiles(String gene2AccessionContents, String zfGeneInfoContents) throws IOException {
        fillTestFiles(gene2AccessionContents, zfGeneInfoContents, false);
    }

    public void fillTestFiles(String gene2AccessionContents, String zfGeneInfoContents, Boolean append) throws IOException {

        if (!append) {
            resetGene2AccessionFile();
            resetZfGeneInfoFile();
        }

        Path g2a = tempDir.resolve("gene2accession.gz");
        Path zfi = tempDir.resolve("zf_gene_info.gz");
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(zfi.toFile(), true));
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(zfGeneInfoContents);
        }
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(g2a.toFile(), true));
        PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(gene2AccessionContents);
        }
    }

    private void resetGene2AccessionFile() throws IOException {
        Path g2a = tempDir.resolve("gene2accession.gz");
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(g2a.toFile()));
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(GENE_2_ACCESSION_HEADER);
        }
    }

    private void resetZfGeneInfoFile() throws IOException {
        Path zfi = tempDir.resolve("zf_gene_info.gz");
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(zfi.toFile()));
            PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(ZF_GENE_INFO_HEADER);
        }
    }


    private static void gzipFile(Path filePath) throws IOException {
        // Implementation to gzip files
        ProcessBuilder pb = new ProcessBuilder("gzip", filePath.toString());
        try {
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            throw new IOException("Failed to gzip file: " + filePath, e);
        }
    }

    private void oldCleanupExistingLinks() {
        String sql = """
            DELETE FROM zdb_active_data
            WHERE zactvd_zdb_id IN (
                SELECT dblink_zdb_id FROM db_link
                WHERE dblink_fdbcont_zdb_id = ?
                AND dblink_linked_recid = ?
            )
            """;

        HibernateUtil.currentSession().createNativeQuery(sql)
            .setParameter(1, NCBI_FDBCONT)
            .setParameter(2, TEST_GENE)
            .executeUpdate();
    }

    private void deleteAllMarkersAndDBLinks() {
        String sql = """
            DELETE FROM zdb_active_data
            WHERE zactvd_zdb_id IN (
                SELECT dblink_zdb_id FROM db_link
            )
            """;

        HibernateUtil.currentSession().createNativeQuery(sql)
            .executeUpdate();

        sql = "DELETE FROM marker_annotation_status";
        HibernateUtil.currentSession().createNativeQuery(sql)
                .executeUpdate();

        sql = """
            DELETE FROM zdb_active_data
            WHERE zactvd_zdb_id IN (
                SELECT mrkr_zdb_id FROM marker
            )
            """;

        HibernateUtil.currentSession().createNativeQuery(sql)
                .executeUpdate();
    }

    private void runNCBILoad() {
        NCBIDirectPort ncbiLoad = new NCBIDirectPort();
        ncbiLoad.workingDir = tempDir.toFile();

        // Set environment variables
        System.setProperty("WORKING_DIR", tempDir.toString());
        System.setProperty("NO_SLEEP", "1");
        System.setProperty("SKIP_DOWNLOADS", "1");
        System.setProperty("LOAD_NCBI_ONE_WAY_GENES", "1");
        System.setProperty("DB_NAME", "zfindb");
        System.setProperty("SKIP_COMPRESS_ARTIFACTS", "1");

        // Run the load
        NCBIDirectPort port = new NCBIDirectPort();
        port.initAll();
        port.run();
    }

    private int getNCBILinkCount(String geneId) {
        String sql = """
            SELECT COUNT(*) FROM db_link
            WHERE dblink_linked_recid = ?
            AND dblink_fdbcont_zdb_id = ?
            """;

        Number result = (Number) HibernateUtil.currentSession()
            .createNativeQuery(sql)
            .setParameter(1, geneId)
            .setParameter(2, NCBI_FDBCONT)
            .uniqueResult();

        return result != null ? result.intValue() : 0;
    }

    private List<String> getNCBILinks(String geneId) {
        String sql = """
            SELECT dblink_acc_num FROM db_link
            WHERE dblink_linked_recid = ?
            AND dblink_fdbcont_zdb_id = ?
            ORDER BY dblink_acc_num
            """;

        return HibernateUtil.currentSession()
            .createNativeQuery(sql)
            .setParameter(1, geneId)
            .setParameter(2, NCBI_FDBCONT)
            .list();
    }

    private int getAttributionCount(String geneId) {
        String sql = """
            SELECT COUNT(*) FROM db_link dl
            JOIN record_attribution ra ON dl.dblink_zdb_id = ra.recattrib_data_zdb_id
            WHERE dl.dblink_linked_recid = ?
            AND dl.dblink_fdbcont_zdb_id = ?
            """;

        Number result = (Number) HibernateUtil.currentSession()
            .createNativeQuery(sql)
            .setParameter(1, geneId)
            .setParameter(2, NCBI_FDBCONT)
            .uniqueResult();

        return result != null ? result.intValue() : 0;
    }
}