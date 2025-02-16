package org.zfin.marker.presentation;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zfin.expression.presentation.MarkerExpression;
import org.zfin.expression.service.ExpressionService;
import org.zfin.feature.Feature;
import org.zfin.feature.repository.FeatureService;
import org.zfin.framework.api.JsonResultResponse;
import org.zfin.framework.api.Pagination;
import org.zfin.framework.api.PubPrioritizationGeneSorting;
import org.zfin.marker.Marker;
import org.zfin.marker.service.MarkerService;
import org.zfin.mutant.DiseaseAnnotationModel;
import org.zfin.wiki.presentation.Version;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.zfin.repository.RepositoryFactory.getPhenotypeRepository;
import static org.zfin.repository.RepositoryFactory.getPublicationRepository;


@RestController
@RequestMapping("/api/publication")
@Log4j2
@Repository
public class PublicationPrioritizationController {

    @Autowired
    private HttpServletRequest request;
    @Autowired
    private ExpressionService expressionService;

    @RequestMapping(value = "/{publicationId}/prioritization/genes")
    public JsonResultResponse<Prioritization> getGenePubPrioritization(@PathVariable String publicationId,
                                                                       @Version Pagination pagination) {

        List<Marker> attributedMarker = getPublicationRepository().getGenesByPublication(publicationId, false);
        List<Prioritization> prioList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(attributedMarker)) {
            Map<Marker, Boolean> isNewGeneMap = getPublicationRepository().areNewGenePubAttribution(attributedMarker, publicationId);
            prioList = attributedMarker.stream()
                    .map(marker -> {
                        Prioritization prioritization = new Prioritization();
                        prioritization.setId(marker.getZdbID());
                        //     prioritization.setMarker(marker);
                        prioritization.setName(marker.getAbbreviation());
                        prioritization.setNewWithThisPaper(isNewGeneMap.get(marker));

                        prioritization.setHasOrthology(getPublicationRepository().hasCuratedOrthology(marker));
                        PhenotypeOnMarkerBean phenotypeOnMarkerBean = MarkerService.getPhenotypeOnGene(marker);
                        prioritization.setPhenotypeFigures(phenotypeOnMarkerBean.getNumFigures());
                        prioritization.setPhenotypePublication(phenotypeOnMarkerBean.getNumPublications());
                        MarkerExpression markerExpression = expressionService.getExpressionForGene(marker);
                        if (marker.isGenedom()) {
                            prioritization.setExpressionFigures(markerExpression.getAllMarkerExpressionInstance().getFigureCount());
                            prioritization.setExpressionInSitu(markerExpression.getInSituFigCount());
                            prioritization.setExpressionPublication(markerExpression.getAllMarkerExpressionInstance().getPublicationCount());

                        }
                        List<DiseaseAnnotationModel> diseaseAnnotationModels = getPhenotypeRepository().getDiseaseAnnotationModelsByGene(marker);
                        if (diseaseAnnotationModels != null)
                            prioritization.setAssociatedDiseases(diseaseAnnotationModels.size());
                        return prioritization;
                    })
                    .collect(Collectors.toList());
        }
        JsonResultResponse<Prioritization> response = new JsonResultResponse<>();
        response.setTotal(prioList.size());
        response.setResults(prioList);
        if (pagination.getSortBy() != null) {
            PubPrioritizationGeneSorting sorting = new PubPrioritizationGeneSorting();
            prioList.sort(sorting.getComparator(pagination.getSortBy()));
        }

        response.setHttpServletRequest(request);

        return response;
    }

    @RequestMapping(value = "/{publicationId}/prioritization/strs")
    public JsonResultResponse<Prioritization> getStrPubPrioritization(@PathVariable String publicationId) {

        List<Marker> attributedStrs = getPublicationRepository().getSTRByPublication(publicationId);
        List<Prioritization> prioList = attributedStrs.stream()
                .map(marker -> {
                    Prioritization prioritization = new Prioritization();
                    prioritization.setId(marker.getZdbID());
                    prioritization.setName(marker.getAbbreviation());
                    PhenotypeOnMarkerBean phenotypeOnMarkerBean = MarkerService.getPhenotypeOnGene(marker);
                    prioritization.setPhenotypeFigures(phenotypeOnMarkerBean.getNumFigures());
                    prioritization.setPhenotypePublication(phenotypeOnMarkerBean.getNumPublications());
                    return prioritization;
                })
                .collect(Collectors.toList());
        JsonResultResponse<Prioritization> response = new JsonResultResponse<>();
        response.setTotal(prioList.size());
        response.setResults(prioList);
        response.setHttpServletRequest(request);

        return response;


    }

    @RequestMapping(value = "/{publicationId}/prioritization/features")
    public JsonResultResponse<Prioritization> getFeaturePubPrioritization(@PathVariable String publicationId) {
        List<Feature> attributedFeatures = getPublicationRepository().getFeaturesByPublication(publicationId);
        List<Prioritization> prioList = attributedFeatures.stream()
                .map(feature -> {
                    Prioritization prioritization = new Prioritization();
                    prioritization.setId(feature.getZdbID());
                    prioritization.setName(feature.getAbbreviation());
                    prioritization.setNewWithThisPaper(getPublicationRepository().isNewFeaturePubAttribution(feature, publicationId));
                    if (feature.getAllelicGene() != null) {
                        prioritization.setAffectedMarkerId(feature.getAllelicGene().getZdbID());
                        prioritization.setAffectedMarkerName(feature.getAllelicGene().getAbbreviation());
                    }
                    PhenotypeOnMarkerBean bean = FeatureService.getPhenotypeOnFeature(feature);
                    if (bean != null) {
                        prioritization.setPhenotypeFigures(bean.getNumFigures());
                        prioritization.setPhenotypePublication(bean.getNumPublications());
                    }
                    return prioritization;
                })
                .collect(Collectors.toList());
        JsonResultResponse<Prioritization> response = new JsonResultResponse<>();
        response.setTotal(prioList.size());

        response.setResults(prioList);
        response.setHttpServletRequest(request);

        return response;
    }

}
