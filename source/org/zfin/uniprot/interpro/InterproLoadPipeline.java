package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import org.zfin.uniprot.UniProtLoadAction;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.handlers.UniProtLoadHandler;

import java.util.*;

@Getter
@Setter
public class InterproLoadPipeline {
    private List<InterproLoadHandler> handlers = new ArrayList<>();
    private Set<InterproLoadAction> actions = new TreeSet<>();
    private InterproLoadContext context;

    private Map<String, RichSequenceAdapter> interproRecords;

    public void addHandler(InterproLoadHandler handler) {
        handlers.add(handler);
    }

    public Set<InterproLoadAction> execute() {
        for (InterproLoadHandler handler : handlers) {
            handler.handle(interproRecords, actions, context);
        }
        return actions;
    }

}
