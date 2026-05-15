package org.zfin.zirc.dto;

import jakarta.validation.constraints.Size;

public record LineSubmissionBackgroundUpdate(
        Boolean singleAllelic,
        @Size(max = 255) String maternalBackground,
        @Size(max = 255) String paternalBackground,
        Boolean backgroundChangeable,
        @Size(max = 2000) String backgroundChangeConcerns) {
}
