package org.zfin.util.database;

import java.io.File;

public class PgDumpSplitFile {

    public enum FileType {
        GENERAL_SECTION,
        TABLE_CONTENTS,
    }

    public File wrappedFile;
    public FileType fileType;
    public String sectionName;
    public String tableName;

    public PgDumpSplitFile(File file) {
        this.wrappedFile = file;
        this.fileType = determineFileType(file);
        if (fileType == FileType.TABLE_CONTENTS) {
            this.sectionName = "table-contents";
            this.tableName = extractTableName(file);
        } else {
            this.sectionName = extractSectionName(file);
        }
    }

    private FileType determineFileType(File file) {
        String filename = file.getName();
        if (filename.contains("table-contents")) {
            return FileType.TABLE_CONTENTS;
        } else {
            return FileType.GENERAL_SECTION;
        }
    }

    private String extractTableName(File file) {
        String filename = file.getName();
        String pattern = "\\d\\d-table-contents-\\d\\d\\d\\d-(.*)\\.sql";
        return filename.replaceAll(pattern, "$1");
    }

    private String extractSectionName(File file) {
        String filename = file.getName();
        String pattern = "\\d\\d-(.*)\\.sql";
        return filename.replaceAll(pattern, "$1");
    }
}
