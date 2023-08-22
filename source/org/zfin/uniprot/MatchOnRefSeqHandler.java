package org.zfin.uniprot;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.collections4.keyvalue.MultiKey;
import org.biojavax.RankedCrossRef;
import org.biojavax.bio.seq.RichSequence;
import org.zfin.uniprot.dto.UniProtContextSequenceDTO;

import java.util.*;

public class MatchOnRefSeqHandler implements UniProtLoadHandler {
    @Override
    public void handle(Map<String, RichSequence> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context) {

        Set<String> accessionsInUniprotLoadFile = uniProtRecords.keySet();

        Set<String> uniprotAccessionsInDB = context.getUniprotDbLinks().keySet();
        System.out.println("uniprotAccessionsInDB: " + uniprotAccessionsInDB.size());

        Set<String> refseqCombinedDbLinksAccessionsInDb = context.getRefseqDbLinks().keySet();
        System.out.println("refseqCombinedDbLinksAccessionsInDb: " + refseqCombinedDbLinksAccessionsInDb.size());

        Set<String> accessionsInDBButNotInLoadFile = new HashSet<>(uniprotAccessionsInDB);
        accessionsInDBButNotInLoadFile.removeAll(accessionsInUniprotLoadFile);

        Set<String> accessionsInLoadFileButNotInDB = new HashSet<>(accessionsInUniprotLoadFile);
        accessionsInLoadFileButNotInDB.removeAll(uniprotAccessionsInDB);

        System.out.println("accessionsInDBButNotInLoadFile: " + accessionsInDBButNotInLoadFile.size());
        System.out.println("accessionsInLoadFileButNotInDB: " + accessionsInLoadFileButNotInDB.size());

        MultiValuedMap<String, List<UniProtContextSequenceDTO>> matchedRefSeqs = new ArrayListValuedHashMap<>();

        //build up all the cases where we can match on RefSeq
        for (String accession : accessionsInLoadFileButNotInDB) {
            //find matching RefSeq accession
            RichSequence loadFileSequence = uniProtRecords.get(accession);
            List<String> refseqs = loadFileSequence.getRankedCrossRefs().stream()
                    .filter(rc -> rc.getCrossRef().getDbname().equals("RefSeq"))
                    .map(rc -> rc.getCrossRef().getAccession())
                    .toList();

            List<UniProtContextSequenceDTO> matchedAccessions = new ArrayList<>();
            for(String refseq : refseqs) {
                if (refseqCombinedDbLinksAccessionsInDb.contains(refseq)) {
                    List<UniProtContextSequenceDTO> refseqDBLinks = context.getRefseqDbLinks().get(refseq).stream().toList();
                    matchedRefSeqs.put(accession + "," + refseq, refseqDBLinks);

                    String tempDbLinks = String.join(";", refseqDBLinks.stream().map(dto -> "\t" + dto.getDataZdbID() + " : " + dto.getMarkerAbbreviation()).toList());
                    System.out.println("accession,refseq,linksize: " + accession + "," + refseq + "," + refseqDBLinks.size() + "" + tempDbLinks);
                }
            }
        }

        //now we have a map of all the cases where we can match on RefSeq
        Collection<Map.Entry<String, List<UniProtContextSequenceDTO>>> matches = matchedRefSeqs.entries();
        for(Map.Entry<String, List<UniProtContextSequenceDTO>> item : matches) {
            String accessionRefSeq = item.getKey();
            List<UniProtContextSequenceDTO> refseqDBLinks = item.getValue();
            System.out.println("accession,refseq,linksize: " + accessionRefSeq + "," + refseqDBLinks.size());
            refseqDBLinks.stream().map(dto -> "\t" + dto.getDataZdbID() + " : " + dto.getMarkerAbbreviation()).forEach(System.out::println);
        }

    }
}
