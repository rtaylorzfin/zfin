package org.zfin.util.database;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * a simple java application that can read an sql file and break it up into its constituent parts.  It should take an input file from `pg_dumpall --clean` (eg. 20230101.sql) and generate output files in a subdirectory (20230101).
 * The original file can be reconstructed by concatenating the output files in order.
 * The output files should be named according to the section of the input file they came from.
 * The sections are defined by comments in the input file.
 *
 *
 * - 01 - preamble.sql
 * - 02 - schemas-and-extensions.sql
 * - 03 - functions.sql
 * - 04 - aggregates.sql
 * - 05 - tables-views-structures.sql
 * - 06 - table-contents-0001-tablenameA.sql
 * - 06 - table-contents-0002-tablenameB.sql
 * - 06 - table-contents-0003-tablenameC.sql
 * ...
 * - 06 - table-contents-9999-tablenameZ.sql
 * - 07 - sequence-init.sql
 * - 08 - keys-and-constraints.sql
 * - 09 - indexes.sql
 * - 10 - triggers.sql
 * - 11 - foreign-keys.sql
 */
public class PgDumpSplitter {
    private File inputFile;
    private File outputDir;

    //state for the state machine
    private String currentSection = "preamble";
    private int currentSectionIndex = 0;

    //counters for content file names
    private int sectionCounter = 1;
    private int tableCounter = 1;

    //map of keywords to section names
    private static final Map<String, String> SECTION_NAMES = new HashMap<>();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: PgDumpSplitter <input file>");
            System.exit(1);
        }

        String inputFile = args[0];

        System.out.println("Splitting " + inputFile);

        try {
            new PgDumpSplitter().split(inputFile);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void split(String inputFilePath) {
        initializeSectionNames();
        initializeFiles(inputFilePath);
        readFileAndSplit();
    }

    private void initializeSectionNames() {
        /**
         * dumpall structure of typical dumpall file:
         * 0..280: sets up DBs: template1, postgres, zfindb (things like 'SET lock_timeout = 0', etc.)
         * 280..321: sets up schemas and extensions (thisse, ui), extension pg_stat_statements
         * 321..15485: function definitions
         * 15490..15500: aggregate (avg_of_largest)
         * 15500..31150: set up table definitions, sequence definitions, view definitions, and ownerships (no foreign keys notably)
         * 31157..100104812: COPY commands for storing table contents ([see details](:/a1c345a51d4a479a953cf1a03d9768ed))
         * 100104812..100107541: set state of sequences with things like `SELECT pg_catalog.setval('public.accnum_sequence', 6182, true);`
         * 100107541..100111641: create primary keys and unique constraints
         * 100111641..100116305: create indexes
         * 100116305..100116936: create triggers
         * 100116936..100121468: create foreign keys
         * EOF
         */
        SECTION_NAMES.put("-- PostgreSQL database cluster dump", "preamble");
        SECTION_NAMES.put("-- Name: (.*); Type: SCHEMA; Schema:.*", "schemas-and-extensions");
        SECTION_NAMES.put("-- Name: (.*); Type: FUNCTION; Schema:.*", "functions");
        SECTION_NAMES.put("-- Name: (.*); Type: AGGREGATE; Schema:.*", "aggregates");

        // Tables, views, structures all in the same section
        SECTION_NAMES.put("-- Name: (.*); Type: TABLE; Schema:.*", "tables-views-structures");
        SECTION_NAMES.put("-- Name: (.*); Type: SEQUENCE; Schema:.*", "tables-views-structures");
        SECTION_NAMES.put("-- Name: (.*); Type: VIEW; Schema:.*", "tables-views-structures");

        // Table contents
        SECTION_NAMES.put("-- Data for Name: (.*?);.*", "table-contents");

        SECTION_NAMES.put("-- Name: (.*); Type: SEQUENCE SET;.*", "sequence-init");
        SECTION_NAMES.put("-- Name: (.*); Type: CONSTRAINT;.*", "keys-and-constraints");
        SECTION_NAMES.put("-- Name: (.*); Type: INDEX; Schema:.*", "indexes");
        SECTION_NAMES.put("-- Name: (.*); Type: TRIGGER; Schema:.*", "triggers");
        SECTION_NAMES.put("-- Name: (.*); Type: FK CONSTRAINT; Schema:.*", "foreign-keys");
    }

    private void readFileAndSplit() {
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String line;
            BufferedWriter writer = null;
            String sectionFileName = formatFileName(outputDir, sectionCounter, "preamble.sql");
            writer = new BufferedWriter(new FileWriter(sectionFileName));

            while ((line = reader.readLine()) != null) {
                if (isStateChange(line)) {
                    writer.close();
                    sectionFileName = formatFileName(outputDir, sectionCounter, currentSection + ".sql");
                    writer = new BufferedWriter(new FileWriter(sectionFileName));
                }
                writer.write(line + "\n");
            }
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isStateChange(String line) {
        //fast fail:
        if (line.length() < 2) {
            return false;
        }
        //all state changes are marked by a comment that starts with "--" (at least for now)
        if (!line.startsWith("--")) {
            return false;
        }

        String newState = getStateForLine(line);
        if (!newState.equals(currentSection)) {
            currentSection = newState;
            System.out.println("Writing section: " + currentSection );
            return true;
        }
        return false;
    }

    private String getStateForLine(String line) {
        for (Map.Entry<String, String> entry : SECTION_NAMES.entrySet()) {
            Matcher matcher = Pattern.compile(entry.getKey()).matcher(line);
            if (matcher.find()) {
                if (entry.getValue().equals("table-contents") && !currentSection.startsWith("table-contents")) {
                    sectionCounter++;
                }
                if (entry.getValue().equals("table-contents")) {
                    return entry.getValue() + "-" + String.format("%04d", tableCounter++) + "-" + matcher.group(1);
                }
                if (!entry.getValue().equals(currentSection)) {
                    sectionCounter++;
                }
                return entry.getValue();
            }
        }
        return currentSection;
    }

    private void initializeFiles(String inputFilePath) {
        inputFile = new File(inputFilePath);
        String baseName = inputFilePath.substring(0, inputFilePath.lastIndexOf('.'));

        // Create output directory based on input file name
        outputDir = new File(baseName);
        if (!outputDir.exists()) {
            outputDir.mkdir();
        }
        System.out.println("Output directory: " + outputDir.getPath());
    }

    private static String formatFileName(File dir, int section, String fileName) {
        return dir.getPath() + File.separator + String.format("%02d", section) + "-" + fileName;
    }

}
