package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
public class AddNewExternalNotesHandler implements InterproLoadHandler {

    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, List<SecondaryTermLoadAction> actions, InterproLoadContext context) {
        List<SecondaryTermLoadAction> secondaryTermLoadActions = new ArrayList<>();
        Set<String> uniprotAccessions = uniProtRecords.keySet();
        List<String> unmatchedUniprots = new ArrayList<>();
        for(String uniprot : uniprotAccessions) {
            RichSequenceAdapter record = uniProtRecords.get(uniprot);
            List<String> comments = record.getComments();
            if (comments == null || comments.isEmpty()) {
                continue;
            }
            String combinedComment = String.join("<br>", comments)
                                            .replaceAll("\\n    ", " ");
            if (StringUtils.isEmpty(combinedComment)) {
                continue;
            }

            List<DBLinkSlimDTO> genesMatchingUniprot = context.getGeneByUniprot(uniprot);
            if(genesMatchingUniprot == null || genesMatchingUniprot.isEmpty()) {
                unmatchedUniprots.add(uniprot);
                continue;
            }
            String firstGeneZdbID = genesMatchingUniprot.get(0).getDataZdbID();

            //TODO: handle multiple gene matches?
            SecondaryTermLoadAction action = SecondaryTermLoadAction.builder()
                    .geneZdbID(firstGeneZdbID)
                    .accession(uniprot)
                    .details(combinedComment)
                    .type(SecondaryTermLoadAction.Type.LOAD)
                    .subType(SecondaryTermLoadAction.SubType.EXTERNAL_NOTE)
                    .build();
            secondaryTermLoadActions.add(action);
        }

        //batch log unmatched uniprots
        if(!unmatchedUniprots.isEmpty()) {
            log.info("Unmatched uniprots (" + unmatchedUniprots.size() + "): " );

            //break up into chunks of 100
            ListUtils.partition(unmatchedUniprots, 100)
                    .forEach(chunk -> log.info(String.join(", ", chunk)));
        }


        actions.addAll(secondaryTermLoadActions);
    }

}
