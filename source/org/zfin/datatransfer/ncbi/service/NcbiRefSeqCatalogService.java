package org.zfin.datatransfer.ncbi.service;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.hibernate.Session;
import org.zfin.datatransfer.ncbi.NCBIReleaseFileReader;
import org.zfin.datatransfer.ncbi.NCBIReleaseFileSet;
import org.zfin.datatransfer.ncbi.dto.RefSeqCatalogDTO;
import org.zfin.datatransfer.ncbi.entity.NcbiRefSeqCatalog;
import org.zfin.datatransfer.persistence.LoadFileLog;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * Downloads and loads the NCBI RefSeq catalog file into the persistent
 * external_resource.ncbi_refseq_catalog table. Follows the same MD5-dedup
 * and batch-flush pattern as NcbiGeneInfoService.
 */
@Log4j2
public class NcbiRefSeqCatalogService {

    private static final String PERSISTENT_TABLE_NAME = "external_resource.ncbi_refseq_catalog";
    private static final String LOAD_NAME = "NCBI_refseq_catalog";
    private static final String DEFAULT_SOURCE_URL = "https://ftp.ncbi.nlm.nih.gov/refseq/release/release-catalog/";

    /**
     * Load RefSeq catalog data from a pre-downloaded file set into the persistent table.
     * Skips reload if the file's MD5 matches the most recent load.
     */
    public static void loadIntoPersistentTable(Session session, NCBIReleaseFileSet fileSet) throws IOException {
        File inputFile = fileSet.getRefSeqCatalog();
        NCBIReleaseFileReader reader = new NCBIReleaseFileReader(fileSet);
        List<RefSeqCatalogDTO> records = reader.readRefSeqCatalogFile();

        String md5 = computeMd5(inputFile);
        if (alreadyLoaded(session, md5)) {
            log.info("RefSeq catalog file MD5 matches last load ({}). Skipping.", md5);
            return;
        }

        session.createNativeQuery("DELETE FROM " + PERSISTENT_TABLE_NAME).executeUpdate();
        session.flush();

        log.info("Loading {} RefSeq catalog records into {}...", records.size(), PERSISTENT_TABLE_NAME);
        int count = 0;
        int batchSize = 50;
        for (RefSeqCatalogDTO dto : records) {
            NcbiRefSeqCatalog entity = toEntity(dto);
            session.persist(entity);
            count++;
            if (count % batchSize == 0) {
                session.flush();
                session.clear();
            }
        }
        session.flush();
        log.info("Loaded {} records into {}.", count, PERSISTENT_TABLE_NAME);

        logLoad(session, inputFile, md5, count);
    }

    private static NcbiRefSeqCatalog toEntity(RefSeqCatalogDTO dto) {
        NcbiRefSeqCatalog entity = new NcbiRefSeqCatalog();
        entity.setAccessionVersioned(dto.accessionVersion());
        entity.setAccession(stripVersion(dto.accessionVersion()));
        entity.setLength(parseLength(dto.length()));
        return entity;
    }

    private static String stripVersion(String accessionVersion) {
        if (accessionVersion == null || "-".equals(accessionVersion)) {
            return null;
        }
        int dot = accessionVersion.indexOf('.');
        return dot > 0 ? accessionVersion.substring(0, dot) : accessionVersion;
    }

    private static Integer parseLength(String length) {
        if (length == null || "-".equals(length) || length.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(length);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String computeMd5(File file) {
        try {
            return DigestUtils.md5Hex(FileUtils.openInputStream(file));
        } catch (IOException e) {
            throw new RuntimeException("Failed to compute MD5 for: " + file.getAbsolutePath(), e);
        }
    }

    private static boolean alreadyLoaded(Session session, String md5) {
        LoadFileLog existing = session.createQuery(
                        "from LoadFileLog where tableName = :tableName order by processedDate desc",
                        LoadFileLog.class)
                .setParameter("tableName", PERSISTENT_TABLE_NAME)
                .setMaxResults(1)
                .uniqueResult();
        return existing != null && md5.equals(existing.getMd5());
    }

    private static void logLoad(Session session, File inputFile, String md5, int recordCount) {
        LoadFileLog logEntry = new LoadFileLog();
        logEntry.setLoadName(LOAD_NAME);
        logEntry.setFilename(inputFile.getName());
        logEntry.setSource(DEFAULT_SOURCE_URL);
        logEntry.setDate(new Date());
        logEntry.setSize(inputFile.length());
        logEntry.setMd5(md5);
        logEntry.setPath(inputFile.getAbsolutePath());
        logEntry.setProcessedDate(new Date());
        logEntry.setTableName(PERSISTENT_TABLE_NAME);
        logEntry.setNotes("Loaded " + recordCount + " records");
        session.persist(logEntry);
    }
}
