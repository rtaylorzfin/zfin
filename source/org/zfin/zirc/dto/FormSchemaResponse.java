package org.zfin.zirc.dto;

import org.zfin.zirc.api.uischema.UiSchemaElement;

import java.util.Map;

/**
 * Wire DTO for the three {@code /form-schema} endpoints.
 *
 * <p>The {@code uiSchema} side migrated to typed records in M5.1
 * (task #93); {@code schema} stays untyped until the parallel migration
 * lands in #94. Once both sides are typed, the DTO becomes
 * {@code FormSchemaResponse(JsonSchema, UiSchemaElement)}.
 */
public record FormSchemaResponse(
        Map<String, Object> schema,
        UiSchemaElement uiSchema) {
}
