package org.zfin.uniprot;

import org.biojavax.bio.seq.RichSequence;

import java.util.List;
import java.util.Map;

public interface UniProtLoadHandler {
    void handle(Map<String, RichSequence> uniProtRecords, List<UniProtLoadAction> actions, UniProtLoadContext context);
}
