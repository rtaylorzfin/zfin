package org.zfin.uniprot;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.biojava.bio.BioException;
import org.biojava.bio.seq.io.SymbolTokenization;
import org.biojava.bio.symbol.AlphabetManager;
import org.biojava.bio.symbol.FiniteAlphabet;
import org.biojavax.Namespace;
import org.biojavax.bio.seq.RichSequence;
import org.biojavax.bio.seq.io.*;
import org.hibernate.Transaction;
import org.zfin.framework.HibernateUtil;
import org.zfin.ontology.datatransfer.AbstractScriptWrapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;


public class UniProtAnalysisTask extends AbstractScriptWrapper {

    private static final String CSV_FILE = "uniprot_analysis_zfin_8275.csv";
    private static final String DEBUG_INPUT_FILE = "/Volumes/MORESPACE/pre_zfin.dat";

    public static void main(String[] args) {
        UniProtAnalysisTask task = new UniProtAnalysisTask();

        try {
            task.runTask();
        } catch (IOException e) {
            System.err.println("IOException Error while running task: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (BioException e) {
            System.err.println("BioException Error while running task: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
        System.exit(0);
    }

    public void runTask() throws IOException, BioException {
        String inputFile = System.getenv("UNIPROT_INPUT_FILE");
        if (inputFile == null) {
            if (Files.exists(Paths.get(DEBUG_INPUT_FILE))) {
                inputFile = DEBUG_INPUT_FILE;
                System.out.println("Using debug input file: " + DEBUG_INPUT_FILE);
            } else {
                System.err.println("No input file specified. Please set the environment variable UNIPROT_INPUT_FILE.");
                System.exit(3);
            }
        }
        BufferedReader br = new BufferedReader(new FileReader(inputFile));
//        RichSequenceFormat inFormat = new UniProtFormat();
        RichSequenceFormat inFormat = new UniProtFormatZFIN();
        inFormat.setElideFeatures(true);
        inFormat.setElideSymbols(true);

        RichSequenceFormat outFormat = new FastaFormat();
        FiniteAlphabet alpha = (FiniteAlphabet) AlphabetManager.alphabetForName("PROTEIN");
        Namespace ns = null;
        SymbolTokenization tokenization = alpha.getTokenization("default");

        RichStreamReader sr = new RichStreamReader(
                br, inFormat, tokenization,
                RichSequenceBuilderFactory.THRESHOLD,
                ns);

        int count = 0;
        while (sr.hasNext()) {
            count++;
            RichSequence seq = sr.nextRichSequence();
            System.out.println(count + ":seqName: " + seq.getName());
            System.out.flush();
        }

//        RichStreamWriter sw = new RichStreamWriter(System.out, outFormat);
//        sw.writeStream(sr, ns);

    }


}
