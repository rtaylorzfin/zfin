package org.zfin.zirc.dto;

import org.zfin.zirc.entity.Mutation;

import java.util.List;

public record MutationResponse(
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
        List<String> publications) {

    public static MutationResponse of(Mutation m) {
        return new MutationResponse(
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
                List.copyOf(m.getPublications() == null ? List.of() : m.getPublications()));
    }
}
