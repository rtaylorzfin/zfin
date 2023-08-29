package org.zfin.uniprot;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.io.FileUtils;
import org.biojava.bio.BioException;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;
import org.zfin.properties.ZfinPropertiesEnum;
import org.zfin.uniprot.history.UniProtRelease;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.zfin.framework.HibernateUtil.currentSession;
import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;
import static org.zfin.uniprot.UniProtReleaseDiffTask.combineAndFilterInputFileSet;
import static org.zfin.uniprot.UniProtReleaseDiffTask.combineAndFilterInputPathSet;

/**
 * This class is used to check for new releases of uniprot.
 *
 */
@Log4j2
public class UniProtReleaseCheckTask extends AbstractScriptWrapper {

//    private static final String DOWNLOAD_URL_1 = "http://localhost:8080/uniprot_sprot_vertebrates.dat.gz";
//    private static final String DOWNLOAD_URL_2 = "http://localhost:8080/uniprot_trembl_vertebrates.dat.gz";
    private String DOWNLOAD_URL_1;
    private String DOWNLOAD_URL_2;
    private static final String COMBINED_FILE_NAME = "pre_zfin.dat";

    //Alternative URLs
//    https://ftp.expasy.org/databases/uniprot/current_release/knowledgebase/taxonomic_divisions/uniprot_trembl_vertebrates.dat.gz
//    https://ftp.expasy.org/databases/uniprot/current_release/knowledgebase/taxonomic_divisions/uniprot_sprot_vertebrates.dat.gz
//    https://ftp.uniprot.org/pub/databases/uniprot/current_release/knowledgebase/taxonomic_divisions/uniprot_trembl_vertebrates.dat.gz
//    https://ftp.uniprot.org/pub/databases/uniprot/current_release/knowledgebase/taxonomic_divisions/uniprot_sprot_vertebrates.dat.gz


    private Path downloadedFile1;
    private Path downloadedFile2;

    private Path downloadedDirectory;

    public static void main(String[] args) throws Exception {
        UniProtReleaseCheckTask task = new UniProtReleaseCheckTask();
        task.runTask();
    }

    public void runTask() throws IOException, BioException, SQLException {
        initAll();
        initIO();

        //get the timestamp of the file on server
        Date releaseDate = getLatestReleaseTimestamp();
        if (releaseAlreadyLoaded(releaseDate)) {
            log.info("No new release found.");
            return;
        }

        //download the files
        Path relativeDownloadDirectory = downloadFiles(releaseDate);

        //process the files
        log.info("Processing files to create " + COMBINED_FILE_NAME + " ...");
        Path destinationPath = downloadedDirectory.resolve(COMBINED_FILE_NAME);
        combineAndFilterInputPathSet(
                List.of(downloadedFile1, downloadedFile2),
                 destinationPath );

        //calculate md5
        log.info("Calculating md5...");
        String md5 = DigestUtils.md5Hex(FileUtils.openInputStream(destinationPath.toFile()));
        log.info("MD5: " + md5);

        //get file size
        long size = destinationPath.toFile().length();
        log.info("File size: " + size);

        String notes = "Release " + releaseDate + " downloaded from: \n" +
                DOWNLOAD_URL_1 + " ( " + downloadedFile1.toFile().length() + " bytes ) and \n" +
                DOWNLOAD_URL_2 + " ( " + downloadedFile2.toFile().length() + " bytes )";

        //record the release in the database
        recordReleaseInDB(releaseDate, destinationPath, md5, size, notes);
    }

    private static void recordReleaseInDB(Date releaseDate, Path relativeDownloadDirectory, String md5, long size, String notes) {
        log.info("Recording release in database...");
        currentSession().beginTransaction();
        UniProtRelease release = new UniProtRelease();
        release.setDate(releaseDate);
        release.setPath(relativeDownloadDirectory.toString());
        release.setMd5(md5);
        release.setSize(size);
        release.setDownloadDate(new Date());
        release.setNotes(notes);
        getInfrastructureRepository().insertUniProtRelease(release);
        currentSession().getTransaction().commit();
    }

    private Path downloadFiles(Date releaseDate) {
        String url1 = DOWNLOAD_URL_1;
        String fileName1 = url1.substring(url1.lastIndexOf('/') + 1);

        String url2 = DOWNLOAD_URL_2;
        String fileName2 = url2.substring(url2.lastIndexOf('/') + 1);

        //set class properties
        downloadedDirectory = createPathForDownloadDestination(releaseDate);
        downloadedFile1 = downloadedDirectory.resolve(fileName1);
        downloadedFile2 = downloadedDirectory.resolve(fileName2);

        //download the files
        downloadFileIfNotExists(url1, downloadedFile1);
        downloadFileIfNotExists(url2, downloadedFile2);

        //calculate path relative to base
        Path base = Paths.get(ZfinPropertiesEnum.UNIPROT_RELEASE_ARCHIVE_DIR.value());
        Path relativePath = base.relativize(downloadedDirectory);

        return relativePath;
    }

    private void downloadFileIfNotExists(String url, Path localFile) {
        if (localFile.toFile().exists()) {
            log.error("File already exists: " + localFile);
            long serverSize = getFileSizeOnServer(url);
            long localSize = localFile.toFile().length();
            if (serverSize == localSize) {
                log.info("File sizes match.  Skipping download.");
                return;
            } else if (serverSize > localSize) {
                log.error("Server file is larger than local file.  Downloading anyway assuming resumed download.");
            } else {
                throw new RuntimeException("Server file is smaller than local file.  This should not happen.");
            }
        }
        downloadFileFromURLViaWget(url, localFile);
    }

    private static void downloadFileFromURLViaWget(String url, Path destination)  {
        log.info("Downloading file: " + url + " to " + destination);
        int exitValue = -1;
        Map map = new HashMap();
        map.put("destination", destination.toFile());
        map.put("url", url);
        CommandLine cmdLine = new CommandLine("wget");

        //continue download if file already exists (resume)
        cmdLine.addArgument("-c");

        //show progress. show dots every 10MB since these are large files
        cmdLine.addArgument("--progress=dot");
        cmdLine.addArgument("-e");
        cmdLine.addArgument("dotbytes=10M");

        //save to destination
        cmdLine.addArgument("-O");
        cmdLine.addArgument("${destination}");

        //download from url
        cmdLine.addArgument("${url}");
        cmdLine.setSubstitutionMap(map);
        log.info("Running command: " + cmdLine.toString());
        DefaultExecutor executor = new DefaultExecutor();
        executor.setExitValue(0);
        ExecuteWatchdog watchdog = new ExecuteWatchdog(3_600_000); //kill after an hour
        executor.setWatchdog(watchdog);
        try {
            exitValue = executor.execute(cmdLine);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (exitValue != 0) {
            throw new RuntimeException("Error downloading file from " + url);
        }
    }

    private static Path createPathForDownloadDestination(Date releaseDate) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd").format(releaseDate);

        Path directoryDestination = Paths.get(
                ZfinPropertiesEnum.UNIPROT_RELEASE_ARCHIVE_DIR.value(),
                timestamp);

        //make directory if it doesn't exist
        File directory = directoryDestination.toFile();
        if (!directory.exists()) {
            directory.mkdirs();
        } else {
            log.error("Directory already exists: " + directoryDestination);
        }

        if (!directory.isDirectory()) {
            throw new RuntimeException("Destination is not a directory: " + directoryDestination);
        }

        return directoryDestination;
    }

    private boolean releaseAlreadyLoaded(Date releaseDate) {
        UniProtRelease up = getInfrastructureRepository().getUniProtReleaseByDate(releaseDate);
        if (up == null) {
            log.debug("No release found for date: " + releaseDate);
            return false;
        }
        log.debug("Release date: " + releaseDate);
        log.debug("path: " + up.getPath());
        return true;
    }

    private Date getLatestReleaseTimestamp() {
        String url = DOWNLOAD_URL_1;
        if (url == null) {
            throw new RuntimeException("No URL found for uniprot release file.");
        }

        //make web HEAD request to get the last modified date
        try {
            HttpURLConnection httpUrlConnection = (HttpURLConnection) new URL(url).openConnection();
            httpUrlConnection.setRequestMethod("HEAD");
            String lastModified = httpUrlConnection.getHeaderField("Last-Modified");

            int responseCode = httpUrlConnection.getResponseCode();
            log.debug("Response Code: " + responseCode);

            log.debug("Headers:");
            Map<String, List<String>> headers = httpUrlConnection.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                log.debug(entry.getKey() + ": " + entry.getValue());
            }

            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
            return sdf.parse(lastModified);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Error getting last modified date for " + url);
            throw new RuntimeException(e);
        }
    }

    private long getFileSizeOnServer(String url) {
        try {
            HttpURLConnection httpUrlConnection = (HttpURLConnection) new URL(url).openConnection();
            httpUrlConnection.setRequestMethod("HEAD");
            return httpUrlConnection.getContentLengthLong();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Error getting file size for " + url);
            throw new RuntimeException(e);
        }
    }

    private void initIO() {
        this.DOWNLOAD_URL_1 = ZfinPropertiesEnum.UNIPROT_TREMBL_FILE_URL.value();
        this.DOWNLOAD_URL_2 = ZfinPropertiesEnum.UNIPROT_SPROT_FILE_URL.value();
    }

}
