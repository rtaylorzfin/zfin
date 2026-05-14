package org.zfin.zirc.presentation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.Mutation;
import org.zfin.zirc.service.LineSubmissionAcceptanceReasonsUpdate;
import org.zfin.zirc.service.LineSubmissionAdditionalInfoUpdate;
import org.zfin.zirc.service.LineSubmissionBackgroundUpdate;
import org.zfin.zirc.service.LineSubmissionOverviewUpdate;
import org.zfin.zirc.service.ZircEntityNotFoundException;
import org.zfin.zirc.service.ZircSubmissionService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/zirc")
public class ZircSubmissionApiController {

    @Autowired
    private ZircSubmissionService zircSubmissionService;

    @RequestMapping(value = "/line-submissions", method = RequestMethod.POST)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createLineSubmission() {
        return lineSubmissionResponse(zircSubmissionService.createDraftForCurrentUser());
    }

    @RequestMapping(value = "/line-submissions/{zdbID}", method = RequestMethod.GET)
    public Map<String, Object> getLineSubmission(@PathVariable String zdbID) {
        return lineSubmissionResponse(zircSubmissionService.getRequiredLineSubmission(zdbID));
    }

    @RequestMapping(value = "/line-submissions/{zdbID}/overview", method = RequestMethod.PATCH)
    public Map<String, Object> updateOverview(@PathVariable String zdbID,
                                              @RequestBody LineSubmissionOverviewUpdate update) {
        return lineSubmissionResponse(zircSubmissionService.updateOverview(zdbID, update));
    }

    @RequestMapping(value = "/line-submissions/{zdbID}/acceptance-reasons", method = RequestMethod.PATCH)
    public Map<String, Object> updateAcceptanceReasons(@PathVariable String zdbID,
                                                       @RequestBody LineSubmissionAcceptanceReasonsUpdate update) {
        return lineSubmissionResponse(zircSubmissionService.updateAcceptanceReasons(zdbID, update));
    }

    @RequestMapping(value = "/line-submissions/{zdbID}/background", method = RequestMethod.PATCH)
    public Map<String, Object> updateBackground(@PathVariable String zdbID,
                                                @RequestBody LineSubmissionBackgroundUpdate update) {
        return lineSubmissionResponse(zircSubmissionService.updateBackground(zdbID, update));
    }

    @RequestMapping(value = "/line-submissions/{zdbID}/additional-info", method = RequestMethod.PATCH)
    public Map<String, Object> updateAdditionalInfo(@PathVariable String zdbID,
                                                    @RequestBody LineSubmissionAdditionalInfoUpdate update) {
        return lineSubmissionResponse(zircSubmissionService.updateAdditionalInfo(zdbID, update));
    }

    @RequestMapping(value = "/line-submissions/{zdbID}/mutations", method = RequestMethod.POST)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addMutation(@PathVariable String zdbID) {
        return mutationResponse(zircSubmissionService.addMutation(zdbID));
    }

    @RequestMapping(value = "/line-submissions/{zdbID}/mutations/{mutationId}", method = RequestMethod.DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMutation(@PathVariable String zdbID,
                               @PathVariable Long mutationId) {
        zircSubmissionService.deleteMutation(zdbID, mutationId);
    }

    @ExceptionHandler(ZircEntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNotFound(ZircEntityNotFoundException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", "not_found");
        response.put("message", e.getMessage());
        return response;
    }

    private static Map<String, Object> lineSubmissionResponse(LineSubmission submission) {
        Map<String, Object> response = new HashMap<>();
        response.put("zdbID", submission.getZdbID());
        response.put("name", submission.getName());
        response.put("abbreviation", submission.getAbbreviation());
        response.put("previousNames", submission.getPreviousNames());
        response.put("singleAllelic", submission.getSingleAllelic());
        response.put("maternalBackground", submission.getMaternalBackground());
        response.put("paternalBackground", submission.getPaternalBackground());
        response.put("backgroundChangeable", submission.getBackgroundChangeable());
        response.put("backgroundChangeConcerns", submission.getBackgroundChangeConcerns());
        response.put("unreportedFeaturesDetails", submission.getUnreportedFeaturesDetails());
        response.put("husbandryInfo", submission.getHusbandryInfo());
        response.put("additionalInfo", submission.getAdditionalInfo());
        response.put("reasons", submission.getReasons());
        response.put("reasonsOther", submission.getReasonsOther());
        response.put("draft", submission.getIsDraft());
        response.put("editUrl", "/action/zirc/line-submission/" + submission.getZdbID() + "/edit");
        response.put("detailUrl", "/action/zirc/line-submission/" + submission.getZdbID());
        return response;
    }

    private static Map<String, Object> mutationResponse(Mutation mutation) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", mutation.getId());
        response.put("lineSubmissionId", mutation.getLineSubmission().getZdbID());
        response.put("sortOrder", mutation.getSortOrder());
        response.put("alleleDesignation", mutation.getAlleleDesignation());
        response.put("alleleInZfin", mutation.getAlleleInZfin());
        response.put("mutationType", mutation.getMutationType());
        response.put("editUrl", "/action/zirc/mutation/" + mutation.getId() + "/edit");
        return response;
    }

}
