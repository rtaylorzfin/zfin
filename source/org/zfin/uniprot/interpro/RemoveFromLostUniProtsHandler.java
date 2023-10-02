package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
public class RemoveFromLostUniProtsHandler implements InterproLoadHandler {
    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<InterproLoadAction> actions, InterproLoadContext context) {
        //if there is an interpro in the DB, but not in the load file for the corresponding gene, delete it.
        // corresponding gene means: get the gene by taking the uniprot from the load file and cross referencing it to loaded uniprots (via this load pub?)
        Collection<List<DBLinkSlimDTO>> iplinks = context.getInterproDbLinks().values();
        List<DBLinkSlimDTO> firstValue = iplinks.iterator().next();
        log.debug("firstValue: " + firstValue);
    }
}
