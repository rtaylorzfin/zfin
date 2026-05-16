package org.zfin.zirc.api;

import jakarta.servlet.ServletContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

/**
 * Serves the hand-curated OpenAPI 3 spec for the ZIRC API as a static
 * YAML file. See {@code reference/zirc-openapi-approach.md} for why we
 * picked hand-curation over springdoc-openapi here.
 *
 * <p>The spec lives at {@code /WEB-INF/openapi/zirc-api.yaml} so it ships
 * with the deployed webapp but isn't directly reachable as a static
 * resource. This controller wraps it in a normal HTTP response.
 */
@RestController
@RequestMapping("/api/zirc")
public class ZircOpenApiController {

    private static final String SPEC_PATH = "/WEB-INF/openapi/zirc-api.yaml";
    private static final MediaType APPLICATION_YAML = MediaType.parseMediaType("application/yaml");

    @Autowired
    private ServletContext servletContext;

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public ResponseEntity<InputStreamResource> getOpenApiSpec() {
        InputStream stream = servletContext.getResourceAsStream(SPEC_PATH);
        if (stream == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(APPLICATION_YAML)
                .body(new InputStreamResource(stream));
    }
}
