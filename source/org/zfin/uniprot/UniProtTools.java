package org.zfin.uniprot;

import org.biojava.bio.BioException;
import org.biojava.bio.seq.io.SymbolTokenization;
import org.biojava.bio.symbol.AlphabetManager;
import org.biojava.bio.symbol.FiniteAlphabet;
import org.biojavax.Note;
import org.biojavax.SimpleRichAnnotation;
import org.biojavax.bio.seq.RichSequence;
import org.biojavax.bio.seq.io.RichSequenceBuilderFactory;
import org.biojavax.bio.seq.io.RichSequenceFormat;
import org.biojavax.bio.seq.io.RichStreamReader;
import org.biojavax.bio.seq.io.RichStreamWriter;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class UniProtTools {
    private static final int MAX_LINE_WIDTH = 500;

    public static RichStreamReader getRichStreamReaderForUniprotDatFile(String inputFileName, boolean elideSymbols) throws FileNotFoundException, BioException {
        BufferedReader br = new BufferedReader(new FileReader(inputFileName));
        RichSequenceFormat inFormat = new UniProtFormatZFIN();

        //skips parsing of certain sections that otherwise would throw exceptions
        inFormat.setElideFeatures(true);
        inFormat.setElideReferences(true);
        inFormat.setElideSymbols(elideSymbols);

        FiniteAlphabet alpha = (FiniteAlphabet) AlphabetManager.alphabetForName("PROTEIN");
        SymbolTokenization tokenization = alpha.getTokenization("default");

        return new RichStreamReader(
                br, inFormat, tokenization,
                RichSequenceBuilderFactory.THRESHOLD,
                null);
    }

    public static RichStreamWriter getRichStreamWriterForUniprotDatFile(String outfile) throws FileNotFoundException {
        FileOutputStream fos = new FileOutputStream(outfile);
        UniProtFormatZFIN format = new UniProtFormatZFIN();

        FiniteAlphabet alphabet = (FiniteAlphabet) AlphabetManager.alphabetForName("PROTEIN");
        format.setOverrideAlphabet(alphabet);
        format.setLineWidth(MAX_LINE_WIDTH);

        return new RichStreamWriter(fos, format);
    }


    public static List<Note> getKeywordNotes(RichSequence seq1) {
        SimpleRichAnnotation seq1NoteSet = new SimpleRichAnnotation();
        seq1NoteSet.setNoteSet(seq1.getNoteSet());
        Note[] keywords = seq1NoteSet.getProperties(RichSequence.Terms.getKeywordTerm());
        return List.of(keywords);
    }


}