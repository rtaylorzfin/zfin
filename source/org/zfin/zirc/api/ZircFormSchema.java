package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.zfin.zirc.entity.LineSubmission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the ZIRC submission form: JSON Schema (data
 * shape + constraints), JSON Forms uiSchema (layout), and a path → entity-
 * setter dispatch table used by the field-path PATCH endpoint.
 *
 * <p>Spike scope: Overview section only. Other sections still go through
 * their own section-PATCH endpoints until they are migrated onto this
 * pattern.
 *
 * <p>Hand-built for now. Once the pattern survives a few more sections we
 * drive it from Java annotations + Bean Validation so the schema,
 * validation rules, and field-handler table stay in one place.
 */
public final class ZircFormSchema {

    private ZircFormSchema() {}

    @FunctionalInterface
    public interface FieldHandler {
        void apply(LineSubmission submission, JsonNode value);
    }

    public static Map<String, Object> schema() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("maxLength", 255);
        nameProp.put("title", "Name");

        Map<String, Object> prevProp = new LinkedHashMap<>();
        prevProp.put("type", "string");
        prevProp.put("maxLength", 2000);
        prevProp.put("title", "Previous Names");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("previousNames", prevProp);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("title", "Overview");
        root.put("properties", properties);
        return root;
    }

    /**
     * JSON Forms uiSchema. The client's renderer registry expects:
     *   - Group elements → SectionRenderer (section/table wrapper)
     *   - Control elements pointing at string properties → RowControlRenderer
     *     (table row with fr-* ids)
     *
     * Once multiple sections exist this becomes a VerticalLayout of Groups.
     */
    public static Map<String, Object> uiSchema() {
        return group("Overview", List.of(
                control("#/properties/name"),
                control("#/properties/previousNames")
        ));
    }

    /**
     * Path → entity-setter dispatch. Keys are JSON-Pointer-ish paths matching
     * the schema's property hierarchy. Adding a field means adding both the
     * schema entry above and a handler here; co-located on purpose so the
     * gap is obvious in code review.
     */
    public static final Map<String, FieldHandler> FIELD_HANDLERS = Map.of(
        "/name",          (s, v) -> s.setName(text(v)),
        "/previousNames", (s, v) -> s.setPreviousNames(text(v))
    );

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

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }
}
