package org.zfin.uniprot.interpro;

import lombok.extern.log4j.Log4j2;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple2;
import org.zfin.marker.Marker;
import org.zfin.ontology.Subset;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.adapter.RichSequenceAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.zfin.ontology.Subset.GO_CHECK_DO_NOT_USE_FOR_ANNOTATIONS;
import static org.zfin.repository.RepositoryFactory.getMarkerRepository;
import static org.zfin.repository.RepositoryFactory.getOntologyRepository;
import static org.zfin.sequence.ForeignDB.AvailableName.INTERPRO;

@Log4j2
public class AddNewInterproToGoHandler implements InterproLoadHandler {

    private final ForeignDB.AvailableName dbName;

    public AddNewInterproToGoHandler(ForeignDB.AvailableName dbName) {
        this.dbName = dbName;
    }

    @Override
    public void handle(Map<String, RichSequenceAdapter> uniProtRecords, Set<InterproLoadAction> actions, InterproLoadContext context) {
        List<InterproLoadAction> interproLoads = actions.stream()
                .filter(action -> dbName.equals(action.getDbName()) && action.getType().equals(InterproLoadAction.Type.LOAD))
                .toList();

        //create markerGoTermEvidences from new interpro IDs
        log.debug("Creating markerGoTermEvidences from new interpro IDs");
        List<InterproLoadAction> markerGoTermEvidences = createMarkerGoTermEvidencesFromNewInterproIDs(interproLoads, context.getInterproTranslationRecords());
        log.debug("Created " + markerGoTermEvidences.size() + " markerGoTermEvidences before filtering");

        //filter out unknown and root terms
        List<InterproLoadAction> filteredMarkerGoTermEvidences = filterUnknownAndRootTerms(markerGoTermEvidences);
        log.debug("After first pass of filtering: " + filteredMarkerGoTermEvidences.size() + " markerGoTermEvidences");

        //filter out terms for WITHDRAWN markers
        List<InterproLoadAction> filteredMarkerGoTermEvidences2 = filterWithdrawnMarkers(filteredMarkerGoTermEvidences);
        log.debug("After second pass of filtering: " + filteredMarkerGoTermEvidences2.size() + " markerGoTermEvidences");

        //filter out terms not meant to be annotated
        List<InterproLoadAction> filteredMarkerGoTermEvidences3 = filterNonAnnotatedTerms(filteredMarkerGoTermEvidences2);
        log.debug("After third pass of filtering: " + filteredMarkerGoTermEvidences3.size() + " markerGoTermEvidences");

        actions.addAll(filteredMarkerGoTermEvidences);
    }

    private List<InterproLoadAction> filterNonAnnotatedTerms(List<InterproLoadAction> markerGoTermEvidences) {
        List<Subset> subsets = getOntologyRepository().getAllSubsets();
        Subset notForAnnotations = subsets.stream().filter(subset -> subset.getInternalName().equals(GO_CHECK_DO_NOT_USE_FOR_ANNOTATIONS)).findFirst().orElse(null);
        if (notForAnnotations == null) {
            throw new RuntimeException("Could not find subset " + GO_CHECK_DO_NOT_USE_FOR_ANNOTATIONS);
        }
        List<String> termZdbIDs = notForAnnotations.getTerms().stream().map(term -> term.getZdbID()).toList();
        return markerGoTermEvidences.stream()
                .filter(action -> !termZdbIDs.contains(action.getGoTermZdbID()))
                .toList();
    }

    private List<InterproLoadAction> filterWithdrawnMarkers(List<InterproLoadAction> filteredMarkerGoTermEvidences) {
        List<Marker> withdrawnMarkers = getMarkerRepository().getWithdrawnMarkers();
        List<String> withdrawnZdbIDs = withdrawnMarkers.stream()
                .map(Marker::getZdbID)
                .toList();
        return filteredMarkerGoTermEvidences.stream()
                .filter(action -> !withdrawnZdbIDs.contains(action.getGeneZdbID()))
                .toList();
    }

    private List<InterproLoadAction> filterUnknownAndRootTerms(List<InterproLoadAction> markerGoTermEvidences) {
        //unknown and root terms are not allowed
        List<String> unknownAndRootTerms = List.of("GO:0005554", "GO:0000004", "GO:0008372", "GO:0005575", "GO:0003674", "GO:0008150");

        return markerGoTermEvidences.stream()
                .filter(action -> !unknownAndRootTerms.contains(action.getGoID()))
                .toList();
    }

    private static List<InterproLoadAction> createMarkerGoTermEvidencesFromNewInterproIDs(List<InterproLoadAction> loads, List<InterPro2GoTerm> interpro2GoTranslationRecords) {
        log.debug("Joining " + loads.size()  + " InterproLoadAction against " + interpro2GoTranslationRecords.size() + " Interpro2GoTerms ");

        List<InterproLoadAction> newMarkerGoTermEvidences = new ArrayList<>();

        //join the load actions to the interpro translation records
        List<Tuple2<InterproLoadAction, InterPro2GoTerm>> joined = Seq.seq(loads)
                .innerJoin(interpro2GoTranslationRecords,
                        (action, ip2go) -> action.getAccession().equals(ip2go.interproID()))
                .toList();
        for(var joinedRecord : joined) {
            InterproLoadAction action = joinedRecord.v1();
            InterPro2GoTerm ip2go = joinedRecord.v2();
            InterproLoadAction newAction = InterproLoadAction.builder()
                    .accession(action.getAccession())
                    .dbName(INTERPRO)
                    .type(InterproLoadAction.Type.LOAD)
                    .subType(InterproLoadAction.SubType.MARKER_GO_TERM_EVIDENCE)
                    .geneZdbID(action.getGeneZdbID())
                    .goID(ip2go.goID())
                    .goTermZdbID(ip2go.termZdbID())
                    .build();
            log.debug(newAction.markerGoTermEvidenceRepresentation());
            newMarkerGoTermEvidences.add(newAction);
        }
        return newMarkerGoTermEvidences;
    }
}
