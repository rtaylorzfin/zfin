package org.zfin.uniprot;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.biojavax.bio.seq.RichSequence;
import org.zfin.uniprot.dto.UniProtContextSequenceDTO;

import java.util.*;

public class MatchOnRefSeqHandler implements UniProtLoadHandler {
    @Override
    public void handle(Map<String, RichSequence> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context) {

        Set<String> accessionsInUniprotLoadFile = uniProtRecords.keySet();

        Set<String> uniprotAccessionsInDB = context.getUniprotDbLinks().keySet();
        System.out.println("uniprotAccessionsInDB: " + uniprotAccessionsInDB.size());

        Set<String> refseqAccessionsInDb = context.getRefseqDbLinks().keySet();
        System.out.println("refseqAccessionsInDb: " + refseqAccessionsInDb.size());

        Set<String> accessionsInDBButNotInLoadFile = new HashSet<>(uniprotAccessionsInDB);
        accessionsInDBButNotInLoadFile.removeAll(accessionsInUniprotLoadFile);

        Set<String> accessionsInLoadFileButNotInDB = new HashSet<>(accessionsInUniprotLoadFile);
        accessionsInLoadFileButNotInDB.removeAll(uniprotAccessionsInDB);

        System.out.println("accessionsInDBButNotInLoadFile: " + accessionsInDBButNotInLoadFile.size());
        System.out.println("accessionsInLoadFileButNotInDB: " + accessionsInLoadFileButNotInDB.size());

        MatchOnRefSeqResults matchResults = new MatchOnRefSeqResults();

        //build up all the cases where we can match on RefSeq
        //build up data structure that links accession to gene(s) with list of matched refseqs
        //something like {"A0A0R4IKB2":[{"ZDB-GENE-030131-5416":["XP_005170963", "XP_005170964"]}, {"ZDB-GENE-030131-5417":["XP..."]}, ...]}
        for (String accession : accessionsInLoadFileButNotInDB) {

            //all refseqs in the load file for this accession
            List<String> refseqs = getRefSeqsFromRichSequence(uniProtRecords.get(accession));

            //find matches to populate inner map
            List<UniProtContextSequenceDTO> matchedAccessions = new ArrayList<>();
            for(String refseq : refseqs) {
                if (refseqAccessionsInDb.contains(refseq)) {
                    List<UniProtContextSequenceDTO> refseqDBLinks = context.getRefseqDbLinks().get(refseq).stream().toList();

                    //add to the inner map
                    for(UniProtContextSequenceDTO dto : refseqDBLinks) {
                        matchResults.put(accession, dto);
                    }
                }
            }
        }

        //now we have a map of all the cases where we can match on RefSeq
        for( Map.Entry<String, MatchOnRefSeqResult> item: matchResults.results.entrySet() ) {
            String uniprotAccession = item.getKey();
            MatchOnRefSeqResult result = item.getValue();

            System.out.println("uniprotAccession: " + uniprotAccession);
            for( Map.Entry<String, MatchOnRefSeqResultSubItem> subItem: result.subItems.entrySet() ) {
                String geneZdbID = subItem.getKey();
                MatchOnRefSeqResultSubItem subResult = subItem.getValue();
                System.out.println("\tgeneZdbID: " + geneZdbID + " (" + subResult.refseqs.get(0).getMarkerAbbreviation() + ")");
                for(UniProtContextSequenceDTO refseq : subResult.refseqs) {
                    System.out.println("\t\trefseq: " + refseq.getAccession());
                }
            }
        }

    }

    private static List<String> getRefSeqsFromRichSequence(RichSequence richSequence) {
        List<String> refseqs = richSequence.getRankedCrossRefs().stream()
                .filter(rc -> rc.getCrossRef().getDbname().equals("RefSeq"))
                .map(rc -> rc.getCrossRef().getAccession())
                .toList();
        return refseqs;
    }

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
