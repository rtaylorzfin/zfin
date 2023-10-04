package org.zfin.uniprot.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.biojava.bio.BioException;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.uniprot.adapter.CrossRefAdapter;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.interpro.*;
import org.zfin.uniprot.persistence.UniProtRelease;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;
import static org.zfin.uniprot.UniProtFilterTask.readAllZebrafishEntriesFromSourceIntoMap;
import static org.zfin.uniprot.UniProtTools.getArgOrEnvironmentVar;

@Log4j2
@Getter
@Setter
public class InterproTask extends AbstractScriptWrapper {
    private String inputFileName;
    private final String outputJsonName;
    private UniProtRelease release;

    public static void main(String[] args) throws Exception {
        String inputFileName = getArgOrEnvironmentVar(args, 0, "UNIPROT_INPUT_FILE", "");
        String outputJsonName = getArgOrEnvironmentVar(args, 1, "UNIPROT_OUTPUT_FILE", defaultOutputFileName(inputFileName));
        InterproTask task = new InterproTask(inputFileName, outputJsonName);
        task.runTask();
    }

    private static String defaultOutputFileName(String inputFileName) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(Calendar.getInstance().getTime());
        return inputFileName + "." + timestamp + ".json";
    }

    private static Optional<UniProtRelease> getLatestUnprocessedUniProtRelease() {
        return Optional.ofNullable(getInfrastructureRepository().getLatestUnprocessedUniProtRelease());
    }

    public InterproTask(String inputFileName, String outputJsonName) {
        this.inputFileName = inputFileName;
        this.outputJsonName = outputJsonName;
    }

    public void runTask() throws IOException, BioException, SQLException {
        initialize();
        log.debug("Starting UniProtLoadTask for file " + inputFileName + ".");
        try (BufferedReader inputFileReader = new BufferedReader(new java.io.FileReader(inputFileName))) {
            Map<String, RichSequenceAdapter> entries = readUniProtEntries(inputFileReader);
            log.debug("Finished reading file: " + entries.size() + " entries read.");
            Set<InterproLoadAction> actions = executePipeline(entries);
            log.debug("Finished executing pipeline: " + actions.size() + " actions created.");
            writeActions(actions);
        }
    }

    private void writeActions(Set<InterproLoadAction> actions) {
        String jsonFile = this.outputJsonName;

        log.debug("Creating JSON file: " + jsonFile);
        try {
            (new ObjectMapper()).writeValue(new File(jsonFile), actions);
        } catch (IOException e) {
            log.error("Failed to write JSON file: " + jsonFile, e);
        }
    }

    private Set<InterproLoadAction> executePipeline(Map<String, RichSequenceAdapter> entries) {
        InterproLoadPipeline pipeline = new InterproLoadPipeline();
        pipeline.setInterproRecords(entries);
        pipeline.setContext(InterproLoadContext.createFromDBConnection());
        pipeline.addHandler(new RemoveFromLostUniProtsHandler());
        return pipeline.execute();
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

        return entries;
    }

}
