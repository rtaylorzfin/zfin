package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zfin.zirc.entity.Mutation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Single source of truth for the per-mutation edit form. Parallel to
 * {@link ZircFormSchema} but operates on {@link Mutation} entities.
 *
 * <p>M3.3 scope: General, Mutagenesis, Lethality, and Publications sections.
 * Per-mutation children (Genes, Lesions, Genotyping Assays, Phenotypes) are
 * later milestones; the assay-type field matrix in M4 is where the deferred
 * path-resolver question finally surfaces.
 *
 * <p>Canonical enum values for mutagenesis stage/protocol and lethality
 * stage are starter lists; curators should review before production.
 */
public final class ZircMutationFormSchema {

    private ZircMutationFormSchema() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Read + write for a single form-schema path on a Mutation entity. */
    public record FieldDescriptor(
            Function<Mutation, JsonNode> read,
            BiConsumer<Mutation, JsonNode> write) {
    }

    private static final List<String> MUTAGENESIS_STAGES = List.of(
            "oocyte", "sperm", "embryo", "larva", "adult", "unknown");

    private static final List<String> MUTAGENESIS_PROTOCOLS = List.of(
            "ENU", "CRISPR/Cas9", "TALEN", "ZFN", "ionizing radiation", "spontaneous");

    private static final List<String> LETHALITY_STAGES = List.of(
            "embryonic", "larval", "juvenile", "adult", "unknown");

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        // General
        properties.put("alleleDesignation",        stringProp(255, "Allele Designation"));
        properties.put("alleleInZfin",             nullableBoolProp("Allele already in ZFIN"));
        properties.put("mutationType",             stringProp(255, "Mutation Type"));
        properties.put("mutationDiscoverer",       stringProp(255, "Discoverer"));
        properties.put("mutationInstitution",      stringProp(255, "Institution"));
        // Mutagenesis
        properties.put("mutagenesisStage",         stringProp(255, "Mutagenesis Stage"));
        properties.put("mutagenesisProtocol",      stringProp(255, "Mutagenesis Protocol"));
        properties.put("molecularlyCharacterized", nullableBoolProp("Molecularly Characterized"));
        // Lethality
        properties.put("homozygousLethal",         nullableBoolProp("Homozygous Lethal"));
        properties.put("lethalityStageTypical",    stringProp(255, "Typical Lethality Stage"));
        properties.put("lethalitySpecificTimepoint", stringProp(255, "Specific Timepoint"));
        properties.put("lethalityWindowStart",     stringProp(255, "Lethality Window Start"));
        properties.put("lethalityWindowEnd",       stringProp(255, "Lethality Window End"));
        properties.put("lethalityAdditionalInfo",  stringProp(5000, "Lethality Additional Info"));
        // Publications
        properties.put("publications",             stringListProp("Publications"));
        // Genotyping assays — summary rows that the AssaysListRenderer
        // turns into expandable cards. Add/Delete go through dedicated
        // endpoints, so MutationEdit's diff filter must skip /assays.
        properties.put("assays",                   assaysSummaryArrayProp());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        return root;
    }

    public static Map<String, Object> uiSchema() {
        // Conditional reveal: lethality detail fields only render when
        // homozygousLethal is exactly true.
        Map<String, Object> showWhenLethal = Map.of(
                "effect", "SHOW",
                "condition", Map.of(
                        "scope", "#/properties/homozygousLethal",
                        "schema", Map.of("const", true)));

        return verticalLayout(List.of(
                group("General", List.of(
                        controlWithOptions("#/properties/alleleDesignation",
                                Map.of("placeholder", "e.g. zf123",
                                       "helpText",    "ZFIN allele designation; leave blank if not yet assigned.")),
                        controlWithOptions("#/properties/alleleInZfin",
                                Map.of("widget", "yesNoRadio")),
                        control("#/properties/mutationType"),
                        controlWithOptions("#/properties/mutationDiscoverer",
                                Map.of("placeholder", "Person who first identified the mutation")),
                        controlWithOptions("#/properties/mutationInstitution",
                                Map.of("placeholder", "Lab / institution"))
                )),
                group("Mutagenesis", List.of(
                        controlWithOptions("#/properties/mutagenesisStage",
                                Map.of("widget", "selectWithOther",
                                       "standardValues", MUTAGENESIS_STAGES)),
                        controlWithOptions("#/properties/mutagenesisProtocol",
                                Map.of("widget", "selectWithOther",
                                       "standardValues", MUTAGENESIS_PROTOCOLS)),
                        controlWithOptions("#/properties/molecularlyCharacterized",
                                Map.of("widget", "yesNoRadio"))
                )),
                group("Lethality", List.of(
                        controlWithOptions("#/properties/homozygousLethal",
                                Map.of("widget", "yesNoRadio")),
                        controlWithRule("#/properties/lethalityStageTypical",
                                Map.of("widget", "selectWithOther",
                                       "standardValues", LETHALITY_STAGES),
                                showWhenLethal),
                        controlWithRule("#/properties/lethalitySpecificTimepoint",
                                Map.of("placeholder", "e.g. 48 hpf",
                                       "helpText",    "Single timepoint when most homozygotes die. Use the window fields below for a range."),
                                showWhenLethal),
                        controlWithRule("#/properties/lethalityWindowStart",
                                Map.of("placeholder", "e.g. 24 hpf"), showWhenLethal),
                        controlWithRule("#/properties/lethalityWindowEnd",
                                Map.of("placeholder", "e.g. 72 hpf"), showWhenLethal),
                        controlWithRule("#/properties/lethalityAdditionalInfo",
                                Map.of("multi", true), showWhenLethal)
                )),
                group("Publications", List.of(
                        controlWithOptions("#/properties/publications",
                                Map.of("widget", "stringList"))
                )),
                // Genotyping Assays is a list of child rows like the
                // submission's Mutations section — drop the table wrapper.
                groupWithOptions("Genotyping Assays",
                        Map.of("layout", "plain"),
                        List.of(controlWithOptions("#/properties/assays",
                                Map.of("widget", "assaysList"))))
        ));
    }

    /**
     * Path → read+write dispatch for mutation fields. Same gatekeeper
     * behavior as {@link ZircFormSchema#FIELDS}: unknown paths are
     * rejected at the controller.
     */
    public static final Map<String, FieldDescriptor> FIELDS = Map.ofEntries(
            // General
            field("/alleleDesignation",
                    Mutation::getAlleleDesignation,         (m, v) -> m.setAlleleDesignation(text(v))),
            field("/alleleInZfin",
                    Mutation::getAlleleInZfin,              (m, v) -> m.setAlleleInZfin(boolNullable(v))),
            field("/mutationType",
                    Mutation::getMutationType,              (m, v) -> m.setMutationType(text(v))),
            field("/mutationDiscoverer",
                    Mutation::getMutationDiscoverer,        (m, v) -> m.setMutationDiscoverer(text(v))),
            field("/mutationInstitution",
                    Mutation::getMutationInstitution,       (m, v) -> m.setMutationInstitution(text(v))),
            // Mutagenesis
            field("/mutagenesisStage",
                    Mutation::getMutagenesisStage,          (m, v) -> m.setMutagenesisStage(text(v))),
            field("/mutagenesisProtocol",
                    Mutation::getMutagenesisProtocol,       (m, v) -> m.setMutagenesisProtocol(text(v))),
            field("/molecularlyCharacterized",
                    Mutation::getMolecularlyCharacterized,  (m, v) -> m.setMolecularlyCharacterized(boolNullable(v))),
            // Lethality
            field("/homozygousLethal",
                    Mutation::getHomozygousLethal,          (m, v) -> m.setHomozygousLethal(boolNullable(v))),
            field("/lethalityStageTypical",
                    Mutation::getLethalityStageTypical,     (m, v) -> m.setLethalityStageTypical(text(v))),
            field("/lethalitySpecificTimepoint",
                    Mutation::getLethalitySpecificTimepoint, (m, v) -> m.setLethalitySpecificTimepoint(text(v))),
            field("/lethalityWindowStart",
                    Mutation::getLethalityWindowStart,      (m, v) -> m.setLethalityWindowStart(text(v))),
            field("/lethalityWindowEnd",
                    Mutation::getLethalityWindowEnd,        (m, v) -> m.setLethalityWindowEnd(text(v))),
            field("/lethalityAdditionalInfo",
                    Mutation::getLethalityAdditionalInfo,   (m, v) -> m.setLethalityAdditionalInfo(text(v))),
            // Publications — clear+addAll keeps Hibernate's persistent collection reference intact
            field("/publications",
                    Mutation::getPublications,
                    (m, v) -> {
                        m.getPublications().clear();
                        if (v != null && v.isArray()) {
                            for (int i = 0; i < v.size(); i++) {
                                String s = v.get(i).asText();
                                if (s != null && !s.isBlank()) {
                                    m.getPublications().add(s.trim());
                                }
                            }
                        }
                    })
    );

    // ─── schema builders ────────────────────────────────────────────────────

    private static Map<String, Object> stringProp(int maxLength, String title) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("title", title);
        p.put("maxLength", maxLength);
        return p;
    }

    private static Map<String, Object> nullableBoolProp(String title) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", List.of("boolean", "null"));
        p.put("title", title);
        return p;
    }

    private static Map<String, Object> stringListProp(String title) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("title", title);
        p.put("items", items);
        return p;
    }

    /**
     * Mirror of {@link org.zfin.zirc.dto.AssaySummary}; the per-card
     * header reads from this. Full assay fields come from a dedicated
     * /api/zirc/assays/{id} endpoint when a card is expanded (M4.2).
     */
    /** Hard cap mirroring the alt-branch (ZFIN-10265) MAX_CHILD_ROWS_PER_MUTATION. */
    public static final int MAX_ASSAYS_PER_MUTATION = 10;

    private static Map<String, Object> assaysSummaryArrayProp() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("id",        Map.of("type", "number"));
        itemProps.put("sortOrder", Map.of("type", "number"));
        itemProps.put("assayType", Map.of("type", List.of("string", "null")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);

        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("title", "Genotyping Assays");
        arr.put("items", item);
        arr.put("maxItems", MAX_ASSAYS_PER_MUTATION);
        return arr;
    }

    // ─── uiSchema builders ──────────────────────────────────────────────────

    private static Map<String, Object> verticalLayout(List<Map<String, Object>> elements) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("type", "VerticalLayout");
        v.put("elements", elements);
        return v;
    }

    private static Map<String, Object> group(String label, List<Map<String, Object>> elements) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", "Group");
        g.put("label", label);
        g.put("elements", elements);
        return g;
    }

    private static Map<String, Object> groupWithOptions(
            String label,
            Map<String, Object> options,
            List<Map<String, Object>> elements) {
        Map<String, Object> g = group(label, elements);
        g.put("options", options);
        return g;
    }

    private static Map<String, Object> control(String scope) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "Control");
        c.put("scope", scope);
        return c;
    }

    private static Map<String, Object> controlWithOptions(String scope, Map<String, Object> options) {
        Map<String, Object> c = control(scope);
        if (!options.isEmpty()) {
            c.put("options", options);
        }
        return c;
    }

    private static Map<String, Object> controlWithRule(
            String scope, Map<String, Object> options, Map<String, Object> rule) {
        Map<String, Object> c = controlWithOptions(scope, options);
        c.put("rule", rule);
        return c;
    }

    // ─── descriptor builders ────────────────────────────────────────────────

    private static Map.Entry<String, FieldDescriptor> field(
            String path,
            Function<Mutation, ?> getter,
            BiConsumer<Mutation, JsonNode> setter) {
        return Map.entry(path, new FieldDescriptor(
                m -> MAPPER.valueToTree(getter.apply(m)),
                setter));
    }

    // ─── value coercers ────────────────────────────────────────────────────

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }

    private static Boolean boolNullable(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        return v.asBoolean();
    }
}
