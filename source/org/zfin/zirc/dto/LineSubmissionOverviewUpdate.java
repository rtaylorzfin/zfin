package org.zfin.zirc.dto;

import jakarta.validation.constraints.Size;

// Abbreviation column exists on the entity but is not editable through the
// reference form, so the DTO omits it; existing values are preserved on save.
public record LineSubmissionOverviewUpdate(
        @Size(max = 255) String name,
        @Size(max = 2000) String previousNames) {
}
