package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.*;

@Getter
@Setter
@Log4j2
public class InterproLoadPipeline {
    private List<InterproLoadHandler> handlers = new ArrayList<>();
    private Set<SecondaryTermLoadAction> actions = new TreeSet<>();
    private InterproLoadContext context;

    private Map<String, RichSequenceAdapter> interproRecords;

    public void addHandler(InterproLoadHandler handler) {
        handlers.add(handler);
    }

    public Set<SecondaryTermLoadAction> execute() {
        int i = 1;
        int actionCount = 0;
        int previousActionCount = 0;

        for (InterproLoadHandler handler : handlers) {
            String handlerClassName = handler.getClass().getName();
            log.debug("Starting handler " + i + " of " + handlers.size() + " (" + handlerClassName + ")");
            handler.handle(interproRecords, actions, context);
            actionCount = actions.size();
            log.debug("Finished handler " + i + " of " + handlers.size() + " (" + handlerClassName + ")");

            if (actionCount == previousActionCount) {
                log.debug("No new actions were created by this handler");
            } else {
                log.debug("This handler created " + (actionCount - previousActionCount) + " new actions");
            }
            previousActionCount = actionCount;
            i++;
        }
        return actions;
    }

}
