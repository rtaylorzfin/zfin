package org.zfin.uniprot.interpro;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.ObjectUtils;
import org.zfin.sequence.ForeignDB;
import org.zfin.uniprot.UniProtLoadLink;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

@Getter
@Setter
@Builder
public class SecondaryTermLoadAction implements Comparable<SecondaryTermLoadAction> {
    private Type type;
    private SubType subType;

    private ForeignDB.AvailableName dbName;
    private String accession;
    private String goID;
    private String goTermZdbID;
    private String geneZdbID;
//    private String pubZdbID;
    private String details;
    private int length;

    @Builder.Default
    private Set<UniProtLoadLink> links = new TreeSet<>();

    public SecondaryTermLoadAction() {
        links = new TreeSet<>();
    }

    public SecondaryTermLoadAction(Type type, SubType subType, ForeignDB.AvailableName dbName, String accession, String goID, String goTermZdbID, String geneZdbID, String details, int length, Set<UniProtLoadLink> links) {
        this.type = type;
        this.subType = subType;
        this.accession = accession;
        this.goID = goID;
        this.goTermZdbID = goTermZdbID;
        this.geneZdbID = geneZdbID;
        this.details = details;
        this.length = length;
        this.links = links;
        this.dbName = dbName;
    }

    public void addLink(UniProtLoadLink uniProtLoadLink) {
        links.add(uniProtLoadLink);
    }

    public void addLinks(Collection<UniProtLoadLink> links) {
        this.links.addAll(links);
    }

    public enum Type {LOAD, INFO, WARNING, ERROR, DELETE, IGNORE, DUPES}

    public enum SubType {
        MARKER_GO_TERM_EVIDENCE("MarkerGoTermEvidence"),
        DB_LINK("DBLink");

        private final String value;

        SubType(String s) {
            this.value = s;
        }

        @JsonValue
        public String getValue() {
            return value;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecondaryTermLoadAction that)) return false;
        return compareTo(that) == 0;
    }

    @Override
    public int compareTo(SecondaryTermLoadAction o) {
        Comparator<SecondaryTermLoadAction> comparator = Comparator.comparing(
                (SecondaryTermLoadAction obj) -> obj.accession, ObjectUtils::compare)
                .thenComparing(obj -> obj.type, ObjectUtils::compare)
                .thenComparing(obj -> obj.subType, ObjectUtils::compare)
                .thenComparing(obj -> obj.geneZdbID, ObjectUtils::compare)
                .thenComparing(obj -> obj.details, ObjectUtils::compare)
                ;
        return comparator.compare(this, o);
    }

    public String toString() {
        return "InterproLoadAction: " + " action=" + type + " subtype=" + subType + " accession=" + accession + " goID=" + goID + " goTermZdbID=" + goTermZdbID + " geneZdbID=" + geneZdbID + " details=" + details + " length=" + length + " links=" + links;
    }

    public String markerGoTermEvidenceRepresentation() {
        return geneZdbID + "," + goTermZdbID + "," + goID + "," + dbName + ":" + this.accession;
    }

}