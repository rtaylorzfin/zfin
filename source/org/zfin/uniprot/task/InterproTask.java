package org.zfin.uniprot.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.biojava.bio.BioException;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.interpro.*;
import org.zfin.uniprot.persistence.UniProtRelease;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;
import static org.zfin.sequence.ForeignDB.AvailableName.*;
import static org.zfin.uniprot.UniProtFilterTask.readAllZebrafishEntriesFromSourceIntoMap;
import static org.zfin.uniprot.UniProtTools.getArgOrEnvironmentVar;

@Log4j2
@Getter
@Setter
public class InterproTask extends AbstractScriptWrapper {
    private String inputFileName;
    private final String outputJsonName;
    private final String ipToGoTranslationFile;
    private final String ecToGoTranslationFile;
    private UniProtRelease release;
    private List<SecondaryTerm2GoTerm> ipToGoRecords;
    private List<SecondaryTerm2GoTerm> ecToGoRecords;

    public static void main(String[] args) throws Exception {
        String inputFileName = getArgOrEnvironmentVar(args, 0, "UNIPROT_INPUT_FILE", "");
        String ipToGoTranslationFile = getArgOrEnvironmentVar(args, 1, "IP2GO_FILE", "");
        String ecToGoTranslationFile = getArgOrEnvironmentVar(args, 2, "EC2GO_FILE", "");
        String outputJsonName = getArgOrEnvironmentVar(args, 3, "UNIPROT_OUTPUT_FILE", defaultOutputFileName(inputFileName));
        InterproTask task = new InterproTask(inputFileName, outputJsonName, ipToGoTranslationFile, ecToGoTranslationFile);
        task.runTask();
    }

    private static String defaultOutputFileName(String inputFileName) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(Calendar.getInstance().getTime());
        return inputFileName + "." + timestamp + ".json";
    }

    private static Optional<UniProtRelease> getLatestUnprocessedUniProtRelease() {
        return Optional.ofNullable(getInfrastructureRepository().getLatestUnprocessedUniProtRelease());
    }

    public InterproTask(String inputFileName, String outputJsonName, String ipToGoTranslationFile, String ecToGoTranslationFile) {
        this.inputFileName = inputFileName;
        this.outputJsonName = outputJsonName;
        this.ipToGoTranslationFile = ipToGoTranslationFile;
        this.ecToGoTranslationFile = ecToGoTranslationFile;
    }

    public void runTask() throws IOException, BioException, SQLException {
        initialize();
        log.debug("Starting UniProtLoadTask for file " + inputFileName + ".");
        try (BufferedReader inputFileReader = new BufferedReader(new java.io.FileReader(inputFileName))) {
            Map<String, RichSequenceAdapter> entries = readUniProtEntries(inputFileReader);
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

        InterproLoadContext context = InterproLoadContext.createFromDBConnection();
        context.setInterproTranslationRecords(ipToGoRecords);
        pipeline.setContext(context);

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(INTERPRO));
        pipeline.addHandler(new AddNewFromUniProtsHandler(INTERPRO));

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(EC));
        pipeline.addHandler(new AddNewFromUniProtsHandler(EC));

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(PFAM));
        pipeline.addHandler(new AddNewFromUniProtsHandler(PFAM));

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(PROSITE));
        pipeline.addHandler(new AddNewFromUniProtsHandler(PROSITE));

        pipeline.addHandler(new AddNewInterproToGoHandler(INTERPRO));
        pipeline.addHandler(new RemoveInterproToGoHandler(INTERPRO));

        return pipeline.execute();
    }

    public void initialize() {
        initAll();
        setInputFileName();
        loadTranslationFiles();
    }

    private void loadTranslationFiles() {
        try {
            log.debug("Loading " + ipToGoTranslationFile);
            ipToGoRecords = SecondaryTerm2GoTermTranslator.convertTranslationFileToUnloadFile(ipToGoTranslationFile, SecondaryTerm2GoTermTranslator.SecondaryTermType.InterPro);
            log.debug("Loading " + ecToGoTranslationFile);
            ecToGoRecords = SecondaryTerm2GoTermTranslator.convertTranslationFileToUnloadFile(ecToGoTranslationFile, SecondaryTerm2GoTermTranslator.SecondaryTermType.EC);
        } catch (FileNotFoundException e) {
            log.error("Failed to load translation file: ", e);
            throw new RuntimeException(e);
        }
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

        return entries;
    }

}
