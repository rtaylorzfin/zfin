package org.zfin.uniprot.interpro;

import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.Map;
import java.util.List;

public interface InterproLoadHandler {
    void handle(Map<String, RichSequenceAdapter> uniProtRecords, List<SecondaryTermLoadAction> actions, InterproLoadContext context);
}
