package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zfin.zirc.entity.Mutation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Single source of truth for the per-mutation edit form. Parallel to
 * {@link ZircFormSchema} but operates on {@link Mutation} entities.
 *
 * <p>M3.2 scope: General section only (allele designation + ZFIN flag,
 * mutation type, discoverer, institution). Later milestones add Mutagenesis,
 * Lethality, Publications, and the per-mutation children (Genes, Lesions,
 * Genotyping Assays, Phenotypes).
 */
public final class ZircMutationFormSchema {

    private ZircMutationFormSchema() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Read + write for a single form-schema path on a Mutation entity. */
    public record FieldDescriptor(
            Function<Mutation, JsonNode> read,
            BiConsumer<Mutation, JsonNode> write) {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("alleleDesignation",   stringProp(255, "Allele Designation"));
        properties.put("alleleInZfin",        nullableBoolProp("Allele already in ZFIN"));
        properties.put("mutationType",        stringProp(255, "Mutation Type"));
        properties.put("mutationDiscoverer",  stringProp(255, "Discoverer"));
        properties.put("mutationInstitution", stringProp(255, "Institution"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        return root;
    }

    public static Map<String, Object> uiSchema() {
        return verticalLayout(List.of(
                group("General", List.of(
                        control("#/properties/alleleDesignation"),
                        controlWithOptions("#/properties/alleleInZfin",
                                Map.of("widget", "yesNoRadio")),
                        control("#/properties/mutationType"),
                        control("#/properties/mutationDiscoverer"),
                        control("#/properties/mutationInstitution")
                ))
        ));
    }

    /**
     * Path → read+write dispatch. Operates on a Mutation entity (not a
     * LineSubmission); the controller looks up the mutation by id before
     * applying the descriptor.
     */
    public static final Map<String, FieldDescriptor> FIELDS = Map.ofEntries(
            field("/alleleDesignation",
                    Mutation::getAlleleDesignation,    (m, v) -> m.setAlleleDesignation(text(v))),
            field("/alleleInZfin",
                    Mutation::getAlleleInZfin,         (m, v) -> m.setAlleleInZfin(boolNullable(v))),
            field("/mutationType",
                    Mutation::getMutationType,         (m, v) -> m.setMutationType(text(v))),
            field("/mutationDiscoverer",
                    Mutation::getMutationDiscoverer,   (m, v) -> m.setMutationDiscoverer(text(v))),
            field("/mutationInstitution",
                    Mutation::getMutationInstitution,  (m, v) -> m.setMutationInstitution(text(v)))
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
