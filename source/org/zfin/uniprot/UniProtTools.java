package org.zfin.uniprot;

import org.biojavax.CrossRef;
import org.biojavax.Note;
import org.biojavax.ontology.ComparableTerm;
import org.zfin.infrastructure.RecordAttribution;
import org.zfin.sequence.DBLink;
import org.zfin.uniprot.dto.DBLinkSlimDTO;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;
import static org.zfin.repository.RepositoryFactory.getSequenceRepository;

public class UniProtTools {
    public static final String MANUAL_CURATION_OF_PROTEIN_IDS = "ZDB-PUB-170131-9";
    public static final String UNIPROT_ID_LOAD_FROM_ENSEMBL = "ZDB-PUB-170502-16";
    public static final String MANUAL_CURATION_OF_UNIPROT_IDS = "ZDB-PUB-220705-2";
    public static final String CURATION_OF_PROTEIN_DATABASE_LINKS = "ZDB-PUB-020723-2";

    public static final String[] NON_LOAD_PUBS = new String[]{MANUAL_CURATION_OF_PROTEIN_IDS, UNIPROT_ID_LOAD_FROM_ENSEMBL, MANUAL_CURATION_OF_UNIPROT_IDS};
    public static final String[] LOAD_PUBS = new String[]{CURATION_OF_PROTEIN_DATABASE_LINKS};


    //use passed in lambda expression to transform the notes
    public static void transformCrossRefNoteSetByTerm(CrossRef crossRef, ComparableTerm term, Function<String, String> transformer) {
        Set<Note> notes = crossRef.getNoteSet();
        for (Note note : notes) {
            if (note.getTerm().equals(term)) {
                note.setValue(transformer.apply(note.getValue()));
            }
        }
    }

    public static String getArgOrEnvironmentVar(String[] args, int index, String envVar) {
        if (args.length > index && args[index] != null) {
            return args[index];
        }

        String result = System.getenv(envVar);

        if (result == null) {
            System.err.println("Missing required argument: " + envVar + ". Please provide it as an environment variable or as argument: " + (index + 1) + ". ");
            System.exit(1);
        }

        return result;
    }

    //TODO: remove this method once we have a proper way to set accession
    //CrossRef doesn't allow setting accession, so we have to use reflection
    //We already wrap RichSequence with a custom class, so we should do the same for CrossRef
    public static void setAccession(CrossRef xref, String accession) {
        try {
            Method method;
            method = xref.getClass().getDeclaredMethod("setAccession", String.class);
            method.setAccessible(true);
            method.invoke(xref, accession);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isAnyDBLinkSupportedByNonLoadPublication(List<DBLinkSlimDTO> dblink) {
        return dblink.stream().anyMatch(dbLinkSlimDTO -> dbLinkSlimDTO.containsNonLoadPublication());
    }

    public static boolean isLoadPublication(String pubID) {return List.of(LOAD_PUBS).contains(pubID);}

    public static boolean isNonLoadPublication(String pubID) {return !isLoadPublication(pubID);}

}
