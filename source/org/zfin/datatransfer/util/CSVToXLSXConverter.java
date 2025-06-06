package org.zfin.datatransfer.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class CSVToXLSXConverter {
    public void run(File xlsxFilePath, List<File> csvFiles) {
        run(xlsxFilePath, csvFiles, null);
    }

    public void run(File xlsxFilePath, List<File> csvFiles, List<String> sheetNames) {
        if (csvFiles == null || csvFiles.size() == 0) {
            System.out.println("No CSV files found");
            return;
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            int sheetIndex = 0;
            for (File csvFile : csvFiles) {
                String sheetName = csvFile.getName().replace(".csv", "");
                if (sheetNames != null && sheetIndex < sheetNames.size()) {
                    sheetName = sheetNames.get(sheetIndex++);
                }
                Sheet sheet = workbook.createSheet(sheetName);
                int rowIndex = 0;
                try (BufferedReader br = new BufferedReader(new FileReader(csvFile));
                     CSVParser csvParser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(br)) {

                    ArrayList<String> headers = new ArrayList<>(csvParser.getHeaderNames());
                    Row headerRow = sheet.createRow(rowIndex++);
                    for (int i = 0; i < headers.size(); i++) {
                        Cell cell = headerRow.createCell(i);
                        cell.setCellValue(headers.get(i));
                    }
                    for (CSVRecord record : csvParser) {
                        Row row = sheet.createRow(rowIndex++);
                        for (int i = 0; i < record.size(); i++) {
                            Cell cell = row.createCell(i);
                            cell.setCellValue(record.get(i));
                        }
                    }

                } catch (IOException e) {
                    System.err.println("Error reading CSV file " + csvFile.getName() + ": " + e.getMessage());
                }
            }
            try (FileOutputStream fileOut = new FileOutputStream(xlsxFilePath)) {
                workbook.write(fileOut);
                System.out.println("Successfully converted CSV files to XLSX.");
            } catch (IOException e) {
                System.err.println("Error writing XLSX file: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java CSVToXLSXConverter <outputXLSXFilePath> [<csvFile1> <csvFile2> ...]");
            return;
        }

        File xlsxFilePath = new File(args[0]);
        List<File> csvFilePaths = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            csvFilePaths.add(new File(args[i]));
        }

        CSVToXLSXConverter converter = new CSVToXLSXConverter();
        converter.run(xlsxFilePath, csvFilePaths);
    }
}