package org.zfin.mutant;

import com.fasterxml.jackson.annotation.JsonView;
import lombok.Getter;
import lombok.Setter;
import org.zfin.expression.Experiment;
import org.zfin.framework.api.View;
import org.zfin.gwt.root.server.DTOConversionService;
import org.zfin.infrastructure.EntityZdbID;
import org.zfin.ontology.GenericTerm;
import org.zfin.publication.Publication;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Disease model entity:
 */
@Setter
@Getter
public class DiseaseAnnotation implements EntityZdbID {

    @JsonView(View.API.class)
    private String zdbID;
    @JsonView(View.API.class)
    private GenericTerm disease;
    private Publication publication;
    private GenericTerm evidenceCode;
    private List<DiseaseAnnotationModel> diseaseAnnotationModel;

    @JsonView(View.API.class)
    public List<Fish> getFishList() {
        return diseaseAnnotationModel.stream().map(model -> model.getFishExperiment().getFish()).collect(Collectors.toList());
    }

    @JsonView(View.API.class)
    public List<Experiment> getEnvironmentList() {
        return diseaseAnnotationModel.stream().map(model -> model.getFishExperiment().getExperiment()).collect(Collectors.toList());
    }

    @Override

    public String getAbbreviation() {
        return disease.getTermName();
    }

    @Override
    public String getAbbreviationOrder() {
        return disease.getTermName();
    }

    @Override
    public String getEntityType() {
        return "Disease Model";
    }

    @Override
    public String getEntityName() {
        return disease.getTermName();
    }

    @JsonView(View.API.class)
    public String getEvidenceCodeString() {
        if (getEvidenceCode() == null) {
            return "";
        }
        return DTOConversionService.evidenceCodeIdToAbbreviation(evidenceCode.getZdbID());
    }

}
