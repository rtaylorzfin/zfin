package org.zfin.zirc.dto;

import org.zfin.zirc.entity.LineSubmission;
import org.zfin.zirc.entity.Mutation;

import java.util.Comparator;
import java.util.List;

public record LineSubmissionResponse(
        String zdbID,
        String name,
        String abbreviation,
        String previousNames,
        Boolean singleAllelic,
        String maternalBackground,
        String paternalBackground,
        Boolean backgroundChangeable,
        String backgroundChangeConcerns,
        String unreportedFeaturesDetails,
        String husbandryInfo,
        String additionalInfo,
        String[] reasons,
        String reasonsOther,
        List<MutationResponse> mutations,
        boolean draft) {

    public static LineSubmissionResponse of(LineSubmission s) {
        List<MutationResponse> muts = s.getMutations() == null ? List.of() :
                s.getMutations().stream()
                        .sorted(Comparator.comparing(
                                Mutation::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(MutationResponse::of)
                        .toList();
        return new LineSubmissionResponse(
                s.getZdbID(),
                s.getName(),
                s.getAbbreviation(),
                s.getPreviousNames(),
                s.getSingleAllelic(),
                s.getMaternalBackground(),
                s.getPaternalBackground(),
                s.getBackgroundChangeable(),
                s.getBackgroundChangeConcerns(),
                s.getUnreportedFeaturesDetails(),
                s.getHusbandryInfo(),
                s.getAdditionalInfo(),
                s.getReasons(),
                s.getReasonsOther(),
                muts,
                Boolean.TRUE.equals(s.getIsDraft()));
    }
}
