package org.zfin.uniprot.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DBLinkSlimDTO {
    private String accession;
    private String dataZdbID;
    private String markerAbbreviation;
    private String dbName;
}
