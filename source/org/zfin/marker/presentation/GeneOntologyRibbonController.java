package org.zfin.marker.presentation;

import com.fasterxml.jackson.annotation.JsonView;
import lombok.extern.log4j.Log4j2;
import org.apache.solr.client.solrj.SolrServerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zfin.framework.api.*;
import org.zfin.marker.service.MarkerGoService;
import org.zfin.ontology.repository.OntologyRepository;
import org.zfin.ontology.service.RibbonService;
import org.zfin.wiki.presentation.Version;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

@RestController
@RequestMapping("/api")
@Log4j2
@Repository
public class GeneOntologyRibbonController {

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private MarkerGoService markerGoService;

    @Autowired
    private RibbonService ribbonService;

    @Autowired
    private OntologyRepository ontologyRepository;

    @RequestMapping(value = "/marker/{zdbID}/go/ribbon-summary")
    public RibbonSummary getGoRibbonSummary(@PathVariable("zdbID") String zdbID) throws Exception {

        return ribbonService.buildGORibbonSummary(zdbID);

    }

    @JsonView(View.API.class)
    @RequestMapping(value = "/marker/{zdbID}/go")
    public JsonResultResponse<GeneOntologyAnnotationTableRow> getGoAnnotations(@PathVariable String zdbID,
                                                                               @RequestParam(required = false) String termId,
                                                                               @RequestParam(required = false) boolean isOther,
                                                                               @RequestParam(value = "filter.termName", required = false) String filterTermName,
                                                                               @Version Pagination pagination) throws IOException, SolrServerException {
        pagination.addFieldFilter(FieldFilter.FILTER_TERM_NAME, filterTermName);
        JsonResultResponse<GeneOntologyAnnotationTableRow> response = markerGoService.getGoEvidence(zdbID, termId, isOther, pagination);
        response.setHttpServletRequest(request);

        return response;
    }
}
