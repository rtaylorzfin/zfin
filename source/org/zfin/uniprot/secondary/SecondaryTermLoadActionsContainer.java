package org.zfin.uniprot.secondary;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Getter
@Setter
@Builder
public class SecondaryTermLoadActionsContainer {

    private Long releaseID;
    private Date creationDate;

    @Builder.Default
    private List<SecondaryTermLoadAction> actions = new ArrayList<>();


}
