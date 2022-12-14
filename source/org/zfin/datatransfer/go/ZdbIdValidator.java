package org.zfin.datatransfer.go;

import org.apache.commons.collections4.CollectionUtils;
import org.zfin.infrastructure.ActiveData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public static boolean validateAllIdsExist(Set<String> IDs) {
        List<ActiveData> results = getInfrastructureRepository().getAllActiveData(IDs);
        return results.size() == IDs.size();
    }
    public static boolean validateAllIdsExist(List<String> IDs) {
        return validateAllIdsExist(new HashSet<>(IDs));
    }

    public static List<String> getInvalidIDsFromSet(Set<String> IDs) {
        List<String> resultIDs = getInfrastructureRepository()
                                    .getAllActiveData(IDs)
                                    .stream()
                                    .map(ActiveData::getZdbID)
                                    .toList();
        return (List<String>) CollectionUtils.subtract(IDs, resultIDs);
    }
}
