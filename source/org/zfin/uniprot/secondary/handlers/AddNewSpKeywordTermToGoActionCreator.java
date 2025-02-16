package org.zfin.uniprot.secondary.handlers;

import lombok.extern.log4j.Log4j2;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.datfiles.UniprotReleaseRecords;
import org.zfin.uniprot.dto.DBLinkSlimDTO;
import org.zfin.uniprot.dto.MarkerGoTermEvidenceSlimDTO;
import org.zfin.uniprot.secondary.SecondaryLoadContext;
import org.zfin.uniprot.secondary.SecondaryTerm2GoTerm;
import org.zfin.uniprot.secondary.SecondaryTermLoadAction;

import java.util.ArrayList;
import java.util.List;

import static org.zfin.util.ZfinCollectionUtils.uniqueBy;

/**
 * Adds new SPKW terms to marker_go_term_evidence table.
 * Special case of AddNewSecondaryTermToGoHandler.
 */
@Log4j2
public class AddNewSpKeywordTermToGoActionCreator extends MarkerGoTermEvidenceActionCreator {
    private static final ForeignDB.AvailableName FOREIGN_DB_NAME = ForeignDB.AvailableName.UNIPROTKB;

    public AddNewSpKeywordTermToGoActionCreator(ForeignDB.AvailableName dbName, List<SecondaryTerm2GoTerm> translationRecords) {
        super(dbName, translationRecords);
    }

    private record GeneKeyword(String geneZdbID, String keyword) {}


    @Override
    public List<SecondaryTermLoadAction> createActions(UniprotReleaseRecords uniProtRecords, List<SecondaryTermLoadAction> actions, SecondaryLoadContext context) {
        //create newMarkerGoTermEvidenceLoadActions from new interpro IDs
        log.info("Creating newMarkerGoTermEvidenceLoadActions from new " + dbName + " IDs");
        List<SecondaryTermLoadAction> newMarkerGoTermEvidenceLoadActions;

        if (!dbName.equals(ForeignDB.AvailableName.UNIPROTKB)) {
            throw new RuntimeException("This handler is only for SPKW");
        }
        newMarkerGoTermEvidenceLoadActions = createMarkerGoTermEvidenceLoadActionsFromUniprotKeywords(uniProtRecords, context, translationRecords);

        log.info("Created " + newMarkerGoTermEvidenceLoadActions.size() + " newMarkerGoTermEvidenceLoadActions before filtering out existing ones");
        newMarkerGoTermEvidenceLoadActions = filterExistingTerms(newMarkerGoTermEvidenceLoadActions, context);

        log.info("Remaining: " + newMarkerGoTermEvidenceLoadActions.size() + " newMarkerGoTermEvidenceLoadActions before filtering for obsoletes, etc.");

        return filterTerms(newMarkerGoTermEvidenceLoadActions);
    }


    public static List<SecondaryTermLoadAction> createMarkerGoTermEvidenceLoadActionsFromUniprotKeywords(
            UniprotReleaseRecords uniProtRecords,
            SecondaryLoadContext context,
            List<SecondaryTerm2GoTerm> translationRecords) {

        List<GeneKeyword> geneKeywords = new ArrayList<>();
        List<SecondaryTermLoadAction> newMarkerGoTermEvidences = new ArrayList<>();

        //iterate over all the uniprot records in the current uniprot load file and
        // find the gene that matches according to our DB (we've already loaded it)
        // gather all the keywords from the uniprot record and create new MarkerGoTermEvidenceLoadAction
        // for each one. Equivalent to generating the kd_spkeywd.unl file in our old load ("GENE|Keyword")
        int unmatchedGeneCount = 0;
        for(String key : uniProtRecords.getAccessions()) {
            RichSequenceAdapter record = uniProtRecords.getByAccession(key);
            List<String> keywords = record.getPlainKeywords();
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            List<DBLinkSlimDTO> matchingGeneDBLinks = context.getGenesByUniprot(key);
            if (matchingGeneDBLinks == null || matchingGeneDBLinks.isEmpty()) {
//                log.info("No matching gene for " + key + " with " + keywords.size() + " keywords");
                unmatchedGeneCount++;
                continue;
            }

            //TODO: is there a need to handle multiple genes?
            DBLinkSlimDTO firstMatchingGeneDBLink = matchingGeneDBLinks.stream().findFirst().get();
            for(String keyword : keywords) {
                geneKeywords.add(new GeneKeyword(firstMatchingGeneDBLink.getDataZdbID(), keyword));
            }
        }
        log.info("Found " + geneKeywords.size() + " keywords for " + uniProtRecords.size() + " uniprot records. " + unmatchedGeneCount + " uniprot records had no matching gene");

        //join the resulting keywords from above to the translation records to get GO terms
        List<Tuple2<GeneKeyword, SecondaryTerm2GoTerm>> joined = Seq.seq(geneKeywords)
                .innerJoin(translationRecords,
                        (geneKeyword, item2go) -> geneKeyword.keyword().equals(item2go.dbTermName()))
                .toList();

        //create new MarkerGoTermEvidenceLoadAction for each joined record
        for(var joinedRecord : joined) {
            GeneKeyword geneKeyword = joinedRecord.v1();
            SecondaryTerm2GoTerm item2go = joinedRecord.v2();
            MarkerGoTermEvidenceSlimDTO markerGoTermEvidenceSlimDTO = MarkerGoTermEvidenceSlimDTO.builder()
                    .markerZdbID(geneKeyword.geneZdbID)
                    .publicationID(SPKW_MRKRGOEV_PUBLICATION_ATTRIBUTION_ID)
                    .goTermZdbID(item2go.termZdbID())
                    .goID(item2go.goID())
                    .inferredFrom("UniProtKB-KW:" + item2go.dbAccession())
                    .build();

            SecondaryTermLoadAction newAction = SecondaryTermLoadAction.builder()
                    .accession(geneKeyword.keyword())
                    .dbName(FOREIGN_DB_NAME)
                    .type(SecondaryTermLoadAction.Type.LOAD)
                    .subType(SecondaryTermLoadAction.SubType.MARKER_GO_TERM_EVIDENCE)
                    .geneZdbID(geneKeyword.geneZdbID())
                    .relatedEntityFields(markerGoTermEvidenceSlimDTO.toMap())
                    .build();
            newMarkerGoTermEvidences.add(newAction);
        }

        //only return a unique set of actions
        return uniqueBy(newMarkerGoTermEvidences, SecondaryTermLoadAction::getMd5);
    }

    private List<SecondaryTermLoadAction> filterExistingTerms(List<SecondaryTermLoadAction> newMarkerGoTermEvidenceLoadActions, SecondaryLoadContext context) {
        return newMarkerGoTermEvidenceLoadActions.stream()
                .filter( newAction -> !spKwAlreadyExists(context, newAction))
                .toList();
    }


    private boolean spKwAlreadyExists(SecondaryLoadContext context, SecondaryTermLoadAction newAction) {
        List<MarkerGoTermEvidenceSlimDTO> existingRecords = context.getExistingMarkerGoTermEvidenceRecords();
        String goID = newAction.getGoID();
        String geneZdbID = newAction.getGeneZdbID();
        String inferredFrom = MarkerGoTermEvidenceSlimDTO.fromMap(newAction.getRelatedEntityFields()).getInferredFrom();
        return existingRecords
                .stream()
                .anyMatch( record -> record.getGoID().equals(goID) &&
                        record.getMarkerZdbID().equals(geneZdbID) &&
                        record.getInferredFrom().equals(inferredFrom));
    }

}
