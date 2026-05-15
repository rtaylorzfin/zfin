package org.zfin.zirc.dto;

import org.zfin.zirc.entity.Mutation;

public record MutationResponse(
        Long id,
        String lineSubmissionId,
        Integer sortOrder,
        String alleleDesignation,
        Boolean alleleInZfin,
        String mutationType,
        String mutationDiscoverer,
        String mutationInstitution) {

    public static MutationResponse of(Mutation m) {
        return new MutationResponse(
                m.getId(),
                m.getLineSubmission().getZdbID(),
                m.getSortOrder(),
                m.getAlleleDesignation(),
                m.getAlleleInZfin(),
                m.getMutationType(),
                m.getMutationDiscoverer(),
                m.getMutationInstitution());
    }
}
