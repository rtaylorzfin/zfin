package org.zfin.datatransfer.ncbi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

//TODO: replace this with a class that matches the new json schema in home/uniprot/zfin-report-schema.json
public class NCBIReportBuilder {

    private ObjectMapper objectMapper;

    public NCBIReportBuilder() {
        this.objectMapper = new ObjectMapper();
    }

    public ObjectNode buildJsonReportData(
            int numNCBIgeneIdBefore, int numNCBIgeneIdAfter,
            int numRefSeqRNABefore, int numRefSeqRNAAfter,
            int numRefPeptBefore, int numRefPeptAfter,
            int numRefSeqDNABefore, int numRefSeqDNAAfter,
            int numGenBankRNABefore, int numGenBankRNAAfter) {

        // Create root object
        ObjectNode jsonReportData = objectMapper.createObjectNode();

        // Create meta section
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("title", "NCBI Load Report");
        meta.put("releaseID", "");
        meta.put("creationDate", System.currentTimeMillis());
        jsonReportData.set("meta", meta);

        // Create summary section
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("description", "NCBI Load: Percentage change of various categories of records");

        // Create tables array
        ArrayNode tables = objectMapper.createArrayNode();

        // Create first table
        ObjectNode firstTable = objectMapper.createObjectNode();
        firstTable.put("description", "First Table");

        // Create headers array
        ArrayNode headers = objectMapper.createArrayNode();
        headers.add(createHeader("desc", "number of db_link records with gene"));
        headers.add(createHeader("before", "before load"));
        headers.add(createHeader("after", "after load"));
        headers.add(createHeader("perc", "percentage change"));
        firstTable.set("headers", headers);

        // Create rows array
        ArrayNode rows = objectMapper.createArrayNode();
        rows.add(createRow("NCBI gene Id", numNCBIgeneIdBefore, numNCBIgeneIdAfter));
        rows.add(createRow("RefSeq RNA", numRefSeqRNABefore, numRefSeqRNAAfter));
        rows.add(createRow("RefPept", numRefPeptBefore, numRefPeptAfter));
        rows.add(createRow("RefSeq DNA", numRefSeqDNABefore, numRefSeqDNAAfter));
        rows.add(createRow("GenBank RNA", numGenBankRNABefore, numGenBankRNAAfter));
        firstTable.set("rows", rows);

        tables.add(firstTable);
        summary.set("tables", tables);
        jsonReportData.set("summary", summary);

        // Create empty actions array
        ArrayNode actions = objectMapper.createArrayNode();
        jsonReportData.set("actions", actions);

        return jsonReportData;
    }

    private ObjectNode createHeader(String key, String title) {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("key", key);
        header.put("title", title);
        return header;
    }

    private ObjectNode createRow(String desc, int before, int after) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("desc", desc);
        row.put("before", before);
        row.put("after", after);
        row.put("perc", percentageDisplay(before, after));
        return row;
    }

    private String percentageDisplay(int before, int after) {
        if (before == 0) {
            return after == 0 ? "0%" : "N/A";
        }
        double percentage = ((double)(after - before) / before) * 100;
        return String.format("%.2f%%", percentage);
    }

    public void writeJsonToFile(ObjectNode jsonData, File filePath) throws IOException {
        objectMapper.writer().writeValue(filePath, jsonData);
    }

    public String getJsonString(ObjectNode jsonData) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonData);
    }

    // Example usage
    public static void main(String[] args) {
        try {
            NCBIReportBuilder builder = new NCBIReportBuilder();

            // Sample data
            ObjectNode jsonReportData = builder.buildJsonReportData(
                    100, 120,  // NCBI gene Id before/after
                    200, 180,  // RefSeq RNA before/after
                    150, 160,  // RefPept before/after
                    300, 350,  // RefSeq DNA before/after
                    250, 240   // GenBank RNA before/after
            );

            // Get JSON string
            String jsonString = builder.getJsonString(jsonReportData);
            System.out.println(jsonString);

            // Write to file
            builder.writeJsonToFile(jsonReportData, new File( "ncbi_report.json"));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}