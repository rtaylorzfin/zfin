package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;
import org.zfin.mutant.MarkerGoTermEvidence;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
public class AddNewSpKeywordTermToGoHandler extends AddNewSecondaryTermToGoHandler {
    private static final ForeignDB.AvailableName FOREIGN_DB_NAME = ForeignDB.AvailableName.UNIPROTKB;

    public AddNewSpKeywordTermToGoHandler(ForeignDB.AvailableName dbName, List<SecondaryTerm2GoTerm> translationRecords) {
        super(dbName, translationRecords);
    }

    private record GeneKeyword(String geneZdbID, String keyword) {}


    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<SecondaryTermLoadAction> actions, InterproLoadContext context) {

        //create newMarkerGoTermEvidenceLoadActions from new interpro IDs
        log.debug("Creating newMarkerGoTermEvidenceLoadActions from new " + dbName + " IDs");
        List<SecondaryTermLoadAction> newMarkerGoTermEvidenceLoadActions;

        if (!dbName.equals(ForeignDB.AvailableName.UNIPROTKB)) {
            throw new RuntimeException("This handler is only for SPKW");
        }
        newMarkerGoTermEvidenceLoadActions = createMarkerGoTermEvidenceLoadActionsFromUniprotKeywords(uniProtRecords, context, translationRecords);

        log.debug("Created " + newMarkerGoTermEvidenceLoadActions.size() + " newMarkerGoTermEvidenceLoadActions before filtering out existing ones");
        newMarkerGoTermEvidenceLoadActions = filterExistingTerms(newMarkerGoTermEvidenceLoadActions, context);

        log.debug("Remaining: " + newMarkerGoTermEvidenceLoadActions.size() + " newMarkerGoTermEvidenceLoadActions before filtering for obsoletes, etc.");
        List<SecondaryTermLoadAction> filteredMarkerGoTermEvidences = filterTerms(newMarkerGoTermEvidenceLoadActions);

        actions.addAll(filteredMarkerGoTermEvidences);
    }


    public static List<SecondaryTermLoadAction> createMarkerGoTermEvidenceLoadActionsFromUniprotKeywords(
            Map<String, RichSequenceAdapter> uniProtRecords,
            InterproLoadContext context,
            List<SecondaryTerm2GoTerm> translationRecords) {

        List<GeneKeyword> geneKeywords = new ArrayList<>();
        List<SecondaryTermLoadAction> newMarkerGoTermEvidences = new ArrayList<>();

        for(String key : uniProtRecords.keySet()) {
            RichSequenceAdapter record = uniProtRecords.get(key);
            List<String> keywords = record.getPlainKeywords();
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            List<DBLinkSlimDTO> matchingGeneDBLinks = context.getGeneByUniprot(key);
            if (matchingGeneDBLinks == null || matchingGeneDBLinks.isEmpty()) {
                log.debug("No matching gene for " + key + " with " + keywords.size() + " keywords");
                continue;
            }

            //TODO: is there a need to handle multiple genes?
            DBLinkSlimDTO firstMatchingGeneDBLink = matchingGeneDBLinks.stream().findFirst().get();
            for(String keyword : keywords) {
                geneKeywords.add(new GeneKeyword(firstMatchingGeneDBLink.getDataZdbID(), keyword));
            }

        }

        //join the load actions to the interpro/ec/spkw translation records
        List<Tuple2<GeneKeyword, SecondaryTerm2GoTerm>> joined = Seq.seq(geneKeywords)
                .innerJoin(translationRecords,
                        (geneKeyword, item2go) -> geneKeyword.keyword().equals(item2go.dbTermName()))
                .toList();

        for(var joinedRecord : joined) {
            GeneKeyword geneKeyword = joinedRecord.v1();
            SecondaryTerm2GoTerm item2go = joinedRecord.v2();
            SecondaryTermLoadAction newAction = SecondaryTermLoadAction.builder()
                    .accession(geneKeyword.keyword())
                    .dbName(FOREIGN_DB_NAME)
                    .type(SecondaryTermLoadAction.Type.LOAD)
                    .subType(SecondaryTermLoadAction.SubType.MARKER_GO_TERM_EVIDENCE)
                    .geneZdbID(geneKeyword.geneZdbID())
                    .goID(item2go.goID())
                    .goTermZdbID(item2go.termZdbID())
                    .build();
        }

        return newMarkerGoTermEvidences;
    }

    private List<SecondaryTermLoadAction> filterExistingTerms(List<SecondaryTermLoadAction> newMarkerGoTermEvidenceLoadActions, InterproLoadContext context) {
        return newMarkerGoTermEvidenceLoadActions.stream()
                .filter( newAction -> !spKwAlreadyExists(context, newAction))
                .toList();
    }


    private boolean spKwAlreadyExists(InterproLoadContext context, SecondaryTermLoadAction newAction) {
        List<MarkerGoTermEvidence> existingRecords = context.getExistingMarkerGoTermEvidenceRecordsForSPKW();
        String goID = "GO:" + newAction.getGoID();
        String geneZdbID = newAction.getGeneZdbID();
        boolean exists = existingRecords.stream()
                .anyMatch( record -> {
                    return record.getGoTerm().getOboID().equals(goID) && record.getMarker().getZdbID().equals(geneZdbID);
                });
        log.debug("SPKW " + newAction.getAccession() + " for gene " + geneZdbID + " and GO term " + goID + " exists: " + exists);
        return exists;
    }

}