package org.zfin.datatransfer.ncbi;


import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zfin.AbstractDangerousDatabaseTest;
import org.zfin.framework.HibernateUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;
import static org.zfin.datatransfer.ncbi.NCBILoadIntegrationTestHelper.*;

/**
 * Run tests against a database that only has test data in it.
 * Not to be run on prod or any other important database.
 * Can be invoked with:
 * docker compose run --rm -p 5005:5005 ncbiload bash -lc 'gradle -PgradleDebug -PncbiLoadTests test --tests org.zfin.datatransfer.ncbi.NCBILoadIntegrationTest '
 * The gradleDebug property will start the JVM in debug mode and listen on port 5005 for a debugger to attach. (optional)
 */
public class NCBILoadIntegrationTest extends AbstractDangerousDatabaseTest {

    private static final String TEST_GENE = "ZDB-GENE-010319-10";
    private static final String TEST_NCBI_ID = "80928";
    public static final Boolean DELETE_ON_EXIT = true;

    private Path tempDir;
    private NCBILoadIntegrationTestHelper helper;

    /**
     * Test of the simplest case. Start with one gene with no NCBI link. The gene has an RNA sequence
     * that can be be matched to an NCBI record. After the load, there should be one new NCBI Gene ID link
     */
    @Test
    public void testInitialLoadCreatesOneLink() throws IOException {
        // Create database state before the load
        helper.beforeStateBuilder()
                .withGene(TEST_GENE, "id:ibd2600")
                .withDBLink(TEST_GENE, "BG985726", GENBANK_FDBCONT, "ZDB-PUB-020723-5")
                .withGene2AccessionFile("7955\t80928\t-\tBG985726.1\t14389806\t-\t-\t-\t-\t-\t-\t?\t-\t-\t-\tid:ibd2600")
                .withZfGeneInfoFile("7955\t80928\tid:ibd2600\t-\t-\tZFIN:ZDB-GENE-010319-10|AllianceGenome:ZFIN:ZDB-GENE-010319-10\t5\t-\tid:ibd2600\tprotein-coding\tid:ibd2600\tid:ibd2600\tO\tuncharacterized protein LOC80928\t20250705\t-")
                .build();

        helper.runNCBILoad();

        NCBILoadIntegrationTestHelper.AfterState afterState = helper.getAfterState();

        // Verify database state
        assertEquals("Should create exactly one NCBI link", 1, afterState.getNCBILinkCount(TEST_GENE));

        List<String> ncbiIds = afterState.getNCBILinks(TEST_GENE);
        assertEquals("Should have exactly one NCBI ID", 1, ncbiIds.size());
        assertEquals("Should link correct NCBI ID", TEST_NCBI_ID, ncbiIds.get(0));

        assertEquals("Should create exactly one attribution record", 1, afterState.getAttributionCount(TEST_GENE));

        // Verify output files
        assertEquals(true, afterState.getFile("before_load.csv").exists());
        assertEquals(true, afterState.getFile("after_load.csv").exists());
        assertEquals(1, afterState.getFile("before_load.csv").getDataLines().size());
        assertEquals(2, afterState.getFile("after_load.csv").getDataLines().size());
    }

    /**
     * Test the case where a gene has an existing NCBI Gene ID link, but NCBI has replaced that ID with a new one.
     * After the load, the old NCBI Gene ID link should be replaced with the new one, and the GenBank
     * accession should also be linked to the new NCBI Gene ID.
     */
    @Test
    public void testGeneWithReplacedNCBIGeneMatchingByEnsembl() throws IOException {
        // Create database state before the load
        helper.beforeStateBuilder()
                .withGene("ZDB-GENE-120709-33", "si:ch211-209j12.2")
                .withDBLink("ZDB-GENE-120709-33", "103910949", NCBI_FDBCONT, "ZDB-PUB-230516-87")
                .withDBLink("ZDB-GENE-120709-33", "ENSDARG00000099337", ENSDARG_FDBCONT, "ZDB-PUB-200123-1")
                .withGene2AccessionFile("7955\t108183900\t-\tGDQQ01002583.1\t-\t-\t-\t-\t-\t-\t-\t?\t-\t-\t-\tsi:ch211-209j12.2")
                .withZfGeneInfoFile("7955\t108183900\tsi:ch211-209j12.2\t-\t-\tZFIN:ZDB-GENE-120709-33|Ensembl:ENSDARG00000099337|AllianceGenome:ZFIN:ZDB-GENE-120709-33\t4\t-\tsi:ch211-209j12.2\tncRNA\tsi:ch211-209j12.2\tsi:ch211-209j12.2\tO\tuncharacterized protein LOC108183900\t20250909\t-")
                .build();

        helper.runNCBILoad();

        NCBILoadIntegrationTestHelper.AfterState afterState = helper.getAfterState();
        assertEquals(2, afterState.getFile("before_load.csv").getDataLines().size());
        assertEquals(3, afterState.getFile("after_load.csv").getDataLines().size());

        //Check that the old NCBI Gene ID was replaced with the new one
        assertFalse(afterState.getFile("after_load.csv").matches("ZDB-GENE-120709-33,103910949.*"));
        assertTrue(afterState.getFile("after_load.csv").matches("ZDB-GENE-120709-33,108183900,.*,ZDB-FDBCONT-040412-1,ZDB-PUB-230516-87.*"));
        assertTrue(afterState.getFile("after_load.csv").matches("ZDB-GENE-120709-33,GDQQ01002583,.*,ZDB-FDBCONT-040412-37,ZDB-PUB-230516-87.*"));
    }

    @Test
    public void testGeneWithVegaLink() throws IOException {
        // Create database state before the load
        helper.beforeStateBuilder()
                .withGene("ZDB-GENE-040724-74", "si:dkey-192d15.2")

//Commented out the starting DBLink to make sure the same DBLink gets added
//                .withDBLink("ZDB-GENE-040724-74", "107980443", NCBI_FDBCONT, "ZDB-PUB-130725-2")
                .withVega("ZDB-GENE-040724-74", "OTTDARG00000004288", "si:dkey-192d15.2-201")
                .withGene2VegaFile("7955\t107980443\tOTTDARG00000004288\tNM_001327832.1\tOTTDART00000004513\tNP_001314761.1\tOTTDARP00000004104")
                .build();

        helper.runNCBILoad();

        NCBILoadIntegrationTestHelper.AfterState afterState = helper.getAfterState();
        assertEquals(1, afterState.getFile("before_load.csv").getDataLines().size());
        assertEquals(2, afterState.getFile("after_load.csv").getDataLines().size());
        assertTrue(afterState.getFile("after_load.csv").matches("ZDB-GENE-040724-74,107980443,.*,ZDB-FDBCONT-040412-1,ZDB-PUB-130725-2.*"));
    }

    @Before
    public void setupTestData() throws IOException {
        //Make sure we are running in the NCBI test environment
        String isNcbiLoadContainer = System.getenv("IS_NCBI_LOAD_CONTAINER");
        if (!"true".equals(isNcbiLoadContainer)) {
            System.out.println("IS_NCBI_LOAD_CONTAINER environment variable is not set to true. Preventing run to avoid data corruption.");
            System.out.flush();
            throw new RuntimeException("NCBI_LOAD_CONTAINER environment variable is not set. Preventing run to avoid data corruption.");
        }

        tempDir = Files.createTempDirectory("ncbi_test_");
        helper = new NCBILoadIntegrationTestHelper(tempDir);
        if (DELETE_ON_EXIT) {
            tempDir.toFile().deleteOnExit();
        }
        helper.createTestFiles();
    }

    /**
     * Clean up resources after each test to prevent resource leakage between tests
     */
    @After
    public void tearDown() throws IOException {
        try {
            // Clear any system properties that might affect subsequent tests
            System.clearProperty("WORKING_DIR");
            System.clearProperty("NO_SLEEP");
            System.clearProperty("SKIP_DOWNLOADS");
            System.clearProperty("LOAD_NCBI_ONE_WAY_GENES");
            System.clearProperty("DB_NAME");
            System.clearProperty("SKIP_COMPRESS_ARTIFACTS");

            // Force cleanup of temp directory if it still exists
            if (tempDir != null && Files.exists(tempDir)) {
                try {
                    // Delete all files in temp directory
                    Files.walk(tempDir)
                            .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                            .forEach(path -> {
//                            try {
//                                Files.deleteIfExists(path);
//                            } catch (IOException e) {
//                                // Log but don't fail the test cleanup
//                                System.err.println("Warning: Could not delete " + path + ": " + e.getMessage());
//                            }
                            });
                } catch (IOException e) {
                    System.err.println("Warning: Could not fully clean up temp directory " + tempDir + ": " + e.getMessage());
                }
            }

            // Reset database transaction state
            if (HibernateUtil.currentSession().getTransaction().isActive()) {
                HibernateUtil.currentSession().getTransaction().rollback();
            }

        } catch (Exception e) {
            // Don't let cleanup failures break the test suite
            System.err.println("Warning: Error during test cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
