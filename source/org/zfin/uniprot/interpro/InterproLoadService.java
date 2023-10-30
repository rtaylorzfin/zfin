package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.ExternalNote;
import org.zfin.datatransfer.go.GafOrganization;
import org.zfin.framework.HibernateUtil;
import org.zfin.gwt.root.dto.ExternalNoteDTO;
import org.zfin.gwt.root.dto.GoEvidenceCodeEnum;
import org.zfin.marker.Marker;
import org.zfin.mutant.GoEvidenceCode;
import org.zfin.mutant.MarkerGoTermEvidence;
import org.zfin.ontology.GenericTerm;
import org.zfin.publication.Publication;
import org.zfin.repository.RepositoryFactory;
import org.zfin.sequence.DBLink;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.ReferenceDatabase;

import java.util.*;

import static org.zfin.framework.HibernateUtil.currentSession;
import static org.zfin.repository.RepositoryFactory.*;

@Getter
@Setter
@Log4j2
public class InterproLoadService {


    private static final String DBLINK_PUBLICATION_ATTRIBUTION_ID = "ZDB-PUB-230615-71";
    private static final String EC_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID = "ZDB-PUB-031118-3";
    private static final String IP_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID = "ZDB-PUB-020724-1";
    private static final String SPKW_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID = "ZDB-PUB-020723-1";
    private static final String EXTNOTE_PUBLICATION_ATTRIBUTION_ID = "ZDB-PUB-230615-71";


    private static final String EXTNOTE_REFERENCE_DATABASE_ID = "ZDB-FDBCONT-040412-47";
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
                    case INTERPRO -> loadMarkerGoTermEvidence(action, IP_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID);
                    case EC -> loadMarkerGoTermEvidence(action, EC_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID);
                    case UNIPROTKB -> loadMarkerGoTermEvidence(action, SPKW_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID);
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
        switch (action.getSubType()) {
            case DB_LINK -> {
                switch (action.getDbName()) {
                    case INTERPRO -> deleteDbLink(action, INTERPRO_REFERENCE_DATABASE_ID);
                    case EC -> deleteDbLink(action, EC_REFERENCE_DATABASE_ID);
                    case PFAM -> deleteDbLink(action, PFAM_REFERENCE_DATABASE_ID);
                    case PROSITE -> deleteDbLink(action, PROSITE_REFERENCE_DATABASE_ID);
                    default -> log.error("Unknown dblink dbname to delete " + action.getDbName());
                }
            }
            case MARKER_GO_TERM_EVIDENCE -> {
                switch (action.getDbName()) {
//                    case INTERPRO -> deleteMarkerGoTermEvidenceInterpro(action);
//                    case EC -> deleteMarkerGoTermEvidenceEc(action);
//                    case UNIPROTKB -> deleteMarkerGoTermEvidenceUniprot(action);
//                    default -> log.error("Unknown marker_go_term_evidence dbname to delete " + action.getDbName());
                }
            }
            case EXTERNAL_NOTE -> {
//                deleteExternalNote();
            }
            default -> log.error("Unhandled action subtype: " + action.getSubType());
        }
    }

    private static void loadDbLink(SecondaryTermLoadAction action, String referenceDatabaseID) {
        log.debug("Loading " + action.getDbName() + " dblink for " + action.getGeneZdbID() + " " + action.getAccession() + " " + referenceDatabaseID );

        Marker marker = getMarkerRepository().getMarker(action.getGeneZdbID());
        MarkerDBLink newLink = new MarkerDBLink();
        newLink.setAccessionNumber(action.getAccession());
        newLink.setMarker(marker);
        newLink.setReferenceDatabase(getReferenceDatabase(referenceDatabaseID));
        newLink.setLength(action.getLength());
        newLink.setLinkInfo(getDBLinkInfo());

        Publication publication = getPublicationRepository().getPublication(DBLINK_PUBLICATION_ATTRIBUTION_ID);

        ArrayList<MarkerDBLink> dblinks = new ArrayList<>();
        dblinks.add(newLink);
        getSequenceRepository().addDBLinks(dblinks, publication, 1);
    }

    private static void deleteDbLink(SecondaryTermLoadAction action, String referenceDatabaseID) {
        ReferenceDatabase referenceDatabase = getReferenceDatabase(referenceDatabaseID);
        log.debug("Removing " + action.getDbName() + " dblink for " + action.getGeneZdbID() + " " + action.getAccession() + " " + referenceDatabaseID );
        DBLink dblink = getSequenceRepository().getDBLink(action.getGeneZdbID(), action.getAccession(), referenceDatabase.getForeignDB().getDbName().toString());
        log.debug("Removing dblink: " + dblink.getZdbID());
        getSequenceRepository().removeDBLinks(Collections.singletonList(dblink));
    }

    private static String loadMarkerGoTermEvidence(SecondaryTermLoadAction action, String pubID)  {
        MarkerGoTermEvidence markerGoTermEvidence = new MarkerGoTermEvidence();
        markerGoTermEvidence.setExternalLoadDate(null);

        GafOrganization uniprotGafOrganization = getMarkerGoTermEvidenceRepository().getGafOrganization(GafOrganization.OrganizationEnum.UNIPROT);
        markerGoTermEvidence.setGafOrganization(uniprotGafOrganization);

        markerGoTermEvidence.setOrganizationCreatedBy(GafOrganization.OrganizationEnum.ZFIN.name());

        Marker marker = getMarkerRepository().getMarker(action.getGeneZdbID());
        markerGoTermEvidence.setMarker(marker);

        GenericTerm goTerm = (GenericTerm) HibernateUtil.currentSession().get(GenericTerm.class, action.getGoTermZdbID());
        if (!goTerm.useForAnnotations())
            throw new RuntimeException("Do not use this term for GO Annotations: " + goTerm.getTermName());

        markerGoTermEvidence.setGoTerm(goTerm);

        // set source
        Publication publication = RepositoryFactory.getPublicationRepository().getPublication(pubID);
        markerGoTermEvidence.setSource(publication);


        GoEvidenceCode goEvidenceCode = getMarkerGoTermEvidenceRepository().getGoEvidenceCode(GoEvidenceCodeEnum.IEA.name());
        markerGoTermEvidence.setEvidenceCode(goEvidenceCode);
        switch(action.getDbName()) {
            case INTERPRO -> markerGoTermEvidence.setNote("ZFIN InterPro 2 GO");
            case EC -> markerGoTermEvidence.setNote("ZFIN EC acc 2 GO");
            case UNIPROTKB -> markerGoTermEvidence.setNote("ZFIN SP keyword 2 GO");
            default -> log.error("Unknown marker_go_term_evidence dbname to load " + action.getDbName());
        }

        Date rightNow = new Date();
        markerGoTermEvidence.setModifiedWhen(rightNow);
        markerGoTermEvidence.setCreatedWhen(rightNow);

        getMarkerGoTermEvidenceRepository().addEvidence(markerGoTermEvidence, false);

        getMutantRepository().addInferenceToGoMarkerTermEvidence(markerGoTermEvidence, action.getPrefixedAccession());

        return markerGoTermEvidence.getZdbID();
    }

    private static void loadExternalNote(SecondaryTermLoadAction action) {
        ExternalNote externalNote = new ExternalNote();
        externalNote.setExternalDataZdbID(action.getGeneZdbID());
        externalNote.setNote(action.getDetails());
        externalNote.setPublication(getPublicationRepository().getPublication(EXTNOTE_PUBLICATION_ATTRIBUTION_ID));
//        externalNote.setType();
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
