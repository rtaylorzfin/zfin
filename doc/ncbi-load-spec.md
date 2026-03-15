# NCBI Gene Load — Specification

## Overview

The NCBI Gene Load imports gene and accession data from NCBI into ZFIN. It matches NCBI genes to ZFIN genes via shared accessions, then loads downstream accessions (RefSeq, GenBank) as `db_link` records with proper attribution and annotation status.

## Phases

The load runs in sequential phases:

1. **Download** NCBI data files (or reuse existing ones from a prior run)
2. **Stage** input data into PostgreSQL tables (`external_resource` schema) for efficient SQL joins
3. **Capture** before-state snapshot for reporting
4. **Match** ZFIN genes to NCBI genes (see [Matching](#matching))
5. **Build** the full set of db_link records to load (see [Record Building](#record-building))
6. **Load** records into the database (see [Loading](#loading))
7. **Update** assembly linkages for newly matched genes (see [Assembly Linkage](#assembly-linkage))
8. **Capture** after-state and generate a summary report

Phases 4–7 run in a single database transaction.

## Input Files

Downloaded from NCBI FTP:

| File | Content |
|------|---------|
| `gene2accession.gz` | NCBI gene → accession mappings (filtered to taxid 7955) |
| `zf_gene_info.gz` | NCBI gene metadata + dbXrefs (Ensembl, ZFIN cross-refs) |
| `RefSeqCatalog.gz` | RefSeq accession sequence lengths |
| `notInCurrentReleaseGeneIDs.unl` | NCBI gene IDs not in current annotation release |
| `RELEASE_NUMBER` | NCBI release version |

These are staged into PostgreSQL tables for the load:

| Table | Source |
|-------|--------|
| `external_resource.ncbi_gene2accession` | gene2accession.gz |
| `external_resource.ncbi_refseq_catalog` | RefSeqCatalog.gz |
| `external_resource.ncbi_gene_info` | zf_gene_info.gz |

## Matching

The goal is to establish 1:1 mappings between ZFIN genes and NCBI genes. Three methods are tried in priority order:

### 1. RNA Accession Matching (Primary)

Find reciprocal 1:1 mappings via shared GenBank RNA accessions.

- Build maps: `ZFIN gene → {RNA accessions}` and `NCBI gene → {RNA accessions}`
- Exclude any gene that has an accession appearing on multiple genes on either side (conservative filter)
- Compute one-way mappings in both directions; keep only pairs where both agree on a 1:1 match

### 2. Ensembl Supplement Matching

For unmatched genes, attempt matching via Ensembl (`ENSDARG`) cross-references in NCBI's `gene_info` dbXrefs column. Skip genes already matched or with pre-existing NCBI Gene IDs.

### 3. Vega Legacy Preservation

Preserve existing NCBI Gene ID links that were originally established via the retired Vega database. Lowest priority — only applies if RNA and Ensembl matching didn't cover the gene.

### Match Priority

When an accession could receive attribution from multiple methods:

1. **RNA** (`ZDB-PUB-020723-5`) — highest
2. **Ensembl Supplement** (`ZDB-PUB-020723-3`)
3. **Vega** (`ZDB-PUB-130725-2`) — lowest

## Record Building

For each matched gene pair (ZFIN gene ↔ NCBI gene):

1. Create an NCBI Gene ID record
2. Query `gene2accession` for all accessions belonging to this NCBI gene
3. Categorize each accession by its status and prefix:

| Status | Prefix Pattern | Type |
|--------|---------------|------|
| `-` (GenBank) | RNA accession | GenBank RNA |
| `-` | Protein accession | GenPept |
| `-` | DNA accession | GenBank DNA |
| Other (RefSeq) | `NM_`, `XM_`, `NR_`, `XR_` | RefSeq RNA |
| Other | `NP_`, `XP_` | RefPept |
| Other | `NC_`, `NT_`, `NW_`, `NG_`, `AC_` | RefSeq DNA |

4. Look up sequence lengths from RefSeq catalog and existing db_links
5. Filter shared genomic accessions: chromosome-level accessions (e.g. `NC_*`) shared by multiple NCBI genes are excluded — they represent chromosomes, not individual genes

## Loading

Two load modes are available:

### Drop-and-Reload (default)

Deletes all load-attributed db_link records, then re-inserts the full computed set. Conceptually simple but slow due to cascade deletes through `zdb_active_data`.

### Incremental (opt-in via `NCBI_INCREMENTAL_LOAD=true`)

Computes the diff between current database state and the desired state, then applies only the delta (adds, deletes, updates). Validated to produce identical output to drop-and-reload.

### Load Rules (apply to both modes)

These rules govern the load regardless of mode:

- **Manual curation wins**: If a manually curated NCBI Gene ID (attributed to a non-load publication) conflicts with the load's match, prefer manual curation and remove the conflicting load record
- **Preserve not-in-current-release**: NCBI Gene IDs flagged as not in the current annotation release are preserved from deletion, unless the gene is being actively replaced by a new match
- **ON CONFLICT handling**: When inserting a db_link that already exists, skip the insert but add the load publication as an attribution on the existing record
- **Many-to-many cleanup**: After loading, check for and remove any cases where multiple ZFIN genes share an NCBI Gene ID (or vice versa)
- **Remove stale attributions**: Strip load-pub attributions from GenBank accessions that also have manual curation
- **Annotation status**: Update `marker_annotation_status` — "Current" for genes with load-attributed NCBI IDs, "Not in current annotation release" for flagged genes

## Assembly Linkage

For genes that received new NCBI Gene IDs and have corresponding entries in the GFF3 data:

1. Create `marker_assembly` records for GRCz12tu
2. Create `sequence_feature_chromosome_location_generated` records (NCBI and ZFIN sources)
3. Record the gene ID linkage in `gff3_ncbi_attribute`
4. GRCz11 fallback: genes with Ensembl locations but no GRCz12tu get a GRCz11 assembly entry

The GFF3 join uses an extracted GeneID index rather than regex matching against raw Dbxref attribute values.

## Key Business Rules

1. **1:1 enforcement**: Each ZFIN gene maps to at most one NCBI Gene ID, and vice versa
2. **Manual curation wins**: Non-automated attributions always take precedence over load matches
3. **Conservative matching**: Any gene with any ambiguous accession is excluded from matching entirely
4. **Annotation status tracking**: Genes' curation status is updated based on whether they received/lost NCBI Gene IDs
5. **Three load publications** track attribution source:
   - `ZDB-PUB-020723-5`: RNA-based match
   - `ZDB-PUB-020723-3`: NCBI supplement (Ensembl-based)
   - `ZDB-PUB-130725-2`: Vega-based (legacy)

## Testing

### Integration Tests

Run against a minimal test database with synthetic NCBI input files. Each test creates specific gene/db_link state, runs the full load, and asserts specific outcomes.

Covers: simple RNA match, RefSeq downstream, Ensembl matching, replaced gene IDs, idempotent re-runs, Vega conflicts, 1:N warnings, lost gene IDs, annotation status, ON CONFLICT attribution, shared genomic accessions, not-in-current-release handling.

```bash
docker compose run --rm ncbiload bash -lc \
  'SKIP_DANGER_WARNING=1 gradle -PncbiLoadTests test --info \
   --tests org.zfin.datatransfer.ncbi.NCBILoadIntegrationTest'
```

### Characterization Test

Run against a full database unload (2026.01.29.1) with real NCBI data from 2026-01-30. Verifies that the load produces identical output to a saved baseline across three dimensions:

- **dblinks**: one row per db_link with aggregated attributions
- **annotation**: one row per gene (marker_annotation_status)
- **assembly**: one row per gene+assembly

Splitting into three CSVs avoids amplification where a single gene-level change appears as N changes (one per db_link row).

```bash
docker compose run --rm compile bash -lc \
  'gradle -DB=/opt/zfin/unloads/db/2026.01.29.1/2026.01.29.1.bak loaddb; \
   psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1179/ZFIN-10082.sql; \
   psql -v ON_ERROR_STOP=1 -f source/org/zfin/db/postGmakePostloaddb/1180/ZFIN-10173-gene2accession.sql; \
   SKIP_DANGER_WARNING=1 gradle -PincludeNcbiCharacterizationTest test --info \
   --tests org.zfin.datatransfer.ncbi.NCBILoadCharacterizationTest.testPointInTimeCharacterization'
```

To regenerate baselines after intentional changes, run `generateBaseline` instead of `testPointInTimeCharacterization`.

## Behavioral Differences from Legacy Code

Two intentional improvements over the original `NCBIDirectPort`:

1. **ON CONFLICT attribution**: The old code created orphaned `record_attribution` entries pointing to non-existent ZDB IDs when an insert conflicted. The new code correctly attributes the existing record.

2. **Accession ownership**: The old code used a 1:1 map (`accession → geneId`) when building gene2accession data, silently dropping entries via last-write-wins. The new code preserves all per-gene accessions.

## Usage

```bash
gradle ncbiLoadPort   # Legacy NCBIDirectPort
gradle ncbiLoad       # New NCBILoadTask (drop-and-reload)

NCBI_INCREMENTAL_LOAD=true gradle ncbiLoad   # Incremental mode
```
