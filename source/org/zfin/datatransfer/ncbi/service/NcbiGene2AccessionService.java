package org.zfin.datatransfer.ncbi.service;

import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.hibernate.Session;
import org.zfin.datatransfer.ncbi.NCBIReleaseFileReader;
import org.zfin.datatransfer.ncbi.NCBIReleaseFileSet;
import org.zfin.datatransfer.ncbi.dto.Gene2AccessionDTO;
import org.zfin.datatransfer.ncbi.entity.NcbiGene2Accession;
import org.zfin.datatransfer.persistence.LoadFileLog;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * Downloads and loads the NCBI gene2accession file into the persistent
 * external_resource.ncbi_gene2accession table. Follows the same MD5-dedup
 * and batch-flush pattern as NcbiGeneInfoService.
 */
@Log4j2
public class NcbiGene2AccessionService {

    private static final String PERSISTENT_TABLE_NAME = "external_resource.ncbi_gene2accession";
    private static final String LOAD_NAME = "NCBI_gene2accession";
    private static final String DEFAULT_SOURCE_URL = "https://ftp.ncbi.nlm.nih.gov/gene/DATA/gene2accession.gz";

    /**
     * Load gene2accession data from a pre-downloaded file set into the persistent table.
     * Skips reload if the file's MD5 matches the most recent load.
     */
    public static void loadIntoPersistentTable(Session session, NCBIReleaseFileSet fileSet) throws IOException {
        File inputFile = fileSet.getGene2accession();
        NCBIReleaseFileReader reader = new NCBIReleaseFileReader(fileSet);
        List<Gene2AccessionDTO> records = reader.readGene2AccessionFile();

        String md5 = computeMd5(inputFile);
        if (alreadyLoaded(session, md5)) {
            log.info("gene2accession file MD5 matches last load ({}). Skipping.", md5);
            return;
        }

        session.createNativeQuery("DELETE FROM " + PERSISTENT_TABLE_NAME).executeUpdate();
        session.flush();

        log.info("Loading {} gene2accession records into {}...", records.size(), PERSISTENT_TABLE_NAME);
        int count = 0;
        int batchSize = 50;
        for (Gene2AccessionDTO dto : records) {
            NcbiGene2Accession entity = toEntity(dto);
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

    private static NcbiGene2Accession toEntity(Gene2AccessionDTO dto) {
        NcbiGene2Accession entity = new NcbiGene2Accession();
        entity.setGeneId(dto.geneID());
        entity.setStatus(dashToNull(dto.status()));
        entity.setRnaAccessionVersioned(dashToNull(dto.rnaNucleotideAccessionVersion()));
        entity.setRnaAccession(stripVersion(dto.rnaNucleotideAccessionVersion()));
        entity.setProteinAccessionVersioned(dashToNull(dto.proteinAccessionVersion()));
        entity.setProteinAccession(stripVersion(dto.proteinAccessionVersion()));
        entity.setGenomicAccessionVersioned(dashToNull(dto.genomicNucleotideAccessionVersion()));
        entity.setGenomicAccession(stripVersion(dto.genomicNucleotideAccessionVersion()));
        return entity;
    }

    private static String dashToNull(String value) {
        if (value == null || "-".equals(value)) {
            return null;
        }
        return value;
    }

    private static String stripVersion(String accessionVersion) {
        if (accessionVersion == null || "-".equals(accessionVersion)) {
            return null;
        }
        int dot = accessionVersion.indexOf('.');
        return dot > 0 ? accessionVersion.substring(0, dot) : accessionVersion;
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
