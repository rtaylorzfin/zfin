package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.persistence.UniProtRelease;

import java.util.*;

import static org.zfin.framework.HibernateUtil.currentSession;

@Getter
@Setter
@Log4j2
public class InterproLoadService {

    public static void processActions(List<SecondaryTermLoadAction> actions) {
        System.out.println("Detailed Actions: " );
        for ( var action: actions) {
            System.out.println(action.getDbName() + " " + action.getType() + " " + action.getSubType());
        }
    }


//    public static void processActions(List<SecondaryTermLoadAction> actions) {
//        currentSession().beginTransaction();
//        for(SecondaryTermLoadAction action : actions) {processAction(action);}
//        currentSession().getTransaction().commit();
//    }


//  private static void processAction(SecondaryTermLoadAction action) {
//        if (action.getType().equals(SecondaryTermLoadAction.Type.LOAD)) {
//            loadAction(action);
//        } else if (action.getType().equals(SecondaryTermLoadAction.Type.DELETE)) {
//            deleteAction(action);
//        } else {
//            //ignore other action types used for reporting
//        }
//    }

//    private static void loadAction(SecondaryTermLoadAction action) {
//        //new java switch based on subType and dbName
//        switch (action.getSubType()) {
//            case DB_LINK -> {
//                switch (action.getDbName()) {
//                    case INTERPRO -> loadInterpro(action);
//                    case ForeignDB.AvailableName.EC -> loadEC(action);
//                    case PFAM -> loadPfam(action);
//                    case UNIPROTKB -> loadSpkw(action);
//                    default -> log.error("Unknown Interpro subType: " + action.getSubType());
//                }
//            }
//            case MARKER_GO_TERM_EVIDENCE -> {
//                if (action.getDbName() == PFAM) {
//                    loadPfamMGTE(action);
//                }
//            }
//            case EXTERNAL_NOTE -> {
//                switch (action.getDbName()) {
//                    case INTERPRO2GO -> loadGo(action);
//                    default -> log.error("Unknown Interpro subType: " + action.getSubType());
//                }
//            }
//            default -> log.error("Unhandled action subtype: " + action.getSubType());
//        }
//    }


}
