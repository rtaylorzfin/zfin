# NCBI Load Rewrite — Next Steps

## Completed
- [x] Split CSV capture into dblinks/annotation/assembly (eliminates amplification)
- [x] Capture new-code baseline and verify characterization test passes with 0 diffs
- [x] Spec document (`doc/ncbi-load-spec.md`)
- [x] Add `ncbiLoad` Gradle task
- [x] Remove legacy behavior flag — commit to correct ON CONFLICT attribution and accession ownership
- [x] Incremental load implementation (`NcbiDiffComputer`, `prepareWithMetadata()`, `incrementalLoad()`)
- [x] Validate incremental load against characterization test (0 diffs across dblinks/annotation/assembly)
- [x] Index GFF3 GeneID for equi-join (`MarkerAssemblyUpdater.createGeneIdLookupTable()`)

## Remaining

### 1. Legacy baseline comparison (Steps 2-4 from original plan)
Capture a baseline from `NCBIDirectPort` using the new split CSV format and diff against the new-code baseline. This documents the exact behavioral differences (ON CONFLICT attribution, accession ownership) for the historical record. Lower priority since the new behavior is demonstrably correct.

### 2. Make incremental load the default
The incremental load has been validated against the characterization test with 0 diffs. Consider making it the default (removing the need for `NCBI_INCREMENTAL_LOAD=true` env var) and keeping drop-and-reload as the fallback.

### 4. Baseline generation CI ergonomics
The `generateBaseline` test writes to `build/ncbi-baselines/` then requires a manual host-side copy to the archive. Consider a Gradle task or script that handles the full flow (load db, run baseline, copy to archive) without permission issues.
