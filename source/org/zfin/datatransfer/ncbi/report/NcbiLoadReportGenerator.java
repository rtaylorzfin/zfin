package org.zfin.datatransfer.ncbi.report;

import lombok.extern.log4j.Log4j2;
import org.zfin.datatransfer.ncbi.NCBIReportBuilder;
import org.zfin.datatransfer.ncbi.matching.MatchResult;
import org.zfin.datatransfer.report.model.ZfinReport;
import org.zfin.properties.ZfinPropertiesEnum;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Generates the NCBI load report using before/after statistics and match results.
 * Produces the same summary tables as the old NCBIDirectPort report.
 */
@Log4j2
public class NcbiLoadReportGenerator {

    private final NcbiLoadStatistics before;
    private final NcbiLoadStatistics after;
    private final MatchResult matches;
    private final File outputDir;

    public NcbiLoadReportGenerator(NcbiLoadStatistics before, NcbiLoadStatistics after,
                                    MatchResult matches, File outputDir) {
        this.before = before;
        this.after = after;
        this.matches = matches;
        this.outputDir = outputDir;
    }

    public void generate() {
        NCBIReportBuilder builder = NCBIReportBuilder.create();
        builder.setTitle("NCBI Load Report");
        builder.setInstance(ZfinPropertiesEnum.INSTANCE.toString());

        // Table 1: db_link record counts per accession type
        NCBIReportBuilder.SummaryTableBuilder table1 =
                builder.addSummaryTable("number of db_link records with gene");
        table1.setHeaders(
                new String[]{"category", "before", "after", "change"},
                new String[]{"Category", "Before Load", "After Load", "Percentage Change"});

        Map<String, Integer> beforeCounts = before.toSummaryMap();
        Map<String, Integer> afterCounts = after.toSummaryMap();
        for (String category : beforeCounts.keySet()) {
            table1.addBeforeAfterCountSummaryRow(category,
                    beforeCounts.get(category), afterCounts.get(category));
        }

        // Table 2: gene-level counts
        NCBIReportBuilder.SummaryTableBuilder table2 =
                builder.addSummaryTable("number of genes");
        table2.setHeaders(
                new String[]{"category", "before", "after", "change"},
                new String[]{"Category", "Before Load", "After Load", "Percentage Change"});

        Map<String, Integer> beforeGenes = before.toGeneSummaryMap();
        Map<String, Integer> afterGenes = after.toGeneSummaryMap();
        for (String category : beforeGenes.keySet()) {
            table2.addBeforeAfterCountSummaryRow(category,
                    beforeGenes.get(category), afterGenes.get(category));
        }

        // Table 3: match summary
        NCBIReportBuilder.SummaryTableBuilder table3 =
                builder.addSummaryTable("matching summary");
        table3.setHeaders(
                new String[]{"category", "count"},
                new String[]{"Category", "Count"});
        table3.addSummaryRow(java.util.List.of(
                "RNA reciprocal matches", String.valueOf(matches.getConfirmed().size())));
        table3.addSummaryRow(java.util.List.of(
                "Ensembl supplement matches", String.valueOf(matches.getSupplement().size())));
        table3.addSummaryRow(java.util.List.of(
                "Vega legacy matches", String.valueOf(matches.getLegacyVega().size())));
        table3.addSummaryRow(java.util.List.of(
                "1:N conflicts (ZFIN→NCBI)", String.valueOf(matches.getOneToN().size())));
        table3.addSummaryRow(java.util.List.of(
                "N:1 conflicts (NCBI→ZFIN)", String.valueOf(matches.getNToOne().size())));

        // Write report
        ZfinReport report = builder.buildZfinReport();
        try {
            File reportFile = new File(outputDir, "ncbi_report.json");
            builder.writeJsonToFile(report, reportFile);
            log.info("Wrote NCBI load report to {}", reportFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write NCBI load report", e);
        }
    }
}
