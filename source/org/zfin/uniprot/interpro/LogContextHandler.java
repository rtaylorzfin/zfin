package org.zfin.uniprot.interpro;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

@Log4j2
public class LogContextHandler implements InterproLoadHandler {
    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, List<SecondaryTermLoadAction> actions, InterproLoadContext context) {

        String timestamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(Calendar.getInstance().getTime());
        String filename = "/tmp/interproLoadContext-" + timestamp + ".json";

        log.debug("Context Logged to " + filename + " for debugging purposes");

        ObjectMapper mapper = new ObjectMapper();
        try {
            mapper.writeValue(new java.io.File(filename), context);
        } catch (Exception e) {
            log.error("Error writing context to file: " + filename, e);
        }

        log.debug("Context written");
    }
}
