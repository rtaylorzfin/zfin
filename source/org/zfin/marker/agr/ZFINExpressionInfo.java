package org.zfin.marker.agr;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.collections4.CollectionUtils;
import org.zfin.marker.AllianceGeneDesc;
import org.zfin.marker.Marker;
import org.zfin.ontology.GenericTerm;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.sequence.ForeignDB;
import org.zfin.sequence.MarkerDBLink;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.zfin.repository.RepositoryFactory.*;

public class ZFINExpressionInfo extends AbstractScriptWrapper {

    private int numfOfRecords = 0;

    public ZFINExpressionInfo(int number) {
        numfOfRecords = number;
    }

    public static void main(String[] args) throws IOException {
        int number = 0;
        if (args.length > 0) {
            number = Integer.valueOf(args[0]);
        }
        ZFINExpressionInfo ZFINExpressionInfo = new ZFINExpressionInfo(number);
        ZFINExpressionInfo.init();
        System.exit(0);
    }

    private void init() throws IOException {
        initAll();
        ZFINAllExpressionDTO basicExpressionDTO = getZFINExpressionInfo(numfOfRecords);
        ObjectMapper mapper = new ObjectMapper();
        ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());

        //Object to JSON in String
        String jsonInString = writer.writeValueAsString(basicExpressionDTO);
        try (PrintStream out = new PrintStream(new FileOutputStream("zfin_wt_expression.json"))) {
            out.print(jsonInString);
        }
    }

    public ZFINAllExpressionDTO getZFINExpressionInfo(int numberOrRecords) {
        List<BasicExpressionDTO> basicExpressionDTOList = getExpressionRepository().getBasicExpressionDTOObjects();

        Map<String, List<ImageDTO>> imageDtoMap = getExpressionRepository().getDirectSubmissionImageDTOMap();

        List<ZFINExpressionDTO> zfinExpressionDTOList = new ArrayList<>();

        for (BasicExpressionDTO basicDTOitem : basicExpressionDTOList){

            ZFINExpressionDTO zfinExpressionDTO = new ZFINExpressionDTO();
            ZFINEvidenceDTO zfinExpressionEvidenceDTO = new ZFINEvidenceDTO();

            zfinExpressionDTO.setAssay(basicDTOitem.getAssay());
            zfinExpressionDTO.setWhereExpressed(basicDTOitem.getWhereExpressed());
            zfinExpressionDTO.setWhenExpressed(basicDTOitem.getWhenExpressed());
            zfinExpressionDTO.setGeneId(basicDTOitem.getGeneId());
            zfinExpressionEvidenceDTO.setCrossReference(basicDTOitem.getEvidence().getCrossReference());
            zfinExpressionEvidenceDTO.setPublicationId(basicDTOitem.getEvidence().getPublicationId());

//
//            if (basicDTOitem.getEvidence().getPublicationId().startsWith("ZFIN:")) {
//                String publicationId = basicDTOitem.getEvidence().getPublicationId();
//                Publication pub = getPublicationRepository().getPublication(publicationId.substring(5));
//                zfinExpressionEvidenceDTO.setPublicationTitle(pub.getTitle());
//            }
//            else {
//                Integer pmid = Integer.parseInt(basicDTOitem.getEvidence().getPublicationId().substring(5));
//                Publication pub = getPublicationRepository().getSinglePublicationByPmid(pmid);
//                zfinExpressionEvidenceDTO.setPublicationTitle(pub.getTitle());
//            }

            zfinExpressionDTO.setEvidence(zfinExpressionEvidenceDTO);
            zfinExpressionDTO.setDateAssigned(basicDTOitem.getDateAssigned());
            zfinExpressionDTO.setCrossReference(basicDTOitem.getCrossReference());
            zfinExpressionDTO.setImages(imageDtoMap.get(basicDTOitem.getExpressionResultId()));

            List<String> dblinkPages = new ArrayList<>();

            Marker gene = getMarkerRepository().getMarkerByID(zfinExpressionDTO.getGeneId().substring(5));

            AllianceGeneDesc geneDescription = getMarkerRepository().getGeneDescByMkr(gene);
            if (geneDescription != null) {
                zfinExpressionDTO.setGeneratedGeneDescription(geneDescription.getGdDesc());
            }

            zfinExpressionDTO.setGeneName(gene.getName());
            zfinExpressionDTO.setGeneSymbol(gene.getAbbreviation());
            
            if (CollectionUtils.isNotEmpty(gene.getDbLinks())) {
            List<CrossReferenceDTO> xrefs= new ArrayList<>();

                    for (MarkerDBLink link : gene.getDbLinks()) {
                        String dbName = DataProvider.getExternalDatabaseName(link.getReferenceDatabase().getForeignDB().getDbName());
                        if (dbName != null) {
                            if (dbName.equals(ForeignDB.AvailableName.ENSEMBL.toString()) && link.getAccessionNumber().startsWith("ENSDARG")) {
                                CrossReferenceDTO xRefDto = new CrossReferenceDTO(dbName, link.getAccessionNumber(), dblinkPages);
                                xrefs.add(xRefDto);
                            }
                        }
                    }
                zfinExpressionDTO.setEnsemblCrossReferences(xrefs);
            }

            GenericTerm mmoTerm = getOntologyRepository().getTermByOboID(zfinExpressionDTO.getAssay());
            zfinExpressionDTO.setAssayName(mmoTerm.getTermName());
            zfinExpressionDTOList.add(zfinExpressionDTO);
        }

        ZFINAllExpressionDTO allExpressionDTO = new ZFINAllExpressionDTO();

        allExpressionDTO.setExpressionList(zfinExpressionDTOList);
        String dataProvider = "ZFIN";
        List<String> pages = new ArrayList<>();
        pages.add("homepage");
        MetaDataDTO meta = new MetaDataDTO(new DataProviderDTO("curated", new CrossReferenceDTO(dataProvider, dataProvider, pages)));
        allExpressionDTO.setMetaData(meta);
        return allExpressionDTO;
    }
}
