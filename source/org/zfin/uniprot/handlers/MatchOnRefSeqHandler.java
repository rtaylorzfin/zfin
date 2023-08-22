package org.zfin.uniprot.handlers;

import org.biojavax.bio.seq.RichSequence;
import org.zfin.uniprot.UniProtLoadAction;
import org.zfin.uniprot.UniProtLoadContext;
import org.zfin.uniprot.dto.UniProtContextSequenceDTO;

import java.util.*;
import java.util.stream.Collectors;

public class MatchOnRefSeqHandler implements UniProtLoadHandler {
    @Override
    public void handle(Map<String, RichSequence> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context) {

        Map<String, List<UniProtContextSequenceDTO>> refseqsInDb = context.getRefseqDbLinks();

        MatchOnRefSeqResults matchResults = new MatchOnRefSeqResults();

        //build up all the cases where we can match on RefSeq
        //build up data structure that links accession to gene(s) with list of matched refseqs
        //something like {"A0A0R4IKB2":[{"ZDB-GENE-030131-5416":["XP_005170963", "XP_005170964"]}, {"ZDB-GENE-030131-5417":["XP..."]}, ...]}
        for (String accession : uniProtRecords.keySet()) {

            //all refseqs in the load file for this accession
            List<String> refseqs = getRefSeqsFromRichSequence(uniProtRecords.get(accession));

            List<UniProtContextSequenceDTO> matchedAccessions = new ArrayList<>();
            for(String refseq : refseqs) {
                if (refseqsInDb.containsKey(refseq)) {
                    for(UniProtContextSequenceDTO dto : refseqsInDb.get(refseq)) {
                        matchResults.put(accession, dto);
                    }
                }
            }
        }

        //now we have a map of all the cases where we can match on RefSeq
        //let's flag the cases where we have multiple genes for a single accession
        StringBuilder errorCases = new StringBuilder();
        StringBuilder okayCases = new StringBuilder();
        for( Map.Entry<String, MatchOnRefSeqResult> item: matchResults.results.entrySet() ) {
            String uniprotAccession = item.getKey();
            MatchOnRefSeqResult result = item.getValue();

            if (result.hasMultipleGeneMatches()) {
                System.out.println("uniprotAccession: " + uniprotAccession + " has multiple gene matches");
                UniProtLoadAction action = new UniProtLoadAction();
                action.setAccession(uniprotAccession);
                action.setDetails("has multiple gene matches: " + result.getGeneZdbIDs());
                action.setType(UniProtLoadAction.Type.ERROR);
                actions.add(action);
                errorCases.append(accessionWithMatchingGeneAndRefSeqToString(uniprotAccession, result)).append("\n");
            } else {
                //add the gene to the uniprot record
                UniProtLoadAction action = new UniProtLoadAction();
                action.setAccession(uniprotAccession);
                action.setDetails("has single gene match: " + result.getGeneZdbIDs());
                action.setType(UniProtLoadAction.Type.LOAD);
                actions.add(action);
                okayCases.append(accessionWithMatchingGeneAndRefSeqToString(uniprotAccession, result)).append("\n");
            }
        }
        System.out.println("ERROR CASES:\nMultiple Genes per RefSeq:\n" + errorCases.toString());
        System.out.println("OKAY CASES:\nSingle Gene per RefSeq:\n" + okayCases.toString());

    }

    private static String accessionWithMatchingGeneAndRefSeqToString(String uniprotAccession, MatchOnRefSeqResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("uniprotAccession: " + uniprotAccession + "\n");
        for( Map.Entry<String, MatchOnRefSeqResultSubItem> subItem: result.subItems.entrySet() ) {
            String geneZdbID = subItem.getKey();
            MatchOnRefSeqResultSubItem subResult = subItem.getValue();
            sb.append("\tgeneZdbID: " + geneZdbID + " (" + subResult.refseqs.get(0).getMarkerAbbreviation() + ")\n");
            for(UniProtContextSequenceDTO refseq : subResult.refseqs) {
                sb.append("\t\trefseq: " + refseq.getAccession() + "\n");
            }
        }
        return sb.toString();
    }

    private static List<String> getRefSeqsFromRichSequence(RichSequence richSequence) {
        List<String> refseqs = richSequence.getRankedCrossRefs().stream()
                .filter(rc -> rc.getCrossRef().getDbname().equals("RefSeq"))
                .map(rc -> rc.getCrossRef().getAccession())
                .toList();
        return refseqs;
    }

    /**
     * These 3 classes are used to build up a data structure that links accession to gene(s) with list of matched refseqs
     */
    private class MatchOnRefSeqResults {
        public Map<String, MatchOnRefSeqResult> results = new HashMap<>();
        public void put(String uniprotAccession, UniProtContextSequenceDTO refseq) {
            MatchOnRefSeqResult result = results.get(uniprotAccession);
            if (result == null) {
                result = new MatchOnRefSeqResult(uniprotAccession);
            }
            result.put(refseq);
            results.put(uniprotAccession, result);
        }
    }

    private class MatchOnRefSeqResult {
        public String uniprotAccession;
        public Map<String, MatchOnRefSeqResultSubItem> subItems;

        public MatchOnRefSeqResult(String uniprotAccession) {
            this.uniprotAccession = uniprotAccession;
            this.subItems = new HashMap<>();
        }

        public void put(UniProtContextSequenceDTO refseq) {
            MatchOnRefSeqResultSubItem subItem = subItems.get(refseq.getDataZdbID());
            if (subItem == null) {
                subItem = new MatchOnRefSeqResultSubItem(refseq.getDataZdbID());
            }
            subItem.add(refseq);
            subItems.put(refseq.getDataZdbID(), subItem);
        }

        public boolean hasMultipleGeneMatches() {
            return subItems.size() > 1;
        }

        public String getGeneZdbIDs() {
            return subItems.keySet().stream().collect(Collectors.joining(", "));
        }
    }

    private class MatchOnRefSeqResultSubItem {
        public String geneZdbID;
        public List<UniProtContextSequenceDTO> refseqs;

        public MatchOnRefSeqResultSubItem(String dataZdbID) {
            this.geneZdbID = dataZdbID;
            this.refseqs = new ArrayList<>();
        }

        public void add(UniProtContextSequenceDTO refseq) {
            if (!contains(refseq)) {
                refseqs.add(refseq);
            }
        }

        public boolean contains(UniProtContextSequenceDTO refseq) {
            return refseqs.stream().anyMatch(r -> r.getAccession().equals(refseq.getAccession()));
        }
    }
}
