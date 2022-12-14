package org.zfin.datatransfer.go;

import org.apache.commons.collections4.CollectionUtils;
import org.zfin.infrastructure.ActiveData;

import java.util.List;

import static org.zfin.repository.RepositoryFactory.getInfrastructureRepository;

//TODO: should this be Id or ID?
public class ZdbIdValidator {
    public static boolean validateExists(String ID) {
        boolean isValidStructure = ActiveData.validateActiveData(ID);
        if (!isValidStructure) {
            return false;
        }
        ActiveData activeData = getInfrastructureRepository().getActiveData(ID);
        return activeData != null;
    }

    public static boolean validateAllIdsExist(List<String> IDs) {
        List<ActiveData> results = getInfrastructureRepository().getAllActiveData(IDs);
        return results.size() == IDs.size();
    }

    public static List<String> getInvalidIDsFromList(List<String> IDs) {
        List<String> resultIDs = getInfrastructureRepository()
                                    .getAllActiveData(IDs)
                                    .stream()
                                    .map(ActiveData::getZdbID)
                                    .toList();
        return (List<String>) CollectionUtils.subtract(IDs, resultIDs);
    }
}
