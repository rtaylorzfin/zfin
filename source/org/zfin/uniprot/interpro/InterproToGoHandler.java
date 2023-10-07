package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.zfin.sequence.ForeignDB.AvailableName.INTERPRO;

@Log4j2
public class InterproToGoHandler implements InterproLoadHandler {

    private final ForeignDB.AvailableName dbName;

    public InterproToGoHandler(ForeignDB.AvailableName dbName) {
        this.dbName = dbName;
    }

    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<InterproLoadAction> actions, InterproLoadContext context) {
        List<InterproLoadAction> loads = actions.stream()
                .filter(action -> dbName.equals(action.getDbName()) && action.getType().equals(InterproLoadAction.Type.LOAD))
                .toList();

        List<InterPro2GoTerm> interpro2GoTranslationRecords = context.getInterproTranslationRecords();


        log.debug("Joining " + loads.size()  + " InterproLoadAction against " + interpro2GoTranslationRecords.size() + " Interpro2GoTerms ");

        //join the load actions to the interpro translation records
        List<Tuple2<InterproLoadAction, InterPro2GoTerm>> joined = Seq.seq(loads)
                .innerJoin(interpro2GoTranslationRecords,
                        (action, ip2go) -> action.getAccession().equals(ip2go.interproID()))
                .toList();
        for(var joinedRecord : joined) {
            InterproLoadAction action = joinedRecord.v1();
            InterPro2GoTerm ip2go = joinedRecord.v2();
            InterproLoadAction newAction = InterproLoadAction.builder()
                    .accession(action.getAccession())
                    .dbName(INTERPRO)
                    .type(InterproLoadAction.Type.LOAD)
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
