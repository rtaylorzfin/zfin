package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
public class RemoveInterproToGoHandler implements InterproLoadHandler {

    private final ForeignDB.AvailableName dbName;

    public RemoveInterproToGoHandler(ForeignDB.AvailableName dbName) {
        this.dbName = dbName;
    }

    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<InterproLoadAction> actions, InterproLoadContext context) {
        List<InterproLoadAction> deletes = actions.stream()
                .filter(action -> dbName.equals(action.getDbName()) && action.getType().equals(InterproLoadAction.Type.DELETE))
                .toList();

        List<SecondaryTerm2GoTerm> interpro2GoTranslationRecords = context.getInterproTranslationRecords();

        log.debug("Joining " + deletes.size()  + " InterproLoadAction against " + interpro2GoTranslationRecords.size() + " Interpro2GoTerms ");

        log.debug("DELETING marker_go_term_evidence");
        //join the load actions to the interpro translation records
        List<Tuple2<InterproLoadAction, SecondaryTerm2GoTerm>> joined = Seq.seq(deletes)
                .innerJoin(interpro2GoTranslationRecords,
                        (action, ip2go) -> action.getAccession().equals(ip2go.interproID()))
                .toList();
        for(var joinedRecord : joined) {
            InterproLoadAction action = joinedRecord.v1();
            SecondaryTerm2GoTerm ip2go = joinedRecord.v2();
            InterproLoadAction newAction = InterproLoadAction.builder()
                    .accession(action.getAccession())
                    .dbName(dbName)
                    .type(InterproLoadAction.Type.DELETE)
                    .subType(InterproLoadAction.SubType.MARKER_GO_TERM_EVIDENCE)
                    .geneZdbID(action.getGeneZdbID())
                    .goID(ip2go.goID())
                    .goTermZdbID(ip2go.termZdbID())
                    .build();
            log.debug(newAction.markerGoTermEvidenceRepresentation());
            actions.add(newAction);
        }

    }
}
