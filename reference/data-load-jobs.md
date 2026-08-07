# Data Load Jobs — Inputs and Outputs

For each Jenkins data-load job: where its data comes from (external URLs), what it writes
(database tables), and which existing ZFIN tables it consults to match incoming records to
ZFIN objects.

Eight loads are covered in detail — the seven high-priority ones plus the unmerged unified GO
load that is meant to replace three of them. Every other data-load and update job is covered
at inventory depth in [The rest of the load jobs](#the-rest-of-the-load-jobs), with the
known gaps listed at the end.

## Conventions used below

- **External inputs** — files fetched over the network at run time. Where the URL is
  overridable, the environment variable or `zfin.properties` key is given.
- **Reference inputs** — tables read to resolve/validate incoming records (accession →
  ZFIN gene, OBO ID → term, PubMed ID → publication, …). These are the "matching" tables.
- **Writes** — tables the job inserts into, updates, or deletes from.
- **Artifacts** — files archived by Jenkins (reports, `.unl` files, downloaded sources).

Two write targets recur across almost every load and are not repeated in each section unless
the job manipulates them directly:

- `zdb_active_data` — every ZDB ID-bearing row is registered here on insert and removed on
  delete (usually via `get_id_and_insert_active_data()` / `deleteActiveDataByZdbID()`).
- `record_attribution` — links loaded rows to the publication that justifies them.

---

## Summary

| Job | Primary external source | Main tables written |
|---|---|---|
| [Load-GPAD-Noctua_w](#load-gpad-noctua_w) | `current.geneontology.org` Noctua GPAD | `marker_go_term_evidence`, `noctua_model` |
| [Load-GAF-GOA_m](#load-gaf-goa_m) | EBI GOA zebrafish GAFs (3 files) | `marker_go_term_evidence` |
| [Load-GAF-FP-Inference_m](#load-gaf-fp-inference_m) | `current.geneontology.org` `zfin-prediction.gaf` | `marker_go_term_evidence` |
| [NCBI-Gene-Load-Java](#ncbi-gene-load-java) | NCBI `gene2accession`, RefSeq catalog, `Danio_rerio.gene_info` | `db_link`, `marker_assembly`, `marker_annotation_status` |
| [Load-NCBI-GFF3-File](#load-ncbi-gff3-file) | NCBI RefSeq GRCz12tu GFF3 | `gff3_ncbi`, `gff3_ncbi_attribute`, `sequence_feature_chromosome_location_generated` |
| [UniProt-Secondary-Term-Load](#uniprot-secondary-term-load) | GO `*2go` mapping files, InterPro `entry.list` | `db_link`, `marker_go_term_evidence`, `interpro_protein`, `protein*` |
| [UniProt-Diff-Load](#uniprot-diff-load) | UniProt release file (downloaded by `UniProt-Release-Check_d`) | `db_link`, `record_attribution`, `uniprot_release` |
| [Load-GPAD-DANRE-mod_m](#load-gpad-danre-mod_m) *(unmerged)* | GO Central unified `DANRE-mod.gpad.gz` | nothing yet — report-only |

---

## Load-GPAD-Noctua_w

GO annotations curated in Noctua, delivered as a GPAD file.

- **Entry point:** `ant load-noctua-gpad` → `org.zfin.datatransfer.go.service.GafLoadJob`
  (organization `Noctua`, parser `org.zfin.datatransfer.go.GpadParser`)
  — [`server_apps/DB_maintenance/build.xml:144`](../server_apps/DB_maintenance/build.xml)
- **Trigger:** `Load-GPAD-Noctua-Daily-Trigger_d` runs `ant check-noctua-gpad-available`
  (`org.zfin.task.CheckNewLoadFileAvailableTask`) daily and starts this job when the upstream
  file's timestamp is newer than the last processed one. The job's own
  `SKIP_DOWNLOAD_IF_UNCHANGED` parameter (default `true`) is a second guard.

### External inputs

| URL | Override | Notes |
|---|---|---|
| `https://current.geneontology.org/products/upstream_and_raw_data/noctua_zfin.gpad.gz` | `NOCTUA_GPAD_URL` | GPAD 1.2; the ant target also archives a byte-identical copy as `<jobName>-source.gpad.gz` |

### Reference inputs

| Table | Used for |
|---|---|
| `eco_go_mapping` | ECO evidence code → GO evidence code (GPAD carries ECO IDs, not GO codes) |
| `term`, `term_subset`, `ontology_subset` | GO term lookup by OBO ID; obsolete check; `gocheck_do_not_annotate` / `gocheck_do_not_manually_annotate` subset rejection |
| `db_link` | UniProt/RNAcentral accession → ZFIN gene |
| `marker`, `marker_relationship` | gene lookup by ZDB ID; clone → gene traversal |
| `publication` | PMID / DOI / `GO_REF:*` → ZFIN publication (see `GoDefaultPublication`) |
| `go_evidence_code` | evidence code validation |
| `zdb_replaced_data` | remaps merged/replaced ZDB IDs in the incoming file |
| `marker_go_term_evidence_annotation_organization` | the `Noctua` organization row that scopes add/remove |

### Writes

| Table | Operation |
|---|---|
| `marker_go_term_evidence` | insert new annotations; delete annotations previously loaded for `Noctua` that are absent from the new file |
| `inference_group_member` | inferred-from entries attached to each annotation |
| `marker_go_term_annotation_extension_group`, `marker_go_term_annotation_extension` | GPAD column 11 annotation extensions |
| `noctua_model` | Noctua model IDs referenced by annotations (created on first sight) |
| `load_file_log` | one row per successful run: URL, filename, size, md5, release date |

### Artifacts

`$TARGETROOT/server_apps/DB_maintenance/gafLoad/Load-GPAD-Noctua_w/` —
`*_summary.txt`, `*_details.txt` (REMOVED / ADDED / UPDATED / ERRORS / EXISTING),
`*_error_summary.txt`, `*_errors.txt`, HTML report, and the archived source GPAD.

---

## Load-GAF-GOA_m

Electronic and external GO annotations for zebrafish from EBI GOA.

- **Entry point:** `ant load-gaf-goa` → `GafLoadJob` (organization `GOA`, parser
  `org.zfin.datatransfer.go.GoaGafParser`)
  — [`server_apps/DB_maintenance/build.xml:85`](../server_apps/DB_maintenance/build.xml)

### External inputs

Three files, concatenated into one before parsing:

| URL | Override | Contents |
|---|---|---|
| `ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/ZEBRAFISH/goa_zebrafish.gaf.gz` | `GOA_GAF_URL1` | main file; UniProt accessions |
| `ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/ZEBRAFISH/goa_zebrafish_isoform.gaf.gz` | `GOA_GAF_URL2` | isoform-specific accessions in column 17 (`UniProtKB:E9QI36-1`) |
| `ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/ZEBRAFISH/goa_zebrafish_rna.gaf.gz` | `GOA_GAF_URL3` | non-coding RNA; RNAcentral `URS*` IDs |

### Reference inputs

Same set as [Load-GPAD-Noctua_w](#load-gpad-noctua_w) minus `eco_go_mapping` (GAF carries GO
evidence codes directly), scoped to the `GOA` organization row. The UniProt → ZFIN gene
mapping through `db_link` is the load's main matching step and the source of most
"Gene not found" errors.

### Writes

Same tables as Load-GPAD-Noctua_w, except `noctua_model`. Removal is scoped to annotations
whose `mrkrgoev_gaf_organization` is `GOA`. Three `load_file_log` rows are written per run,
one per source file.

### Artifacts

`$TARGETROOT/server_apps/DB_maintenance/gafLoad/Load-GAF-GOA_m/` — as above, plus a
before/after "Load impact" section in the HTML report (GOA only) comparing annotation counts
attributed to the organization.

---

## Load-GAF-FP-Inference_m

GO annotations inferred by the GO Consortium's phylogenetic/FP pipeline for ZFIN genes.

- **Entry point:** `ant load-gaf-fpinference` → `GafLoadJob` (organization `FP Inferences`,
  parser `org.zfin.datatransfer.go.FpInferenceGafParser`)
  — [`server_apps/DB_maintenance/build.xml:186`](../server_apps/DB_maintenance/build.xml)

### External inputs

| URL | Override | Notes |
|---|---|---|
| `https://current.geneontology.org/products/upstream_and_raw_data/zfin-prediction.gaf` | none — hard-coded in the ant target | uncompressed GAF; column 1 already carries `ZFIN:ZDB-GENE-*` IDs |

### Reference inputs

As for GOA, but the gene match is direct by ZDB ID rather than through `db_link`, so
`marker` / `zdb_replaced_data` do the bulk of the matching work. `term`, `publication`,
and `go_evidence_code` are still consulted for validation.

### Writes

`marker_go_term_evidence` (+ `inference_group_member`, annotation-extension tables) scoped to
the `FP Inferences` organization; `load_file_log`.

### Artifacts

`$TARGETROOT/server_apps/DB_maintenance/gafLoad/Load-GAF-FP-Inference_m/`.

---

## Load-GPAD-DANRE-mod_m

> **Not on `main`.** Lives on branch `ZFIN-10025-danre-mod-unified-load`. Documented here
> because it is intended to replace the three GO loads above, and because its report-only
> posture is easy to misread as "the load is broken".

The unified GO load. GO Central's GOA-first pipeline publishes a single MOD-ID-keyed GPAD file
that is a merged superset of the sources currently arriving as three separate files. This job
consumes that one file, and at cutover [Load-GAF-GOA_m](#load-gaf-goa_m),
[Load-GPAD-Noctua_w](#load-gpad-noctua_w), and
[Load-GAF-FP-Inference_m](#load-gaf-fp-inference_m) are retired.

- **Entry point:** `ant load-gpad-danre-mod` → `GafLoadJob` (organization `DANRE-mod`, parser
  `org.zfin.datatransfer.go.DanreModGpadParser`, which extends `GpadParser` unchanged — the
  file is GPAD 2.0)
- **Registered in:** `server_apps/jenkins/jobs.production.properties`

### Report-only by default

The ant target sets `<env key="GAF_LOAD_REPORT_ONLY" value="true"/>`. In that mode
`GafLoadJob` computes the full add/update/remove diff and writes every report, but skips
`addAnnotations` / `updateAnnotations` / `removeAnnotations` **and** the `load_file_log` row.
So as shipped **this job writes nothing to the database** — before and after snapshots match
and the diff workbooks are empty. That is deliberate, not a failure. Report-only comes off
only once the diff is trusted.

### External inputs

| URL | Override |
|---|---|
| `https://current.geneontology.org/annotations/gpad/DANRE-mod.gpad.gz` | `DANRE_MOD_GPAD_URL` — e.g. the staging copy at `https://skyhook.geneontology.io/pipeline-from-goa/main/annotations/gpad/DANRE-mod.gpad.gz` |

### Per-source ownership

One file, many sources. Each row's GPAD `assigned_by` column (surfaced as
`GafEntry.getCreatedBy()`) is resolved to the `GafOrganization` that *owns* the resulting
annotation, by `DanreModSourceOrganization`:

| `assigned_by` | Owning organization |
|---|---|
| `ZFIN` | `Noctua` |
| everything else (`UniProt`, `GO_Central`, `InterPro`, `GOC`, `RHEA`, `IntAct`, ~15 more) | `GOA` (the default) |

This matters for **removal**: `generateRemovedEntries` runs once per owning organization, so
each source prunes only the rows attributed to it. Without that partitioning, a single umbrella
organization would mass-delete every other source's annotations on cutover. The mapping
deliberately reproduces legacy ownership so the first real load is a near no-op rather than a
churn of mass add+remove.

The `DANRE-mod` value added to `GafOrganization.OrganizationEnum` is the load's own identity;
it is not used as an ownership target while the per-source mapping is in force.

Two ownership questions are still open (see `workbench/danre-mod-unified-load-plan.md` §7):
`GO_Central` phylo IBA rows currently fall through to `GOA` rather than keeping a distinct
FP-Inference identity, and `GOC` (`GO_REF:0000108`) is net-new content the legacy GOA load
rejected, also currently falling through to `GOA`.

### Reference inputs

As for [Load-GPAD-Noctua_w](#load-gpad-noctua_w), plus:

| Table | Change |
|---|---|
| `eco_go_mapping` | a migration adds `ECO:0007322` → `IEA`. That code ("curator inference used in automatic assertion") is absent from GO's flat `gaf-eco-mapping.txt` but is what the file uses for UniProtKB-SubCell IEAs (~17,350 rows). Without the row those rows fail to map and are rejected, and the per-source removal then wants to delete ~24,283 stored IEA counterparts. |
| `publication` | `GoDefaultPublication` gains `GO_REF:0000002` → `ZDB-PUB-020724-1` (InterPro2GO) and `GO_REF:0000003` → `ZDB-PUB-031118-3` (EC2GO), because the unified load takes the interpro2go/ec2go IEAs straight from the file while the UniProt secondary load drops its `*2go` GO-mapping handlers |

### Writes (once report-only is lifted)

Same tables as [Load-GPAD-Noctua_w](#load-gpad-noctua_w), but partitioned across the `GOA`
and `Noctua` organizations by source rather than scoped to one.

### Artifacts

`$TARGETROOT/server_apps/DB_maintenance/gafLoad/Load-GPAD-DANRE-mod_m/`:

- the standard GAF-load set — `*_summary.txt`, `*_details.txt`, `*_error_summary.txt`,
  `*_errors.txt`, HTML report
- `mgoe_before_<ORG>.csv` / `mgoe_after_<ORG>.csv` for `ORG` in {`GOA`, `Noctua`} — direct
  `marker_go_term_evidence` snapshots taken by shell build steps wrapped around the ant call,
  via `gafLoad/snapshot_mgte.sql`
- `mgoe_dbdiff_<ORG>.xlsx` — one workbook per org (deletes / adds / updated_1 / updated_2
  sheets), produced by `gradle csvDiff` with `CSVDIFF_XLSX_ONLY=true`. Keying on every column
  *except* `zdb_id` means a recycled ZDB ID shows up as an ignored update rather than as a
  spurious add + delete pair.

The point of the per-org snapshots is that the unified load's delta for `GOA` and for `Noctua`
is directly comparable against what the two legacy jobs produce.

---

## NCBI-Gene-Load-Java

Reconciles ZFIN ↔ NCBI Gene cross-references and RefSeq accessions. The largest and most
side-effect-heavy of the loads.

- **Entry point:** `gradle ncbiPort` (Gradle abbreviates to `:ncbiLoadPort`) →
  `org.zfin.datatransfer.ncbi.NCBIDirectPort` — [`console.gradle:238`](../console.gradle)
- **Working directory:** the Jenkins workspace (`WORKING_DIR=$WORKSPACE`), cleared at the
  start of each run.
- **Parameter:** `LOAD_NCBI_ONE_WAY_GENES` (default `true`) — additionally load NCBI genes
  that link to ZFIN without a reciprocal ZFIN → NCBI link, validated by a shared Ensembl ID
  (ZFIN-8517).

### External inputs

| URL | Override | Post-download filter |
|---|---|---|
| `ftp://ftp.ncbi.nlm.nih.gov/refseq/release/RELEASE_NUMBER` | — | determines the catalog file name |
| `ftp://ftp.ncbi.nlm.nih.gov/refseq/release/release-catalog/RefSeq-release<N>.catalog.gz` | `OVERRIDE_REFSEQ_CATALOG` | `grep 'Danio rerio'` |
| `ftp://ftp.ncbi.nlm.nih.gov/gene/DATA/gene2accession.gz` | `OVERRIDE_GENE2ACCESSION` | `grep -E '7955|tax_id'` |
| `ftp://ftp.ncbi.nlm.nih.gov/gene/DATA/GENE_INFO/Non-mammalian_vertebrates/Danio_rerio.gene_info.gz` | — | `grep -E '7955|tax_id'` |
| `ftp://ftp.ncbi.nlm.nih.gov/gene/DATA/ARCHIVE/gene2vega.gz` | — | `grep -E '7955|tax_id'` (`NCBIReleaseFetcher`) |
| `https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi` | `SKIP_EFETCH` / `FORCE_EFETCH` | fetches sequences into `seq.fasta` to compute accession lengths |

Set `SKIP_DOWNLOADS=1` to reuse the cached files already in the working directory; archived
input sets live under `/research/zarchive/load_files/NCBI-gene-load-archive/<date>/`.

It also consumes two CSVs produced by sibling tasks in `$SOURCEROOT`:
`ncbi_matches_through_ensembl.csv` (`NcbiMatchThroughEnsemblTask`) and
`ncbi_gene_symbol_matches.csv` (`NcbiGeneSymbolMatchTask`).

### Reference inputs

| Table | Used for |
|---|---|
| `db_link` | current ZFIN ↔ NCBI/GenBank/RefSeq/Ensembl/Vega accessions — the core comparison set |
| `foreign_db_contains` | resolves the `fdbcont` rows that scope each accession type |
| `marker`, `marker_relationship`, `marker_type_group_member` | gene identity, EST/clone → gene traversal, type filtering |
| `record_attribution` | preserves curator-attributed links from deletion |
| `expression_experiment2` | protects dblinks in use by expression data from deletion |
| `ortholog_external_reference` | ortholog-side NCBI references |
| `external_resource.ncbi_danio_rerio_gene_info_ensembl` | NCBI → Ensembl ID map used by the one-way-gene matching |
| `vocabulary_term` | NCBI annotation-status vocabulary (`Current`, etc.) |
| `assembly` | assembly PK for `marker_assembly` rows |

### Writes

Driven by four SQL files in `server_apps/data_transfer/NCBIGENE/`:

| Table | Operation | Source |
|---|---|---|
| `db_link` | insert new NCBI/RefSeq/GenBank accessions; delete withdrawn ones | `loadNCBIgeneAccs.sql` |
| `record_attribution` | insert attributions for new links; delete for removed links | `loadNCBIgeneAccs.sql` |
| `zdb_active_data` | delete rows for removed dblinks | `loadNCBIgeneAccs.sql`, `temporaryVegaRemove.sql` |
| `reference_protein` | delete rows tied to removed dblinks | `loadNCBIgeneAccs.sql` |
| `marker_annotation_status` | full rebuild (`DELETE` then `INSERT`) — records whether each gene is in the current NCBI annotation release | `loadNCBIgeneAccs.sql` |
| `marker_assembly` | insert/refresh gene ↔ assembly rows | `markerAssemblyUpdate.sql` |
| `sequence_feature_chromosome_location_generated` | insert genomic locations for newly linked genes | `markerAssemblyUpdate.sql` |
| `gff3_ncbi_attribute` | insert attributes for newly linked genes | `markerAssemblyUpdate.sql` |
| `ncbi_gene_load`, `ncbi_gene_delete`, `tmp_pre_ncbi_gene_delete` | staging tables populated from the `.unl` files | `prepareNCBIgeneLoad.sql`, `loadNCBIgeneAccs.sql` |

### Artifacts

Everything in the workspace: `report*`, `log*`, `*.unl` (`toLoad`, `toDelete`, `toMap`,
`toPreserve`, `length`, `noLength`, `notInCurrentReleaseGeneIDs`, `reportNtoAll`),
`*.html`, `*.csv`, `*.xlsx`, `*.json`, `seq.fasta*`, and the downloaded `.gz` sources.

`EARLY_EXIT=1` stops the job before any SQL runs, which is the way to inspect the `.unl`
files for an expected result set without touching the database.

---

## Load-NCBI-GFF3-File

Loads the NCBI RefSeq GFF3 annotation for the GRCz12tu assembly.

- **Entry point:** `gradle loadGff3NcbiFile` → `org.zfin.sequence.gff.NCBIGff3Processor`
  — [`console.gradle:257`](../console.gradle) (runs with `-Xmx16g`)

### External inputs

| URL |
|---|
| `https://ftp.ncbi.nlm.nih.gov/genomes/refseq/vertebrate_other/Danio_rerio/all_assembly_versions/GCF_049306965.1_GRCz12tu/GCF_049306965.1_GRCz12tu_genomic.gff.gz` |

The URL is hard-coded. If `GCF_049306965.1_GRCz12tu_genomic.gff.test` exists in the working
directory the download is skipped and that file is parsed instead.

### Reference inputs

| Table | Used for |
|---|---|
| `db_link` | NCBI Gene ID → ZFIN gene (selects the 1-to-1 ZFIN↔NCBI records to locate) |
| `marker` | gene records for the located features |
| `assembly` | GRCz12tu assembly row |
| `vocabulary_term` | `NCBI_ANNOTATION_STATUS` = `Current` |
| `sequence_feature_chromosome_location_generated` | existing `NCBI_LOADER`-sourced locations, to decide insert vs. update |

### Writes

| Table | Operation |
|---|---|
| `gff3_ncbi` | insert one row per GFF3 feature (batched) |
| `gff3_ncbi_attribute` | insert the retained attribute keys: `gene_id`, `gene_name`, `gene`, `Parent`, `ID`, `Dbxref` |
| `sequence_feature_chromosome_location_generated` | upsert gene genomic locations with source `NCBI_LOADER` |

The processor inserts into `gff3_ncbi` without first truncating it, so the table is expected
to be empty (or the prior release removed) before a run.

### Artifacts

`gff3_ncbi_report.html` / `gff3_ncbi_report.*` in the workspace — a load summary, a feature-type
histogram, and a gene-location summary.

---

## UniProt-Secondary-Term-Load

Loads the "secondary" terms carried on UniProt records — InterPro, EC, PROSITE, Pfam, PDB,
UniProt keywords — and the GO annotations derived from them.

- **Entry point:** `gradle uniprotSecondaryTermLoadTask` →
  `org.zfin.uniprot.task.UniprotSecondaryTermLoadTask` — [`console.gradle:84`](../console.gradle)
- **Parameter:** `UNIPROT_COMMIT_CHANGES` (default `false`). Unchecked = `REPORT` mode, a dry
  run that produces the action list and report but writes nothing. Checked = `REPORT_AND_LOAD`.
- **Prerequisite:** the primary [UniProt-Diff-Load](#uniprot-diff-load) must have run first.
  The task picks the newest `uniprot_release` row with a `processed_date` but no
  `secondary_load_date`.
- **Safety valve:** the run aborts if more than 30,000 actions are generated
  (`ACTION_SIZE_ERROR_THRESHOLD` to override).

### External inputs

| URL | Property |
|---|---|
| `https://current.geneontology.org/ontology/external2go/uniprotkb_kw2go` | `UNIPROT_KW2GO_FILE_URL` |
| `https://current.geneontology.org/ontology/external2go/interpro2go` | `UNIPROT_IP2GO_FILE_URL` |
| `https://current.geneontology.org/ontology/external2go/ec2go` | `UNIPROT_EC2GO_FILE_URL` |
| `https://ftp.ebi.ac.uk/pub/databases/interpro/current_release/entry.list` | hard-coded; InterPro domain names/types |

The UniProt records themselves come from the already-downloaded release file on disk
(`UNIPROT_RELEASE_ARCHIVE_DIR=/research/zarchive/load_files/UniProt-archive/`), not from a
fresh download. Each `*2go` file can be supplied locally via `IP2GO_FILE` / `EC2GO_FILE` /
`UP2GO_FILE` / `DOMAIN_FILE`.

### Reference inputs

`SecondaryLoadContext.createFromDBConnection()` snapshots the current state before computing
actions (and can be replayed from a saved context JSON via `CONTEXT_INPUT_FILE`):

| Table | Used for |
|---|---|
| `db_link` | existing InterPro / EC / Pfam / PROSITE / UniProtKB accessions per gene |
| `marker_go_term_evidence` | existing GO annotations attributable to InterPro/EC/keyword mappings, so only diffs are applied |
| `interpro_protein`, `protein`, `protein_to_interpro`, `protein_to_pdb`, `marker_to_protein` | existing protein-side state |
| `term` | GO terms named by the `*2go` mapping files |
| `publication` | the InterPro2GO / EC2GO / UniProtKB-KW2GO mapping publications (`GoDefaultPublication`) |
| `uniprot_release` | selects which release file to load and records completion |

### Writes

| Table | Operation |
|---|---|
| `db_link` | add/remove INTERPRO, EC, PFAM, PROSITE accessions for genes |
| `record_attribution` | attributions for the added dblinks |
| `marker_go_term_evidence` | add/remove GO annotations derived from InterPro, EC, and UniProt keyword mappings |
| `inference_group_member` | inferred-from entries for those annotations |
| `interpro_protein` | insert/delete InterPro domain records (ID, name, type) |
| `protein` | insert new proteins; update `up_length` |
| `protein_to_interpro` | insert/delete protein ↔ InterPro domain pairs |
| `protein_to_pdb` | insert/delete protein ↔ PDB structure pairs |
| `marker_to_protein` | insert/delete gene ↔ protein pairs |
| `reference_protein` | delete rows tied to removed dblinks |
| `uniprot_release` | sets `secondary_load_date` |

### Artifacts

Written to the workspace and archived wholesale (`<artifacts>*</artifacts>`):
`uniprot_secondary_load_report_<timestamp>.json.zip` (the full action list),
`…report.html.zip`, and `…context.json.zip` (the pre-load DB snapshot, which lets a run be
reproduced offline).

---

## UniProt-Diff-Load

The primary UniProt load: reconciles ZFIN gene ↔ UniProtKB accession links against a new
UniProt release.

> Naming note: there is no job called `UniProt-Secondary-Diff-Load`. The two UniProt diff
> loads are `UniProt-Diff-Load` (this one, manual, run when a new release lands) and
> `Uniprot-Monthly-Diff-Load_m`, which re-runs both the primary and secondary loads against
> the latest *already-processed* release to re-integrate ZFIN-side changes. The monthly job
> goes UNSTABLE and skips if an unprocessed release is pending.

- **Entry point:** `gradle uniprotLoadTask` → `org.zfin.uniprot.task.UniProtLoadTask`
  — [`console.gradle:78`](../console.gradle)
- **Parameter:** `UNIPROT_COMMIT_CHANGES` (default `false`) — dry run unless checked.
- **Upstream job:** `UniProt-Release-Check_d` downloads new releases and inserts the
  `uniprot_release` row. `Check-Recent-UniProt-Releases_d` handles notification.

### External inputs

This job reads the release file already on disk. The download happens in
`UniProt-Release-Check_d` (`gradle uniprotReleaseCheckTask`), which fetches, filters to
zebrafish, and archives:

| Property | URL |
|---|---|
| `UNIPROT_SPROT_FILE_URL` | `https://rest.uniprot.org/uniprotkb/stream?compressed=true&format=txt&query=((organism_id:7955) AND (reviewed:true))` |
| `UNIPROT_TREMBL_FILE_URL` | `https://rest.uniprot.org/uniprotkb/stream?compressed=true&format=txt&query=((organism_id:7955) AND (reviewed:false))` |
| `UNIPROT_SPROT_FILE_URL_ALT1..3` | `ftp.expasy.org` / `ftp.uniprot.org` `uniprot_sprot_vertebrates.dat.gz` fallbacks |
| `UNIPROT_TREMBL_FILE_URL_ALT1..3` | same, `uniprot_trembl_vertebrates.dat.gz` |
| `UNIPROT_URL_FOR_RELEASE_DATE` | `ftp.uniprot.org/…/uniprot_trembl_vertebrates.dat.gz` — timestamp probe only |

Archive directory: `UNIPROT_RELEASE_ARCHIVE_DIR=/research/zarchive/load_files/UniProt-archive/`.

At run time the load also calls NCBI E-utilities
(`https://eutils.ncbi.nlm.nih.gov/entrez/eutils/`, via `NCBIRefSeqFetch`) to check whether
UniProt accessions we are about to lose correspond to obsoleted RefSeq records. Those
responses are cached to `NCBI_FETCH_CACHE_OUTPUT_FILE` and can be replayed from
`NCBI_FETCH_CACHE_INPUT_FILE`.

### Reference inputs

| Table | Used for |
|---|---|
| `db_link` | current UniProtKB and RefSeq accessions per gene; RefSeq is the matching key for new UniProt accessions (`MatchOnRefSeqHandler`) |
| `marker` | gene lookup by ZDB ID |
| `record_attribution` | distinguishes curator-attributed links from load-attributed ones |
| `publication` | `AUTOMATED_CURATION_OF_UNIPROT_DATABASE_LINKS` — the attribution pub for every link this load adds |
| `uniprot_release` | selects the release file; guards against loading an older release over a newer one |

### Writes

| Table | Operation |
|---|---|
| `db_link` | insert UniProtKB links for genes matched via shared RefSeq accessions; delete links whose accessions disappeared from UniProt |
| `record_attribution` | insert attributions for new and newly-supported links |
| `uniprot_release` | sets `processed_date` |

### Artifacts

`uniprot_load_report_<timestamp>.html.zip`, `uniprot_context_<timestamp>.json.zip`,
`ncbi_refseq_api_results.json`.

---

## The rest of the load jobs

Covered at inventory depth: entry point, external source, and the tables each job writes.
Where a job's matching logic is non-obvious it is called out; otherwise assume the usual
`db_link` / `marker` / `publication` lookups.

### Ontology loads

Thirteen jobs, one pipeline. Each is an ant target in
`server_apps/data_transfer/LoadOntology/build.xml` that downloads an OBO file, runs it
through `parseObo.pl`, and applies the same SQL sequence (`loadTerms.sql`,
`handleSynonyms.sql`, `handleRelationships.sql`, `handleSecondaryTerms.sql`,
`loadDBxrefs.sql`, `loadSubsets.sql`, `obsoleteMerge<Ontology>.sql`,
`fixAnnotationsUponOntologyLoad.sql`). They differ only in the source URL and the
obsolete-merge script.

| Job | Ant target | OBO source |
|---|---|---|
| Load-Gene-Ontology_d | `load-gene` | `http://purl.obolibrary.org/obo/go/snapshot/go.obo` |
| Load-Chebi-Ontology_m | `load-chebi` | `ftp://ftp.ebi.ac.uk/pub/databases/chebi/ontology/chebi.obo` |
| Load-Disease-Ontology_d | `load-disease` | `http://purl.obolibrary.org/obo/doid.obo` |
| Load-Eco-Ontology_d | `load-eco` | `https://raw.githubusercontent.com/evidenceontology/evidenceontology/master/eco.obo` |
| Load-SO-Ontology_d | `load-so` | `https://raw.githubusercontent.com/The-Sequence-Ontology/SO-Ontologies/master/Ontology_Files/so.obo` |
| Load-PATO-Ontology_d | `load-quality` | `http://purl.obolibrary.org/obo/pato.obo` |
| Load-Spatial-Ontology_d | `load-spatial` | `http://purl.obolibrary.org/obo/bspo.obo` |
| Load-Cell-Ontology | `load-cell` | `http://purl.obolibrary.org/obo/cl.obo` |
| Load-Mouse-Pathology-Ontology_d | `load-mpath` | `https://raw.githubusercontent.com/PaulNSchofield/mpath/master/mpath.obo` |
| Load-Anatomy-Ontology | `load-anatomy` | `https://raw.githubusercontent.com/ZFIN/zebrafish-anatomical-ontology/refs/heads/master/src/ontology/zfa-edit.obo` |
| Load-Zeco-Ontology | `load-zeco` | `/research/zusers/informix/Curation/zeco.obo` (curator-maintained, local) |
| Load-Zeco-Taxonomy-Ontology | `load-zeco-taxonomy` | `file:///research/zusers/informix/Curation/zeco-taxonomy.obo`, built first by `gradle prepareZecoTaxonomyObo` |
| Load-Measurements-And-Methods-Ontology_d | `load-mmo` | `file:///research/zusers/informix/Curation/mmo.obo` |

Several loads pull a second local curator file: `zfin-ro.obo` (relations), `obi.obo`,
`zfs.obo` (stages, for the anatomy load), `basic.obo` from Uberon.

**Writes** (all ontology loads):

| Table | Operation |
|---|---|
| `term` | insert new terms; update names/definitions/comments; flip `term_is_obsolete` |
| `term_relationship`, `term_relationship_type` | rebuild the relationship graph |
| `all_term_contains` | closure/containment rows |
| `data_alias`, `alias_scope`, `alias_group` | synonyms |
| `term_subset`, `ontology_subset` | subset membership (this is what feeds the GO loads' `gocheck_do_not_annotate` check) |
| `term_xref`, `db_link`, `external_reference` | dbxrefs on terms |
| `term_stage` | stage ranges (anatomy) |
| `obsolete_term_replacement`, `obsolete_term_suggestion`, `zdb_replaced_data` | obsoletion and merge handling |
| `ontology` | ontology header/version row |
| `marker_go_term_evidence`, `phenotype_statement`, `expression_result2`, `apato_infrastructure` | repointed by `fixAnnotationsUponOntologyLoad.sql` when a term is merged into another |
| `zdb_active_data` | new term IDs registered, obsoleted/merged IDs removed |

The annotation-repointing step is the reason an ontology load can change data far outside the
`term*` tables: merging a term rewrites every annotation that used the old ID.

### MeSH

| Job | Entry point | External source | Writes |
|---|---|---|---|
| Load-MeSH-Terms_m | `data_transfer/PUBMED/loadMeshTerms.groovy` | NLM MeSH XML (descriptor + qualifier records) | full rebuild of `mesh_term` (delete-then-insert, with before/after `.unl` snapshots) |
| Load-Mesh-Chebi-Mapping | `gradle runMeshChebiGenerator` → `org.zfin.datatransfer.ctd.LoadCtdData` | see Load-CTD-Data_m below | `term_external_reference` MESH/CAS mappings |

### Sequence and cross-reference loads

| Job | Entry point | External source | Writes |
|---|---|---|---|
| Load-Reference-Proteome | ant `load-reference-proteome` → `LoadReferenceProteome` | `https://ftp.uniprot.org/pub/databases/uniprot/current_release/knowledgebase/reference_proteomes/Eukaryota/UP000000437/` | rebuilds the UniProt reference-proteome flag set (`deleteUnitProtProteome()` then re-mark); matches accessions via `db_link` against the UniProtKB polypeptide reference DB |
| Load-RNACentralLinks_m | `data_transfer/RNACentral/LoadRNACentralIDs.groovy` | `ftp://ftp.ebi.ac.uk/pub/databases/RNAcentral/current_release/id_mapping/database_mappings/zfin.tsv` | `db_link` (RNA Central), `zdb_active_data`, `record_attribution`. Filters to IDs present in `transcript`; skips `URS*` accessions already linked |
| Load-NCBIStartEndPositions | `data_transfer/NCBIStartEnd/NCBIStartEnd.pl` | `ftp://ftp.ncbi.nlm.nih.gov/genomes/refseq/vertebrate_other/Danio_rerio/all_assembly_versions/GCF_000002035.6_GRCz11/GCF_000002035.6_GRCz11_feature_table.txt.gz` | `sequence_feature_chromosome_location_generated` (delete + insert). GRCz11 — the GRCz12tu equivalent is [Load-NCBI-GFF3-File](#load-ncbi-gff3-file) |
| Load-Missing-Uniprot-IDs | ant `load-missing-uniprots` → `LoadMissingUnitProt`, driving `load-missing-uniprot-records.sql` | none — works from Ensembl/UniProt data already in the DB | `db_link`, `record_attribution`, `zdb_active_data`, `ensembl_gene`, `ensembl_gene_raw`, `ensembl_gene_with_zdb`, `double_gene_uniprot` |
| Load-Flank-Seq_w | ant `load-flank-seq` → `FlankSeqLoadJob` / `FlankSeqProcessor` | local genome FASTA under `/opt/zfin/gff3/` (`GenomicLocationService.FASTA_URL_BASE_DIR`) | updates flanking sequences on `feature_genomic_mutation_detail`. Full re-scan of non-`sa` features runs on Tuesdays, or with `FORCE_FULL_UPDATE` |
| GenBank-Accession-Update_d | `data_transfer/Genbank/gbaccession.pl` | `ftp://ftp.ncbi.nlm.nih.gov/genbank/daily-nc/` daily update files | `accession_bank` (insert/update), `db_link` (update) |
| Update-AccessionBank-DbLink_d | ant `run-data-report` | none | synchronizes `accession_bank` and `db_link`; reports the accessions touched |
| Update-Length-For-Ensembl-Transcript | `gradle runEnsemblTranscriptLengthUpdater` → `EnsemblTranscriptUpdateLengthTask` | Ensembl (`www.ensembl.org/Danio_rerio/…`) | transcript length on `db_link`; report `ensembl-transcript-load-report.html` |
| Update-Transcript_Sequences_w | `data_transfer/RNACentral/runTscriptSequenceLoad.pl` | RNAcentral / Ensembl transcript sequences | transcript sequence storage |
| Fetch-NCBI-Replaced-Gene-IDs_m | `gradle fetchNcbiReplacedGeneIDs` → `NCBIReplacedGeneIDsTask` | NCBI E-utilities | writes dead-ID and mapped-ID files consumed by the NCBI gene load; no direct table writes |
| Dump-RNACentral-File_w | ant `create-transcript-info-file` | none | export only — produces the transcript info file ZFIN publishes to RNAcentral |

### Comparative and external-resource loads

| Job | Entry point | External source | Writes |
|---|---|---|---|
| Load-Panther_m | `data_transfer/Panther/LoadPanther.groovy` | `ftp://ftp.pantherdb.org/sequence_classifications/12.0/PANTHER_Sequence_Classification_files/PTHR12.0_zebrafish` | full replace of the PANTHER `db_link` set (delete all for the fdbcont, then insert), `zdb_active_data`, `record_attribution`. Filters to `marker` rows of type `GENE` |
| Load-AllianceLinks_m | `data_transfer/Panther/LoadAGR.groovy` | none — derives links from local `marker` / `term` | `db_link` for "AGR Gene" and "AGR Disease", `zdb_active_data`, `record_attribution` |
| Load-AllianceGeneDesc_m | `data_transfer/AllianceGeneDesc/LoadAllianceGeneDesc.groovy` | `https://fms.alliancegenome.org/api/datafile/by/GENE-DESCRIPTION-JSON/ZFIN?latest=true` → `https://download.alliancegenome.org/<s3Path>` | `gene_description` (insert new, update existing). Filters to IDs present in `marker` |
| Load-CTD-Data_m | `gradle runMeshChebiGenerator` → `org.zfin.datatransfer.ctd.LoadCtdData` | `https://ctdbase.org/query.go?…taxon=TAXON%3A7955&reviewStatus=curated` and `https://ctdbase.org/reports/<file>.gz` | `term_external_reference` (CAS/MESH), curator notes attributed to `ZDB-PERS-030612-1`; matches publications by PMID |
| Load-Addgene_w | `data_transfer/Addgene/LoadAddgene.groovy` | `https://api.developers.addgene.org/download/plasmids/` | `db_link` (Addgene, `ZDB-FDBCONT-141007-1`), `zdb_active_data`. Matches the plasmid's ZFIN gene reference against existing `db_link` rows on `ZDB-FDBCONT-040412-1` |
| Load-Signafish_w | ant `load-signafish` → `LoadSignafishJob` | `https://signalink.org/zfin_ids.lst` | replaces the Signafish `db_link` set — deletes links not in the incoming list, adds the rest. Resolves incoming IDs through `getMarkersByZdbIDsIncludingReplaced`, so merged genes survive |
| Pull-FPBase-Proteins_m | `gradle importMissingFPBaseProteinsTask` | `https://www.fpbase.org/api/proteins/?format=json` | fluorescent-protein records missing from ZFIN |
| OMIM-Update_w | `data_transfer/OMIM/OMIM.pl` + `loadOMIM.sql` | `http://omim.org/static/omim/data/mim2gene.txt`, `https://data.omim.org/downloads/<token>/genemap2.txt` (token-gated) | `omim_phenotype` (rebuild), `human_gene_detail` |
| Update-Orthology_w | `gradle --no-daemon updateOrthologyReport` → `OrthoUpdateReportJob`, which shells out to the `data_transfer/ORTHO` perl pipeline | `ftp://ftp.ncbi.nlm.nih.gov/gene/DATA/GENE_INFO/Mammalia/Homo_sapiens.gene_info.gz`, `…/Mus_musculus.gene_info.gz`, `…/Invertebrates/Drosophila_melanogaster.gene_info.gz` | `ncbi_ortholog`, `ncbi_ortholog_alias`, `ncbi_ortholog_external_reference`, `ortholog_external_reference`, `ortholog`, `ortholog_load_tracking` |
| Update-Gene-Name-According-To-Orthologue-Name | `data_transfer/ORTHO/updateZebrafishGeneNames.pl` | none — uses loaded ortholog data | `marker` (name/abbreviation), `marker_history`, `updates`, `zdb_active_data` |
| Microarray-Update_w | ant `microarray-update` → `MicroarrayWebserviceJob` | NCBI GEO via E-utilities (`NCBIEfetch.getMicroarraySequences()`) | adds/removes publication attributions on the microarray pub. Matches GEO gene symbols against `marker` abbreviations and GEO accessions against `db_link` |
| Zfishbook-Data-Load | `data_transfer/zfishbook/zfishbook.sh` + `loadZfishbookData.sql` | zfishbook data file | `feature`, `feature_assay`, `feature_marker_relationship`, `genotype`, `fish`, `data_alias`, `db_link` |

### Publication pipeline

| Job | Entry point | External source | Writes |
|---|---|---|---|
| Fetch-Pubs-From-Pubmed_d | `data_transfer/PUBMED/fetchPubsFromPubMed.groovy` | NCBI E-utilities (PubMed) | new `publication` records and abstracts |
| Fetch-Pubs-From-Pubmed-By-Accession | same script, PMIDs passed as a build parameter | same | same |
| Load-Complete-Author-Names_d | ant `load-author-names` → `LoadCompleteAuthorNames` | NCBI E-utilities (`retrieveAuthorInfo`) | `pubmed_publication_author` — fills in full author names for pubs that lack them |
| Update-DOIs_d | ant `update-dois` → `UpdateDOIJob` / `DOIProcessor` | Europe PMC / Citexplore web service | `publication.doi` for pubs missing one; capped at 10 attempts per run |
| Update-PMC-Ids_d | `data_transfer/PUBMED/addPMCidsToAllPubs.groovy` | PMC ID converter | PMC accessions on publications |
| Update-Pub-Date_d | `data_transfer/PUBMED/updatePublicationDate.groovy` | PubMed | `publication.pub_date`, with an audit row in `updates` |
| Update-Pub-GEO-IDs_w | `data_transfer/PUBMED/addGeoIdsToAllPubs.groovy` | `https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=gds…` + `esummary.fcgi` | `pub_db_xref` (GEO accessions) |
| Update-Pub-MeSH-Terms_m | `data_transfer/PUBMED/addMeshTermsToAllPubs.groovy` | PubMed MeSH headings | `mesh_heading`, `mesh_heading_qualifier` |
| Update-Pub-Status_w | `data_transfer/PUBMED/pubActivation.groovy` | PubMed | `pub_tracking_history`, `updates` — moves pubs through curation status |
| Update-RRIDs_d | `data_transfer/RRID/updateRRIDs.groovy` | RRID resolver | `db_link` (RRID), `zdb_active_data` |

### Stock-centre pulls

All three run the shared `pullFromResourceCenter.pl` machinery, wget-ing a set of tab files
from the centre and syncing supplier links.

| Job | Source |
|---|---|
| ZIRC-Resource-Center-Pull_d | `http://zebrafish.org/zirc/zfin/<file>` |
| EZRC-Resource-Center-Pull_d | `https://www.ezrc.kit.edu/downloads/<file>` |
| CZRC-Resource-Center-Pull_d | `http://www.zfish.cn/<file>` |

**Writes:** `int_data_supplier` (rebuilt from the incoming file via
`syncFishOrderThisLinks.sql`), plus the genotype/feature ordering links.

| Job | Entry point | Source | Writes |
|---|---|---|---|
| Load-ZircPdfs_m | `data_transfer/ResourceCenters/LoadZircPdfs.groovy` | `http://zebrafish.org/zfin/protocol.txt` | `db_link` (ZIRC Protocol), `zdb_active_data`. Filters to IDs present in `feature` |

### Housekeeping

| Job | Entry point | Writes |
|---|---|---|
| Load-External-Notes | `DB_maintenance/loadExternalNotes.pl` + `.sql` | `external_note`, `zdb_active_data` |
| Update-Features-Unspecified_d | ant `run-data-report` | keeps feature name/abbreviation in sync with the associated gene |
| Update-Remove-Orphans_d | `DB_maintenance/orphanChecks.pl` | removes orphaned rows left behind by deletes |
| Update-GAF-Record-Time | `data_transfer/GO/updateMrkrGoEvdTime.pl` | timestamps on `marker_go_term_evidence` |
| Load-Database / Unload-Database_d | shell wrappers around the DB dump/restore scripts | whole-database restore / unload; not a data load in the sense used here |

### Gaps in this pass

These were inventoried from their entry points and SQL, not traced end to end. Treat the
table lists as "what the SQL touches", not as a verified account of the load's semantics:

- `Update-Transcript_Sequences_w`, `Dump-RNACentral-File_w`, `Fetch-Pubs-From-Pubmed_d`,
  `Update-PMC-Ids_d` — external endpoint identified only by the calling module, not by a
  literal URL in the source.
- The three stock-centre pulls share so much machinery that per-centre differences in which
  files are fetched were not enumerated.
- `Load-Database` / `Unload-Database_d` are infrastructure, not data loads; listed only so
  the inventory is complete.

## See also

- [load-gaf-goa.md](load-gaf-goa.md) — deeper walkthrough of the monthly GOA load
- [build-and-docker.md](build-and-docker.md) — how these jobs are built and deployed
- `server_apps/DB_maintenance/build.xml` — ant targets for the DB_maintenance jobs
- `console.gradle` — `JavaExec` task definitions for the gradle-driven jobs
