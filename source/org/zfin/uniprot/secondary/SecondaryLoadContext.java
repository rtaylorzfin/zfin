package org.zfin.uniprot.secondary;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.zfin.mutant.MarkerGoTermEvidence;
import org.zfin.sequence.DBLinkExternalNote;
import org.zfin.sequence.ForeignDB;
import org.zfin.sequence.MarkerDBLink;
import org.zfin.sequence.repository.SequenceRepository;
import org.zfin.uniprot.dto.DBLinkExternalNoteSlimDTO;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.util.*;

import static org.zfin.Species.Type.ZEBRAFISH;
import static org.zfin.repository.RepositoryFactory.*;
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
public class SecondaryLoadContext {
    private static final String SPKW_PUB_ID = "ZDB-PUB-020723-1";

    private Map<String, List<DBLinkSlimDTO>> uniprotDbLinks;
    private Map<String, List<DBLinkSlimDTO>> interproDbLinks;
    private Map<String, List<DBLinkSlimDTO>> ecDbLinks;
    private Map<String, List<DBLinkSlimDTO>> prositeDbLinks;
    private Map<String, List<DBLinkSlimDTO>> pfamDbLinks;
    private Map<String, List<DBLinkSlimDTO>> uniprotDbLinksByGeneZdbID;
    private Map<DBLinkSlimDTO, DBLinkExternalNoteSlimDTO> externalNotesByUniprotAccession;

    private List<MarkerGoTermEvidence> existingMarkerGoTermEvidenceRecordsForSPKW;

    private List<SecondaryTerm2GoTerm> interproTranslationRecords;
    private List<SecondaryTerm2GoTerm> ecTranslationRecords;

    public static SecondaryLoadContext createFromDBConnection() {
        SecondaryLoadContext loadContext = new SecondaryLoadContext();
        SequenceRepository sr = getSequenceRepository();

        log.debug("Load Step 1: Getting Existing Uniprot DB Links");
        loadContext.setUniprotDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(UNIPROTKB, POLYPEPTIDE, SEQUENCE, ZEBRAFISH))));

        log.debug("Load Step 2: Getting Existing Interpro DB Links");
        loadContext.setInterproDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(INTERPRO, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Load Step 3: Getting Existing EC DB Links");
        loadContext.setEcDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(EC, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Load Step 4: Getting Existing PFAM DB Links");
        loadContext.setPfamDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(PFAM, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Load Step 5: Getting Existing PROSITE DB Links");
        loadContext.setPrositeDbLinks(
                convertToDTO(
                        sr.getMarkerDBLinks(
                                sr.getReferenceDatabase(PROSITE, DOMAIN, PROTEIN, ZEBRAFISH))));

        log.debug("Load Step 6: Creating Index of Uniprot DB Links by Gene ZDB ID");
        loadContext.createUniprotDbLinksByGeneZdbID();

        log.debug("Load Step 7: Getting Existing MarkerGoTermEvidence Records");
        loadContext.setExistingMarkerGoTermEvidenceRecordsForSPKW(
                getMarkerGoTermEvidenceRepository().getMarkerGoTermEvidencesForPubZdbID(SPKW_PUB_ID)
        );

        log.debug("Load Step 8: Getting Existing External Notes");
        loadContext.setExternalNotesByUniprotAccession(
                getInfrastructureRepository().getDBLinkExternalNoteByPublicationID(SecondaryTermLoadService.EXTNOTE_PUBLICATION_ATTRIBUTION_ID)
        );

        return loadContext;
    }

    private void setExternalNotesByUniprotAccession(List<DBLinkExternalNote> dbLinkExternalNoteByPublicationID) {
        this.externalNotesByUniprotAccession = new HashMap<>();
        dbLinkExternalNoteByPublicationID.forEach(dbLinkExternalNote -> {
            DBLinkSlimDTO notesKey = new DBLinkSlimDTO();
            notesKey.setAccession(dbLinkExternalNote.getDblink().getAccessionNumber());
            notesKey.setDataZdbID(dbLinkExternalNote.getDblink().getDataZdbID());

            this.externalNotesByUniprotAccession.put(notesKey, DBLinkExternalNoteSlimDTO.from(dbLinkExternalNote));
        });
    }

    public DBLinkExternalNoteSlimDTO getExternalNoteByGeneAndAccession(String geneZdbID, String accessionNumber) {
        DBLinkSlimDTO notesKey = new DBLinkSlimDTO();
        notesKey.setAccession(accessionNumber);
        notesKey.setDataZdbID(geneZdbID);

        if (externalNotesByUniprotAccession == null) {
            throw new RuntimeException("Initialize error external notes");
        }

        if (externalNotesByUniprotAccession.containsKey(notesKey)) {
            return externalNotesByUniprotAccession.get(notesKey);
        }

        return null;
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

    public Map<String, List<DBLinkSlimDTO>> getDbLinksByDbName(ForeignDB.AvailableName dbName) {
        return switch (dbName) {
            case INTERPRO -> getInterproDbLinks();
            case EC -> getEcDbLinks();
            case PFAM -> getPfamDbLinks();
            case PROSITE -> getPrositeDbLinks();
            case UNIPROTKB -> getUniprotDbLinks();
            default -> null;
        };
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

    public Collection<DBLinkExternalNoteSlimDTO> getAllExternalNotes() {
        Map<DBLinkSlimDTO, DBLinkExternalNoteSlimDTO> map = getExternalNotesByUniprotAccession();
        return map.values();
    }
}