package org.zfin.publication.presentation;

import com.fasterxml.jackson.annotation.JsonView;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.zfin.antibody.Antibody;
import org.zfin.antibody.AntibodyService;
import org.zfin.antibody.repository.AntibodyRepository;
import org.zfin.feature.Feature;
import org.zfin.figure.presentation.ExpressionTableRow;
import org.zfin.figure.presentation.PhenotypeTableRow;
import org.zfin.figure.service.FigureViewService;
import org.zfin.framework.HibernateUtil;
import org.zfin.framework.api.FieldFilter;
import org.zfin.framework.api.JsonResultResponse;
import org.zfin.framework.api.Pagination;
import org.zfin.framework.api.View;
import org.zfin.gwt.root.dto.ConditionDTO;
import org.zfin.gwt.root.dto.MarkerDTO;
import org.zfin.gwt.root.server.DTOConversionService;
import org.zfin.gwt.root.util.StringUtils;
import org.zfin.infrastructure.EntityZdbID;
import org.zfin.mapping.MappingService;
import org.zfin.marker.Marker;
import org.zfin.marker.MarkerRelationship;
import org.zfin.marker.MarkerType;
import org.zfin.marker.presentation.GeneBean;
import org.zfin.marker.presentation.STRTargetRow;
import org.zfin.marker.repository.MarkerRepository;
import org.zfin.mutant.DiseaseAnnotation;
import org.zfin.mutant.Fish;
import org.zfin.mutant.PhenotypeWarehouse;
import org.zfin.mutant.SequenceTargetingReagent;
import org.zfin.orthology.Ortholog;
import org.zfin.publication.CorrespondenceNeeded;
import org.zfin.publication.CorrespondenceNeededReason;
import org.zfin.publication.Publication;
import org.zfin.publication.repository.PublicationRepository;
import org.zfin.repository.RepositoryFactory;
import org.zfin.wiki.presentation.Version;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

import static org.zfin.repository.RepositoryFactory.*;

@RestController
@RequestMapping("/api/correspondence")
public class CorrespondenceAPIController {

    @Autowired
    private PublicationRepository publicationRepository;

    @JsonView(View.API.class)
    @RequestMapping(value = "/{pubID}", method = RequestMethod.GET)
    public List<CorrespondenceNeededDTO> getPublicationCorrespondenceNeeded(@PathVariable String pubID) {
        return CorrespondenceService.getCorrespondenceNeededDTOsGridByPublicationID(pubID);
    }


    @JsonView(View.API.class)
    @RequestMapping(value = "/{pubID}", method = RequestMethod.POST)
    public List<CorrespondenceNeededDTO> setPublicationCorrespondenceNeeded(@PathVariable String pubID,
                                                                            @RequestBody List<CorrespondenceNeededDTO> correspondenceNeededDTOs) {
        HibernateUtil.createTransaction();
        CorrespondenceService.setCorrespondenceNeededByPublicationID(pubID, correspondenceNeededDTOs);
        HibernateUtil.flushAndCommitCurrentSession();
        return correspondenceNeededDTOs;
    }

}
