package org.zfin.uniprot;

import lombok.Getter;
import lombok.Setter;
import org.biojavax.bio.seq.RichSequence;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class UniProtLoadAction {
    private String title;
    private String accession;
    private String details;
    private Type type;

    private List<UniProtLoadLink> links = new ArrayList<>();

    public UniProtLoadAction() {
    }

    public enum Type {LOAD, INFO, ERROR}
}
