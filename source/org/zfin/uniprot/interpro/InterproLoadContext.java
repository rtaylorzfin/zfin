package org.zfin.uniprot.interpro;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.sequence.ForeignDB;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.ReferenceDatabase;
import org.zfin.sequence.repository.SequenceRepository;
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
    private Map<String, List<DBLinkSlimDTO>> ecDbLinks;
    private Map<String, List<DBLinkSlimDTO>> prositeDbLinks;
    private Map<String, List<DBLinkSlimDTO>> pfamDbLinks;
    private Map<String, List<DBLinkSlimDTO>> uniprotDbLinksByGeneZdbID;

    private List<InterPro2GoTerm> interproTranslationRecords;

    public static InterproLoadContext createFromDBConnection() {
        InterproLoadContext interproLoadContext = new InterproLoadContext();
        SequenceRepository sr = getSequenceRepository();

        log.debug("Interpro Context Step 1: Getting Existing Uniprot DB Links");
        interproLoadContext.setUniprotDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(UNIPROTKB, POLYPEPTIDE, SEQUENCE, ZEBRAFISH))));

        log.debug("Interpro Context Step 2: Getting Existing Interpro DB Links");
        interproLoadContext.setInterproDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(INTERPRO, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Interpro Context Step 3: Getting Existing EC DB Links");
        interproLoadContext.setEcDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(EC, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Interpro Context Step 4: Getting Existing PFAM DB Links");
        interproLoadContext.setPfamDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(PFAM, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Interpro Context Step 5: Getting Existing PROSITE DB Links");
        interproLoadContext.setPrositeDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(PROSITE, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Interpro Context Step 6: Creating Index of Uniprot DB Links by Gene ZDB ID");
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

    public Map<String, List<DBLinkSlimDTO>> getDbLinksByDbName(ForeignDB.AvailableName dbName) {
        switch (dbName) {
            case INTERPRO:
                return getInterproDbLinks();
            case EC:
                return getEcDbLinks();
            case PFAM:
                return getPfamDbLinks();
            case PROSITE:
                return getPrositeDbLinks();
            case UNIPROTKB:
                return getUniprotDbLinks();
            default:
                return null;
        }
    }

    public DBLinkSlimDTO getDbLinkByGeneAndAccession(ForeignDB.AvailableName dbName, String geneID, String accession) {
        List<DBLinkSlimDTO> dblinks = getDbLinksByDbName(dbName).get(accession);
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
