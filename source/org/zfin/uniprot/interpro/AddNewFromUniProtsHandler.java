package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.collections4.CollectionUtils;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.CrossRefAdapter;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
public class AddNewFromUniProtsHandler implements InterproLoadHandler {

    private final ForeignDB.AvailableName dbName;

    public AddNewFromUniProtsHandler(ForeignDB.AvailableName dbName) {
        this.dbName = dbName;
    }

    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, List<SecondaryTermLoadAction> actions, InterproLoadContext context) {
        //if there is an interpro in the load file, but not in the DB for the corresponding gene, add it.
        // corresponding gene means: get the gene by taking the uniprot from the load file and cross referencing it to loaded uniprots

        int previouslyExistedCount = 0;
        int newlyAddedCount = 0;

        for(String uniprot : uniProtRecords.keySet()) {
            List<DBLinkSlimDTO> dbls = context.getGeneByUniprot(uniprot);
            if(CollectionUtils.isEmpty(dbls)) {
                continue;
            }

            String geneID = dbls.get(0).getDataZdbID(); //TODO: should we account for more than 1 gene?

            //at this point, we know that the uniprot is in the load file and has a gene in the DB
            //so we should load any interpros for that gene

            RichSequenceAdapter record = uniProtRecords.get(uniprot);

            for(CrossRefAdapter iplink : record.getCrossRefsByDatabase(dbName.toString())) {
                iplink.getAccession();

                //does it already exist?
                DBLinkSlimDTO interproLink = context.getDbLinkByGeneAndAccession(dbName, geneID, iplink.getAccession());
                boolean alreadyExists = interproLink != null;

                //if not, add it
                if(!alreadyExists) {
                    log.info("Adding " + dbName + " " + iplink.getAccession() + " to gene " + geneID + " in context of " + uniprot + " uniprot");
                    actions.add(SecondaryTermLoadAction.builder().type(SecondaryTermLoadAction.Type.LOAD)
                            .subType(SecondaryTermLoadAction.SubType.DB_LINK)
                            .accession(iplink.getAccession())
                            .dbName(dbName)
                            .geneZdbID(geneID)
                            .build());
                    newlyAddedCount++;
                } else {
                    previouslyExistedCount++;
                }
            }
        }
        log.debug("Previously existed: " + previouslyExistedCount);
        log.debug("Newly added: " + newlyAddedCount);
    }
}
