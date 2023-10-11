package org.zfin.uniprot.interpro;

import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.Map;
import java.util.Set;

public interface InterproLoadHandler {
    void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<SecondaryTermLoadAction> actions, InterproLoadContext context);
}
