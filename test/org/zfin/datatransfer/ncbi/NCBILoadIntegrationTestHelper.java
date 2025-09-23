package org.zfin.datatransfer.ncbi;

import org.hibernate.Session;
import org.zfin.framework.HibernateUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Helper class for NCBI load integration tests
 */
public class NCBILoadIntegrationTestHelper {

    public static final String ZF_GENE_INFO_HEADER = "#tax_id\tGeneID\tSymbol\tLocusTag\tSynonyms\tdbXrefs\tchromosome\tmap_location\tdescription\ttype_of_gene\tSymbol_from_nomenclature_authority\tFull_name_from_nomenclature_authority\tNomenclature_status\tOther_designations\tModification_date\tFeature_type\n";
    public static final String GENE_2_ACCESSION_HEADER = "#tax_id\tGeneID\tstatus\tRNA_nucleotide_accession.version\tRNA_nucleotide_gi\tprotein_accession.version\tprotein_gi\tgenomic_nucleotide_accession.version\tgenomic_nucleotide_gi\tstart_position_on_the_genomic_accession\tend_position_on_the_genomic_accession\torientation\tassembly\tmature_peptide_accession.version\tmature_peptide_gi\tSymbol\n";
    public static final String NCBI_FDBCONT = "ZDB-FDBCONT-040412-1";
    public static final String GENBANK_FDBCONT = "ZDB-FDBCONT-040412-37";
    public static final String ENSDARG_FDBCONT = "ZDB-FDBCONT-200123-1";
    public static final String OTTDARG_FDBCONT = "ZDB-FDBCONT-040412-14";
    private static final String REFSEQ_CATALOG_ARCHIVE = "/research/zarchive/load_files/NCBI-gene-load-archive/2025-09-23/RefSeqCatalog.gz";

    private final Path tempDir;

    public NCBILoadIntegrationTestHelper(Path tempDir) {
        this.tempDir = tempDir;
    }

    public void createDBLink(String geneID, String accNum, String fdbcontID, String pubID) {
        String dbLinkID = createActiveDataID("DBLINK");

        String sql = """
                INSERT INTO db_link ("dblink_linked_recid", "dblink_acc_num", "dblink_zdb_id", "dblink_fdbcont_zdb_id")
                VALUES (?, ?, ?, ?)
                """;
        HibernateUtil.currentSession().createNativeQuery(sql)
                .setParameter(1, geneID)
                .setParameter(2, accNum)
                .setParameter(3, dbLinkID)
                .setParameter(4, fdbcontID)
                .executeUpdate();

        String attribSql = """
                INSERT INTO record_attribution (recattrib_data_zdb_id, recattrib_source_zdb_id, recattrib_source_type)
                VALUES (?, ?, 'standard')
                """;
        HibernateUtil.currentSession().createNativeQuery(attribSql)
                .setParameter(1, dbLinkID)
                .setParameter(2, pubID)
                .executeUpdate();
    }

    private void createVegaLink(BeforeStateBuilder.VegaData vega) {
        String sql = """
                DROP TRIGGER if exists marker_relationship_trigger ON marker_relationship;
                """;
        HibernateUtil.currentSession().createNativeQuery(sql).executeUpdate();

        String id = createTranscript(vega.ottdargAbbrev);
        createDBLink(id, vega.ottdargId, OTTDARG_FDBCONT, "ZDB-PUB-030703-1");
        createMarkerRelationship(vega.geneId, id, "gene produces transcript");
    }

    private void createMarkerRelationship(String geneId, String id, String mrelType) {
        String mrelID = createActiveDataID("MREL");
        String sql = """
                INSERT INTO marker_relationship (mrel_zdb_id, mrel_type, mrel_mrkr_1_zdb_id, mrel_mrkr_2_zdb_id)
                VALUES (?,?,?,?)
                """;
        HibernateUtil.currentSession().createNativeQuery(sql)
                .setParameter(1, mrelID)
                .setParameter(2, mrelType)
                .setParameter(3, geneId)
                .setParameter(4, id)
                .executeUpdate();
    }

    public void createGene(String geneID, String geneAbbrev) {
        String activeDataSql = "INSERT INTO zdb_active_data (zactvd_zdb_id) VALUES (?)";
        HibernateUtil.currentSession().createNativeQuery(activeDataSql)
                .setParameter(1, geneID)
                .executeUpdate();

        String sql = """
                INSERT INTO marker ("mrkr_zdb_id", "mrkr_name", "mrkr_abbrev", "mrkr_type", "mrkr_owner")
                VALUES (?, ?, ?, 'GENE', ?)
                """;
        HibernateUtil.currentSession().createNativeQuery(sql)
                .setParameter(1, geneID)
                .setParameter(2, geneAbbrev)
                .setParameter(3, geneAbbrev)
                .setParameter(4, "ZDB-PERS-990902-1")
                .executeUpdate();
    }

    public String createActiveDataID(String type) {
        String dateYYMMDD = new SimpleDateFormat("yyMMdd").format(new Date());
        String currentTime = "%05d".formatted(System.currentTimeMillis() % 100000);
        String dbID = "ZDB-" + type + "-" + dateYYMMDD + "-" + currentTime;

        String activeDataSql = "INSERT INTO zdb_active_data (zactvd_zdb_id) VALUES (?)";
        HibernateUtil.currentSession().createNativeQuery(activeDataSql)
                .setParameter(1, dbID)
                .executeUpdate();

        return dbID;
    }

    public String createTranscript(String markerAbbrev) {
        String tscriptID =  createActiveDataID("TSCRIPT");

        String sql = """
                INSERT INTO marker ("mrkr_zdb_id", "mrkr_name", "mrkr_abbrev", "mrkr_type", "mrkr_owner")
                VALUES (?, ?, ?, 'TSCRIPT', ?)
                """;
        HibernateUtil.currentSession().createNativeQuery(sql)
                .setParameter(1, tscriptID)
                .setParameter(2, markerAbbrev)
                .setParameter(3, markerAbbrev)
                .setParameter(4, "ZDB-PERS-990902-1")
                .executeUpdate();

        return tscriptID;
    }

    public void createTestFiles() throws IOException {
        System.out.println("Creating test files in: " + tempDir);
        System.out.flush();

        // Create gene2accession.gz
        Files.writeString(tempDir.resolve("gene2accession"), GENE_2_ACCESSION_HEADER);

        // Create zf_gene_info.gz
        Files.writeString(tempDir.resolve("zf_gene_info"), ZF_GENE_INFO_HEADER);

        // Create other required files (empty)
        Files.writeString(tempDir.resolve("gene2vega"), "");
        Files.writeString(tempDir.resolve("seq.fasta"), "");
        Files.writeString(tempDir.resolve("notInCurrentReleaseGeneIDs.unl"), "");
        Files.writeString(tempDir.resolve("RELEASE_NUMBER"), "231");
        Files.writeString(tempDir.resolve("ncbi_matches_through_ensembl.csv"),
                "ncbi_id,zdb_id,ensembl_id,symbol,dblinks,publications,rna_accessions");

        // Compress required files
        gzipFile(tempDir.resolve("gene2accession"));
        gzipFile(tempDir.resolve("zf_gene_info"));
        gzipFile(tempDir.resolve("gene2vega"));

        Files.copy(Path.of(REFSEQ_CATALOG_ARCHIVE), tempDir.resolve("RefSeqCatalog.gz"));
    }

    public void fillTestFiles(String gene2AccessionContents, String zfGeneInfoContents) throws IOException {
        fillTestFiles(gene2AccessionContents, zfGeneInfoContents, false);
    }

    public void fillTestFiles(String gene2AccessionContents, String zfGeneInfoContents, Boolean append) throws IOException {
        if (!append) {
            resetGene2AccessionFile();
            resetZfGeneInfoFile();
        }

        Path g2a = tempDir.resolve("gene2accession.gz");
        Path zfi = tempDir.resolve("zf_gene_info.gz");
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(zfi.toFile(), true));
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(zfGeneInfoContents);
        }
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(g2a.toFile(), true));
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(gene2AccessionContents);
        }
    }

    public void fillTestFile(String filename, String contents) {
        File file = tempDir.resolve(filename).toFile();
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println(contents);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write test file: " + filename, e);
        }
    }

    private void resetGene2AccessionFile() throws IOException {
        Path g2a = tempDir.resolve("gene2accession.gz");
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(g2a.toFile()));
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(GENE_2_ACCESSION_HEADER);
        }
    }

    private void resetZfGeneInfoFile() throws IOException {
        Path zfi = tempDir.resolve("zf_gene_info.gz");
        try (OutputStream os = new GZIPOutputStream(new FileOutputStream(zfi.toFile()));
             PrintWriter w = new PrintWriter(new OutputStreamWriter(os))) {
            w.println(ZF_GENE_INFO_HEADER);
        }
    }

    private static void gzipFile(Path filePath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("gzip", "-f", filePath.toString());
        try {
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            throw new IOException("Failed to gzip file: " + filePath, e);
        }
    }

    public void deleteAllMarkersAndDBLinks() {
        String sql = """
            DELETE FROM zdb_active_data
            WHERE zactvd_zdb_id IN (
                SELECT dblink_zdb_id FROM db_link
            )
            """;

        HibernateUtil.currentSession().createNativeQuery(sql)
                .executeUpdate();

        sql = "DELETE FROM marker_annotation_status";
        HibernateUtil.currentSession().createNativeQuery(sql)
                .executeUpdate();

        sql = """
            DELETE FROM zdb_active_data
            WHERE zactvd_zdb_id IN (
                SELECT mrkr_zdb_id FROM marker
            )
            """;

        HibernateUtil.currentSession().createNativeQuery(sql)
                .executeUpdate();
    }

    public void runNCBILoad() {
        File ncbiMatchFile = tempDir.resolve("ncbi_matches_through_ensembl.csv").toFile();

        // Set environment variables
        System.setProperty("WORKING_DIR", tempDir.toString());
        System.setProperty("NO_SLEEP", "1");
        System.setProperty("SKIP_DOWNLOADS", "1");
        System.setProperty("LOAD_NCBI_ONE_WAY_GENES", "1");
        System.setProperty("DB_NAME", "zfindb");
        System.setProperty("SKIP_COMPRESS_ARTIFACTS", "1");

        // Run the load
        NCBIDirectPort port = new NCBIDirectPort();
        port.initAll();
        port.run();
    }

    public int getNCBILinkCount(String geneId) {
        String sql = """
            SELECT COUNT(*) FROM db_link
            WHERE dblink_linked_recid = ?
            AND dblink_fdbcont_zdb_id = ?
            """;

        Number result = (Number) HibernateUtil.currentSession()
                .createNativeQuery(sql)
                .setParameter(1, geneId)
                .setParameter(2, NCBI_FDBCONT)
                .uniqueResult();

        return result != null ? result.intValue() : 0;
    }

    public List<String> getNCBILinks(String geneId) {
        String sql = """
            SELECT dblink_acc_num FROM db_link
            WHERE dblink_linked_recid = ?
            AND dblink_fdbcont_zdb_id = ?
            ORDER BY dblink_acc_num
            """;

        return HibernateUtil.currentSession()
                .createNativeQuery(sql)
                .setParameter(1, geneId)
                .setParameter(2, NCBI_FDBCONT)
                .list();
    }

    public int getAttributionCount(String geneId) {
        String sql = """
            SELECT COUNT(*) FROM db_link dl
            JOIN record_attribution ra ON dl.dblink_zdb_id = ra.recattrib_data_zdb_id
            WHERE dl.dblink_linked_recid = ?
            AND dl.dblink_fdbcont_zdb_id = ?
            """;

        Number result = (Number) HibernateUtil.currentSession()
                .createNativeQuery(sql)
                .setParameter(1, geneId)
                .setParameter(2, NCBI_FDBCONT)
                .uniqueResult();

        return result != null ? result.intValue() : 0;
    }

    // ========== Builder Pattern Classes ==========

    /**
     * Builder for setting up test state before NCBI load runs
     */
    public BeforeStateBuilder beforeStateBuilder() {
        return new BeforeStateBuilder(this);
    }

    /**
     * Get after state for verification
     */
    public AfterState getAfterState() {
        return new AfterState(this);
    }

    public static class BeforeStateBuilder {
        private final NCBILoadIntegrationTestHelper helper;
        private final List<GeneData> genes = new ArrayList<>();
        private final List<DBLinkData> dbLinks = new ArrayList<>();
        private final List<VegaData> vegaLinks = new ArrayList<>();
        private final List<String> zfGeneInfoLines = new ArrayList<>();
        private final List<String> gene2AccessionLines = new ArrayList<>();
        private final List<String> gene2VegaLines = new ArrayList<>();

        public BeforeStateBuilder(NCBILoadIntegrationTestHelper helper) {
            this.helper = helper;
        }

        public BeforeStateBuilder withGene(String geneId, String geneAbbrev) {
            genes.add(new GeneData(geneId, geneAbbrev));
            return this;
        }

        public BeforeStateBuilder withDBLink(String geneId, String accNum, String fdbcontId, String pubId) {
            dbLinks.add(new DBLinkData(geneId, accNum, fdbcontId, pubId));
            return this;
        }

        public BeforeStateBuilder withZfGeneInfoFile(String line) {
            zfGeneInfoLines.add(line);
            return this;
        }

        public BeforeStateBuilder withGene2AccessionFile(String line) {
            gene2AccessionLines.add(line);
            return this;
        }

        public BeforeStateBuilder withGene2VegaFile(String line) {
            gene2VegaLines.add(line);
            return this;
        }

        public void build() throws IOException {
            // Setup database state
            Session session = HibernateUtil.currentSession();
            session.beginTransaction();
            helper.deleteAllMarkersAndDBLinks();

            for (GeneData gene : genes) {
                helper.createGene(gene.geneId, gene.geneAbbrev);
            }

            for (DBLinkData dbLink : dbLinks) {
                helper.createDBLink(dbLink.geneId, dbLink.accNum, dbLink.fdbcontId, dbLink.pubId);
            }

            for (VegaData vega : vegaLinks) {
                helper.createVegaLink(vega);
            }

            session.getTransaction().commit();

            // Setup test files
            if (!zfGeneInfoLines.isEmpty() || !gene2AccessionLines.isEmpty()) {
                String zfGeneInfo = String.join("\n", zfGeneInfoLines);
                String gene2Accession = String.join("\n", gene2AccessionLines);
                helper.fillTestFiles(gene2Accession, zfGeneInfo);
            }
            if (!gene2VegaLines.isEmpty()) {
                String gene2Vega = String.join("\n", gene2VegaLines);
                String header = "#tax_id\tGeneID\tVega_gene_identifier\tRNA_nucleotide_accession.version\tVega_rna_identifier\tprotein_accession.version\tVega_protein_identifier";
                helper.fillTestFile("gene2vega", header + "\n" + gene2Vega);
                helper.gzipFile(helper.tempDir.resolve("gene2vega"));
            }
        }

        public BeforeStateBuilder withVega(String geneZdbID, String ottdargID, String ottdargAbbrev) {
            this.vegaLinks.add(new VegaData(geneZdbID, ottdargID, ottdargAbbrev));
            return this;
        }

        private record GeneData(String geneId, String geneAbbrev) {}
        private record DBLinkData(String geneId, String accNum, String fdbcontId, String pubId) {}
        private record VegaData(String geneId, String ottdargId, String ottdargAbbrev) {}
    }

    public static class AfterState {
        private final NCBILoadIntegrationTestHelper helper;

        public AfterState(NCBILoadIntegrationTestHelper helper) {
            this.helper = helper;
        }

        public OutputFile getFile(String filename) {
            Path filePath = helper.tempDir.resolve(filename);
            return new OutputFile(filePath);
        }

        public int getNCBILinkCount(String geneId) {
            return helper.getNCBILinkCount(geneId);
        }

        public List<String> getNCBILinks(String geneId) {
            return helper.getNCBILinks(geneId);
        }

        public int getAttributionCount(String geneId) {
            return helper.getAttributionCount(geneId);
        }
    }

    public static class OutputFile {
        private final Path filePath;

        public OutputFile(Path filePath) {
            this.filePath = filePath;
        }

        public boolean exists() {
            return Files.exists(filePath);
        }

        public long getLineCount() {
            try {
                return Files.lines(filePath).count();
            } catch (IOException e) {
                throw new RuntimeException("Failed to count lines in: " + filePath, e);
            }
        }

        public List<String> getDataLines() {
            try {
                return Files.lines(filePath).skip(1).toList(); // Skip header
            } catch (IOException e) {
                throw new RuntimeException("Failed to read data lines from: " + filePath, e);
            }
        }

        public String getContent() {
            try {
                return Files.readString(filePath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read content from: " + filePath, e);
            }
        }

        public boolean matches(String regex) {
            List<String> lines = getDataLines();
            return lines.stream().anyMatch(line -> line.matches(regex));
        }
    }
}
