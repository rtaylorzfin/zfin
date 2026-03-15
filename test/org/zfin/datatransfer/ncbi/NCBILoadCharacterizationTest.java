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

        // Verify database state — compare each aspect independently to avoid
        // gene-level changes (annotation status, assembly) being amplified across all db_link rows

        // 1. Compare db_links (per db_link row)
        assertCsvMatch("dblinks",
                new String[]{"dblink_linked_recid", "dblink_acc_num", "dblink_fdbcont_zdb_id"},
                new String[]{"dblink_info", "dblink_zdb_id"});

        // 2. Compare marker_annotation_status (per gene)
        assertCsvMatch("annotation",
                new String[]{"mrkr_zdb_id"},
                new String[]{});

        // 3. Compare marker_assembly (per gene+assembly)
        assertCsvMatch("assembly",
                new String[]{"mrkr_zdb_id", "assembly_name"},
                new String[]{});
    }

    private void assertCsvMatch(String suffix, String[] keyColumns, String[] ignoreColumns) throws IOException {
        String expectedFile = tempDir.resolve("expected_" + suffix + ".csv").toString();
        String actualFile = tempDir.resolve("after_load_" + suffix + ".csv").toString();

        CSVDiff diff = new CSVDiff("aftertest_" + suffix, keyColumns, ignoreColumns);
        Map<String, List<CSVRecord>> results = diff.processToMap(expectedFile, actualFile);

        List<CSVRecord> added = results.get("added");
        List<CSVRecord> deleted = results.get("deleted");
        List<CSVRecord> updated1 = results.get("updated1");
        List<CSVRecord> updated2 = results.get("updated2");

        // Write diffs to files for analysis
        writeDiffFile(tempDir.resolve("diff_" + suffix + "_added.csv").toString(), added, suffix + " added");
        writeDiffFile(tempDir.resolve("diff_" + suffix + "_deleted.csv").toString(), deleted, suffix + " deleted");
        writeDiffFile(tempDir.resolve("diff_" + suffix + "_updated1.csv").toString(), updated1, suffix + " updated1");
        writeDiffFile(tempDir.resolve("diff_" + suffix + "_updated2.csv").toString(), updated2, suffix + " updated2");

        if (!added.isEmpty()) {
            System.out.println(suffix.toUpperCase() + " ADDED (" + added.size() + " records):");
            added.stream().limit(10).forEach(r -> System.out.println("  " + r));
        }
        if (!deleted.isEmpty()) {
            System.out.println(suffix.toUpperCase() + " DELETED (" + deleted.size() + " records):");
            deleted.stream().limit(10).forEach(r -> System.out.println("  " + r));
        }
        if (!updated1.isEmpty()) {
            System.out.println(suffix.toUpperCase() + " UPDATED (" + updated1.size() + " records):");
            updated1.stream().limit(10).forEach(r -> System.out.println("  " + r));
        }

        assertEquals(suffix + " added records", 0, added.size());
        assertEquals(suffix + " deleted records", 0, deleted.size());
        assertEquals(suffix + " updated1 records", 0, updated1.size());
        assertEquals(suffix + " updated2 records", 0, updated2.size());
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

    /**
     * Generate baseline CSV files for the characterization test.
     * Run this once against a freshly loaded database to create the expected output files.
     *
     * docker compose run --rm compile bash -lc 'gradle -DB=/opt/zfin/unloads/db/2026.01.29.1/2026.01.29.1.bak loaddb; \
     *   psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1179/ZFIN-10082.sql; \
     *   psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1180/ZFIN-10173-gene2accession.sql; \
     *   SKIP_DANGER_WARNING=1 gradle -PincludeNcbiCharacterizationTest test --info \
     *   --tests org.zfin.datatransfer.ncbi.NCBILoadCharacterizationTest.generateBaseline; exec bash'
     */
    @Test
    public void generateBaseline() throws IOException {
        assertDatabaseDate(2026, 1, 29);

        copyInputFiles();

        helper.runNCBILoad();

        // Gzip generated CSVs and save to source tree (which is mounted rw)
        Path outputDir = Path.of("/opt/zfin/source_roots/zfin.org/build/ncbi-baselines");
        Files.createDirectories(outputDir);
        for (String suffix : List.of("dblinks", "annotation", "assembly")) {
            Path source = tempDir.resolve("after_load_" + suffix + ".csv");
            if (!Files.exists(source)) {
                throw new RuntimeException("Expected output file not found: " + source);
            }
            long lines = Files.lines(source).count();
            System.out.println("Generated " + source.getFileName() + " with " + lines + " lines (including header)");

            // Gzip to output directory
            Path gzTarget = outputDir.resolve("after_load_" + suffix + ".csv.gz");
            try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gzTarget));
                 var in = Files.newInputStream(source)) {
                in.transferTo(out);
            }
            System.out.println("Saved baseline to " + gzTarget);
        }
        System.out.println("\n*** Baseline files saved to: " + outputDir);
        System.out.println("*** Copy to archive from host with:");
        System.out.println("***   cp build/ncbi-baselines/after_load_*.csv.gz /research/zarchive/load_files/NCBI-gene-load-archive/2026-01-30/");
    }

    /**
     * Copy only the NCBI input files (no baselines) to the temp directory.
     */
    private void copyInputFiles() {
        String sourceDir = "/mnt/research/zarchive/load_files/NCBI-gene-load-archive/2026-01-30";
        List<String> filesToCopy = List.of(
            "gene2accession.gz",
            "notInCurrentReleaseGeneIDs.unl",
            "RefSeqCatalog.gz",
            "RELEASE_NUMBER",
            "seq.fasta",
            "zf_gene_info.gz"
        );
        for (String filename : filesToCopy) {
            File file = new File(sourceDir, filename);
            if (!file.exists()) {
                throw new RuntimeException("Test data file does not exist: " + file.getAbsolutePath());
            }
            try {
                Files.copy(file.toPath(), tempDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void copyCharacterizationTestData() {
        copyInputFiles();

        // Copy baseline files (three separate CSVs)
        String sourceDir = "/mnt/research/zarchive/load_files/NCBI-gene-load-archive/2026-01-30";
        for (String suffix : List.of("dblinks", "annotation", "assembly")) {
            String baselineFilename = "after_load_" + suffix + ".csv.gz";
            File baselineFile = new File(sourceDir, baselineFilename);
            if (!baselineFile.exists()) {
                throw new RuntimeException("Baseline file does not exist: " + baselineFile.getAbsolutePath()
                        + "\nRun the load once and save the output files as baselines. See doc/ncbi-characterization-test.md");
            }
            try {
                Files.copy(baselineFile.toPath(), tempDir.resolve(baselineFilename), StandardCopyOption.REPLACE_EXISTING);
                String expectedName = "expected_" + suffix + ".csv.gz";
                Files.move(tempDir.resolve(baselineFilename), tempDir.resolve(expectedName), StandardCopyOption.REPLACE_EXISTING);
                gunzipFile(tempDir.resolve(expectedName).toAbsolutePath().toString());
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
