package org.zfin.zirc.dto;

import org.zfin.zirc.entity.LineSubmission;

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
        boolean draft) {

    public static LineSubmissionResponse of(LineSubmission s) {
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
                Boolean.TRUE.equals(s.getIsDraft()));
    }
}
