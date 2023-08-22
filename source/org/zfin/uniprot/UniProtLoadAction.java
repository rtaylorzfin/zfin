package org.zfin.uniprot;

import lombok.Getter;
import lombok.Setter;
import org.biojavax.bio.seq.RichSequence;

@Getter
@Setter
public class UniProtLoadAction {
    private String title;
    private String accession;
    private String details;
    private Type type;

    public UniProtLoadAction() {
    }

    public static UniProtLoadAction determineAction(RichSequence entry) {
        return null;
    }

    public enum Type {LOAD, INFO, ERROR}
}
