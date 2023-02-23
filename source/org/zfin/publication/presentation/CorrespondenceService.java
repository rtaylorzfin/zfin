package org.zfin.publication.presentation;

import org.zfin.publication.CorrespondenceNeeded;
import org.zfin.publication.CorrespondenceNeededReason;

import java.util.ArrayList;
import java.util.List;

import static org.zfin.repository.RepositoryFactory.getPublicationRepository;

public class CorrespondenceService {

    public static List<CorrespondenceNeededDTO> getCorrespondenceNeededDTOsGridByPublicationID(String pubID) {
        List<CorrespondenceNeededReason> neededReasons = getCorrespondenceNeededReasonsByPublicationID(pubID);
        return getCorrespondenceNeededDTOsGridFromCorrespondenceNeededList(neededReasons);
    }

    private static List<CorrespondenceNeededReason> getCorrespondenceNeededReasonsByPublicationID(String pubID) {
        List<CorrespondenceNeeded> cn = getPublicationRepository().getCorrespondenceNeededByPublicationID(pubID);
        List<CorrespondenceNeededReason> reasons = cn.stream().map(CorrespondenceNeeded::getReason).toList();
        return reasons;
    }

    private static List<CorrespondenceNeededDTO> getCorrespondenceNeededDTOsGridFromCorrespondenceNeededList(List<CorrespondenceNeededReason> neededReasons) {
        List<CorrespondenceNeededReason> allReasons = getPublicationRepository().getAllCorrespondenceNeededReasons();
        ArrayList<CorrespondenceNeededDTO> correspondenceNeededDTOs = new ArrayList<>();
        for (CorrespondenceNeededReason reason : allReasons) {
            correspondenceNeededDTOs.add(CorrespondenceNeededDTO.fromCorrespondenceNeeded(reason, neededReasons.contains(reason)));
        }
        return correspondenceNeededDTOs;
    }

    public static void setCorrespondenceNeededByPublicationID(String pubID, List<CorrespondenceNeededDTO> correspondenceNeededDTOs) {
        //delete all correspondence needed for this publication
        getPublicationRepository().deleteCorrespondenceNeededByPublicationID(pubID);

        //add all correspondence needed for this publication
        for (CorrespondenceNeededDTO correspondenceNeededDTO : correspondenceNeededDTOs) {
            if (correspondenceNeededDTO.isNeeded()) {
                CorrespondenceNeeded correspondenceNeeded = new CorrespondenceNeeded();
                correspondenceNeeded.setPublication(getPublicationRepository().getPublication(pubID));
                correspondenceNeeded.setReason(getPublicationRepository().getCorrespondenceNeededReasonByID(correspondenceNeededDTO.getId()));
                getPublicationRepository().insertCorrespondenceNeeded(correspondenceNeeded);
            }
        }
    }
}
