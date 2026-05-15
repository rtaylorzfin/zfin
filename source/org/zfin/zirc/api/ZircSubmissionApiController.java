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
import org.zfin.zirc.dto.LineSubmissionAcceptanceReasonsUpdate;
import org.zfin.zirc.dto.LineSubmissionAdditionalInfoUpdate;
import org.zfin.zirc.dto.LineSubmissionBackgroundUpdate;
import org.zfin.zirc.dto.LineSubmissionOverviewUpdate;
import org.zfin.zirc.dto.LineSubmissionResponse;
import org.zfin.zirc.dto.MutationResponse;
import org.zfin.zirc.service.ZircSubmissionService;

@RestController
@RequestMapping(path = "/api/zirc", produces = MediaType.APPLICATION_JSON_VALUE)
public class ZircSubmissionApiController {

    @Autowired
    private ZircSubmissionService zircSubmissionService;

    @PostMapping("/line-submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public LineSubmissionResponse createLineSubmission() {
        return LineSubmissionResponse.of(zircSubmissionService.createDraftForCurrentUser());
    }

    @GetMapping("/line-submissions/{zdbID}")
    public LineSubmissionResponse getLineSubmission(@PathVariable String zdbID) {
        return LineSubmissionResponse.of(zircSubmissionService.getRequiredLineSubmission(zdbID));
    }

    @PatchMapping("/line-submissions/{zdbID}/overview")
    public LineSubmissionResponse updateOverview(
            @PathVariable String zdbID,
            @Valid @RequestBody LineSubmissionOverviewUpdate update) {
        return LineSubmissionResponse.of(zircSubmissionService.updateOverview(zdbID, update));
    }

    @PatchMapping("/line-submissions/{zdbID}/acceptance-reasons")
    public LineSubmissionResponse updateAcceptanceReasons(
            @PathVariable String zdbID,
            @Valid @RequestBody LineSubmissionAcceptanceReasonsUpdate update) {
        return LineSubmissionResponse.of(zircSubmissionService.updateAcceptanceReasons(zdbID, update));
    }

    @PatchMapping("/line-submissions/{zdbID}/background")
    public LineSubmissionResponse updateBackground(
            @PathVariable String zdbID,
            @Valid @RequestBody LineSubmissionBackgroundUpdate update) {
        return LineSubmissionResponse.of(zircSubmissionService.updateBackground(zdbID, update));
    }

    @PatchMapping("/line-submissions/{zdbID}/additional-info")
    public LineSubmissionResponse updateAdditionalInfo(
            @PathVariable String zdbID,
            @Valid @RequestBody LineSubmissionAdditionalInfoUpdate update) {
        return LineSubmissionResponse.of(zircSubmissionService.updateAdditionalInfo(zdbID, update));
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
