package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zfin.zirc.entity.LineSubmission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Single source of truth for the ZIRC submission form. Defines:
 *   - the JSON Schema describing data shape + constraints
 *   - the JSON Forms uiSchema describing layout + per-field widget choice
 *   - the path → entity-field descriptor table used by the field-path PATCH
 *     endpoint (read for audit log capture, write for persistence)
 *
 * <p>Hand-built for now. The descriptor table grew to a manageable ~11 entries
 * covering all four root-level sections; for nested entities (mutations[i],
 * genes[j]) a path resolver will be needed — that's M3+ scope.
 */
public final class ZircFormSchema {

    private ZircFormSchema() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Read + write for a single form-schema path. Audit log uses read; PATCH uses write. */
    public record FieldDescriptor(
            Function<LineSubmission, JsonNode> read,
            BiConsumer<LineSubmission, JsonNode> write) {
    }

    /**
     * Canonical acceptance-reasons list. Renaming a label is a code-only
     * change; renaming a value requires a data migration because existing
     * rows in line_submission.ls_reasons store the old value.
     */
    private static final List<Map<String, String>> CANONICAL_REASONS = List.of(
            entry("frequently_requested",      "Currently frequently requested"),
            entry("expect_high_demand",        "Expect high demand"),
            entry("interesting_gene",          "Interesting gene"),
            entry("community_resource",        "Community resource/tool"),
            entry("mutant_gene_cloned",        "Mutant gene cloned"),
            entry("danger_of_losing",          "Danger of losing line"),
            entry("lack_of_space_or_funding",  "Lack of space or funding to maintain line"),
            entry("other",                     "Other")
    );

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name",          stringProp(255, "Name"));
        properties.put("previousNames", stringProp(2000, "Previous Names"));
        properties.put("acceptance", obj("Acceptance Reasons", Map.of(
                "reasons", reasonsArrayProp(),
                "reasonsOther", stringProp(2000, "Other reason")
        )));
        properties.put("mutations", mutationsSummaryArrayProp());
        properties.put("background", obj("Background", linkedMap(
                "singleAllelic",        nullableBoolProp("Single-allelic submission"),
                "maternalBackground",   stringProp(255, "Maternal"),
                "paternalBackground",   stringProp(255, "Paternal"),
                "backgroundChangeable", nullableBoolProp("Background Changeable")
        )));
        properties.put("additionalInfo", obj("Additional Info", linkedMap(
                "unreportedFeaturesDetails", stringProp(5000, "Unreported Features Details"),
                "husbandryInfo",             stringProp(5000, "Husbandry Info"),
                "additionalInfo",            stringProp(5000, "Additional Info")
        )));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        return root;
    }

    /**
     * JSON Forms uiSchema. VerticalLayout of Groups, with widget hints on
     * Controls that need custom rendering (radios, selects, multi-field
     * composites, multi-line textareas).
     */
    public static Map<String, Object> uiSchema() {
        return verticalLayout(List.of(
                group("Overview", List.of(
                        controlWithOptions("#/properties/name",
                                Map.of("placeholder", "e.g. nasl1<sup>zf123</sup>",
                                       "helpText",    "Line name as it should appear in publications.")),
                        controlWithOptions("#/properties/previousNames",
                                Map.of("placeholder", "Comma-separated former names",
                                       "helpText",    "Useful when this line was previously known by a different designation."))
                )),
                group("Acceptance Reasons", List.of(
                        controlWithOptions("#/properties/acceptance", Map.of(
                                "widget", "multipleChoiceWithOther",
                                "label", "Why ZIRC should accept this line"
                        ))
                )),
                // Mutations is structurally different from the field sections —
                // a list of child rows, edited on their own pages. The "plain"
                // layout option tells SectionRenderer to drop the table wrapper.
                groupWithOptions("Mutations",
                        Map.of("layout", "plain"),
                        List.of(controlWithOptions("#/properties/mutations",
                                Map.of("widget", "mutationsList")))),
                group("Background", List.of(
                        controlWithOptions("#/properties/background/properties/singleAllelic",
                                Map.of("widget", "yesNoRadio")),
                        controlWithOptions("#/properties/background/properties/maternalBackground",
                                Map.of("widget", "selectWithOther",
                                       "standardValues", List.of("AB", "TU", "WIK", "AB/TU", "unknown"))),
                        controlWithOptions("#/properties/background/properties/paternalBackground",
                                Map.of("widget", "selectWithOther",
                                       "standardValues", List.of("AB", "TU", "WIK", "AB/TU", "unknown"))),
                        controlWithOptions("#/properties/background/properties/backgroundChangeable",
                                Map.of("widget", "yesNoRadio"))
                )),
                group("Additional Info", List.of(
                        controlWithOptions(
                                "#/properties/additionalInfo/properties/unreportedFeaturesDetails",
                                Map.of("multi", true)),
                        controlWithOptions(
                                "#/properties/additionalInfo/properties/husbandryInfo",
                                Map.of("multi", true,
                                       "placeholder",
                                       "Husbandry-specific information, e.g. special feeding regime")),
                        controlWithOptions(
                                "#/properties/additionalInfo/properties/additionalInfo",
                                Map.of("multi", true))
                ))
        ));
    }

    /**
     * Path → read+write dispatch. The PATCH endpoint rejects any path not in
     * this map (the form schema is the gatekeeper, no untyped string dispatch).
     * Audit-log old-value capture uses the same descriptor's read.
     */
    public static final Map<String, FieldDescriptor> FIELDS = Map.ofEntries(
            field("/name",                       LineSubmission::getName,                       (s, v) -> s.setName(text(v))),
            field("/previousNames",              LineSubmission::getPreviousNames,              (s, v) -> s.setPreviousNames(text(v))),
            // Acceptance: stored flat on the entity (reasons/reasonsOther) but nested in the form schema
            field("/acceptance/reasons",         s -> s.getReasons() == null ? new String[0] : s.getReasons(),
                                                                                                (s, v) -> s.setReasons(stringArray(v))),
            field("/acceptance/reasonsOther",    LineSubmission::getReasonsOther,               (s, v) -> s.setReasonsOther(text(v))),
            // Background
            field("/background/singleAllelic",        LineSubmission::getSingleAllelic,         (s, v) -> s.setSingleAllelic(boolNullable(v))),
            field("/background/maternalBackground",   LineSubmission::getMaternalBackground,    (s, v) -> s.setMaternalBackground(text(v))),
            field("/background/paternalBackground",   LineSubmission::getPaternalBackground,    (s, v) -> s.setPaternalBackground(text(v))),
            field("/background/backgroundChangeable", LineSubmission::getBackgroundChangeable,  (s, v) -> s.setBackgroundChangeable(boolNullable(v))),
            // Additional Info
            field("/additionalInfo/unreportedFeaturesDetails", LineSubmission::getUnreportedFeaturesDetails,
                                                                                                (s, v) -> s.setUnreportedFeaturesDetails(text(v))),
            field("/additionalInfo/husbandryInfo",  LineSubmission::getHusbandryInfo,           (s, v) -> s.setHusbandryInfo(text(v))),
            field("/additionalInfo/additionalInfo", LineSubmission::getAdditionalInfo,          (s, v) -> s.setAdditionalInfo(text(v)))
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

    /** Hard cap mirroring the alt-branch (ZFIN-10265) form spec. */
    public static final int MAX_MUTATIONS_PER_SUBMISSION = 5;

    /**
     * Schema for the mutations summary list shown on the submission page.
     * Items are read-only summaries — editing happens on the per-mutation
     * page. The shape mirrors {@link org.zfin.zirc.dto.MutationResponse}.
     */
    private static Map<String, Object> mutationsSummaryArrayProp() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("id",                Map.of("type", "number"));
        itemProps.put("lineSubmissionId",  Map.of("type", "string"));
        itemProps.put("sortOrder",         Map.of("type", "number"));
        itemProps.put("alleleDesignation", Map.of("type", List.of("string", "null")));
        itemProps.put("alleleInZfin",      Map.of("type", List.of("boolean", "null")));
        itemProps.put("mutationType",      Map.of("type", List.of("string", "null")));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);

        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("title", "Mutations");
        arr.put("items", item);
        arr.put("maxItems", MAX_MUTATIONS_PER_SUBMISSION);
        return arr;
    }

    private static Map<String, Object> reasonsArrayProp() {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        items.put("oneOf", CANONICAL_REASONS);
        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("uniqueItems", true);
        arr.put("items", items);
        return arr;
    }

    private static Map<String, Object> obj(String title, Map<String, Object> properties) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("title", title);
        o.put("properties", properties);
        return o;
    }

    private static Map<String, Object> linkedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
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
        c.put("options", options);
        return c;
    }

    private static Map<String, String> entry(String value, String label) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("const", value);
        e.put("title", label);
        return e;
    }

    // ─── descriptor builders ────────────────────────────────────────────────

    private static Map.Entry<String, FieldDescriptor> field(
            String path,
            Function<LineSubmission, ?> getter,
            BiConsumer<LineSubmission, JsonNode> setter) {
        return Map.entry(path, new FieldDescriptor(
                s -> MAPPER.valueToTree(getter.apply(s)),
                setter));
    }

    // ─── value coercers (JsonNode → Java) ──────────────────────────────────

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }

    private static Boolean boolNullable(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        return v.asBoolean();
    }

    private static String[] stringArray(JsonNode v) {
        if (v == null || v.isNull() || !v.isArray()) {return new String[0];}
        String[] result = new String[v.size()];
        for (int i = 0; i < v.size(); i++) {
            result[i] = v.get(i).asText();
        }
        return result;
    }
}
