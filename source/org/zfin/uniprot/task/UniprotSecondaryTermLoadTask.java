package org.zfin.uniprot.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.FileUtils;
import org.biojava.bio.BioException;
import org.zfin.gwt.root.util.StringUtils;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.properties.ZfinPropertiesEnum;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.secondary.*;
import org.zfin.uniprot.persistence.UniProtRelease;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.zfin.datatransfer.service.DownloadService.downloadFileViaWget;
import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;
import static org.zfin.sequence.ForeignDB.AvailableName.*;
import static org.zfin.uniprot.UniProtFilterTask.readAllZebrafishEntriesFromSourceIntoMap;
import static org.zfin.uniprot.UniProtTools.getArgOrEnvironmentVar;

@Log4j2
@Getter
@Setter
public class UniprotSecondaryTermLoadTask extends AbstractScriptWrapper {

    private static final int ACTION_SIZE_ERROR_THRESHOLD = 10_000;
    private static final String LOAD_REPORT_TEMPLATE_HTML = "/home/uniprot/secondary-load-report.html";
    private static final String JSON_PLACEHOLDER_IN_TEMPLATE = "JSON_GOES_HERE";

    private enum LoadTaskMode {
        REPORT,
        LOAD,
        LOAD_AND_REPORT
    }
    private final LoadTaskMode mode;
    private final String actionsFileName;
    private String inputFileName;
    private final String outputJsonName;
    private final String outputReportName;
    private final String contextOutputFile;
    private final String ipToGoTranslationFile;
    private final String ecToGoTranslationFile;
    private final String upToGoTranslationFile;
    private UniProtRelease release;
    private List<SecondaryTerm2GoTerm> ipToGoRecords;
    private List<SecondaryTerm2GoTerm> ecToGoRecords;
    private List<SecondaryTerm2GoTerm> upToGoRecords;

    public static void main(String[] args) throws Exception {

        String mode = "";
        String inputFileName = "";
        String ipToGoTranslationFile = "";
        String ecToGoTranslationFile = "";
        String upToGoTranslationFile = "";
        String outputJsonName = "";

        String actionsFileName = "";

        //mode can be one of the following:
        // 1. "REPORT" - generate actions from the input file and write them to a file (no load)
        // 2. "LOAD" - load actions from a file into the database
        // 3. "LOAD_AND_REPORT" - generate actions from the input file, write them to a file, and load them into the database
        mode = getArgOrEnvironmentVar(args, 0, "UNIPROT_LOAD_MODE", "REPORT");

        if (mode.equalsIgnoreCase("REPORT") || mode.equalsIgnoreCase("LOAD_AND_REPORT")) {
            inputFileName = getArgOrEnvironmentVar(args, 1, "UNIPROT_INPUT_FILE", "");
            ipToGoTranslationFile = getArgOrEnvironmentVar(args, 2, "IP2GO_FILE", "");
            ecToGoTranslationFile = getArgOrEnvironmentVar(args, 3, "EC2GO_FILE", "");
            upToGoTranslationFile = getArgOrEnvironmentVar(args, 4, "UP2GO_FILE", "");
            outputJsonName = getArgOrEnvironmentVar(args, 5, "UNIPROT_OUTPUT_FILE", defaultOutputFileName(inputFileName));
        } else if (mode.equalsIgnoreCase("LOAD")) {
            actionsFileName = getArgOrEnvironmentVar(args, 1, "UNIPROT_ACTIONS_FILE", "");
        } else {
            printUsage();
            System.exit(1);
        }

        UniprotSecondaryTermLoadTask task = new UniprotSecondaryTermLoadTask(mode, inputFileName, outputJsonName, ipToGoTranslationFile, ecToGoTranslationFile, upToGoTranslationFile, actionsFileName);
        task.runTask();
    }

    private static String defaultOutputFileName(String inputFileName) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(Calendar.getInstance().getTime());
        return inputFileName + ".actions." + timestamp + ".json";
    }

    private static Optional<UniProtRelease> getLatestUnprocessedUniProtRelease() {
        List<UniProtRelease> releases = getInfrastructureRepository().getAllUniProtReleases();
        if (releases.isEmpty()) {
            return Optional.empty();
        }

        //get the latest release that's been processed by the primary load logic, but not by the secondary load logic
        return releases
                .stream()
                .filter(release -> release.getSecondaryLoadDate() == null)
                .filter(release -> release.getProcessedDate() != null)
                .findFirst();
    }

    public UniprotSecondaryTermLoadTask(String mode, String inputFileName, String outputJsonName, String ipToGoTranslationFile, String ecToGoTranslationFile, String upToGoTranslationFile, String actionsFileName) {
        this.mode = LoadTaskMode.valueOf(mode.toUpperCase());
        this.inputFileName = inputFileName;
        this.outputJsonName = outputJsonName;
        this.outputReportName = outputJsonName + ".report.html";
        this.contextOutputFile = outputJsonName + ".context.json";

        this.ipToGoTranslationFile = ipToGoTranslationFile;
        this.ecToGoTranslationFile = ecToGoTranslationFile;
        this.upToGoTranslationFile = upToGoTranslationFile;
        this.actionsFileName = actionsFileName;
    }

    public void runTask() throws IOException, BioException, SQLException {
        initialize();
        log.debug("Starting UniProtLoadTask for file " + inputFileName + ".");

        if (mode.equals(LoadTaskMode.REPORT) || mode.equals(LoadTaskMode.LOAD_AND_REPORT)) {
            try (BufferedReader inputFileReader = new BufferedReader(new java.io.FileReader(inputFileName))) {
                loadTranslationFiles();
                Map<String, RichSequenceAdapter> entries = readUniProtEntries(inputFileReader);
                List<SecondaryTermLoadAction> actions = executePipeline(entries);
                log.debug("Finished executing pipeline: " + actions.size() + " actions created.");
                writeActions(actions);
                writeOutputReportFile(actions);

                if (actions.size() > ACTION_SIZE_ERROR_THRESHOLD) {
                    log.error("Too many actions created: " + actions.size() + " actions created.");
                    log.error("Threshold set to: " + ACTION_SIZE_ERROR_THRESHOLD);
                    log.error("Exiting script in case this is due to an error.");
                    System.exit(1);
                }

                if (mode.equals(LoadTaskMode.LOAD_AND_REPORT)) {
                    processActions(actions);
                }
            }
        } else if (mode.equals(LoadTaskMode.LOAD)) {
            List<SecondaryTermLoadAction> actions = readActionsFile();
            log.debug("Finished reading actions file: " + actions.size() + " actions read.");
            processActions(actions);
        } else {
            System.out.println("Invalid mode: " + mode);
            printUsage();
            System.exit(1);
        }

    }

    private List<SecondaryTermLoadAction> readActionsFile() {
        String jsonFile = this.actionsFileName;
        if (StringUtils.isEmpty(jsonFile)) {
            log.error("No actions file specified for mode: " + getMode() +  ".");
            System.exit(6);
        }
        log.debug("Reading JSON file: " + jsonFile);
        try {
            SecondaryTermLoadActionsContainer actionsContainer =
                    (new ObjectMapper()).readValue(new File(jsonFile), new TypeReference<SecondaryTermLoadActionsContainer>() {});
            this.release = getInfrastructureRepository().getUniProtReleaseByID(actionsContainer.getReleaseID());
            return actionsContainer.getActions();
        } catch (IOException e) {
            log.error("Failed to read JSON file: " + jsonFile, e);
            return null;
        }
    }


    private void writeActions(List<SecondaryTermLoadAction> actions) {
        String jsonFile = this.outputJsonName;
        log.debug("Creating JSON file: " + jsonFile);
        try {
            String jsonContents = actionsToJson(actions);
            FileUtils.writeStringToFile(new File(jsonFile), jsonContents, "UTF-8");
        } catch (IOException e) {
            log.error("Failed to write JSON file: " + jsonFile, e);
        }
    }

    private String actionsToJson(List<SecondaryTermLoadAction> actions) {
        SecondaryTermLoadActionsContainer actionsContainer = SecondaryTermLoadActionsContainer.builder()
                .actions(actions)
                .releaseID(this.release == null ? null : this.release.getUpr_id())
                .creationDate(new Date())
                .build();
        try {
            return (new ObjectMapper()).writeValueAsString(actionsContainer);
        } catch (JsonProcessingException e) {
            return null;
        }
    }


    private void writeOutputReportFile(List<SecondaryTermLoadAction> actions) {
        String reportFile = this.outputReportName;

        log.debug("Creating report file: " + reportFile);
        try {
            String jsonContents = actionsToJson(actions);
            String template = ZfinPropertiesEnum.SOURCEROOT.value() + LOAD_REPORT_TEMPLATE_HTML;
            String templateContents = FileUtils.readFileToString(new File(template), "UTF-8");
            String filledTemplate = templateContents.replace(JSON_PLACEHOLDER_IN_TEMPLATE, jsonContents);
            FileUtils.writeStringToFile(new File(reportFile), filledTemplate, "UTF-8");
        } catch (IOException e) {
            log.error("Error creating report (" + reportFile + ") from template\n" + e.getMessage(), e);
        }
    }

    private void writeContext(SecondaryLoadContext context) {
        log.debug("TODO: Implement serialization for writing context file: " + contextOutputFile + ".");
//        if (contextOutputFile != null && !contextOutputFile.isEmpty()) {
//            ObjectMapper objectMapper = new ObjectMapper();
//            try {
//                log.info("Writing context file: " + contextOutputFile + ".");
//                objectMapper.writeValue(new File(contextOutputFile), context);
//            } catch (IOException e) {
//                log.error("Error writing context file " + contextOutputFile + ": " + e.getMessage(), e);
//            }
//        }
    }

    private void processActions(List<SecondaryTermLoadAction> actions) {
        SecondaryTermLoadService.processActions(actions, release);
    }

    private List<SecondaryTermLoadAction> executePipeline(Map<String, RichSequenceAdapter> entries) {
        SecondaryTermLoadPipeline pipeline = new SecondaryTermLoadPipeline();
        pipeline.setUniprotRecords(entries);

        SecondaryLoadContext context = SecondaryLoadContext.createFromDBConnection();
        writeContext(context);
        pipeline.setContext(context);

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(INTERPRO));
        pipeline.addHandler(new AddNewFromUniProtsHandler(INTERPRO));

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(EC));
        pipeline.addHandler(new AddNewFromUniProtsHandler(EC));

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(PFAM));
        pipeline.addHandler(new AddNewFromUniProtsHandler(PFAM));

        pipeline.addHandler(new RemoveFromLostUniProtsHandler(PROSITE));
        pipeline.addHandler(new AddNewFromUniProtsHandler(PROSITE));

        pipeline.addHandler(new AddNewSecondaryTermToGoHandler(INTERPRO, ipToGoRecords));
        pipeline.addHandler(new RemoveSecondaryTermToGoHandler(INTERPRO, ipToGoRecords));

        pipeline.addHandler(new AddNewSecondaryTermToGoHandler(EC, ecToGoRecords));
        pipeline.addHandler(new RemoveSecondaryTermToGoHandler(EC, ecToGoRecords));

        pipeline.addHandler(new AddNewSpKeywordTermToGoHandler(UNIPROTKB, upToGoRecords));
        pipeline.addHandler(new RemoveSpKeywordTermToGoHandler(UNIPROTKB, upToGoRecords));

        pipeline.addHandler(new ExternalNotesHandler());
        return pipeline.execute();
    }

    public void initialize() {
        initAll();
        setInputFileName();
    }

    private void loadTranslationFiles() {
        try {
            //If the 2go files are not provided, download them
            String upToGo = upToGoTranslationFile;
            if (StringUtils.isEmpty(upToGo)) {
                File downloadedFile1 = File.createTempFile("upkw2go", ".dat");
                upToGo = downloadedFile1.getAbsolutePath();
                String url1 = ZfinPropertiesEnum.UNIPROT_KW2GO_FILE_URL.value();
                downloadFileViaWget(url1, downloadedFile1.toPath(), 10_000, log);
            }
            log.debug("Loading " + upToGo);
            upToGoRecords = SecondaryTerm2GoTermTranslator.convertTranslationFileToUnloadFile(upToGo, SecondaryTerm2GoTermTranslator.SecondaryTermType.UniProtKB);
            log.debug("Loaded " + upToGoRecords.size() + " UP to GO records.");

            String ipToGo = ipToGoTranslationFile;
            if (StringUtils.isEmpty(ipToGo)) {
                File downloadedFile2 = File.createTempFile("ip2go", ".dat");
                ipToGo = downloadedFile2.getAbsolutePath();
                String url2 = ZfinPropertiesEnum.UNIPROT_IP2GO_FILE_URL.value();
                downloadFileViaWget(url2, downloadedFile2.toPath(), 10_000, log);
            }
            log.debug("Loading " + ipToGo);
            ipToGoRecords = SecondaryTerm2GoTermTranslator.convertTranslationFileToUnloadFile(ipToGo, SecondaryTerm2GoTermTranslator.SecondaryTermType.InterPro);
            log.debug("Loaded " + ipToGoRecords.size() + " InterPro to GO records.");

            String ecToGo = ecToGoTranslationFile;
            if (StringUtils.isEmpty(ecToGo)) {
                File downloadedFile3 = File.createTempFile("ec2go", ".dat");
                ecToGo = downloadedFile3.getAbsolutePath();
                String url3 = ZfinPropertiesEnum.UNIPROT_EC2GO_FILE_URL.value();
                downloadFileViaWget(url3, downloadedFile3.toPath(), 10_000, log);
            }
            log.debug("Loading " + ecToGo);
            ecToGoRecords = SecondaryTerm2GoTermTranslator.convertTranslationFileToUnloadFile(ecToGo, SecondaryTerm2GoTermTranslator.SecondaryTermType.EC);
            log.debug("Loaded " + ecToGoRecords.size() + " EC to GO records.");

        } catch (IOException e) {
            log.error("Failed to load translation file: ", e);
            throw new RuntimeException(e);
        }
    }

    private void setInputFileName() {
        Optional<UniProtRelease> releaseOptional = getLatestUnprocessedUniProtRelease();

        //only need an input file if we are generating a report of actions, otherwise, we are loading directly from the actions
        if (mode.equals(LoadTaskMode.REPORT) || mode.equals(LoadTaskMode.LOAD_AND_REPORT)) {
            if (inputFileName.isEmpty() && releaseOptional.isPresent()) {
                log.debug("Loading from latest UniProt release: " + releaseOptional.get().getPath() + "(md5:" + releaseOptional.get().getMd5() + ")" );
                inputFileName = releaseOptional.get().getLocalFile().getAbsolutePath();
                release = releaseOptional.get();
            } else if (inputFileName.isEmpty()) {
                throw new RuntimeException("No input file specified and no unprocessed UniProt release found.");
            }
        }
    }

    public Map<String, RichSequenceAdapter> readUniProtEntries(BufferedReader inputFileReader) throws BioException, IOException {
        Map<String, RichSequenceAdapter> entries = readAllZebrafishEntriesFromSourceIntoMap(inputFileReader);
        log.debug("Finished reading file: " + entries.size() + " entries read.");

        return entries;
    }

    public static void printUsage() {
        System.out.println("Usage: uniprotSecondaryTermLoadTask <mode> [more args]");
        System.out.println("  mode: 'load' or 'report' or 'load_and_report'");
        System.out.println("  if mode is 'load', more args = <actions file>");
        System.out.println("  if mode is 'load_and_report' or 'report', more args = <input file> <up to go translation file> <ip to go translation file> <ec to go translation file> <output file>");
        System.out.println("  instead of arguments, you can use environment variables: UNIPROT_LOAD_MODE, INPUT_FILE, UP_TO_GO_TRANSLATION_FILE, IP_TO_GO_TRANSLATION_FILE, EC_TO_GO_TRANSLATION_FILE, OUTPUT_FILE");
        System.out.println("  or, for load mode, env vars: UNIPROT_LOAD_MODE, UNIPROT_ACTIONS_FILE");
        System.out.println("  the actions file is generated by the report mode");
    }

}