package org.zfin.zirc.dto;

import java.util.Map;

public record FormSchemaResponse(
        Map<String, Object> schema,
        Map<String, Object> uiSchema) {
}
