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
        List<DBLinkSlimDTO> iplinks = context.getInterproDbLinks().values().stream().flatMap(Collection::stream).toList();

        for(DBLinkSlimDTO iplink : iplinks) {
            DBLinkSlimDTO uniprot = context.getUniprotByGene(iplink.getDataZdbID());
            if(uniprot == null) {
                log.info("Removing interpro " + iplink.getAccession() + " from gene " + iplink.getDataZdbID() + " lost uniprots");
                actions.add(InterproLoadAction.builder().type(InterproLoadAction.Type.DELETE)
                        .subType(InterproLoadAction.SubType.PLACEHOLDER1)
                        .accession(iplink.getAccession())
                        .geneZdbID(iplink.getDataZdbID())
                        .build());
            }
        }
    }
}
