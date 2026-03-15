# NCBI Gene Load — Specification

## Overview

The NCBI Gene Load imports gene and accession data from NCBI into ZFIN. It matches NCBI genes to ZFIN genes via shared accessions, then loads downstream accessions (RefSeq, GenBank) as `db_link` records with proper attribution and annotation status.

`NCBILoadTask` replaces `NCBIDirectPort` — the new implementation is a from-scratch rewrite in Java that produces equivalent results with clearer architecture and better correctness in two areas (see [Behavioral Differences](#behavioral-differences-from-legacy-code)).

## Architecture

```
NCBILoadTask (orchestrator)
├── Phase A: Download NCBI data files (or reuse existing)
├── Phase B: Load external_resource tables
│   ├── NcbiGene2AccessionService
│   ├── NcbiRefSeqCatalogService
│   └── NcbiGeneInfoService
├── Phase C: Capture before-state + prepare delete candidates
│   ├── NcbiLoadStatistics.capture("before")
│   └── NcbiDeletePreparer.prepare()
├── Phase D-G: Match + Load (single transaction)
│   ├── RnaAccessionMatcher.match()         — primary 1:1 RNA matching
│   ├── EnsemblSupplementMatcher.augment()  — secondary Ensembl matching
│   ├── VegaLegacyHandler.reintroduce()     — preserve retired Vega links
│   ├── AccessionWriter.buildLoadRecords()  — build full record set
│   ├── NcbiDbLinkLoader.deleteAndLoad()    — delete/insert cycle
│   └── MarkerAssemblyUpdater.update()      — assembly + chromosome locations
└── Phase H: Capture after-state + generate report
    ├── NcbiLoadStatistics.capture("after")
    └── NcbiLoadReportGenerator.generate()
```

### Key Files

| File | Purpose |
|------|---------|
| `NCBILoadTask.java` | Orchestrator — chains all phases |
| `matching/RnaAccessionMatcher.java` | Primary gene matching via shared RNA accessions |
| `matching/EnsemblSupplementMatcher.java` | Secondary matching via Ensembl cross-references |
| `matching/VegaLegacyHandler.java` | Preserves legacy Vega-attributed NCBI links |
| `matching/MatchResult.java` | Value object holding all match results |
| `load/AccessionWriter.java` | Builds load records from matches + gene2accession |
| `load/NcbiDbLinkLoader.java` | Delete/insert cycle with conflict resolution |
| `load/NcbiDeletePreparer.java` | Identifies db_link records to delete |
| `load/MarkerAssemblyUpdater.java` | Updates marker_assembly + chromosome locations |
| `NCBIOutputFileToLoad.java` | In-memory load file (replaces `toLoad.unl`) |
| `report/NcbiLoadStatistics.java` | Before/after state capture to CSV |
| `report/NcbiLoadReportGenerator.java` | Summary report generation |

## Matching Algorithm

### Step 1: RNA Accession Matching (Primary)

The primary matcher finds reciprocal 1:1 mappings between ZFIN genes and NCBI genes via shared GenBank RNA accessions.

1. Build ZFIN map: `ZFIN gene → {RNA accessions}` from `db_link` where `fdbcont = GenBank RNA`
2. Build NCBI map: `NCBI gene → {RNA accessions}` from `external_resource.ncbi_gene2accession` where status = "-" (GenBank)
3. Identify "problematic" accessions — those appearing on multiple genes on either side
4. **Conservative filter**: Exclude any gene that has *any* problematic accession
5. One-way ZFIN→NCBI: For each ZFIN gene, find which NCBI genes it can reach via clean accessions
6. One-way NCBI→ZFIN: Mirror direction
7. Keep only pairs where both directions agree on a 1:1 mapping

**Output**: `confirmed` BidiMap (ZFIN gene ↔ NCBI gene), plus `oneToN` and `nToOne` conflict maps.

### Step 2: Ensembl Supplement Matching (Secondary)

For genes that failed RNA matching, attempt matching via Ensembl cross-references in NCBI's `gene_info` file.

1. Find NCBI genes that reference `Ensembl:ENSDARG*` in their `dbXrefs` column
2. Find ZFIN genes with matching ENSDARG db_links
3. Skip genes already matched via RNA or with pre-existing NCBI Gene IDs
4. Add as supplementary matches (attributed to `ZDB-PUB-020723-3`, the Ensembl supplement pub)

### Step 3: Vega Legacy Preservation

For genes matched via the now-retired Vega database, preserve existing NCBI Gene ID links attributed to `ZDB-PUB-130725-2` (Vega pub).

1. Query existing NCBI Gene ID links with Vega attribution
2. Skip if already matched via RNA or Ensembl
3. Reintroduce as lowest-priority matches

### Match Priority

When the same accession could receive multiple attributions, priority determines which publication wins:

1. **RNA** (`ZDB-PUB-020723-5`) — highest priority
2. **Ensembl Supplement** (`ZDB-PUB-020723-3`)
3. **Vega** (`ZDB-PUB-130725-2`) — lowest priority

## Load Process

### AccessionWriter — Building Records

For each matched gene pair (ZFIN gene ↔ NCBI gene):

1. Write an NCBI Gene ID record (`fdbcont = ZDB-FDBCONT-040412-1`)
2. Query `gene2accession` for all accessions belonging to this NCBI gene
3. Categorize by status and prefix:

| Status | Prefix Pattern | FDBCont Type |
|--------|---------------|-------------|
| `-` (GenBank) | RNA accession | GenBank RNA |
| `-` | Protein accession | GenPept |
| `-` | DNA accession | GenBank DNA |
| Any other | `NM_`, `XM_`, `NR_`, `XR_` | RefSeq RNA |
| Any other | `NP_`, `XP_` | RefPept |
| Any other | `NC_`, `NT_`, `NW_`, `NG_`, `AC_` | RefSeq DNA |

4. Look up sequence lengths from RefSeq catalog and existing db_links
5. **Filter shared genomic accessions**: Chromosome-level accessions (e.g., `NC_*`) shared by multiple NCBI genes are excluded — they represent chromosomes, not individual genes

### NcbiDbLinkLoader — Delete/Insert Cycle

Nine-step process within a single transaction:

1. **Preserve "not in current release"**: Don't delete NCBI Gene IDs for genes flagged as not in current NCBI release (unless being actively replaced)
2. **Delete reference_protein**: Clean up protein cross-references for records being deleted
3. **Delete zdb_active_data**: Cascade-deletes db_link + record_attribution for all delete candidates
4. **Remove load-pub attributions**: Strip automated attributions from manually curated GenBank accessions
5. **Resolve manual curation conflicts**: If a manually curated NCBI Gene ID conflicts with the load's match, prefer manual curation
6. **Insert db_link records**: Bulk insert with `ON CONFLICT DO NOTHING` for pre-existing records
7. **Insert record_attribution**: Link each new db_link to its publication
8. **Many-to-many cleanup**: Post-load check — if multiple ZFIN genes share an NCBI Gene ID (or vice versa), remove all of them
9. **Update marker_annotation_status**: Set status to "Current" (12) for genes with load-attributed NCBI IDs; "Not in current annotation release" (13) for flagged genes; "Unknown" for genes that lost their NCBI ID

### MarkerAssemblyUpdater — Assembly Linkage

For genes that received new NCBI Gene IDs and have matching GFF3 entries:

1. Insert `marker_assembly` record for GRCz12tu
2. Insert `sequence_feature_chromosome_location_generated` records (NCBI + ZFIN sources)
3. Insert gene_id into `gff3_ncbi_attribute`
4. GRCz11 fallback for genes with Ensembl locations but no GRCz12tu

## Key Business Rules

1. **1:1 enforcement**: Each ZFIN gene should map to at most one NCBI Gene ID, and vice versa
2. **Manual curation wins**: Manually curated NCBI Gene IDs (non-load publications) take precedence over automated matches
3. **Conservative matching**: Any gene with *any* ambiguous accession is excluded from matching entirely
4. **Drop-and-reload**: All load-attributed db_links are deleted and recreated each run (full recalculation)
5. **Annotation status tracking**: Genes' curation status is updated based on whether they received/lost NCBI Gene IDs
6. **Load publications**: Three distinct publications track attribution source:
   - `ZDB-PUB-020723-5`: RNA-based match
   - `ZDB-PUB-020723-3`: NCBI supplement (Ensembl-based)
   - `ZDB-PUB-130725-2`: Vega-based (legacy)

## Testing Strategy

### Integration Tests (`NCBILoadIntegrationTest`)

Run in the `ncbiload` Docker container against a minimal test database. Each test:
1. Creates specific gene/db_link state via `BeforeStateBuilder`
2. Sets up synthetic NCBI input files (gene2accession, gene_info, etc.)
3. Runs `NCBILoadTask`
4. Asserts specific db_link and attribution outcomes

14 tests cover: simple RNA match, RefSeq downstream, Ensembl matching, replaced gene IDs, idempotent re-runs, Vega conflicts, 1:N warnings, lost gene IDs, annotation status, ON CONFLICT attribution, shared genomic accessions, not-in-current-release handling.

```bash
docker compose run --rm ncbiload bash -lc \
  'SKIP_DANGER_WARNING=1 gradle -PncbiLoadTests test --info \
   --tests org.zfin.datatransfer.ncbi.NCBILoadIntegrationTest'
```

### Characterization Test (`NCBILoadCharacterizationTest`)

Run against a full database unload (2026.01.29.1) with real NCBI data from 2026-01-30. Verifies that the load produces identical output to a saved baseline.

State is captured in three separate CSVs to avoid amplification:
- `after_load_dblinks.csv` — one row per db_link (with aggregated attributions)
- `after_load_annotation.csv` — one row per gene (marker_annotation_status)
- `after_load_assembly.csv` — one row per gene+assembly

```bash
docker compose run --rm compile bash -lc \
  'gradle -DB=/opt/zfin/unloads/db/2026.01.29.1/2026.01.29.1.bak loaddb; \
   psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1179/ZFIN-10082.sql; \
   psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1180/ZFIN-10173-gene2accession.sql; \
   SKIP_DANGER_WARNING=1 gradle -PincludeNcbiCharacterizationTest test --info \
   --tests org.zfin.datatransfer.ncbi.NCBILoadCharacterizationTest.testPointInTimeCharacterization'
```

### Unit Tests (`NCBILoadCharacterizationTest` — baseline generation)

To regenerate baselines after intentional changes:

```bash
# Same as above but run generateBaseline instead of testPointInTimeCharacterization
```

## Behavioral Differences from Legacy Code

Two intentional improvements over `NCBIDirectPort`:

### 1. ON CONFLICT Attribution

**Legacy**: When inserting a db_link that already exists, the old code created a `record_attribution` entry pointing to a non-existent db_link ZDB ID (orphaned attribution).

**New**: Uses `ON CONFLICT DO NOTHING` for the db_link insert, then adds attribution to the *existing* record. Result: proper attribution on existing records.

### 2. Accession Ownership

**Legacy**: Used a 1:1 `Map<accession, geneId>` when building gene2accession data. For NCBI genes with multiple accession rows, the last-write-wins semantics silently dropped earlier entries.

**New**: Uses a `Map<geneId, List<accession>>` — all per-gene accessions from gene2accession are preserved. Result: more complete accession linkage.

## Gradle Tasks

```bash
gradle ncbiLoadPort   # Legacy NCBIDirectPort
gradle ncbiLoad       # New NCBILoadTask
```

## Input Files

Downloaded from NCBI FTP and processed:

| File | Content |
|------|---------|
| `gene2accession.gz` | NCBI gene → accession mappings (filtered to taxid 7955) |
| `zf_gene_info.gz` | NCBI gene metadata + dbXrefs (Ensembl, ZFIN cross-refs) |
| `RefSeqCatalog.gz` | RefSeq accession lengths |
| `notInCurrentReleaseGeneIDs.unl` | NCBI genes not in current release |
| `seq.fasta` | BLAST sequences (used by external BLAST step, not by load) |
| `RELEASE_NUMBER` | NCBI release version |

## External Resource Tables

The load populates these PostgreSQL tables from input files for efficient SQL joins:

| Table | Source File | Purpose |
|-------|------------|---------|
| `external_resource.ncbi_gene2accession` | gene2accession.gz | Gene→accession mappings |
| `external_resource.ncbi_refseq_catalog` | RefSeqCatalog.gz | Accession sequence lengths |
| `external_resource.ncbi_gene_info` | zf_gene_info.gz | Gene metadata + cross-references |

## Performance Notes

The load takes ~2.5 hours against a full production database. The main bottlenecks are:

1. **Bulk DELETE from zdb_active_data** (~45 min): Cascade-deletes ~100K db_link records through database triggers
2. **INSERT INTO sequence_feature_chromosome_location_generated** (~30 min): Cross-join with regex matching against GFF3 tables
3. **Conflict-path individual DELETEs** (~15 min): One-by-one cleanup of ON CONFLICT records

Future optimization: incremental load (compute diff and apply delta instead of drop-and-reload).
