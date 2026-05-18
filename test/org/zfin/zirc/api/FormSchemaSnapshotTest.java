package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Byte-level baseline for the three {@code /form-schema} endpoints.
 *
 * <p>Path 2 migration (typed records replacing {@code Map<String, Object>})
 * must produce identical wire output. This test is the safety net — it
 * runs the schema-building code, serializes the result with Jackson, and
 * diffs against a pre-recorded snapshot under
 * {@code test/resources/zirc/snapshot/}.
 *
 * <p><b>Updating snapshots</b>: when an intentional change to the schema
 * lands, rerun with {@code -Dzirc.snapshot.update=true} (typically
 * via {@code gradle test -Dzirc.snapshot.update=true --tests
 * org.zfin.zirc.api.FormSchemaSnapshotTest}) to regenerate the
 * baselines, then review the diff before committing.
 */
public class FormSchemaSnapshotTest {

    private static final boolean UPDATE_SNAPSHOTS =
            Boolean.getBoolean("zirc.snapshot.update");

    /**
     * Pretty-printed + alphabetically-keyed JSON. The current Map-based
     * builders use {@code Map.of(...)}, which doesn't preserve insertion
     * order (>1 entry); without sorting the snapshot files would flap
     * between runs. We're testing for structural equivalence, not
     * byte-identical wire output — JSON Forms reads by key name, not
     * position, and clients don't care about key order.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    public void submissionSchemaMatchesSnapshot() throws Exception {
        assertSnapshot("submission",
                ZircFormSchema.schema(),
                ZircFormSchema.uiSchema());
    }

    @Test
    public void mutationSchemaMatchesSnapshot() throws Exception {
        assertSnapshot("mutation",
                ZircMutationFormSchema.schema(),
                ZircMutationFormSchema.uiSchema());
    }

    @Test
    public void assaySchemaMatchesSnapshot() throws Exception {
        assertSnapshot("assay",
                ZircAssayFormSchema.schema(),
                ZircAssayFormSchema.uiSchema());
    }

    private void assertSnapshot(String name, Object schema, Object uiSchema) throws Exception {
        // LinkedHashMap to lock down ordering of the wrapper, so snapshots
        // are stable across runs.
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", schema);
        root.put("uiSchema", uiSchema);
        String actual = MAPPER.writeValueAsString(root);

        Path snapshotPath = locateSnapshot(name);

        if (UPDATE_SNAPSHOTS) {
            Files.createDirectories(snapshotPath.getParent());
            Files.writeString(snapshotPath, actual, StandardCharsets.UTF_8);
            return;
        }

        if (!Files.exists(snapshotPath)) {
            fail("Snapshot missing at " + snapshotPath.toAbsolutePath()
                    + ". Re-run with -Dzirc.snapshot.update=true to create it.");
        }

        String expected = Files.readString(snapshotPath, StandardCharsets.UTF_8);
        assertEquals("Schema output for " + name
                + " drifted from snapshot at " + snapshotPath.toAbsolutePath()
                + "\nIf the drift is intentional, rerun the test with "
                + "-Dzirc.snapshot.update=true to refresh and review the diff.",
                expected, actual);
    }

    /**
     * Snapshot files live alongside the test, but the working directory
     * differs depending on how the test is invoked. Probe both common
     * roots (matches the idiom in ZircOpenApiDriftTest).
     */
    private static Path locateSnapshot(String name) {
        String rel = "test/resources/zirc/snapshot/" + name + ".form-schema.json";
        File here = new File(rel);
        File parent = new File("../" + rel);
        return (here.exists() || here.getParentFile().exists())
                ? here.toPath()
                : parent.toPath();
    }
}
