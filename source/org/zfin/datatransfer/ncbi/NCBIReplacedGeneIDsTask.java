package org.zfin.datatransfer.ncbi;

import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.zfin.datatransfer.webservice.NCBIEfetch;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.properties.ZfinPropertiesEnum;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Log4j2
public class NCBIReplacedGeneIDsTask extends AbstractScriptWrapper {

    public static final String NCBI_DOWNLOAD_DIRECTORY_BASE = ZfinPropertiesEnum.NCBI_RELEASE_ARCHIVE_DIR.value();
    private File deadIDsOutputFile;
    private File mappedIDsOutputFile;
    private Long fetchCount = 0L;
    private List<String> deadIDs = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        NCBIReplacedGeneIDsTask task = new NCBIReplacedGeneIDsTask();
        task.initAll();
        task.config(args);
        task.run();
    }

    private void config(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: NCBIReplacedGeneIDsTask <deadIDsOutputFile> <mappedIDsOutputFile>");
            System.exit(1);
        }
        String deadIDsOutputFileString = args[0];
        String mappedIDsOutputFileString = args[1];
        deadIDsOutputFile = new File(deadIDsOutputFileString);
        mappedIDsOutputFile = new File(mappedIDsOutputFileString);
    }

    @SneakyThrows
    private void run() {
        //fetch all genes not alive at NCBI
        deadIDs = NCBIEfetch.fetchGeneIDsNotAlive(100_000);
        System.out.println("Found " + deadIDs.size() + " dead gene IDs at NCBI");
        Files.writeString(deadIDsOutputFile.toPath(), String.join("\n", deadIDs));

        try (FileWriter writer = new FileWriter(mappedIDsOutputFile)) {
            writer.write("OldID,NewID\n");
            writer.flush();
            for (String deadID : deadIDs) {
                printProgress();
                Optional<String> replacedID = NCBIEfetch.getReplacedGeneID(deadID);
                if (replacedID.isPresent()) {
                    writer.write(deadID + "," + replacedID.get() + "\n");
                }
                writer.flush();
            }
        }
        System.out.println("Done");
    }

    private void printProgress() {
        System.out.print(".");
        if (fetchCount % 30 == 0) {
            System.out.println();
        }
        if (fetchCount % 100 == 0) {
            System.out.println("Processed " + fetchCount + " IDs of " + deadIDs.size());
        }
        fetchCount++;
    }

}