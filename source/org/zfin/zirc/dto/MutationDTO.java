package org.zfin.zirc.dto;

import org.zfin.zirc.entity.GenotypingAssay;
import org.zfin.zirc.entity.Mutation;

import java.util.Comparator;
import java.util.List;

public record MutationDTO(
        Long id,
        String lineSubmissionId,
        Integer sortOrder,
        // General
        String alleleDesignation,
        Boolean alleleInZfin,
        String mutationType,
        String mutationDiscoverer,
        String mutationInstitution,
        // Mutagenesis
        String mutagenesisStage,
        String mutagenesisProtocol,
        Boolean molecularlyCharacterized,
        // Lethality
        Boolean homozygousLethal,
        String lethalityStageTypical,
        String lethalitySpecificTimepoint,
        String lethalityWindowStart,
        String lethalityWindowEnd,
        String lethalityAdditionalInfo,
        // Publications
        List<String> publications,
        // Genotyping assays — summary rows only; full per-assay fields are
        // fetched separately when a card is expanded.
        List<AssaySummaryDTO> assays) {

    public static MutationDTO of(Mutation m) {
        List<AssaySummaryDTO> assays = m.getGenotypingAssays() == null ? List.of() :
                m.getGenotypingAssays().stream()
                        .sorted(Comparator.comparing(
                                GenotypingAssay::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(AssaySummaryDTO::of)
                        .toList();
        return new MutationDTO(
                m.getId(),
                m.getLineSubmission().getZdbID(),
                m.getSortOrder(),
                m.getAlleleDesignation(),
                m.getAlleleInZfin(),
                m.getMutationType(),
                m.getMutationDiscoverer(),
                m.getMutationInstitution(),
                m.getMutagenesisStage(),
                m.getMutagenesisProtocol(),
                m.getMolecularlyCharacterized(),
                m.getHomozygousLethal(),
                m.getLethalityStageTypical(),
                m.getLethalitySpecificTimepoint(),
                m.getLethalityWindowStart(),
                m.getLethalityWindowEnd(),
                m.getLethalityAdditionalInfo(),
                List.copyOf(m.getPublications() == null ? List.of() : m.getPublications()),
                assays);
    }
}
