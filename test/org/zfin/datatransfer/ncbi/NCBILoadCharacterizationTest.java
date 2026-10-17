package org.zfin.datatransfer.ncbi;


import org.apache.commons.csv.CSVRecord;
import org.junit.Before;
import org.junit.Test;
import org.zfin.AbstractDangerousDatabaseTest;
import org.zfin.datatransfer.util.CSVDiff;
import org.zfin.framework.HibernateUtil;

import java.io.File;
import java.io.IOException;
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
     * Test the characterization of the NCBI load using the 2026-01-09 input files
     * (NCBI-gene-load-archive/2026-01-09) against the database unloaded on 2026-01-14.
     * When we run the NCBI load, we expect the changes to match those documented in
     * after_load.csv.gz
     *
     * Can be run like so:
     * docker compose run --rm  compile bash -lc 'gradle -DB=/opt/zfin/unloads/db/2026.01.14.1/2026.01.14.1.bak loaddb; psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1179/ZFIN-10082.sql; SKIP_DANGER_WARNING=1 gradle -PincludeNcbiCharacterizationTest test --info  --tests org.zfin.datatransfer.ncbi.NCBILoadCharacterizationTest.testPointInTimeCharacterization; exec bash'
     *
     * The input files are read from the NCBI load archive: NCBI_ARCHIVE_DIR if set, else /research/zarchive/...,
     * else /mnt/research/zarchive/... (where the compile container mounts /research).
     *
     * (The SKIP_DANGER_WARNING environment variable is required to actually run the test as a precaution against running against a production database.
     * The `exec bash` at the end is just to keep the container open so you can explore the generated artifacts in /tmp/ncbi_...)
     *
     *
     */
    @Test
    public void testPointInTimeCharacterization() throws IOException {

        // Sanity check to make sure we are running against the unload from 2026-01-14
        assertDatabaseDate(2026,1,14);

        // Create database state before the load
        copyCharacterizationTestData();

        helper.runNCBILoad();

        // Verify database state
        CSVDiff diff = new CSVDiff("aftertest",
                new String[]{"dblink_linked_recid", "dblink_acc_num", "dblink_fdbcont_zdb_id"},
                new String[]{"dblink_info", "dblink_zdb_id"});

        Map<String, List<CSVRecord>> results = diff.processToMap(tempDir.resolve("expected_changes.csv").toString(),
                tempDir.resolve("after_load.csv").toString());

        assertTrue(results.get("added").isEmpty());
        assertTrue(results.get("deleted").isEmpty());
        assertTrue(results.get("updated1").isEmpty());
        assertTrue(results.get("updated2").isEmpty());
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

    /**
     * The NCBI load archive: NCBI_ARCHIVE_DIR if set, else /research/zarchive/..., else
     * /mnt/research/zarchive/..., which is where the compile container mounts /research.
     */
    private String archiveDir() {
        String fromEnv = System.getenv("NCBI_ARCHIVE_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        for (String candidate : List.of("/research/zarchive/load_files/NCBI-gene-load-archive",
                                        "/mnt/research/zarchive/load_files/NCBI-gene-load-archive")) {
            if (new File(candidate).isDirectory()) {
                return candidate;
            }
        }
        return "/research/zarchive/load_files/NCBI-gene-load-archive";
    }

    private void copyCharacterizationTestData() {
        String sourceDir = archiveDir() + "/2026-01-09";
        List<String> filesToCopy = List.of(
            "gene2accession.gz",
            "gene2vega.gz",
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
