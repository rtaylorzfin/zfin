package org.zfin.uniprot.task;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.biojava.bio.BioException;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.uniprot.adapter.CrossRefAdapter;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.persistence.UniProtRelease;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;
import static org.zfin.uniprot.UniProtFilterTask.readAllZebrafishEntriesFromSourceIntoMap;
import static org.zfin.uniprot.UniProtTools.getArgOrEnvironmentVar;

@Log4j2
@Getter
@Setter
public class InterproTask extends AbstractScriptWrapper {
    private String inputFileName;
    private UniProtRelease release;

    public static void main(String[] args) throws Exception {
        String inputFileName = getArgOrEnvironmentVar(args, 0, "UNIPROT_INPUT_FILE", "");
        InterproTask task = new InterproTask(inputFileName);
        task.runTask();
    }

    private static Optional<UniProtRelease> getLatestUnprocessedUniProtRelease() {
        return Optional.ofNullable(getInfrastructureRepository().getLatestUnprocessedUniProtRelease());
    }

    public InterproTask(String inputFileName) {
        this.inputFileName = inputFileName;
    }

    public void runTask() throws IOException, BioException, SQLException {
        initialize();
        log.debug("Starting UniProtLoadTask for file " + inputFileName + ".");
        try (BufferedReader inputFileReader = new BufferedReader(new java.io.FileReader(inputFileName))) {
            Map<String, RichSequenceAdapter> entries = readUniProtEntries(inputFileReader);
            log.debug("Finished reading file: " + entries.size() + " entries read.");
        }
    }

    public void initialize() {
        initAll();
        setInputFileName();
    }

    private void setInputFileName() {
        Optional<UniProtRelease> releaseOptional = getLatestUnprocessedUniProtRelease();
        if (inputFileName.isEmpty() && releaseOptional.isPresent()) {
            inputFileName = releaseOptional.get().getLocalFile().getAbsolutePath();
            release = releaseOptional.get();
        } else if (inputFileName.isEmpty()) {
            throw new RuntimeException("No input file specified and no unprocessed UniProt release found.");
        }
    }

    public Map<String, RichSequenceAdapter> readUniProtEntries(BufferedReader inputFileReader) throws BioException, IOException {
        Map<String, RichSequenceAdapter> entries = readAllZebrafishEntriesFromSourceIntoMap(inputFileReader);
        log.debug("Finished reading file: " + entries.size() + " entries read.");

        RichSequenceAdapter rsa = entries.values().stream().findFirst().get();
        Collection<CrossRefAdapter> interproIds = rsa.getRankedCrossRefsByDatabase("InterPro");

        return entries;
    }

}
