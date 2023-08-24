package org.zfin.uniprot.handlers;

import org.biojavax.bio.seq.RichSequence;
import org.zfin.infrastructure.RecordAttribution;
import org.zfin.sequence.DBLink;
import org.zfin.uniprot.UniProtLoadAction;
import org.zfin.uniprot.UniProtLoadContext;
import org.zfin.uniprot.UniProtLoadLink;
import org.zfin.uniprot.UniProtTools;
import org.zfin.uniprot.dto.UniProtContextSequenceDTO;

import java.util.*;

import static org.zfin.repository.RepositoryFactory.*;

/**
 *
 */
public class ReportLostUniProtsHandler implements UniProtLoadHandler {

    public static final String MANUAL_CURATION_OF_PROTEIN_IDS = "ZDB-PUB-170131-9";
    public static final String UNIPROT_ID_LOAD_FROM_ENSEMBL = "ZDB-PUB-170502-16";
    public static final String MANUAL_CURATION_OF_UNIPROT_IDS = "ZDB-PUB-220705-2";

    @Override
    public void handle(Map<String, RichSequence> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context) {
        //actions should contain all cases where we have a match based on RefSeq
        List<UniProtLoadAction> actionsMatchedOnRefSeq = actions.stream().filter(action -> action.getTitle().equals(UniProtLoadAction.MatchTitle.MATCH_BY_REFSEQ.toString())).toList();

        System.out.println("ReportLostUniProtsHandler.handle. Count of actions: " + actions.size());
        System.out.println("ReportLostUniProtsHandler.handle. Filtered count of actions: " + actionsMatchedOnRefSeq.size());

//        //for each action, get the accession
//        //if the accession is not in the uniProtRecords, then we have a lost UniProt
//        for(UniProtLoadAction action : actionsMatchedOnRefSeq) {
//            String accession = action.getAccession();
//
//            Map<String, List<UniProtContextSequenceDTO>> tmp = context.getUniprotDbLinks();
//            if (!context.getUniprotDbLinks().containsKey(accession)) {
//                action.setTitle(UniProtLoadAction.MatchTitle.LOST_UNIPROT.toString());
//                action.setType(UniProtLoadAction.Type.ERROR);
//            }
//        }
//
//
//        for(String accession : context.getUniprotDbLinks().keySet() ) {
//            List<UniProtContextSequenceDTO> ge = context.getUniprotDbLinks().get(accession);
//            ge.stream().map(dto -> dto.getDataZdbID());
//        }

        //all genes with existing uniprot associations
        List<UniProtContextSequenceDTO> sequencesForGenesWithExistingUniprotAssociations = context.getUniprotDbLinks()
                .values()
                .stream()
                .flatMap(List::stream)
                .toList();


        //all genes that get matched based on RefSeqs in load file
        List<String> genesWithMatchesInLoad = actionsMatchedOnRefSeq.stream().map(UniProtLoadAction::getGeneZdbID).toList();

        //no duplicate printouts
        Set<String> alreadyEncounteredThisGeneID = new HashSet<>();

        //build up a list of genes that have existing uniprot associations but are not matched by RefSeq in load file
        List<UniProtContextSequenceDTO> lostUniProts = new ArrayList<>();
        for(UniProtContextSequenceDTO sequenceDTO : sequencesForGenesWithExistingUniprotAssociations) {
            if (!genesWithMatchesInLoad.contains(sequenceDTO.getDataZdbID())) {

                //no duplicates
                if (alreadyEncounteredThisGeneID.contains(sequenceDTO.getDataZdbID())) {
                    continue;
                }
//                System.out.println("LOST UNIPROT: " + sequenceDTO.getDataZdbID() + " " + sequenceDTO.getMarkerAbbreviation() );
                lostUniProts.add(sequenceDTO);
                alreadyEncounteredThisGeneID.add(sequenceDTO.getDataZdbID());
//                UniProtLoadAction action = new UniProtLoadAction();
//                action.setGeneZdbID(sequenceDTO.getDataZdbID());
//                action.setTitle(UniProtLoadAction.MatchTitle.LOST_UNIPROT.toString());
//                action.setType(UniProtLoadAction.Type.ERROR);
//                action.setAccession(sequenceDTO.getAccession());
//                action.setDetails("This gene currently has a UniProt association, but when we run the latest UniProt release through our matching pipeline, we don't find a match.\n" +
//                        "Perhaps this UniProt accession should be removed?");
//                setActionLinks(action, sequenceDTO);
//                actions.add(action);
            }
        }

        //do some filtering based on attributions for lost UniProts
        List<UniProtContextSequenceDTO> filteredLostUniProts = new ArrayList<>();
        for(UniProtContextSequenceDTO lostUniProt: lostUniProts) {
            String geneID = lostUniProt.getDataZdbID();
            String accession = lostUniProt.getAccession();
            DBLink dblink = getSequenceRepository().getDBLink(geneID, accession);
            if (dblink != null) {
                List<RecordAttribution> attributions = getInfrastructureRepository().getRecordAttributions(dblink.getZdbID());
                List<String> attributionPubIDs = attributions.stream().map(attribution -> attribution.getSourceZdbID()).toList();

                if (attributionPubIDs.contains(MANUAL_CURATION_OF_PROTEIN_IDS) ||
                        attributionPubIDs.contains(UNIPROT_ID_LOAD_FROM_ENSEMBL) ||
                        attributionPubIDs.contains(MANUAL_CURATION_OF_UNIPROT_IDS)) {
                    continue;
                }
                filteredLostUniProts.add(lostUniProt);
            } else {
                filteredLostUniProts.add(lostUniProt);
            }
        }

        //create actions for lost UniProts
        for(var lostUniProt: filteredLostUniProts) {
            String sequenceDetails = "Sequence details: \n=================\n";RichSequence richSequence = uniProtRecords.get(lostUniProt.getAccession());
            if (richSequence != null) {
                sequenceDetails += UniProtTools.sequenceToString(richSequence);
            } else {
                sequenceDetails += "No sequence details found for " + lostUniProt.getAccession();
            }

            UniProtLoadAction action = new UniProtLoadAction();
            action.setGeneZdbID(lostUniProt.getDataZdbID());
            action.setTitle(UniProtLoadAction.MatchTitle.LOST_UNIPROT.toString());
            action.setType(UniProtLoadAction.Type.ERROR);
            action.setAccession(lostUniProt.getAccession());
            action.setDetails("This gene currently has a UniProt association, but when we run the latest UniProt release through our matching pipeline, we don't find a match.\n" +
                    "Perhaps this UniProt accession should be removed?\n\n" + sequenceDetails);
            setActionLinks(action, lostUniProt);
            actions.add(action);
        }

    }

    private void setActionLinks(UniProtLoadAction action, UniProtContextSequenceDTO sequenceDTO) {
        action.addLink(new UniProtLoadLink("ZFIN: " + sequenceDTO.getDataZdbID(), "http://zfin.org/" + sequenceDTO.getDataZdbID()));
        action.addLink(new UniProtLoadLink("UniProt: " + sequenceDTO.getAccession(), "https://www.uniprot.org/uniprot/" + sequenceDTO.getAccession()));
    }
}
