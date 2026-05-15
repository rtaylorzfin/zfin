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
import org.zfin.zirc.dto.FieldUpdate;
import org.zfin.zirc.dto.FormSchemaResponse;
import org.zfin.zirc.dto.LineSubmissionResponse;
import org.zfin.zirc.dto.MutationResponse;
import org.zfin.zirc.service.ZircSubmissionService;

@RestController
@RequestMapping(path = "/api/zirc", produces = MediaType.APPLICATION_JSON_VALUE)
public class ZircSubmissionApiController {

    @Autowired
    private ZircSubmissionService zircSubmissionService;

    @GetMapping("/form-schema")
    public FormSchemaResponse getFormSchema() {
        return new FormSchemaResponse(ZircFormSchema.schema(), ZircFormSchema.uiSchema());
    }

    @PostMapping("/line-submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public LineSubmissionResponse createLineSubmission() {
        return LineSubmissionResponse.of(zircSubmissionService.createDraftForCurrentUser());
    }

    @GetMapping("/line-submissions/{zdbID}")
    public LineSubmissionResponse getLineSubmission(@PathVariable String zdbID) {
        return LineSubmissionResponse.of(zircSubmissionService.getRequiredLineSubmission(zdbID));
    }

    /**
     * Single field change against the form schema. The path is checked against
     * {@link ZircFormSchema#FIELDS}; unknown paths reject with 400.
     */
    @PatchMapping("/line-submissions/{zdbID}")
    public LineSubmissionResponse updateField(
            @PathVariable String zdbID,
            @Valid @RequestBody FieldUpdate update) {
        return LineSubmissionResponse.of(zircSubmissionService.updateField(zdbID, update));
    }

    @PostMapping("/line-submissions/{zdbID}/mutations")
    @ResponseStatus(HttpStatus.CREATED)
    public MutationResponse addMutation(@PathVariable String zdbID) {
        return MutationResponse.of(zircSubmissionService.addMutation(zdbID));
    }

    @DeleteMapping("/line-submissions/{zdbID}/mutations/{mutationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMutation(@PathVariable String zdbID, @PathVariable Long mutationId) {
        zircSubmissionService.deleteMutation(zdbID, mutationId);
    }
}
