package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.marker.Marker;
import org.zfin.publication.Publication;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.ReferenceDatabase;

import java.util.*;

import static org.zfin.Species.Type.ZEBRAFISH;
import static org.zfin.framework.HibernateUtil.currentSession;
import static org.zfin.repository.RepositoryFactory.*;
import static org.zfin.sequence.ForeignDB.AvailableName.INTERPRO;
import static org.zfin.sequence.ForeignDBDataType.DataType.DOMAIN;
import static org.zfin.sequence.ForeignDBDataType.SuperType.PROTEIN;

@Getter
@Setter
@Log4j2
public class InterproLoadService {


    private static final String PUBLICATION_ATTRIBUTION_ID = "ZDB-PUB-230615-71";

    private static final String INTERPRO_REFERENCE_DATABASE_ID = "ZDB-FDBCONT-040412-48";
    private static final String EC_REFERENCE_DATABASE_ID = "ZDB-FDBCONT-040412-49";
    private static final String PFAM_REFERENCE_DATABASE_ID = "ZDB-FDBCONT-040412-50";
    private static final String PROSITE_REFERENCE_DATABASE_ID = "ZDB-FDBCONT-040412-51";


    /**
     * Process the actions.
     * Some examples of actions from a run are below (count dbName type subType):
     *   33 InterPro DELETE DB_LINK
     *  851 InterPro LOAD DB_LINK
     *  306 EC LOAD DB_LINK
     *   15 Pfam DELETE DB_LINK
     *  332 Pfam LOAD DB_LINK
     *    9 PROSITE DELETE DB_LINK
     *  233 PROSITE LOAD DB_LINK
     *  635 InterPro LOAD MARKER_GO_TERM_EVIDENCE
     *   16 InterPro DELETE MARKER_GO_TERM_EVIDENCE
     *  400 EC LOAD MARKER_GO_TERM_EVIDENCE
     *  636 UniProtKB LOAD MARKER_GO_TERM_EVIDENCE
     *  124 UniProtKB DELETE MARKER_GO_TERM_EVIDENCE
     * 25983 null LOAD EXTERNAL_NOTE
     *
     * @param actions
     */


    public static void processActions(List<SecondaryTermLoadAction> actions) {
        currentSession().beginTransaction();
        for(SecondaryTermLoadAction action : actions) {processAction(action);}
        currentSession().getTransaction().commit();
    }


  private static void processAction(SecondaryTermLoadAction action) {
        if (action.getType().equals(SecondaryTermLoadAction.Type.LOAD)) {
            loadAction(action);
        } else if (action.getType().equals(SecondaryTermLoadAction.Type.DELETE)) {
            deleteAction(action);
        } else {
            //ignore other action types used for reporting
        }
    }

    private static void loadAction(SecondaryTermLoadAction action) {
        switch (action.getSubType()) {
            case DB_LINK -> {
                switch (action.getDbName()) {
                    case INTERPRO -> loadDbLink(action, INTERPRO_REFERENCE_DATABASE_ID);
                    case EC -> loadDbLink(action, EC_REFERENCE_DATABASE_ID);
                    case PFAM -> loadDbLink(action, PFAM_REFERENCE_DATABASE_ID);
                    case PROSITE -> loadDbLink(action, PROSITE_REFERENCE_DATABASE_ID);
                    default -> log.error("Unknown dblink dbname to load " + action.getDbName());
                }
            }
            case MARKER_GO_TERM_EVIDENCE -> {
                switch (action.getDbName()) {
                    case INTERPRO -> loadMarkerGoTermEvidenceInterpro(action);
                    case EC -> loadMarkerGoTermEvidenceEc(action);
                    case UNIPROTKB -> loadMarkerGoTermEvidenceUniprot(action);
                    default -> log.error("Unknown marker_go_term_evidence dbname to load " + action.getDbName());
                }
            }
            case EXTERNAL_NOTE -> {
                loadExternalNote(action);
            }
            default -> log.error("Unhandled action subtype: " + action.getSubType());
        }
    }

    private static void deleteAction(SecondaryTermLoadAction action) {
//        switch (action.getSubType()) {
//            case DB_LINK -> {
//                switch (action.getDbName()) {
//                    case INTERPRO -> deleteDbLinkInterpro(action);
//                    case EC -> deleteDbLinkEc(action);
//                    case PFAM -> deleteDbLinkPfam(action);
//                    case PROSITE -> deleteDbLinkProsite(action);
//                    default -> log.error("Unknown dblink dbname to delete " + action.getDbName());
//                }
//            }
//            case MARKER_GO_TERM_EVIDENCE -> {
//                switch (action.getDbName()) {
//                    case INTERPRO -> deleteMarkerGoTermEvidenceInterpro(action);
//                    case EC -> deleteMarkerGoTermEvidenceEc(action);
//                    case UNIPROTKB -> deleteMarkerGoTermEvidenceUniprot(action);
//                    default -> log.error("Unknown marker_go_term_evidence dbname to delete " + action.getDbName());
//                }
//            }
//            case EXTERNAL_NOTE -> {
//                deleteExternalNote();
//            }
//            default -> log.error("Unhandled action subtype: " + action.getSubType());
//        }
    }

    private static void loadDbLink(SecondaryTermLoadAction action, String referenceDatabaseID) {
        log.debug("Loading " + action.getDbName() + " dblink for " + action.getGeneZdbID() + " " + action.getAccession() + " " + referenceDatabaseID );

        Marker marker = getMarkerRepository().getMarker(action.getGeneZdbID());
        MarkerDBLink newLink = new MarkerDBLink();
        newLink.setAccessionNumber(action.getAccession());
//        newLink.setAccessionNumberDisplay(action.getAccession());
        newLink.setMarker(marker);
        newLink.setReferenceDatabase(getReferenceDatabase(referenceDatabaseID));
        newLink.setLength(action.getLength());
        newLink.setLinkInfo(getDBLinkInfo());

        Publication publication = getPublicationRepository().getPublication(PUBLICATION_ATTRIBUTION_ID);

        ArrayList<MarkerDBLink> dblinks = new ArrayList<>();
        dblinks.add(newLink);
        getSequenceRepository().addDBLinks(dblinks, publication, 1);

    }

    private static void loadMarkerGoTermEvidenceInterpro(SecondaryTermLoadAction action) {

    }

    private static void loadMarkerGoTermEvidenceEc(SecondaryTermLoadAction action) {
    }

    private static void loadMarkerGoTermEvidenceUniprot(SecondaryTermLoadAction action) {
    }

    private static void loadExternalNote(SecondaryTermLoadAction action) {
    }

    public static ReferenceDatabase getReferenceDatabase(String referenceDatabaseID) {
        return getSequenceRepository().getReferenceDatabaseByID(referenceDatabaseID);
    }

    private static String getDBLinkInfo() {
        //eg. 2023-08-27 Swiss-Prot
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date());
        return timestamp + " Swiss-Prot";
    }
}
