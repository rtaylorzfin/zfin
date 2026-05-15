package org.zfin.zirc.api;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.zfin.zirc.dto.AssayResponse;
import org.zfin.zirc.dto.FieldUpdate;
import org.zfin.zirc.dto.FormSchemaResponse;
import org.zfin.zirc.dto.MutationResponse;
import org.zfin.zirc.service.ZircSubmissionService;

/**
 * Endpoints for the genotyping-assay collection under a mutation.
 *
 * <p>Add lives under the parent: {@code POST /api/zirc/mutations/{mutationId}/assays}
 * returns the updated MutationResponse so the React Query cache can
 * refresh in one round trip (matches how POST /mutations works for the
 * submission aggregate).
 *
 * <p>Delete keys directly off the assay id since the UI doesn't carry the
 * parent mutation in the URL after the row is rendered.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ZircAssayApiController {

    @Autowired
    private ZircSubmissionService zircSubmissionService;

    @PostMapping("/api/zirc/mutations/{mutationId}/assays")
    public MutationResponse addAssay(@PathVariable Long mutationId) {
        return MutationResponse.of(zircSubmissionService.addAssay(mutationId));
    }

    @DeleteMapping("/api/zirc/assays/{assayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAssay(@PathVariable Long assayId) {
        zircSubmissionService.deleteAssay(assayId);
    }

    @GetMapping("/api/zirc/assays/form-schema")
    public FormSchemaResponse getFormSchema() {
        return new FormSchemaResponse(
                ZircAssayFormSchema.schema(),
                ZircAssayFormSchema.uiSchema());
    }

    @GetMapping("/api/zirc/assays/{assayId}")
    public AssayResponse getAssay(@PathVariable Long assayId) {
        return AssayResponse.of(zircSubmissionService.getRequiredAssayById(assayId));
    }

    @PatchMapping("/api/zirc/assays/{assayId}")
    public AssayResponse updateField(
            @PathVariable Long assayId,
            @Valid @RequestBody FieldUpdate update) {
        return AssayResponse.of(zircSubmissionService.updateAssayField(assayId, update));
    }
}
