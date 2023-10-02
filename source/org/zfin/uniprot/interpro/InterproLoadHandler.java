package org.zfin.uniprot.interpro;

import org.zfin.uniprot.UniProtLoadAction;
import org.zfin.uniprot.UniProtLoadContext;
import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.Map;
import java.util.Set;

public interface InterproLoadHandler {
    void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<InterproLoadAction> actions, InterproLoadContext context);
}
