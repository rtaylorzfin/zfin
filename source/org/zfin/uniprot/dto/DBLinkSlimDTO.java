package org.zfin.uniprot.dto;

import lombok.Getter;
import lombok.Setter;
import org.zfin.uniprot.UniProtTools;

import java.util.List;

import static org.zfin.uniprot.UniProtTools.isNonLoadPublication;

@Getter
@Setter
public class DBLinkSlimDTO {
    private String accession;
    private String dataZdbID;
    private String markerAbbreviation;
    private String dbName;
    private List<String> publicationIDs;

    private String getPublicationIDsAsString() {
        if (publicationIDs == null) {
            return "";
        }
        return String.join(",", publicationIDs);
    }

    public boolean containsNonLoadPublication() {
        if (publicationIDs == null) {
            return false;
        }
        return publicationIDs.stream().anyMatch(UniProtTools::isNonLoadPublication);
    }
}