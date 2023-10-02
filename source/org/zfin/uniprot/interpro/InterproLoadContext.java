package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.ReferenceDatabase;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.*;

import static org.zfin.Species.Type.ZEBRAFISH;
import static org.zfin.repository.RepositoryFactory.getSequenceRepository;
import static org.zfin.sequence.ForeignDB.AvailableName.*;
import static org.zfin.sequence.ForeignDBDataType.DataType.POLYPEPTIDE;
import static org.zfin.sequence.ForeignDBDataType.SuperType.SEQUENCE;

/**
 * This class is meant to represent the context in which a load is being performed.
 * Specifically, it is the contents of the database that gets referenced during the load.
 */
@Getter
@Setter
public class InterproLoadContext {

    private Map<String, List<DBLinkSlimDTO>> uniprotDbLinks;
    private Map<String, List<DBLinkSlimDTO>> interproDbLinks;

    public static InterproLoadContext createFromDBConnection() {
        InterproLoadContext interproLoadContext = new InterproLoadContext();

        ReferenceDatabase uniprotRefDB = getSequenceRepository().getReferenceDatabase(UNIPROTKB, POLYPEPTIDE, SEQUENCE, ZEBRAFISH);
        interproLoadContext.setUniprotDbLinks( convertToDTO(getSequenceRepository().getMarkerDBLinks(uniprotRefDB)) );

        ReferenceDatabase interproRefDB = getSequenceRepository().getReferenceDatabase(INTERPRO, POLYPEPTIDE, SEQUENCE, ZEBRAFISH);

        Map<String, Collection<MarkerDBLink>> markerDBLinks = getSequenceRepository().getMarkerDBLinks(interproRefDB);
        interproLoadContext.setInterproDbLinks( convertToDTO(markerDBLinks));

        return interproLoadContext;
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
}
