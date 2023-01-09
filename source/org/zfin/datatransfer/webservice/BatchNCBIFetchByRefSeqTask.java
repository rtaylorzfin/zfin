package org.zfin.datatransfer.webservice;

import lombok.extern.log4j.Log4j2;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.zfin.datatransfer.ServiceConnectionException;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;

import java.io.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;


/**
 * Batch fetch to pull in associations between refseqs and NCBI genes
 * Give an input file where each line is a refseq and it will generate
 * an output file with each ncbi gene id that is returned for that as a search term.
 *
 * example:
 * if the input file has a line with XP_003197842.1, this will make an api request to
 *
 *  https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=gene&term=XP_003197842.1&retmode=xml
 *
 * then it will write to the output file:
 * XP_003197842.1,100538113
 *
 */
@Log4j2
public class BatchNCBIFetchByRefSeqTask extends AbstractScriptWrapper {

    private static final int BATCH_SIZE = 5;
    private static final int RETRY_COUNT = 5;
    private String inputFilename;
    private String outputFilename;
    private String apiKey;
    private BufferedWriter writer;
    private BufferedReader reader;

    public static void main(String[] args) {
        BatchNCBIFetchByRefSeqTask task = new BatchNCBIFetchByRefSeqTask(args);
        task.run();
    }

    public BatchNCBIFetchByRefSeqTask(String[] args) {
        initProperties();
        initInputs(args);
        initFiles();
    }

    public void run() {
        List<String> accessionLines = getInputLines();
        List<List<String>> batchesOfAccessions = partitionInputs(accessionLines);
        fetchAndWrite(batchesOfAccessions);
        closeFiles();
    }

    private void fetchAndWrite(List<List<String>> batchesOfAccessions) {
        for(List<String> batch : batchesOfAccessions) {
            try {
                fetchAndWriteBatchWithRetries(batch, RETRY_COUNT);
            } catch (ServiceConnectionException e) {
                e.printStackTrace();
                LOG.error(e.getMessage());
                LOG.error("Exiting due to error");
                System.exit(3);
            } catch (IOException e) {
                e.printStackTrace();
                LOG.error(e.getMessage());
                LOG.error("Exiting due to IOException error");
                System.exit(4);
            }
        }
    }

    private void fetchAndWriteBatchWithRetries(List<String> batch, int retryCount) throws ServiceConnectionException, IOException {
        int sleepTime = (RETRY_COUNT - retryCount) * 3000; //sleep a little longer each time between retries
        try {
            fetchAndWriteBatch(batch);
        } catch (ServiceConnectionException e) {
            if (retryCount > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                log.info("retrying batch fetch");
                fetchAndWriteBatchWithRetries(batch, retryCount - 1);
            } else {
                throw e;
            }
        }

    }

    private void fetchAndWriteBatch(List<String> batch) throws ServiceConnectionException, IOException {
        try {
            String apiResult = fetchResults(batch);
            writer.write(apiResult);
        } catch (IOException | ServiceConnectionException e) {
            LOG.error("Error fetching batch ");
            LOG.error(e.getMessage());
            e.printStackTrace();

            throw e;
        }
    }

    private String fetchResults(List<String> batch) throws IOException, ServiceConnectionException {
        StringBuilder buffer = new StringBuilder();
        //create a new calendar instance
        long lastTimeStamp;
        long currentTimeStamp;

        for (String accession : batch) {
            lastTimeStamp = Calendar.getInstance().getTimeInMillis();
            buffer.append(fetchSingleResult(accession)).append("\n");
            currentTimeStamp = Calendar.getInstance().getTimeInMillis();
            long delay = currentTimeStamp - lastTimeStamp;

            //respect rate limit
            if (delay < 350) {
                try {
                    Thread.sleep(350 - delay);
                } catch (InterruptedException e) {
                    LOG.error("Thread sleep error");
                }
            }

        }
        writer.write(buffer.toString());
        writer.flush();

        return buffer.toString();
    }

    private String fetchSingleResult(String accession) throws ServiceConnectionException {
        String type = "gene";
        LOG.debug("Fetching: " + accession);
        Document doc = new NCBIRequest(NCBIRequest.Eutil.SEARCH)
                .with("db", type)
                .with("term", accession)
                .with("retmax", 5000)
                .with("api_key", apiKey)
                .go();

        NodeList idlist = doc.getElementsByTagName("IdList");

        StringBuilder buffer = new StringBuilder(accession);
        for (int i = 0; i < idlist.getLength(); i++) {
            NodeList ids = idlist.item(i).getChildNodes();
            for (int j = 0; j < ids.getLength(); j++) {
                String ncbiID = ids.item(j).getTextContent();
                if ("".equals(ncbiID.trim())) {
                    continue;
                }
                buffer.append(",").append(ncbiID);
            }
        }
        return buffer.toString();
    }

    private List<List<String>> partitionInputs(List<String> accessionLines) {
        int numBatches = (int)Math.ceil( ((double)accessionLines.size()) / ((double)BATCH_SIZE));
        List<List<String>> resultSet = new ArrayList<>();

        int count = 0;
        for(int i = 0; i < numBatches; i++) {
            List<String> batch = new ArrayList<>();
            for(int j = 0; j < BATCH_SIZE && count < accessionLines.size(); j++) {
                batch.add(accessionLines.get(count));
                count++;
            }
            resultSet.add(batch);
        }

        return resultSet;
    }

    public List<String> getInputLines() {
        List<String> lines = new ArrayList<>();
        try {
            String line = reader.readLine();
            while (line != null) {
                lines.add(line);
                line = reader.readLine();
            }
            reader.close();
        } catch (IOException e) {
            LOG.error("Error reading file from ENV[NCBI_LOAD_INPUT]: " + inputFilename);
            System.exit(2);
        }
        return lines;
    }

    private void initFiles() {
        writer = getBufferedWriter();
        try {
            reader = new BufferedReader(new FileReader(inputFilename));
        } catch (FileNotFoundException e) {
            LOG.error("Error reading file from ENV[NCBI_LOAD_INPUT]: " + inputFilename);
            System.exit(2);
        }
    }

    private BufferedWriter getBufferedWriter() {
        try {
            return new BufferedWriter(new FileWriter(outputFilename, true));
        } catch (IOException e) {
            LOG.error("Could not write to " + outputFilename);
            System.exit(3);
        }
        return null;
    }

    private void initInputs(String[] args) {

        inputFilename = System.getProperty("ncbiLoadInput");
        outputFilename = System.getProperty("ncbiLoadOutput");
        apiKey = System.getProperty("apiKey");

        if (null == inputFilename || null == outputFilename || null == apiKey) {
            //try the command line arguments
            if (args.length >= 3) {
                inputFilename = args[0];
                outputFilename = args[1];
                apiKey = args[2];
            }
        }

        if (null == inputFilename || null == outputFilename || null == apiKey) {
            LOG.error("Must provide system properties: ncbiLoadInput, ncbiLoadOutput, and apiKey");
            System.exit(1);
        }
    }

    private void closeFiles() {
        try {
            reader.close();
            writer.close();
        } catch (IOException e) {
            LOG.error("Couldn't close files");
            System.exit(6);
        }
    }
}