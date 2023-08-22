package org.zfin.uniprot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.biojava.bio.BioException;
import org.biojavax.bio.seq.RichSequence;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

import org.zfin.uniprot.handlers.IgnoreAccessionsAlreadyInDatabaseHandler;
import org.zfin.uniprot.handlers.IgnoreSpecificAccessionsHandler;
import org.zfin.uniprot.handlers.MatchOnRefSeqHandler;
import org.zfin.uniprot.handlers.RemoveVersionHandler;

import static org.zfin.uniprot.UniProtFilterTask.readAllZebrafishEntriesFromSourceIntoMap;
import static org.zfin.uniprot.UniProtTools.getArgOrEnvironmentVar;

/**
 * This class is used to perform a load of uniprot dat file.
 *
 */
@Log4j2
public class UniProtLoadTask extends AbstractScriptWrapper {
    private BufferedReader inputFileReader = null;
    private UniProtLoadContext context;

    public static void main(String[] args) throws Exception {
        String inputFileName = getArgOrEnvironmentVar(args, 0, "UNIPROT_INPUT_FILE");

        BufferedReader inputFileReader = new BufferedReader(new java.io.FileReader(inputFileName));

        UniProtLoadTask task = new UniProtLoadTask(inputFileReader);
        task.runTask();
    }

    public UniProtLoadTask(BufferedReader bufferedReader) {
        this.inputFileReader = bufferedReader;
    }

    public void runTask() throws IOException, BioException, SQLException {
        initAll();

        // calculate the current context
        calculateContext();

        System.out.println("Starting to read file: " );
        Map<String, RichSequence> entries = readAllZebrafishEntriesFromSourceIntoMap(inputFileReader);
        System.out.println("Finished reading file: " + entries.size() + " entries read.");


        // data entry pipeline
        UniProtLoadPipeline pipeline = new UniProtLoadPipeline();
        pipeline.setContext(context);
        pipeline.setUniProtRecords(entries);
        pipeline.addHandler(new RemoveVersionHandler());
        pipeline.addHandler(new IgnoreSpecificAccessionsHandler());
        pipeline.addHandler(new IgnoreAccessionsAlreadyInDatabaseHandler());
        pipeline.addHandler(new MatchOnRefSeqHandler());
        List<UniProtLoadAction> actions = pipeline.execute();

        //do something with the actions
    }

    private void calculateContext() {
        context = UniProtLoadContext.createFromDBConnection();

        //create temp file
        try {
            String tempFileName = "/tmp/uniprot_load_" + System.currentTimeMillis() + ".tmp";
            System.out.println("tempFileName: " + tempFileName);
            File tempFile = new File(tempFileName);
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(tempFile, context);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
