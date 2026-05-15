package org.zfin.zirc.dto;

import jakarta.validation.constraints.Size;

public record LineSubmissionAcceptanceReasonsUpdate(
        String[] reasons,
        @Size(max = 2000) String reasonsOther) {
}
