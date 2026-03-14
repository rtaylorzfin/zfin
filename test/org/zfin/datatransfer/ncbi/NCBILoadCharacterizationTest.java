package org.zfin.datatransfer.ncbi;


import org.apache.commons.csv.CSVRecord;
import org.junit.Before;
import org.junit.Test;
import org.zfin.AbstractDangerousDatabaseTest;
import org.zfin.datatransfer.util.CSVDiff;
import org.zfin.framework.HibernateUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zfin.datatransfer.ncbi.port.PortHelper.envTrue;
import static org.zfin.util.FileUtil.gunzipFile;

/**
 * Run tests against a database that only has test data in it.
 * Not to be run on prod or any other important database.
 * Can be invoked with:
 *
 * docker compose run ... (see instructions below)
 *
 * The gradleDebug property will start the JVM in debug mode and listen on port 5005 for a debugger to attach. (optional)
 */
public class NCBILoadCharacterizationTest extends AbstractDangerousDatabaseTest {

    public static final Boolean DELETE_ON_EXIT = false;

    private Path tempDir;
    private NCBILoadIntegrationTestHelper helper;

    /**
     * Test the characterization of the NCBI load using 2026-01-30 data
     * Given the input files from 2026-01-30 and the database state as of 2026-01-29
     * When we run the NCBI load, we expect the changes to match those documented in
     * after_load.csv.gz
     *
     * Can be run like so:
     * docker compose run --rm  compile bash -lc 'gradle -DB=/opt/zfin/unloads/db/2026.01.29.1/2026.01.29.1.bak loaddb; psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1179/ZFIN-10082.sql; SKIP_DANGER_WARNING=1 gradle -PincludeNcbiCharacterizationTest test --info  --tests org.zfin.datatransfer.ncbi.NCBILoadCharacterizationTest.testPointInTimeCharacterization; exec bash'
     *
     * (The SKIP_DANGER_WARNING environment variable is required to actually run the test as a precaution against running against a production database.
     * The `exec bash` at the end is just to keep the container open so you can explore the generated artifacts in /tmp/ncbi_...)
     *
     *
     */
    @Test
    public void testPointInTimeCharacterization() throws IOException {

        // Sanity check to make sure we are running against the unload from 2026-01-29
        assertDatabaseDate(2026,1,29);

        // Create database state before the load
        copyCharacterizationTestData();

        helper.runNCBILoad();

        // Verify database state
        CSVDiff diff = new CSVDiff("aftertest",
                new String[]{"dblink_linked_recid", "dblink_acc_num", "dblink_fdbcont_zdb_id"},
                new String[]{"dblink_info", "dblink_zdb_id"});

        Map<String, List<CSVRecord>> results = diff.processToMap(tempDir.resolve("expected_changes.csv").toString(),
                tempDir.resolve("after_load.csv").toString());

        List<CSVRecord> added = results.get("added");
        List<CSVRecord> deleted = results.get("deleted");
        List<CSVRecord> updated1 = results.get("updated1");
        List<CSVRecord> updated2 = results.get("updated2");

        // Write diffs to files for analysis
        writeDiffFile(tempDir.resolve("diff_added.csv").toString(), added, "added");
        writeDiffFile(tempDir.resolve("diff_deleted.csv").toString(), deleted, "deleted");
        writeDiffFile(tempDir.resolve("diff_updated1.csv").toString(), updated1, "updated1");
        writeDiffFile(tempDir.resolve("diff_updated2.csv").toString(), updated2, "updated2");

        if (!added.isEmpty()) {
            System.out.println("ADDED (" + added.size() + " records) — written to " + tempDir.resolve("diff_added.csv"));
            added.stream().limit(20).forEach(r -> System.out.println("  " + r));
        }
        if (!deleted.isEmpty()) {
            System.out.println("DELETED (" + deleted.size() + " records):");
            deleted.stream().limit(20).forEach(r -> System.out.println("  " + r));
        }
        if (!updated1.isEmpty()) {
            System.out.println("UPDATED1 (" + updated1.size() + " records):");
            updated1.stream().limit(20).forEach(r -> System.out.println("  " + r));
        }
        if (!updated2.isEmpty()) {
            System.out.println("UPDATED2 (" + updated2.size() + " records):");
            updated2.stream().limit(20).forEach(r -> System.out.println("  " + r));
        }

        assertEquals("added records", 0, added.size());
        assertEquals("deleted records", 0, deleted.size());
        assertEquals("updated1 records", 0, updated1.size());
        assertEquals("updated2 records", 0, updated2.size());
    }

    private void writeDiffFile(String path, List<CSVRecord> records, String label) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (CSVRecord r : records) {
                pw.println(r);
            }
            System.out.println("Wrote " + records.size() + " " + label + " records to " + path);
        } catch (IOException e) {
            System.err.println("Failed to write " + label + " diff file: " + e.getMessage());
        }
    }

    private void assertDatabaseDate(int year, int month, int day) {
        //let's get the date as YYYY-MM-DD and compare to string
        String sql = "select to_char(di_date_unloaded, 'YYYY-MM-DD') from database_info";
        String date = (String) HibernateUtil.currentSession()
                .createNativeQuery(sql)
                .getSingleResult();
        String expectedDate = String.format("%04d-%02d-%02d", year, month, day);
        assertEquals("Database unload date should be " + expectedDate, expectedDate, date);
    }

    private void copyCharacterizationTestData() {
        String sourceDir = "/mnt/research/zarchive/load_files/NCBI-gene-load-archive/2026-01-30";
        List<String> filesToCopy = List.of(
            "gene2accession.gz",
            "notInCurrentReleaseGeneIDs.unl",
            "RefSeqCatalog.gz",
            "RELEASE_NUMBER",
            "seq.fasta",
            "zf_gene_info.gz",
            "after_load.csv.gz"
        );
        for (String filename : filesToCopy) {
            File file = new File(sourceDir, filename);
            if (!file.exists()) {
                throw new RuntimeException("Test data file does not exist: " + file.getAbsolutePath());
            }
            try {
                Files.copy(file.toPath(), tempDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);

                //special handling for after_load.csv.gz
                if (filename.equals("after_load.csv.gz")) {
                    Files.move(tempDir.resolve(filename), tempDir.resolve("expected_changes.csv.gz"), StandardCopyOption.REPLACE_EXISTING);
                    gunzipFile(tempDir.resolve("expected_changes.csv.gz").toAbsolutePath().toString());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


    @Before
    public void setupTestData() throws IOException, InterruptedException {
        //output a big warning in case running against a non-test database?
        System.out.println("********************************************************************************");
        System.out.println("********************************************************************************");
        System.out.println("********************************************************************************");
        System.out.println("WARNING: This test will modify the database. Make sure you are running against a test database!");
        if (envTrue("SKIP_DANGER_WARNING")) {
            System.out.println("Skipping danger warning wait time because SKIP_DANGER_WARNING is set");
        } else {
            System.out.println("Exiting!!! (Set SKIP_DANGER_WARNING environment variable to really run this test)");
            System.out.println("********************************************************************************");
            System.out.println("********************************************************************************");
            System.out.println("********************************************************************************");
            System.exit(0);
        }

        tempDir = Files.createTempDirectory("ncbi_test_");
        helper = new NCBILoadIntegrationTestHelper(tempDir);
        if (DELETE_ON_EXIT) {
            tempDir.toFile().deleteOnExit();
        }
        helper.createTestFiles();
    }

}
