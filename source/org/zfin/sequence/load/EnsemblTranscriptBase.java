package org.zfin.sequence.load;

import org.apache.commons.io.FileUtils;
import org.biojava.bio.BioException;
import org.biojava.bio.seq.io.SymbolTokenization;
import org.biojavax.SimpleNamespace;
import org.biojavax.bio.seq.RichSequence;
import org.biojavax.bio.seq.RichSequenceIterator;
import org.zfin.marker.Marker;
import org.zfin.util.FileUtil;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static htsjdk.samtools.util.ftp.FTPClient.READ_TIMEOUT;

abstract public class EnsemblTranscriptBase {

    protected static final String baseUrl = "https://rest.ensembl.org";
    protected String cdnaFileName = "Danio_rerio.GRCz11.cdna.all.fa";

    protected List<EnsemblErrorRecord> errorRecords = new ArrayList<>();

    protected record TranscriptRecord(Marker marker, String ensdartID, RichSequence richSequence) {
    }


    protected static void downloadFile(String fileName) {
        String zippedFileName = fileName + ".gz";
        String fileURL = "https://ftp.ensembl.org/pub/current_fasta/danio_rerio/cdna/" + zippedFileName;

        try {
            FileUtils.copyURLToFile(
                new URL(fileURL),
                new File(zippedFileName),
                60000,
                READ_TIMEOUT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        FileUtil.gunzipFile(zippedFileName);
    }

    protected static Map<String, List<RichSequence>> getGeneTranscriptMap(String fileName) {
        try {
            List<RichSequence> transcriptList = getFastaIterator(fileName);
            return transcriptList.stream().collect(Collectors.groupingBy(EnsemblTranscriptBase::getGeneId));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String getGeneId(RichSequence sequence) {

        String line = sequence.getDescription();
        String pattern = "(.*)(gene:)(ENSDARG.*)( gene_biotype)(.*)";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(line);

        if (m.find()) {
            return m.group(3);
        }
        return "";
    }


    private static List<RichSequence> getFastaIterator(String fileName) throws FileNotFoundException {
        FileReader fileReader = new FileReader(fileName);
        BufferedReader br = new BufferedReader(fileReader);
        RichSequenceIterator iterator;
        SymbolTokenization symbolTokenization = RichSequence.IOTools.getNucleotideParser();
        iterator = RichSequence.IOTools.readFasta(br, symbolTokenization, new SimpleNamespace(""));

        List<RichSequence> sequenceList = new ArrayList<>();
        while (iterator.hasNext()) {
            try {
                sequenceList.add(iterator.nextRichSequence());
            } catch (BioException e) {
                e.printStackTrace();
            }
        }
        return sequenceList;
    }


    public static String getString(RichSequence richSequence) {
        return getUnversionedAccession(richSequence.getAccession());
    }

    public static String getUnversionedAccession(String versionedAccession) {
        return versionedAccession.split("\\.")[0];
    }

    protected record EnsemblTranscript(String id, String name, String type) {
    }

    private record EnsemblErrorRecord(String ensdartID, String ensdartName, int ensdartLength, String zfinID, String zfinName, String zfinIDExisting, String zfinNameExisting) {
    }

}
