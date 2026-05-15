package org.zfin.zirc.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.zfin.zirc.entity.LineSubmission;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the ZIRC submission form: JSON Schema, rjsf
 * uiSchema, and a path → entity-setter table used by the field-path PATCH
 * endpoint.
 *
 * <p>Spike scope: Overview section only. Other sections still go through
 * their own section-PATCH endpoints until we decide whether to migrate.
 *
 * <p>For now the schema is hand-built. If the pattern survives evaluation,
 * the next step is to drive it from Java annotations + Bean Validation so
 * the schema, validation rules, and field-handler table stay in one place.
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

    public static Map<String, Object> uiSchema() {
        // Default text widgets for both fields — no custom UX needed yet.
        // The Overview "ID" row is rendered outside the form (it shows the
        // server-generated ZDB ID once present).
        return Map.of();
    }

    /**
     * Path → entity-setter dispatch. Keys are JSON-Pointer-ish paths matching
     * the schema's property hierarchy. Adding a field means adding both the
     * schema entry above and a handler here; the spec is intentionally
     * co-located so the gap is obvious in code review.
     */
    public static final Map<String, FieldHandler> FIELD_HANDLERS = Map.of(
        "/name",          (s, v) -> s.setName(text(v)),
        "/previousNames", (s, v) -> s.setPreviousNames(text(v))
    );

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) {return null;}
        String s = v.asText();
        return s.isBlank() ? null : s.trim();
    }
}
