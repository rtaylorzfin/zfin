package org.zfin.uniprot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.zfin.AbstractDatabaseTest;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.CrossRefAdapter;
import org.zfin.uniprot.adapter.RichSequenceAdapter;
import org.zfin.uniprot.adapter.RichStreamReaderAdapter;
import org.zfin.uniprot.interpro.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.zfin.uniprot.datfiles.DatFileReader.getRichStreamReaderForUniprotDatString;
import static org.zfin.uniprot.interpro.InterPro2GoTermTranslator.convertTranslationFileToUnloadFile;

public class UniProtInterproTest extends AbstractDatabaseTest {

    @Test
    public void parseECCorrectly() {
        String record = testDat();
        try {
            RichStreamReaderAdapter reader = getRichStreamReaderForUniprotDatString(record, true);
            RichSequenceAdapter sequence = reader.nextRichSequence();
            List<CrossRefAdapter> result = sequence.getECCrossReferences().stream().toList();
            assertEquals(2, result.size());
            assertTrue(result.get(0).getAccession().equals("2.7.11.27"));
            assertTrue(result.get(1).getAccession().equals("2.7.11.31"));
        } catch (Exception e) {
            fail("Should not have thrown an exception");
        }
    }

    @Test
    public void testInterproToGoHandler() {
        InterproToGoHandler handler = new InterproToGoHandler(ForeignDB.AvailableName.INTERPRO);
        ObjectMapper objectMapper = new ObjectMapper();

        //read from test/uniprot-data/interpro-actions.json and convert to UniProtLoadAction list
        try {
            // Specify the path to the JSON file
            File jsonFile = new File("test/uniprot-data/interpro-actions.json");

            // Use the ObjectMapper to convert the JSON content to the list of UniProtLoadAction objects
            Set<InterproLoadAction> actions = objectMapper.readValue(jsonFile, new TypeReference<Set<InterproLoadAction>>() {});

            InterproLoadContext context = new InterproLoadContext();

            List<InterPro2GoTerm> ip2go = convertTranslationFileToUnloadFile("test/uniprot-data/interpro2go");

            context.setInterproTranslationRecords(ip2go);

            handler.handle(null,actions,context);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private String testDat() {
        return """
                ID   E9QDC9_DANRE          Unreviewed;         116 AA.
                AC   E9QDC9; A0A8M1PV84;
                DE   RecName: Full=Acetyl-CoA carboxylase kinase {ECO:0000256|ARBA:ARBA00032270};
                DE            EC=2.7.11.27 {ECO:0000256|ARBA:ARBA00012412};
                DE            EC=2.7.11.31 {ECO:0000256|ARBA:ARBA00012403};
                DE   AltName: Full=Hydroxymethylglutaryl-CoA reductase kinase {ECO:0000256|ARBA:ARBA00032865};                
                DR   RefSeq; XP_001343958.4; XM_001343922.7.
                PE   3: Inferred from homology;
                KW   Metal-binding {ECO:0000256|ARBA:ARBA00022723}; Reference proteome {ECO:0000313|Proteomes:UP000000437}; Zinc {ECO:0000256|ARBA:ARBA00022833}.
                //
                """;
    }

}
