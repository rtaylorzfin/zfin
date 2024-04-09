package org.zfin.util.database;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Take the output of PgDumpSplitter and compare it to another run of PgDumpSplitter.
 * This will allow us to see what has changed between two runs of the splitter.
 * This is useful for tracking changes in the database schema over time.
 * The output will be a list of files that have changed between the two runs.
 *
 * Remember the structure of the output of PgDumpSplitter is a directory with files like:
 *
 * - 01-preamble.sql
 * - 02-schemas-and-extensions.sql
 * - 03-functions.sql
 * - 04-aggregates.sql
 * - 05-tables-views-structures.sql
 * - 06-table-contents-0001-tablenameA.sql
 * - 06-table-contents-0002-tablenameB.sql
 * - 06-table-contents-0003-tablenameC.sql
 * ...
 * - 06-table-contents-9999-tablenameZ.sql
 * - 07-sequence-init.sql
 * - 08-keys-and-constraints.sql
 * - 09-indexes.sql
 * - 10-triggers.sql
 * - 11-foreign-keys.sql
 *
 */
public class PgDumpSplitDiff {
    record FileChanges(List<String> onlyDirectory1, List<String> onlyDirectory2, List<String> inBoth) {}

    private FileChanges fileChanges;
    private final String directory1;
    private final String directory2;

    public PgDumpSplitDiff(String directory1, String directory2) {
        this.directory1 = directory1;
        this.directory2 = directory2;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: PgDumpSplitDiff <directory1> <directory2>");
            System.exit(1);
        }

        String directory1 = args[0];
        String directory2 = args[1];

        System.out.println("Comparing " + directory1 + " to " + directory2);

        try {
            new PgDumpSplitDiff(directory1, directory2).diff();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void diff() {
        this.fileChanges = calculateFileChanges();
        printFileChanges();
    }

    private void printFileChanges() {
        System.out.println("Files only in " + directory1 + ":");
        fileChanges.onlyDirectory1().forEach(System.out::println);

        System.out.println("Files only in " + directory2 + ":");
        fileChanges.onlyDirectory2().forEach(System.out::println);

        System.out.println("Files in both directories that have changes:");
        fileChanges.inBoth().forEach(this::printDiff);
    }

    private void printDiff(String filename) {
        boolean fileDiff = isFileDifferent(directory1 + "/" + filename, directory2 + "/" + filename);
        if (fileDiff) {
            System.out.println("File " + filename + " is different");
        }
    }

    private boolean isFileDifferent(String file1, String file2) {
        Runtime rt = Runtime.getRuntime();
        try {
            Process proc = rt.exec(new String[]{"diff", "-q", file1, file2});
            proc.waitFor();
            int exitVal = proc.exitValue();
            return exitVal != 0;
        } catch (Exception e) {
            e.printStackTrace();
        }
        throw new RuntimeException("Error running diff command");
    }

    private FileChanges calculateFileChanges() {
        List<String> directory1Contents = listFiles(directory1);
        List<String> directory2Contents = listFiles(directory2);

        List<String> filesOnlyInDirectory1 = new ArrayList<>();
        List<String> filesOnlyInDirectory2 = new ArrayList<>();
        List<String> filesInBothDirectories = new ArrayList<>();

        for (String filename : directory1Contents) {
            if (directory2Contents.contains(filename)) {
                filesInBothDirectories.add(filename);
                directory2Contents.remove(filename);
            } else {
                filesOnlyInDirectory1.add(filename);
            }
        }
        filesOnlyInDirectory2.addAll(directory2Contents);

        //sort them
        filesOnlyInDirectory1.sort(String::compareTo);
        filesOnlyInDirectory2.sort(String::compareTo);
        filesInBothDirectories.sort(String::compareTo);

        return new FileChanges(filesOnlyInDirectory1, filesOnlyInDirectory2, filesInBothDirectories);

//        System.out.println("Files only in " + directory1 + ":");
//        filesOnlyInDirectory1.forEach(System.out::println);
//
//        System.out.println("Files only in " + directory2 + ":");
//        filesOnlyInDirectory2.forEach(System.out::println);
//
//        System.out.println("Files in both directories:");
//        filesInBothDirectories.forEach(System.out::println);
    }

    private List<String> listFiles(String directory1) {
        File dir = new File(directory1);
        if (!dir.exists()) {
            throw new IllegalArgumentException("Directory does not exist: " + directory1);
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + directory1);
        }

        List<File> files = Arrays.asList(dir.listFiles());
        List<String> fileNames = new ArrayList<>();
        for (File file : files) {
            fileNames.add(file.getName());
        }
        return fileNames;
    }
}
