package org.zfin.util.database;

//import com.github.difflib.DiffUtils;
//import com.github.difflib.UnifiedDiffUtils;
//import com.github.difflib.patch.Patch;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
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
 * This can be compiled and run with:
 *  javac -d . -cp $(paste -sd ':' classpath.txt) ~/zfin/source/org/zfin/util/database/PgDumpSplitDiff.java
 *  java -cp .:$(paste -sd ':' classpath.txt) org.zfin.util.database.PgDumpSplitDiff ./2023.12.27.1 ./2023.12.28.1
 * where classpath.txt contains:
 *  ~/zfin/home/WEB-INF/lib/commons-io-2.5.jar
 *  ~/zfin/home/WEB-INF/lib/java-diff-utils-4.12.jar
 *
 */
public class PgDumpSplitDiff {
    record FileChanges(List<String> onlyDirectory1, List<String> onlyDirectory2, List<String> inBoth) {}

    private FileChanges fileChanges;
    private final String directory1;
    private final String directory2;
    private final Boolean force;
    private String outputDirectory;

    public PgDumpSplitDiff(String directory1, String directory2, boolean force) {
        this.directory1 = directory1;
        this.directory2 = directory2;
        this.force = force;

        String directory1lastPart = directory1.substring(directory1.lastIndexOf('/') + 1);
        String directory2lastPart = directory2.substring(directory2.lastIndexOf('/') + 1);
        outputDirectory = "diff-" + directory1lastPart + "-" + directory2lastPart;

        //does outputDirectory exist?
        File outputDir = new File(outputDirectory);
        if (outputDir.exists()) {
            throw new IllegalArgumentException("Output directory already exists: " + outputDirectory);
        }
        outputDir.mkdir();
    }

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 3) {
            System.out.println("Usage: PgDumpSplitDiff [-f] <directory1> <directory2>");
            System.exit(1);
        }

        if (args.length == 3 && !args[0].equals("-f")) {
            System.out.println("Usage: PgDumpSplitDiff [-f] <directory1> <directory2>");
            System.exit(1);
        }

        boolean force = false;
        if (args[0].equals("-f")) {
            args = Arrays.copyOfRange(args, 1, args.length);
            force = true;
        }

        String directory1 = args[0];
        String directory2 = args[1];

        System.out.println("Comparing " + directory1 + " to " + directory2);

        try {
            PgDumpSplitDiff diff = new PgDumpSplitDiff(directory1, directory2, force);
            diff.computeDiffs();
            if (force) {
                System.out.println("Saving file changes to " + diff.outputDirectory);
                diff.saveFileChanges();
            } else {
                diff.printFileChanges();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void computeDiffs() {
        this.fileChanges = calculateFileChanges();
    }

    private void printFileChanges() {
        System.out.println("Files only in " + directory1 + ":");
        fileChanges.onlyDirectory1().forEach(System.out::println);

        System.out.println("Files only in " + directory2 + ":");
        fileChanges.onlyDirectory2().forEach(System.out::println);

        System.out.println("Files in both directories that have changes:");
        fileChanges.inBoth().forEach(this::printDiff);
    }

    private void saveFileChanges() {
        File newFilesDir = new File(outputDirectory + "/new");
        newFilesDir.mkdir();

        for (String filename : fileChanges.onlyDirectory2()) {
            System.out.println("Copying new file " + filename);
            try {
                Files.copy(new File(directory2 + "/" + filename).toPath(), new File(newFilesDir + "/" + filename).toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        File deletedFilesList = new File(outputDirectory + "/deleted.txt");
        String contents = String.join("\n", fileChanges.onlyDirectory1());
        try {
            Files.write(deletedFilesList.toPath(), contents.getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (String filename : fileChanges.inBoth()) {
            if(isFileDifferent(directory1 + "/" + filename, directory2 + "/" + filename)) {
                System.out.println("Saving diff for file " + filename);
                saveDiff(directory1 + "/" + filename, directory2 + "/" + filename, outputDirectory + "/" + filename + ".diff");
            }
        }
    }

    private void saveDiff(String file1, String file2, String diffFile) {
//        saveDiffUsingJavaLib(file1, file2, diffFile);
        saveDiffUsingDiffCommand(file1, file2, diffFile);
    }

//    private void saveDiffUsingJavaLib(String file1, String file2, String diffFile) {
//        try {
//            List<String> original = Files.readAllLines(new File(file1).toPath());
//            List<String> revised = Files.readAllLines(new File(file2).toPath());
//            Patch<String> patch = DiffUtils.diff(original, revised);
//            List<String> udiff = UnifiedDiffUtils.generateUnifiedDiff(file1, file2, original, patch, 3);
//            Files.write(new File(diffFile).toPath(), udiff);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }

    /**
     * Use the diff command to generate a diff file. This seems much faster than the java version
     * @param file1
     * @param file2
     * @param diffFile
     */
    private void saveDiffUsingDiffCommand(String file1, String file2, String diffFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("diff", "-u", file1, file2);
            pb.redirectOutput(new File(diffFile));
            Process p = pb.start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    private void printDiff(String filename) {
        boolean fileDiff = isFileDifferent(directory1 + "/" + filename, directory2 + "/" + filename);
//        boolean fileDiff = isFileDifferentIgnoringComments(directory1 + "/" + filename, directory2 + "/" + filename);
        if (fileDiff) {
            System.out.println("File " + filename + " is different");
        }
    }


    private boolean isFileDifferent(String file1, String file2) {
        try {
            return !IOUtils.contentEquals(new FileInputStream(file1), new FileInputStream(file2));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
