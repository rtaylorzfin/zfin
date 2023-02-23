package org.zfin.publication.presentation;

import com.fasterxml.jackson.annotation.JsonView;
import lombok.Data;
import org.zfin.framework.api.View;
import org.zfin.publication.CorrespondenceNeededReason;

@Data
public class CorrespondenceNeededDTO {

    @JsonView(View.API.class)
    private long id;

    @JsonView(View.API.class)
    private String name;

    @JsonView(View.API.class)
    private boolean needed;

    public static CorrespondenceNeededDTO fromCorrespondenceNeeded(CorrespondenceNeededReason reason, boolean needed) {
        CorrespondenceNeededDTO dto = new CorrespondenceNeededDTO();
        dto.setId(reason.getId());
        dto.setName(reason.getName());
        dto.setNeeded(needed);
        return dto;
    }
}
