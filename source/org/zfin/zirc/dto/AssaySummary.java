package org.zfin.zirc.dto;

import org.zfin.zirc.entity.GenotypingAssay;

/**
 * Lightweight per-assay row sent inside {@link MutationResponse#assays()}.
 * Drives the collapsed-card header in AssaysListRenderer — the full
 * per-assay field set is fetched separately when a card is expanded.
 */
public record AssaySummary(
        Long id,
        Integer sortOrder,
        String assayType) {

    public static AssaySummary of(GenotypingAssay a) {
        return new AssaySummary(a.getId(), a.getSortOrder(), a.getAssayType());
    }
}
