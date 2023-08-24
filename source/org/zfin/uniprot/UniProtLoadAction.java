package org.zfin.uniprot;

import lombok.Getter;
import lombok.Setter;
import org.biojavax.bio.seq.RichSequence;
import org.zfin.ExternalNote;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class UniProtLoadAction {
    private String title;
    private String accession;
    private String geneZdbID;
    private String details;
    private Type type;

    private List<UniProtLoadLink> links = new ArrayList<>();

    public UniProtLoadAction() {
    }

    public void addLink(UniProtLoadLink uniProtLoadLink) {
        links.add(uniProtLoadLink);
    }

    public enum Type {LOAD, INFO, ERROR}

    public enum MatchTitle {
        MULTIPLE_GENES_PER_ACCESSION("Multiple Genes per Accession"),
        MATCH_BY_REFSEQ("Matched via RefSeq: Single Gene per Accession"),
        LOST_UNIPROT("Using RefSeq Matching: ZFIN Gene Would Lost UniProt Accession");

        private String value;

        MatchTitle(String s) {
            this.value = s;
        }
    }
}
