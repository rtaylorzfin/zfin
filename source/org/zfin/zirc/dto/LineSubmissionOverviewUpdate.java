package org.zfin.zirc.dto;

import jakarta.validation.constraints.Size;

public record LineSubmissionOverviewUpdate(
        @Size(max = 255) String name,
        @Size(max = 255) String abbreviation,
        @Size(max = 2000) String previousNames) {
}
