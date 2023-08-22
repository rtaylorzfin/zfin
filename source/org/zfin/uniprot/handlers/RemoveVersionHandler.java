package org.zfin.uniprot.handlers;

import org.biojavax.CrossRef;
import org.biojavax.RankedCrossRef;
import org.biojavax.SimpleCrossRef;
import org.biojavax.SimpleRankedCrossRef;
import org.biojavax.bio.seq.RichSequence;
import org.zfin.uniprot.UniProtLoadAction;
import org.zfin.uniprot.UniProtLoadContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class RemoveVersionHandler implements UniProtLoadHandler {
    @Override
    public void handle(Map<String, RichSequence> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context) {
        for (String acc : uniProtRecords.keySet()) {
            //find matching RefSeq accession
            RichSequence loadFileSequence = uniProtRecords.get(acc);

            //transform the RefSeq accessions to omit the version number
            Set<RankedCrossRef> rankedXrefs = loadFileSequence.getRankedCrossRefs();
            Set<RankedCrossRef> transformedXrefs = new TreeSet<>();

            for (RankedCrossRef rankedXref : rankedXrefs) {
                if (rankedXref.getCrossRef().getDbname().equals("RefSeq")) {
                    CrossRef xref = rankedXref.getCrossRef();
                    String refSeqAccession = xref.getAccession();
                    String refSeqAccessionWithoutVersion = refSeqAccession.replaceAll("\\.\\d+$", "");
                    CrossRef newXref = new SimpleCrossRef(xref.getDbname(), refSeqAccessionWithoutVersion, 0);
                    RankedCrossRef newRankedXref = new SimpleRankedCrossRef(newXref, rankedXref.getRank());
                    transformedXrefs.add(newRankedXref);
                }
            }
            loadFileSequence.setRankedCrossRefs(transformedXrefs);

            uniProtRecords.put(acc, loadFileSequence);
        }
    }
}
