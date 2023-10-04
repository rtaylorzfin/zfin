package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.ReferenceDatabase;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.*;

import static org.zfin.Species.Type.ZEBRAFISH;
import static org.zfin.repository.RepositoryFactory.getSequenceRepository;
import static org.zfin.sequence.ForeignDB.AvailableName.*;
import static org.zfin.sequence.ForeignDBDataType.DataType.DOMAIN;
import static org.zfin.sequence.ForeignDBDataType.DataType.POLYPEPTIDE;
import static org.zfin.sequence.ForeignDBDataType.SuperType.PROTEIN;
import static org.zfin.sequence.ForeignDBDataType.SuperType.SEQUENCE;

/**
 * This class is meant to represent the context in which a load is being performed.
 * Specifically, it is the contents of the database that gets referenced during the load.
 */
@Getter
@Setter
@Log4j2
public class InterproLoadContext {

    private Map<String, List<DBLinkSlimDTO>> uniprotDbLinks;
    private Map<String, List<DBLinkSlimDTO>> interproDbLinks;

    private Map<String, List<DBLinkSlimDTO>> uniprotDbLinksByGeneZdbID;

    public static InterproLoadContext createFromDBConnection() {
        InterproLoadContext interproLoadContext = new InterproLoadContext();

        log.debug("Interpro Context Step 1/3: Getting Existing Uniprot DB Links");
        ReferenceDatabase uniprotRefDB = getSequenceRepository().getReferenceDatabase(UNIPROTKB, POLYPEPTIDE, SEQUENCE, ZEBRAFISH);
        interproLoadContext.setUniprotDbLinks( convertToDTO(getSequenceRepository().getMarkerDBLinks(uniprotRefDB)) );

        log.debug("Interpro Context Step 2/3: Getting Existing Interpro DB Links");
        ReferenceDatabase interproRefDB = getSequenceRepository().getReferenceDatabase(INTERPRO, DOMAIN, PROTEIN, ZEBRAFISH);
        Map<String, Collection<MarkerDBLink>> markerDBLinks = getSequenceRepository().getMarkerDBLinks(interproRefDB);
        interproLoadContext.setInterproDbLinks( convertToDTO(markerDBLinks));

        log.debug("Interpro Context Step 3/3: Creating Index of Uniprot DB Links by Gene ZDB ID");
        interproLoadContext.createUniprotDbLinksByGeneZdbID();

        return interproLoadContext;
    }

    private void createUniprotDbLinksByGeneZdbID() {
        log.debug("Creating uniprotDbLinksByGeneZdbID");
        this.uniprotDbLinks.values().stream().flatMap(Collection::stream).forEach(uniprotDbLink -> {
            if(uniprotDbLink.getDataZdbID() != null) {
                if(uniprotDbLinksByGeneZdbID == null) {
                    uniprotDbLinksByGeneZdbID = new HashMap<>();
                }
                if(!uniprotDbLinksByGeneZdbID.containsKey(uniprotDbLink.getDataZdbID())) {
                    uniprotDbLinksByGeneZdbID.put(uniprotDbLink.getDataZdbID(), new ArrayList<>());
                }
                uniprotDbLinksByGeneZdbID.get(uniprotDbLink.getDataZdbID()).add(uniprotDbLink);
            }
        });
        log.debug("Finished Creating uniprotDbLinksByGeneZdbID");
    }

    private static Map<String, List<DBLinkSlimDTO>> convertToDTO(Map<String, Collection<MarkerDBLink>> markerDBLinks) {
        Map<String, List<DBLinkSlimDTO>> transformedMap = new HashMap<>();
        for(Map.Entry<String, Collection<MarkerDBLink>> entry : markerDBLinks.entrySet()) {
            String key = entry.getKey();
            ArrayList<DBLinkSlimDTO> sequenceDTOs = new ArrayList<>();

            for(MarkerDBLink markerDBLink : entry.getValue()) {
                DBLinkSlimDTO sequenceDTO = new DBLinkSlimDTO();
                sequenceDTO.setAccession(markerDBLink.getAccessionNumber());
                sequenceDTO.setDataZdbID(markerDBLink.getDataZdbID());
                sequenceDTO.setMarkerAbbreviation(markerDBLink.getMarker().getAbbreviation());
                sequenceDTO.setDbName(markerDBLink.getReferenceDatabase().getForeignDB().getDbName().name());
                sequenceDTO.setPublicationIDs( markerDBLink.getPublicationIdsAsList() );
                sequenceDTOs.add(sequenceDTO);
            }
            transformedMap.put(key, sequenceDTOs);
        }
        return transformedMap;
    }

    public DBLinkSlimDTO getUniprotByGene(String dataZdbID) {
        return uniprotDbLinksByGeneZdbID.get(dataZdbID) == null ? null : uniprotDbLinksByGeneZdbID.get(dataZdbID).stream().findFirst().orElse(null);
    }

    public List<DBLinkSlimDTO> getGeneByUniprot(String dataZdbID) {
        return this.uniprotDbLinks.get(dataZdbID);
    }

    public DBLinkSlimDTO getInterproByGene(String geneID, String accession) {
        List<DBLinkSlimDTO> dblinks = interproDbLinks.get(accession);
        if(dblinks == null) {
            return null;
        }
        return dblinks
                .stream()
                .filter(dbLinkSlimDTO -> dbLinkSlimDTO.getDataZdbID().equals(geneID))
                .findFirst()
                .orElse(null);
    }
}
