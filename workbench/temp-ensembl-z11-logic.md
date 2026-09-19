# Ensembl GRCz11 load logic and ZFIN ID matching

Scope: `server_apps/data_transfer/Ensembl/` (plus the Java class its Ant target invokes).

## 1. Where GRCz11 is anchored

GRCz11 is not a parameter — it is hard-coded in three places. Everything else
takes "whatever Ensembl currently serves": the fetch scripts `curl`
`ftp.ensembl.org/pub/current_mysql/` and `sed` out the newest
`danio_rerio_core_*` release name.

| Location | What it pins |
|---|---|
| `update_ensembl_release_version.sql:6` | Renames `foreign_db` row `fdb_db_pk_id = 7` to `Ensembl(GRCz11)` and repoints `foreign_db_contains` row `ZDB-FDBCONT-061018-1` (Ensembl gene container) at it. Manual, run once per assembly change. |
| `fetch_ensembl_agp.sh:8` | `AGP="GRCz11.agp"` — the clone-tiling / AGP→GFF3 path. |
| `updateSequenceFeatureChromosomeLocation.sql:260-268` (§L) | Relabels leftover `sfclg_assembly = 'GRCv10'` rows to `'GRCz11'` for the Zfin and Ensembl loaders. |

The `readme` warns that other SQL hard-codes assembly strings in the
`fdb_db_name = 'Ensembl(zV7)'` style, so an assembly bump has to be grepped for
across the tree.

## 2. Four independent ID-matching strategies

### 2a. Gene ↔ ZDB-GENE, via Ensembl's own ZFIN xref

The match is **not computed at ZFIN** — it is Ensembl's assertion, filtered
down to strictly 1:1 pairs. Two variants of the fetch exist for the same job:

- **BioMart** — `fetchEnsdarg.groovy` (current). Posts a `drerio_gene_ensembl`
  query filtered to chromosomes 1–25 + MT with `with_zfin_id`, pulling
  `ensembl_gene_id` + `zfin_id_id`, then drops any ZDB ID claimed by more than
  one ENSDARG (`zdbList.count {it == zdbId} == 1`) and emits `zdb,ensdarg` CSV.
- **Direct MySQL** — `fetch_ensdarG.mysql`. `gene join xref on display_xref_id`,
  `external_db_id in (2510,2530)`, `group by dbprimary_acc having count(*) = 1`
  — the same 1:1 constraint expressed in SQL.

`load_ensdarG.sql` does the reconciliation, and this is where most of the real
logic lives:

1. Rewrites obsolete ZDB IDs through `zdb_replaced_data` (old → new) so gene
   merges don't break links.
2. Drops anything whose ZDB ID is absent from `zdb_active_data`, unloading the
   casualties to `zdb_ids_not_in_zdb_active_data.txt`.
3. Three report-only QC unloads: `zdb_ens_ids_not_unique.txt`,
   `ensdargs_on_more_than_one_gene.txt`, `zdb_id_exists_more_than_once.txt` —
   then deletes ZDB IDs appearing more than once.
4. Deletes existing `ENSDARG%` db_links under `ZDB-FDBCONT-061018-1` that the
   new file no longer supports — **except** those attributed to a hard-coded
   list of Sanger load pubs (`ZDB-PUB-120207-1`, `ZDB-PUB-130213-1`, …
   `ZDB-PUB-190221-12`). Those are protected and reported to
   `changedSangerEnsdargs.txt` instead.
5. Inserts survivors as new `db_link` rows with `dblink_info = 'uncurrated <now>'`,
   attributed to the fake Ensembl pub `ZDB-PUB-061101-1`.

### 2b. Transcript ↔ ZFIN, indirectly through the Vega/OTT accession

`fetch_ensdarT_dbacc.mysql` pulls `transcript.stable_id` + xref `dbprimary_acc`
where `external_db_id = 2510` and `dbprimary_acc = xref.display_label`. **No
ZFIN ID appears in that data at all.**

`load_ensdarT_dbacc.sql` joins the fetched `ensacc_dbacc` against an *existing*
ZFIN db_link's `dblink_acc_num` and inherits its `dblink_linked_recid` as the
transcript's ZFIN owner. It also:

- detects "moved" ENSDARTs (same ZFIN record, different ENSDART) →
  `dblinks_deleted_because_ensdart_moved.txt`;
- deletes stale links under `ZDB-FDBCONT-110301-1`;
- exempts anything attributed to `ZDB-PUB-190221-12` from deletion.

Sibling mapping tables load the same way:

| Table | Fetch → load |
|---|---|
| `ensdarg_ottdarg_mapping` | `fetch_ensdargOttdargTable.*` (OTTG xrefs) → `loadEnsdargOttdarg.sql` |
| `ensdarg_ensdarp_mapping` | `fetch_ensdarpInfo.mysql` (gene/transcript/translation + protein length) → `loadEnsdarPMapping.sql` |
| `ensdar_mapping` | BioMart gene/transcript/clone triples (`mart_exportName1.txt`) → `loadFromBioMart.sql` |

### 2c. GFF3 → chromosome locations

`fetchEnsemblGff3.groovy`:

- FTPs `Danio_rerio.*.chr.gff3.gz` from `pub/current_gff3/danio_rerio/`,
  parsing build and version out of the filename;
- prefixes every source with `Ensembl_`, strips `gene:` / `transcript:` off
  `ID` and `Parent`;
- keeps only sources `ensembl`, `ensembl_havana`, `havana`, `RefSeq`;
- wipes `gff3 where substring(gff_source from 1 for 8) = 'Ensembl_'` and bulk
  `copy`s the rest back in;
- then runs `updateSequenceFeatureChromosomeLocation.sql`.

`updateSequenceFeatureChromosomeLocation.sql` turns `gff3` into locations:

- **§B** keys on `gff_id like 'ENSDART%'` joined to
  `ensdart_name_mapping.enm_ensdart_stable_id` to get the gene, takes
  min(start)/max(end) per transcript, rolls up to gene level (`tmp_gene`).
- **§D** inserts `EnsemblStartEndLoader` rows by joining `tmp_gene.accnum1`
  back to `db_link.dblink_acc_num` — **that db_link join is the ZFIN-ID match,
  and it is exactly the link created by 2a.**
- **§C/§F/§G/§I/§J** handle UCSC, ZfinGbrowse, DirectSubmission, BurgessLin Zv9,
  and knockdown-reagent rows.
- **§K** drops chromosomes `AB` / `U` / `0`; **§L** stamps GRCz11.

Per the file's own header, this script is the canonical writer for UCSC,
Ensembl, Zfin and DirectSubmission rows; a warehouse script
(`server_apps/DB_maintenance/warehouse/chromosomeMartPostgres/updateSequenceFeatureChromosomeLocationPostgres.sql`)
runs downstream and adds an ENSDARG-fallback path (§E) for genes with no
ENSDART, plus an extra ZMP pub tag.

### 2d. UniProt back-fill — the only piece actually automated today

The `readme` is explicit that `fetch_ensdarg.sh` is the *old manual* workflow.
The automated job is Jenkins `Load-Missing-Uniprot-IDs` → `build.xml` target
`load-missing-uniprots` → `org.zfin.datatransfer.LoadMissingUnitProt`.

`LoadMissingUnitProt` reads `mart_export.txt` (Gene stable ID / Swiss-Prot /
TrEMBL, tab-delimited; one row fans into up to two mappings) plus
`uniprot-all.txt`, and hands the `.unl` data to
`load-missing-uniprot-records.sql` via `DatabaseService.runDbScriptFile`.

That SQL matches ENSDARG → ZFIN purely by
`db_link.dblink_acc_num = ensembl_id`, and is aggressively conservative — it
deletes rows where:

- the ENSDARG is not in ZFIN;
- the gene already carries that UniProt;
- the UniProt ID exists anywhere under `ZDB-FDBCONT-040412-47`;
- one UniProt maps to multiple genes;
- one ENSDARG maps to multiple ZFIN genes.

Survivors become db_links attributed to `ZDB-PUB-170502-16`, and the new links
are unloaded to `new_uniprot_ids` for the report
(`report.properties` supplies the header columns and message).

## 3. AGP / clone track (GRCz11.agp)

`fetch_ensembl_agp.sh` unloads ZFIN BAC/PAC/FOSMID clones via
`unload_zfin_DNA_clone.sql` (GenBank container `ZDB-FDBCONT-040412-36`) into
`zfin_DNA_clone.txt`, then `agp_to_gff3.awk` streams the AGP and matches by
**accession, version-stripped** (`$6` chopped at the first `.`) against that
file. On a hit it emits `Name=`, `zdb_id=` and `Alias=` attributes and
recomputes start/end from the clone length and strand; on a miss it falls back
to the bare accession as `Name`. Gap rows (`$5 == "N"`) are skipped.

## 4. Cross-cutting patterns

- **1:1 or nothing.** Every path discards ambiguous mappings rather than
  picking one, and unloads the discards to a `.txt` for curators.
- **Curated data wins.** Deletions are guarded by `record_attribution` checks
  against specific pub IDs; machine-made links are marked
  `uncurrated` / `uncurated`.
- **Hard-coded IDs everywhere:**

  | ID | Meaning |
  |---|---|
  | `ZDB-FDBCONT-061018-1` | Ensembl gene container |
  | `ZDB-FDBCONT-110301-1` | Ensembl transcript container |
  | `ZDB-FDBCONT-040412-47` | UniProt container |
  | `ZDB-FDBCONT-040412-36` | GenBank clone container |
  | `ZDB-PUB-061101-1` | fake Ensembl attribution pub |
  | `ZDB-PUB-170502-16` | missing-UniProt load pub |
  | `ZDB-PUB-190221-12`, Sanger pub list | deletion-exempt attributions |
  | `external_db_id` 2510 / 2530 | Ensembl xref sources for ZFIN IDs |
  | `fdb_db_pk_id = 7` | the `Ensembl(GRCz11)` foreign_db row |

## 5. Caveats

- The shell scripts are largely stale and predate the Docker setup:
  `#!/bin/tcsh`, `/local/bin/mysql`, `/local/bin/curl`, `/opt/zfin/bin/reline`,
  `/usr/bin/nawk`, `$ROOT_PATH`, `/research/zprodmore/gff3`.
- `fetch_ensdarg.sh` has an unmatched `endif` at the end — tcsh would error out,
  so it would not run as-is.
- `pullFromBioMart.pl` references `$dbname` / `$username` / `$password` that are
  never assigned, and its `downloadFiles()` sub is never called.
