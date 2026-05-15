package org.zfin.zirc.dto;

import jakarta.validation.constraints.Size;

public record LineSubmissionAdditionalInfoUpdate(
        @Size(max = 5000) String unreportedFeaturesDetails,
        @Size(max = 5000) String husbandryInfo,
        @Size(max = 5000) String additionalInfo) {
}
