package org.zfin.uniprot.interpro;

import org.apache.commons.io.FileUtils;
import org.zfin.ontology.GenericTerm;
import org.zfin.ontology.Ontology;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.zfin.repository.RepositoryFactory.getOntologyRepository;

public class InterPro2GoTermTranslator {

    public static List<InterPro2GoTerm> convertTranslationFileToUnloadFile(String ipToGoTranslationFile) throws FileNotFoundException {
        return parseInterPro2GoTerms(ipToGoTranslationFile);
    }

    private static List<InterPro2GoTerm> parseInterPro2GoTerms(String ipToGoTranslationFile) throws FileNotFoundException {
        List<String> badGoIDs = getOntologyRepository()
                .getObsoleteAndSecondaryTerms()
                .stream()
                .map(GenericTerm::getOboID)
                .filter(id -> id.startsWith("GO:"))
                .toList();

        Map<String, GenericTerm> allGoIDs = getOntologyRepository().getGoTermsToZdbID();

        StringBuilder sb = new StringBuilder();
        List<InterPro2GoTerm> results = new ArrayList<>();
        Scanner scanner = null;
        scanner = new Scanner(new File(ipToGoTranslationFile));
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            if (line.startsWith("InterPro:")) {
                String[] splitLine = line.split(" > ");
                String[] ip = splitLine[0].split("[: ]");
                String[] termId = splitLine[1].split(" ; ");
                String[] term = termId[0].split("GO:");
                String[] id = termId[1].split(":");

                // FB case: 6392 -- not to map GO:0005515
                if (!termId[1].equals("GO:0005515") && !termId[1].equals("GO:0005488")) {
                    if (badGoIDs.contains(term[1])) {
                        continue;
                    }
                    GenericTerm termObject = allGoIDs.get(termId[1]);
                    results.add(new InterPro2GoTerm(ip[1], term[1], id[1], termObject == null ? null : termObject.getZdbID()));
                }
            }
        }
        scanner.close();
        return results;
    }

    public static void main(String[] args) throws IOException {
        String ipToGoTranslationFile = args[0];
        List<InterPro2GoTerm> results = convertTranslationFileToUnloadFile(ipToGoTranslationFile);
        StringBuilder sb = new StringBuilder();
        for (InterPro2GoTerm result : results) {
            sb.append(result.interproID()).append("|").append(result.term()).append("|").append(result.goID()).append("\n");
        }
        FileUtils.writeStringToFile(new File("/tmp/ip_mrkrgoterm.unl"), sb.toString(), "UTF-8");
    }
}
