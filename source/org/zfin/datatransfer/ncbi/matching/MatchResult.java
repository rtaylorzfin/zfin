package org.zfin.datatransfer.ncbi.matching;

import lombok.Getter;
import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.util.HashMap;
import java.util.Map;

/**
 * Value object holding the results of all matching phases:
 * - confirmed: reciprocal 1:1 RNA-based matches (ZFIN gene → NCBI gene)
 * - supplement: Ensembl-based supplementary matches (ZFIN gene → NCBI gene)
 * - legacyVega: reintroduced Vega-based matches (ZFIN gene → NCBI gene)
 * - oneToN: ZFIN genes that mapped to multiple NCBI genes (conflicts)
 * - nToOne: NCBI genes that mapped to multiple ZFIN genes (conflicts)
 */
@Getter
public class MatchResult {

    // Reciprocal 1:1 RNA-based matches: ZFIN gene ID → NCBI gene ID
    private final BidiMap<String, String> confirmed = new DualHashBidiMap<>();

    // Supplementary Ensembl-based matches: ZFIN gene ID → NCBI gene ID
    private final BidiMap<String, String> supplement = new DualHashBidiMap<>();

    // Legacy Vega matches to reintroduce: ZFIN gene ID → NCBI gene ID
    private final BidiMap<String, String> legacyVega = new DualHashBidiMap<>();

    // 1:N conflicts: ZFIN gene ID → {NCBI gene ID → accession}
    private final Map<String, Map<String, String>> oneToN = new HashMap<>();

    // N:1 conflicts: NCBI gene ID → {ZFIN gene ID → accession}
    private final Map<String, Map<String, String>> nToOne = new HashMap<>();

}
