package org.zfin.report;

import org.zfin.datatransfer.report.model.LoadReportAction;
import org.zfin.datatransfer.report.model.LoadReportActionLink;
import org.zfin.datatransfer.report.model.LoadReportActionTag;
import org.zfin.datatransfer.report.model.LoadReportSummaryTable;
import org.zfin.datatransfer.report.model.LoadReportTableHeader;
import org.zfin.datatransfer.report.model.ZfinReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a legacy {@link ZfinReport} (flat action list with type/subType)
 * into the new {@link Report} (recursive node tree). Lets producers like
 * {@code NCBIReportBuilder} adopt the new viewer without changing how they
 * collect their data.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@code meta.title}             → {@code Report.meta.title}</li>
 *   <li>{@code meta.releaseID}         → {@code Report.meta.subtitle} (when non-empty)</li>
 *   <li>{@code meta.creationDate}      → {@code Report.meta.createdAt}</li>
 *   <li>{@code summary.description}    → root {@code body}</li>
 *   <li>{@code summary.tables}         → root {@code tables}</li>
 *   <li>{@code supplementalData}       → top-level {@code blobs}</li>
 *   <li>{@code actions}                → grouped by {@code type} → {@code subType} → action leaf
 *       (typeNode and subTypeNode are structural; action leaves carry fields/body/tables/links/tags)</li>
 *   <li>{@code action.relatedActionsKeys} → not translated (the new schema's
 *       drill-down via subType groups already aggregates related items)</li>
 * </ul>
 */
public class LegacyReportAdapter {

    public Report adapt(ZfinReport legacy) {
        Report report = new Report()
            .meta(adaptMeta(legacy))
            .definitions(buildDefinitions());

        if (legacy.getSupplementalData() != null) {
            for (Map.Entry<String, Object> e : legacy.getSupplementalData().entrySet()) {
                if (e.getValue() != null) report.addBlob(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        ReportNode root = new ReportNode().id("_root");
        if (legacy.getMeta() != null && legacy.getMeta().getTitle() != null) {
            root.title(legacy.getMeta().getTitle());
        }
        if (legacy.getSummary() != null) {
            String desc = legacy.getSummary().getDescription();
            if (desc != null && !desc.isEmpty()) root.body(Report.Body.text(desc));
            if (legacy.getSummary().getTables() != null) {
                for (LoadReportSummaryTable t : legacy.getSummary().getTables()) {
                    root.addTable(adaptTable(t));
                }
            }
        }

        Map<String, Map<String, List<LoadReportAction>>> grouped = groupActions(legacy.getActions());
        for (Map.Entry<String, Map<String, List<LoadReportAction>>> typeEntry : grouped.entrySet()) {
            String type = typeEntry.getKey();
            ReportNode typeNode = new ReportNode()
                .id("type-" + slugify(type))
                .title(type)
                .categoryRef(type);
            int typeTotal = 0;
            int subIdx = 0;
            for (Map.Entry<String, List<LoadReportAction>> stEntry : typeEntry.getValue().entrySet()) {
                String subType = stEntry.getKey();
                List<LoadReportAction> actions = stEntry.getValue();
                ReportNode subTypeNode = new ReportNode()
                    .id("type-" + slugify(type) + "-st-" + subIdx++ + "-" + slugify(subType))
                    .title(subType)
                    .categoryRef(type)
                    .count(actions.size());

                int actionIdx = 0;
                for (LoadReportAction a : actions) {
                    subTypeNode.addChild(adaptAction(a, type, subTypeNode.getId() + "-a-" + actionIdx++));
                }
                typeNode.addChild(subTypeNode);
                typeTotal += actions.size();
            }
            typeNode.count(typeTotal);
            root.addChild(typeNode);
        }

        report.root(root);
        return report;
    }

    private Report.Meta adaptMeta(ZfinReport legacy) {
        Report.Meta meta = new Report.Meta().schemaVersion("1");
        if (legacy.getMeta() != null) {
            meta.title(legacy.getMeta().getTitle());
            meta.createdAt(legacy.getMeta().getCreationDate());
            String releaseID = legacy.getMeta().getReleaseID();
            if (releaseID != null && !releaseID.isEmpty()) {
                meta.subtitle("Release: " + releaseID);
            }
        }
        return meta;
    }

    /** Defines the legacy action-type enum values as categories. */
    private Report.Definitions buildDefinitions() {
        return new Report.Definitions()
            .category("LOAD",    new Report.CategoryDef().label("Load").icon("✅").order(1))
            .category("UPDATE",  new Report.CategoryDef().label("Update").icon("✏️").order(2))
            .category("DELETE",  new Report.CategoryDef().label("Delete").icon("🗑").order(3))
            .category("ERROR",   new Report.CategoryDef().label("Error").icon("❌").order(4))
            .category("WARNING", new Report.CategoryDef().label("Warning").icon("⚠️").order(5))
            .category("INFO",    new Report.CategoryDef().label("Info").icon("ℹ️").order(6))
            .category("DUPES",   new Report.CategoryDef().label("Duplicates").icon("📑").order(7))
            .category("IGNORE",  new Report.CategoryDef().label("Ignored").icon("·").order(8))
            .category("REPORTS", new Report.CategoryDef().label("Reports").icon("📊").order(9));
    }

    private ReportNode adaptAction(LoadReportAction a, String typeRef, String idFallback) {
        String id = a.getId() != null ? String.valueOf(a.getId()) : idFallback;
        ReportNode n = new ReportNode()
            .id(id)
            .title(buildActionTitle(a))
            .categoryRef(typeRef);

        if (a.getAccession() != null)        n.field("accession", a.getAccession());
        if (a.getGeneZdbID() != null)        n.field("geneZdbID", a.getGeneZdbID());
        if (a.getDbName() != null)           n.field("database", a.getDbName());
        if (a.getMd5() != null)              n.field("md5", a.getMd5());
        if (a.getLength() != null)           n.field("length", a.getLength());
        if (a.getRelatedEntityID() != null)  n.field("relatedEntityID", a.getRelatedEntityID());
        if (a.getRelatedEntityFields() != null) {
            for (Map.Entry<String, Object> e : a.getRelatedEntityFields().entrySet()) {
                n.field(e.getKey(), e.getValue());
            }
        }
        if (a.getUniprotAccessions() != null && !a.getUniprotAccessions().isEmpty()) {
            n.field("uniprotAccessions", String.join(", ", a.getUniprotAccessions()));
        }

        if (a.getDetails() != null && !a.getDetails().isEmpty()) {
            n.body(Report.Body.text(a.getDetails()));
        }

        if (a.getTables() != null) {
            for (LoadReportSummaryTable t : a.getTables()) {
                n.addTable(adaptTable(t));
            }
        }

        if (a.getLinks() != null) {
            for (LoadReportActionLink l : a.getLinks()) {
                if (l != null && l.getTitle() != null && l.getHref() != null) {
                    n.addLink(l.getTitle(), l.getHref());
                }
            }
        }

        if (a.getTags() != null) {
            for (LoadReportActionTag t : a.getTags()) {
                if (t != null && t.getName() != null) n.addTag(t.getName());
            }
        }

        if (a.getSupplementalDataKeys() != null) {
            for (String k : a.getSupplementalDataKeys()) n.addBlobRef(k);
        }

        return n;
    }

    private ReportTable adaptTable(LoadReportSummaryTable t) {
        ReportTable table = new ReportTable();
        if (t.getDescription() != null) table.title(t.getDescription());
        if (t.getHeaders() != null) {
            for (LoadReportTableHeader h : t.getHeaders()) {
                table.addColumn(ReportTable.Column.of(h.getKey(), h.getTitle()));
            }
        }
        if (t.getRows() != null) {
            for (Map<String, Object> row : t.getRows()) {
                table.addRow(new LinkedHashMap<>(row));
            }
        }
        return table;
    }

    private static String buildActionTitle(LoadReportAction a) {
        if (a.getAccession() != null && a.getGeneZdbID() != null) {
            return a.getGeneZdbID() + " — " + a.getAccession();
        }
        if (a.getAccession() != null) return a.getAccession();
        if (a.getGeneZdbID() != null) return a.getGeneZdbID();
        return a.getSubType() != null ? a.getSubType() : "Item";
    }

    private static Map<String, Map<String, List<LoadReportAction>>> groupActions(List<LoadReportAction> actions) {
        Map<String, Map<String, List<LoadReportAction>>> result = new LinkedHashMap<>();
        if (actions == null) return result;
        for (LoadReportAction a : actions) {
            String type = a.getType() != null ? a.getType().name() : "INFO";
            String sub = a.getSubType() != null ? a.getSubType() : "(no subtype)";
            result.computeIfAbsent(type, k -> new LinkedHashMap<>())
                  .computeIfAbsent(sub, k -> new ArrayList<>())
                  .add(a);
        }
        return result;
    }

    private static String slugify(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
