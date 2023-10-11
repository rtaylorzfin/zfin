package org.zfin.uniprot.interpro;

public record SecondaryTerm2GoTerm(String interproID, String term, String goID, String termZdbID) {
    public String prefixedGoID() {
        return "GO:" + goID;
    }
}
