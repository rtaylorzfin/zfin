package org.zfin.zirc.dto;

import jakarta.validation.constraints.Size;

// backgroundChangeConcerns column exists on the entity but is not editable
// through the reference form, so the DTO omits it; existing values are
// preserved on save.
public record LineSubmissionBackgroundUpdate(
        Boolean singleAllelic,
        @Size(max = 255) String maternalBackground,
        @Size(max = 255) String paternalBackground,
        Boolean backgroundChangeable) {
}
