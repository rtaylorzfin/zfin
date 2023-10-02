package org.zfin.uniprot.adapter;

import org.biojavax.CrossRef;
import org.biojavax.RankedCrossRef;

import java.util.Collection;
import java.util.Set;

public class CrossRefAdapter {
    public final CrossRef originalCrossRef;

    public CrossRefAdapter(CrossRef wrappedObject) {
        this.originalCrossRef = wrappedObject;
    }

    public static Collection<CrossRefAdapter> fromRankedCrossRefs(Set<RankedCrossRef> rankedCrossRefs) {
        return rankedCrossRefs.stream().map(rc -> new CrossRefAdapter(rc.getCrossRef())).toList();
    }

    public String getAccession() {
        return originalCrossRef.getAccession();
    }

    public String getDbname() {
        return originalCrossRef.getDbname();
    }
}
