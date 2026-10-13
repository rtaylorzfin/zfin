package org.zfin.datatransfer.go.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.zfin.report.Report;
import org.zfin.report.ReportNode;
import org.zfin.report.ReportTable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Tests the Removed table's "Loss type" / "Covering annotation(s)" enrichment against a small
 * hand-built dbdiff directory (before/after snapshots plus a subsumption workbook).
 */
public class GafCutoverImpactSummaryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SNAPSHOT_HEADER = "zdb_id,org,marker,gene,term,go_id,go_term,go_aspect,"
        + "source,evidence,relation,relation_name,created_by,contributed_by,protein_acc,qualifier_flag,"
        + "inferred_from,annotation_extensions,noctua_model";

    private static String snapshotRow(String zdbId, String org, String gene, String goId,
                                      String evidence, String source, String qualifierFlag) {
        return String.join(",", zdbId, org, "ZDB-GENE-" + gene, gene, "ZDB-TERM-" + goId, goId,
            "term " + goId, "F", source, evidence, "-", "", "", "-", "-", qualifierFlag, "", "", "");
    }

    @Test
    public void classifiesRemovedRowsAndListsCoveringAnnotations() throws IOException {
        File dir = tmp.newFolder("dbdiff");
        Files.write(new File(dir, "mgte_before_ALL.csv").toPath(), List.of(SNAPSHOT_HEADER,
            snapshotRow("ZDB-MRKRGOEV-1", "GOA", "g1", "GO:1", "IBA", "ZDB-PUB-9", "-"),
            snapshotRow("ZDB-MRKRGOEV-2", "UniProt", "g2", "GO:2", "IEA", "ZDB-PUB-9", "-"),
            snapshotRow("ZDB-MRKRGOEV-3", "Noctua", "g3", "GO:3", "IDA", "ZDB-PUB-9", "-"),
            snapshotRow("ZDB-MRKRGOEV-4", "Noctua", "g4", "GO:5", "IDA", "ZDB-PUB-9", "not")));
        Files.write(new File(dir, "mgte_after_ALL.csv").toPath(), List.of(SNAPSHOT_HEADER,
            // g1 GO:1 re-homed to PAINT: replaced.
            snapshotRow("ZDB-MRKRGOEV-11", "PAINT", "g1", "GO:1", "IBA", "ZDB-PUB-1", "-"),
            // g2 GO:2 survives only as NOT: opposite polarity, so not a replacement.
            snapshotRow("ZDB-MRKRGOEV-12", "Noctua", "g2", "GO:2", "IMP", "ZDB-PUB-2", "not"),
            // g4 GO:5 survives only as positive while the removed one was NOT.
            snapshotRow("ZDB-MRKRGOEV-14", "Noctua", "g4", "GO:5", "IDA", "ZDB-PUB-4", "-")));

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            sheet(wb, "mgte_subsumption_true_loss",
                List.of("bucket", "org", "gene", "go_id", "is_not"),
                List.of("true_loss", "UniProt", "g2", "GO:2", "f"));
            sheet(wb, "mgte_subsumption_subsumed",
                List.of("bucket", "org", "gene", "go_id", "is_not"),
                List.of("subsumed", "Noctua", "g3", "GO:3", "f"));
            sheet(wb, "mgte_subsumption_covering",
                List.of("bucket", "gene", "go_id", "is_not", "covering_go_id", "covering_go_term",
                    "org", "source", "evidence", "covering_zdb_id"),
                List.of("subsumed", "g3", "GO:3", "f", "GO:4", "child", "Noctua", "ZDB-PUB-3", "IDA", "ZDB-MRKRGOEV-13"),
                List.of("subsumed", "g3", "GO:3", "f", "GO:4", "child", "PAINT", "ZDB-PUB-5", "IBA", "ZDB-MRKRGOEV-15"));
            try (FileOutputStream out = new FileOutputStream(new File(dir, "mgte_subsumption.xlsx"))) {
                wb.write(out);
            }
        }

        ReportTable removed = new ReportTable()
            .addColumn(ReportTable.Column.of("zdbID", "ZDB ID"))
            .addColumn(ReportTable.Column.of("marker", "Gene"))
            .addColumn(ReportTable.Column.of("goTermID", "GO ID"));
        for (String[] r : new String[][]{
            {"ZDB-MRKRGOEV-1", "g1", "GO:1"}, {"ZDB-MRKRGOEV-2", "g2", "GO:2"},
            {"ZDB-MRKRGOEV-3", "g3", "GO:3"}, {"ZDB-MRKRGOEV-4", "g4", "GO:5"}}) {
            removed.addRow("zdbID", r[0], "marker", r[1], "goTermID", r[2]);
        }
        Report report = new Report().root(new ReportNode().id("root")
            .addChild(new ReportNode().id("cat-removed").addTable(removed)));

        GafCutoverImpactSummary summary = new GafCutoverImpactSummary();
        summary.enrichRemovedWithLossType(report, dir);
        summary.enrichRemovedWithLossType(report, dir); // idempotent

        Map<String, Map<String, Object>> byId = removed.getRows().stream()
            .collect(Collectors.toMap(r -> (String) r.get("zdbID"), r -> r));
        assertEquals("replaced", byId.get("ZDB-MRKRGOEV-1").get("lossType"));
        assertEquals("PAINT · IBA · ZDB-PUB-1", byId.get("ZDB-MRKRGOEV-1").get("covering"));
        assertEquals("true_loss", byId.get("ZDB-MRKRGOEV-2").get("lossType"));
        assertEquals("", byId.get("ZDB-MRKRGOEV-2").get("covering"));
        assertEquals("subsumed", byId.get("ZDB-MRKRGOEV-3").get("lossType"));
        assertEquals("GO:4 child (Noctua · IDA · ZDB-PUB-3, PAINT · IBA · ZDB-PUB-5)",
            byId.get("ZDB-MRKRGOEV-3").get("covering"));
        // A NOT annotation is not replaced by a positive one with the same gene and term.
        assertEquals("", byId.get("ZDB-MRKRGOEV-4").get("lossType"));

        List<String> keys = removed.getColumns().stream().map(ReportTable.Column::getKey).toList();
        assertEquals(List.of("zdbID", "marker", "goTermID", "lossType", "covering"), keys);
    }

    @SafeVarargs
    private static void sheet(XSSFWorkbook wb, String name, List<String> header, List<String>... rows) {
        Sheet sheet = wb.createSheet(name);
        writeRow(sheet.createRow(0), header);
        for (int i = 0; i < rows.length; i++) {
            writeRow(sheet.createRow(i + 1), rows[i]);
        }
    }

    private static void writeRow(Row row, List<String> values) {
        for (int c = 0; c < values.size(); c++) {
            row.createCell(c).setCellValue(values.get(c));
        }
    }
}
