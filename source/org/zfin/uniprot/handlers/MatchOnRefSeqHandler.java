package org.zfin.uniprot.handlers;

import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.UniProtLoadAction;
import org.zfin.uniprot.UniProtLoadContext;
import org.zfin.uniprot.UniProtLoadLink;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.*;
import java.util.stream.Collectors;

import static org.zfin.uniprot.UniProtTools.isAnyGeneAccessionRelationshipSupportedByNonLoadPublication;


public class MatchOnRefSeqHandler implements UniProtLoadHandler {

    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context) {

        Map<String, List<DBLinkSlimDTO>> refseqsInDb = context.getRefseqDbLinks();

        MatchOnRefSeqResults matchResults = new MatchOnRefSeqResults();

        //build up all the cases where we can match on RefSeq
        //build up data structure that links accession to gene(s) with list of matched refseqs
        //something like {"A0A0R4IKB2":[{"ZDB-GENE-030131-5416":["XP_005170963", "XP_005170964"]}, {"ZDB-GENE-030131-5417":["XP..."]}, ...]}
        for (String accession : uniProtRecords.keySet()) {

            //all refseqs in the load file for this accession
            Set<String> refseqs = uniProtRecords.get(accession).getRefSeqs();

            for(String refseq : refseqs) {
                if (refseqsInDb.containsKey(refseq)) {
                    for(DBLinkSlimDTO dto : refseqsInDb.get(refseq)) {
                        matchResults.put(accession, dto);
                    }
                }
            }
        }

        //now we have a map of all the cases where we can match on RefSeq
        //let's flag the cases where we have multiple genes for a single accession
        for( Map.Entry<String, MatchOnRefSeqResult> item: matchResults.results.entrySet() ) {
            String uniprotAccession = item.getKey();
            MatchOnRefSeqResult result = item.getValue();
            String details = accessionWithMatchingGeneAndRefSeqToString(uniprotAccession, result);

            UniProtLoadAction action = new UniProtLoadAction();
            action.setAccession(uniprotAccession);
            action.setDetails(details);
            setActionLinks(action, result);
            actions.add(action);

            if (result.hasMultipleGeneMatches()) {
                if (isAnyGeneAccessionRelationshipSupportedByNonLoadPublication(result.uniprotAccession, result.getGeneZdbIDs())) {
                    action.setTitle(UniProtLoadAction.MatchTitle.MULTIPLE_GENES_PER_ACCESSION_BUT_APPROVED.getValue());
                    action.setType(UniProtLoadAction.Type.WARNING);
                    action.setDetails("This UniProt accession has multiple genes associated with it, but at least one of the gene associations is supported by a non-load publication.\n\n" + details);
                } else {
                    action.setTitle(UniProtLoadAction.MatchTitle.MULTIPLE_GENES_PER_ACCESSION.getValue());
                    action.setType(UniProtLoadAction.Type.ERROR);
                }
            } else {
                action.setTitle(UniProtLoadAction.MatchTitle.MATCH_BY_REFSEQ.getValue());
                action.setType(UniProtLoadAction.Type.LOAD);
                action.setGeneZdbID(result.getGeneZdbIDs().get(0));
            }

        }
    }

    private void setActionLinks(UniProtLoadAction action, MatchOnRefSeqResult result) {
        List<UniProtLoadLink> links = new ArrayList<>();
        links.add(new UniProtLoadLink("UniProtKB: " + result.uniprotAccession, "https://www.uniprot.org/uniprot/" + result.uniprotAccession));

        for (String refseq: result.refSeqAccessions()) {
            links.add(new UniProtLoadLink("RefSeq: " + refseq, "https://www.ncbi.nlm.nih.gov/protein/" + refseq));
        }

        for (String geneZdbID: result.getGeneZdbIDs()) {
            links.add(new UniProtLoadLink("ZFIN: " + geneZdbID, "https://zfin.org/" + geneZdbID));
        }
        action.addLinks(links);
    }

    private static String accessionWithMatchingGeneAndRefSeqToString(String uniprotAccession, MatchOnRefSeqResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("UniProt Accession: ").append(uniprotAccession).append("\n");
        sb.append("==========================\n");
        for( Map.Entry<String, MatchOnRefSeqResultSubItem> subItem: result.subItems.entrySet() ) {
            String geneZdbID = subItem.getKey();
            MatchOnRefSeqResultSubItem subResult = subItem.getValue();
            sb.append("Matched ZFIN Gene ID: " + geneZdbID + " (" + subResult.refseqs.get(0).getMarkerAbbreviation() + ")\n");
            sb.append("Based on the RefSeq(s): ");
            sb.append(String.join(", ", subResult.refSeqAccessions()));
            sb.append("\n");
        }
        return sb.toString();
    }


    /**
     * These 3 classes are used to build up a data structure that links accession to gene(s) with list of matched refseqs
     * something like {"A0A0R4IKB2":[{"ZDB-GENE-030131-5416":["XP_005170963", "XP_005170964"]}, {"ZDB-GENE-030131-5417":["XP..."]}, ...]}
     */
    private class MatchOnRefSeqResults {
        public Map<String, MatchOnRefSeqResult> results = new HashMap<>();
        public void put(String uniprotAccession, DBLinkSlimDTO refseq) {
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

        public void put(DBLinkSlimDTO refseq) {
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

        public List<String> getGeneZdbIDs() {
            return subItems.keySet().stream().toList();
        }

        public List<String> refSeqAccessions() {
            return subItems.values().stream().flatMap(s -> s.refSeqAccessions().stream()).collect(Collectors.toList());
        }
    }

    private class MatchOnRefSeqResultSubItem {
        public String geneZdbID;
        public List<DBLinkSlimDTO> refseqs;

        public MatchOnRefSeqResultSubItem(String dataZdbID) {
            this.geneZdbID = dataZdbID;
            this.refseqs = new ArrayList<>();
        }

        public void add(DBLinkSlimDTO refseq) {
            if (!contains(refseq)) {
                refseqs.add(refseq);
            }
        }

        public boolean contains(DBLinkSlimDTO refseq) {
            return refseqs.stream().anyMatch(r -> r.getAccession().equals(refseq.getAccession()));
        }

        public List<String> refSeqAccessions() {
            return refseqs.stream().map(DBLinkSlimDTO::getAccession).toList();
        }
    }
}