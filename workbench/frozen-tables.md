# Frozen tables: candidates for a cleanup ticket

_First pass, 2026-10-22. Temporary branch note: move into the ticket, then delete._

Out of the year of daily snapshots (2025.10.14 to 2026.10.21, `/research/zunloads/table_churn/dazed`),
**234 tables** (not counting `_old_`, `_bkup` or `_temp` leftovers) kept exactly the same content
on every snapshot. Example: `publication_correspondence`. Release 1083 (`db/load/1083/beerMug/migrateData.sql`)
migrated its 651 rows into the `pub_correspondence_*` tables, and nothing has written to it since.

For each table, I checked whether its name appears in any of the 5,928 code files under
`source/`, `server_apps/`, `lib/`, `home/` and `conf/`. Whole-word, case-insensitive grep.
"Migrations-only" means it appears only under `db/load`, `db/postGmakePostloaddb`, `db/examples` or
in Liquibase changelogs and migrations.

| Class | Tables | Meaning |
|---|---|---|
| none | 26 | not mentioned anywhere in the code |
| migrations-only | 54 | only in old migration or load scripts |
| live | 154 | still mentioned by app code, jobs or scripts (lookup tables, archives, ...) |

**Caveats before dropping anything:**
- **Implicit users.** `revinfo` and `post_aud` belong to Hibernate Envers auditing. `databasechangeloglock`
  belongs to Liquibase. These have no textual reference because frameworks use them implicitly.
- **Stable is not dead.** Small lookup tables (`state_code`, `source_url_purpose`, `vector_type`, ...)
  can be static and still in use, for example through a FK or a view.
- **Things outside this repo.** Check views, functions, triggers and FKs in the DB (`pg_depend`).
  Also check other repos and ad-hoc reports.
- **Schemas.** `thisse.*` holds a set of one-off import tables from 2017.

## Not mentioned anywhere in the code (26)

| Table | Rows |
|---|---|
| `public.pheno_term_old` | 75042 |
| `public.affected_gene_group_member` | 41682 |
| `public.all_anatomy_contains` | 34597 |
| `public.environment_group` | 29250 |
| `public.fish_genox` | 29250 |
| `public.xpat_figure_group_member` | 27885 |
| `public.xpat_figure_group` | 15859 |
| `public.genotype_group` | 13773 |
| `public.bl_plate_tracking` | 2712 |
| `public.linkages_copy` | 454 |
| `public.editconstruct` | 202 |
| `public.state_code` | 59 |
| `public.sangerfeatures` | 54 |
| `public.missing_xpatfig` | 33 |
| `public.remove_phenos_ids` | 27 |
| `public.geneprefix_institution` | 10 |
| `public.expression_phenotype_term_controlled_vocab` | 8 |
| `public.source_url_purpose` | 2 |
| `public.databasechangeloglock` | 1 |
| `public.author_publication` | 0 |
| `public.genotype_group_member` | 0 |
| `public.genox_fish_annotation_search` | 0 |
| `public.genox_group` | 0 |
| `public.genox_group_member` | 0 |
| `public.person_author` | 0 |
| `public.publication_contact_tracking` | 0 |

## Only in old migration or load scripts (54)

| Table | Rows |
|---|---|
| `public.fishmisfortune` | 197137 |
| `public.pheno_term` | 123066 |
| `public.sanger_location` | 112614 |
| `public.sanger_location_post` | 111476 |
| `public.ftrconsequencenew` | 73564 |
| `public.ftrmutdetssanger` | 39862 |
| `public.sanger_flanking_sequence` | 37613 |
| `public.feature_group` | 37050 |
| `public.pmflankseq` | 36815 |
| `public.sanger_flanking_sequence_post_proc` | 35210 |
| `thisse.screen_april17` | 15890 |
| `public.featmkr` | 12381 |
| `thisse.zebrafish_ish17` | 11453 |
| `public.ensdarg_tt` | 3212 |
| `public.ensdarg_tt_no_dups` | 2760 |
| `thisse.clon_class14` | 2661 |
| `public.seqvar` | 1620 |
| `public.tscriptens1` | 1518 |
| `public.fgmdnote` | 1513 |
| `public.ftrconsequence` | 1509 |
| `public.tscriptens` | 1039 |
| `public.ftrmutdets` | 811 |
| `public.publication_correspondence` | 651 |
| `public.featuredata` | 638 |
| `thisse.excel_import_zfish_ish_dbase_avril_2017` | 379 |
| `public.missing_varseq` | 301 |
| `public.expressed_gene_fish_mirna` | 143 |
| `public.cnechr1` | 113 |
| `public.cnechr` | 113 |
| `public.cnedata` | 113 |
| `thisse.excel_import_nuclear_receptors` | 101 |
| `public.constructrel` | 71 |
| `public.burgess_location_post` | 70 |
| `public.newfeaturedata` | 64 |
| `public.constructreln` | 24 |
| `public.featuregenedata` | 20 |
| `public.int_vegatype_tscripttype` | 20 |
| `public.dupcrispr` | 19 |
| `public.name_precedence` | 19 |
| `public.vector_type` | 14 |
| `public.featurenogenedata` | 7 |
| `public.external_note_type` | 6 |
| `public.featurezmp` | 6 |
| `public.featurezmpdata` | 5 |
| `public.map_metric` | 4 |
| `public.developer_data` | 1 |
| `public.average_time_per_person` | 0 |
| `public.experiment_condition_restricted` | 0 |
| `public.feature_audit` | 0 |
| `public.post_aud` | 0 |
| `public.pub_correspondence_sent_email_contains_subject` | 0 |
| `public.revinfo` | 0 |
| `ui.chebi_zebrafish_models_display` | 0 |
| `ui.chebi_zebrafish_models_evidence_association` | 0 |


## Scratch tables (second pass, 2026-10-22)

**Already handled.** Ryan's list of `tmp_*` tables (`tmp_addgene` ... `tmpcne`, 28 tables) was dropped by ZFIN-10185
(`1180/ZFIN-10185.sql`, 50 tables in all), which ran in production on 2026-04-13. The daily snapshots show all 28
present through 04.01 and none since. ZFIN-10350's `1183/.../0050` likewise dropped the three
phenotype-mart `_bkup` tables.

**What is left.** I scored every one of the 477 tables in the 10.21 snapshot (leaving out `_old_<timestamp>`, which the
cleanup job handles) on these signals:
- the name looks like scratch (`tmp`, `temp`, `bkup`, `old`, `copy`, `new`, `dup(s)`, `post`, `missing_`, `remove_`,
  or a trailing number or date);
- no code reference (+2), or references only in migrations (+1);
- no primary key;
- no foreign key in either direction, and no view uses it;
- unchanged for the whole year.

A table comment counts against it (-2); live code references count against it (-3). DB facts come from the local
10.15 load (`pg_constraint`, `pg_depend`).

Score 6-7 (scratch name, unreferenced, no keys):

| Score | Table | Rows | Code refs | Identical since | Signals |
|---|---|---|---|---|---|
| 7 | `public.xpat_results_generated_bkup` | 308,334 | none | >= 2025.10.14.1 | name,no code ref,no PK,no FK/view,frozen 1y |
| 7 | `public.xpat_exp_details_generated_bkup` | 142,551 | none | >= 2025.10.14.1 | name,no code ref,no PK,no FK/view,frozen 1y |
| 7 | `public.pheno_term_old` | 75,042 | none | >= 2025.10.14.1 | name,no code ref,no PK,no FK/view,frozen 1y |
| 7 | `public.linkages_copy` | 454 | none | >= 2025.10.14.1 | name,no code ref,no PK,no FK/view,frozen 1y |
| 7 | `public.missing_xpatfig` | 33 | none | >= 2025.10.14.1 | name,no code ref,no PK,no FK/view,frozen 1y |
| 7 | `public.remove_phenos_ids` | 27 | none | >= 2025.10.14.1 | name,no code ref,no PK,no FK/view,frozen 1y |
| 6 | `public.expression_pattern_figure_bkup` | 503,867 | none | 2025.11.08.1 | name,no code ref,no PK,no FK/view |
| 6 | `public.expression_result_bkup` | 503,867 | none | 2025.11.08.1 | name,no code ref,no PK,no FK/view |
| 6 | `public.expression_experiment_bkup` | 200,506 | none | 2025.11.08.1 | name,no code ref,no PK,no FK/view |
| 6 | `public.sanger_location_post` | 111,476 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `public.sanger_flanking_sequence_post_proc` | 35,210 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `public.genox_fish_annotation_search_bkup` | 17,185 | none | >= 2025.10.14.1 | name,no code ref,no FK/view,frozen 1y |
| 6 | `thisse.screen_april17` | 15,890 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `thisse.zebrafish_ish17` | 11,453 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `public.ensdarg_tt_no_dups` | 2,760 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `thisse.clon_class14` | 2,661 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `thisse.excel_import_zfish_ish_dbase_avril_2017` | 379 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `public.missing_varseq` | 301 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |
| 6 | `public.burgess_location_post` | 70 | migrations-only | >= 2025.10.14.1 | name,migrations only,no PK,no FK/view,frozen 1y |

Score 4-5 (unreferenced or migrations-only one-off load tables; most have no scratch-like name):

| Score | Table | Rows | Code refs | Identical since |
|---|---|---|---|---|
| 5 | `public.fish_genox` | 29,250 | none | >= 2025.10.14.1 |
| 5 | `public.xpat_figure_group_member` | 27,885 | none | >= 2025.10.14.1 |
| 5 | `public.xpat_figure_group` | 15,859 | none | >= 2025.10.14.1 |
| 5 | `public.editconstruct` | 202 | none | >= 2025.10.14.1 |
| 5 | `public.temp_existing_crispr_fmrel` | 141 | migrations-only | 2026.02.23.1 |
| 5 | `public.sangerfeatures` | 54 | none | >= 2025.10.14.1 |
| 5 | `public.expression_phenotype_term_controlled_vocab` | 8 | none | >= 2025.10.14.1 |
| 5 | `public.genox_group` | 0 | none | >= 2025.10.14.1 |
| 5 | `public.genox_group_member` | 0 | none | >= 2025.10.14.1 |
| 5 | `public.temp_existing_crispr_replaced_gene` | 0 | migrations-only | 2026.02.23.1 |
| 5 | `public.temp_existing_talen_fmrel` | 0 | migrations-only | 2026.02.23.1 |
| 4 | `public.fishmisfortune` | 197,137 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.pheno_term` | 123,066 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.sanger_location` | 112,614 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.ftrconsequencenew` | 73,564 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.affected_gene_group_member` | 41,682 | none | >= 2025.10.14.1 |
| 4 | `public.ftrmutdetssanger` | 39,862 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.sanger_flanking_sequence` | 37,613 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.pmflankseq` | 36,815 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.all_anatomy_contains` | 34,597 | none | >= 2025.10.14.1 |
| 4 | `public.environment_group` | 29,250 | none | >= 2025.10.14.1 |
| 4 | `public.genotype_group` | 13,773 | none | >= 2025.10.14.1 |
| 4 | `public.featmkr` | 12,381 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.ensdarg_tt` | 3,212 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.bl_plate_tracking` | 2,712 | none | >= 2025.10.14.1 |
| 4 | `public.seqvar` | 1,620 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.tscriptens1` | 1,518 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.fgmdnote` | 1,513 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.ftrconsequence` | 1,509 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.tscriptens` | 1,039 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.ftrmutdets` | 811 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.featuredata` | 638 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.expressed_gene_fish_mirna` | 143 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.cnechr` | 113 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.cnechr1` | 113 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.cnedata` | 113 | migrations-only | >= 2025.10.14.1 |
| 4 | `thisse.excel_import_nuclear_receptors` | 101 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.constructrel` | 71 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.newfeaturedata` | 64 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.state_code` | 59 | none | >= 2025.10.14.1 |
| 4 | `public.constructreln` | 24 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.featuregenedata` | 20 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.dupcrispr` | 19 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.featurenogenedata` | 7 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.featurezmp` | 6 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.featurezmpdata` | 5 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.databasechangeloglock` | 1 | none | >= 2025.10.14.1 |
| 4 | `public.average_time_per_person` | 0 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.feature_audit` | 0 | migrations-only | >= 2025.10.14.1 |
| 4 | `public.genotype_group_member` | 0 | none | >= 2025.10.14.1 |
| 4 | `public.genox_fish_annotation_search` | 0 | none | >= 2025.10.14.1 |
| 4 | `public.post_aud` | 0 | migrations-only | >= 2025.10.14.1 |

**Notes:**
- **The largest are backups:**
  - `expression_result_bkup` / `expression_pattern_figure_bkup` (504k each) and `expression_experiment_bkup` (201k)
    were last changed 2025.11.08.
  - `xpat_results_generated_bkup` (308k), `xpat_exp_details_generated_bkup` (143k) and
    `genox_fish_annotation_search_bkup` have not changed all year.
- **Some names are one-off load tables:** `sanger_*`, `ftrmutdets*`, `featuredata`, `cne*`, `tscriptens*`, `fishmisfortune`,
  `pheno_term`/`pheno_term_old` and the `thisse.*` 2017 imports. They match the migration scripts that created them.
- **The `temp_existing_*` tables** were last changed 2026.02.23, so they come from a fairly recent one-off.
- **False positives to keep:**
  - `databasechangeloglock` (Liquibase) and `post_aud` (Envers);
  - static lookups such as `state_code` and `expression_phenotype_term_controlled_vocab`, until checked.
- **To prevent new scratch tables:** use `create temp table` (the ZFIN-10185 pattern), or drop the table in the same
  script. A daily-dashboard flag for new tables with scratch-like names would catch the ones that slip through.
  The dashboard already lists added tables.

## Database dependency check (2026-10-22)

I checked the 100 candidates (everything scored 4 or higher, plus every table with no code reference or only
migration references) against the 10.15 production data in the local `zfindb`:
- views and materialized views built on them (`pg_rewrite`/`pg_depend`);
- foreign keys in both directions;
- non-internal triggers;
- any function whose source names the table (`pg_proc.prosrc`, whole word).

### Blocked: something in the database still uses them (11)

| Table | Rows | What depends on it |
|---|---|---|
| `public.audit_log` | 27 | functions: public.audit_trigger |
| `public.name_precedence` | 19 | functions: public.regen_name_ends_list public.regen_names public.regen_names_feature_list public.regen_names_fish_list public.regen_names_genotype_list public.regen_names_marker_list |
| `public.vector_type` | 14 | FK children: public.vector |
| `public.geneprefix_institution` | 10 | FK children: public.fest_lib_inst |
| `public.external_note_type` | 6 | FK children: public.external_note |
| `public.map_metric` | 4 | FK children: public.linkage_pair public.mapped_marker public.panels public.zmap_pub_pan_mark |
| `public.source_url_purpose` | 2 | FK children: public.source_url |
| `public.databasechangeloglock` | 1 | Liquibase lock table |
| `public.developer_data` | 1 | triggers: developer_data_sync_modified_at_trigger |
| `public.revinfo` | 0 | FK children: public.post_aud |
| `ui.chebi_zebrafish_models_display` | 0 | FK children: ui.chebi_zebrafish_models_evidence_association |

These stay unless their dependents go too. Notes:
- `name_precedence` looks dead from the code, but the `regen_names*` functions read it.
- `audit_log` is written by the `audit_trigger` function.
- `revinfo`/`post_aud` belong to Hibernate Envers.
- The lookup tables (`map_metric`, `vector_type`, `source_url_purpose`, `external_note_type`, `geneprefix_institution`)
  are static but still referenced by live rows.

### Clear: no code references (outside old migrations) and nothing in the database depends on them (89)

An FK *to* a live table ("FK parents") does not block dropping: the constraint goes away with the table.

| Table | Rows | Code refs | FK parents (outgoing) |
|---|---|---|---|
| `public.expression_pattern_figure_bkup` | 503,867 | none |  |
| `public.expression_result_bkup` | 503,867 | none |  |
| `public.feature_group_member` | 391,336 | migrations-only |  |
| `public.xpat_results_generated_bkup` | 308,334 | none |  |
| `public.expression_experiment_bkup` | 200,506 | none |  |
| `public.fishmisfortune` | 197,137 | migrations-only |  |
| `public.xpat_exp_details_generated_bkup` | 142,551 | none |  |
| `public.pheno_term` | 123,066 | migrations-only |  |
| `public.sanger_location` | 112,614 | migrations-only |  |
| `public.sanger_location_post` | 111,476 | migrations-only |  |
| `public.pheno_term_old` | 75,042 | none |  |
| `public.ftrconsequencenew` | 73,564 | migrations-only |  |
| `public.all_alias_tokens` | 54,248 | none | public.zdb_active_data |
| `public.affected_gene_group_member` | 41,682 | none |  |
| `public.ftrmutdetssanger` | 39,862 | migrations-only |  |
| `public.sanger_flanking_sequence` | 37,613 | migrations-only |  |
| `public.feature_group` | 37,050 | migrations-only |  |
| `public.pmflankseq` | 36,815 | migrations-only |  |
| `public.sanger_flanking_sequence_post_proc` | 35,210 | migrations-only |  |
| `public.all_anatomy_contains` | 34,597 | none |  |
| `public.environment_group` | 29,250 | none |  |
| `public.fish_genox` | 29,250 | none |  |
| `public.xpat_figure_group_member` | 27,885 | none |  |
| `public.genox_fish_annotation_search_bkup` | 17,185 | none |  |
| `thisse.screen_april17` | 15,890 | migrations-only |  |
| `public.xpat_figure_group` | 15,859 | none |  |
| `public.genotype_group` | 13,773 | none |  |
| `public.featmkr` | 12,381 | migrations-only |  |
| `thisse.zebrafish_ish17` | 11,453 | migrations-only |  |
| `public.ensdarg_tt` | 3,212 | migrations-only |  |
| `public.ensdarg_tt_no_dups` | 2,760 | migrations-only |  |
| `public.bl_plate_tracking` | 2,712 | none |  |
| `thisse.clon_class14` | 2,661 | migrations-only |  |
| `public.submission_log` | 2,507 | none |  |
| `public.crispr_name_index` | 2,199 | migrations-only |  |
| `public.seqvar` | 1,620 | migrations-only |  |
| `public.tscriptens1` | 1,518 | migrations-only |  |
| `public.fgmdnote` | 1,513 | migrations-only |  |
| `public.ftrconsequence` | 1,509 | migrations-only |  |
| `public.crispr_detail` | 1,416 | migrations-only |  |
| `public.feature_dna_mutation_detail_id` | 1,416 | migrations-only |  |
| `public.tscriptens` | 1,039 | migrations-only |  |
| `public.ftrmutdets` | 811 | migrations-only |  |
| `public.publication_correspondence` | 651 | migrations-only | public.person public.publication |
| `public.featuredata` | 638 | migrations-only |  |
| `public.linkages_copy` | 454 | none |  |
| `public.crispr_name_index_max` | 397 | migrations-only |  |
| `thisse.excel_import_zfish_ish_dbase_avril_2017` | 379 | migrations-only |  |
| `public.missing_varseq` | 301 | migrations-only |  |
| `public.editconstruct` | 202 | none |  |
| `public.expressed_gene_fish_mirna` | 143 | migrations-only |  |
| `public.temp_existing_crispr_fmrel` | 141 | migrations-only |  |
| `public.cnechr` | 113 | migrations-only |  |
| `public.cnechr1` | 113 | migrations-only |  |
| `public.cnedata` | 113 | migrations-only |  |
| `thisse.excel_import_nuclear_receptors` | 101 | migrations-only |  |
| `public.constructrel` | 71 | migrations-only |  |
| `public.burgess_location_post` | 70 | migrations-only |  |
| `public.newfeaturedata` | 64 | migrations-only |  |
| `public.state_code` | 59 | none |  |
| `public.sangerfeatures` | 54 | none |  |
| `public.missing_xpatfig` | 33 | none |  |
| `public.remove_phenos_ids` | 27 | none |  |
| `public.constructreln` | 24 | migrations-only |  |
| `public.featuregenedata` | 20 | migrations-only |  |
| `public.int_vegatype_tscripttype` | 20 | migrations-only | public.transcript_type |
| `public.dupcrispr` | 19 | migrations-only |  |
| `public.expression_phenotype_term_controlled_vocab` | 8 | none |  |
| `public.featurenogenedata` | 7 | migrations-only |  |
| `public.featurezmp` | 6 | migrations-only |  |
| `public.featurezmpdata` | 5 | migrations-only |  |
| `public.talen_name_index` | 5 | migrations-only |  |
| `public.talen_name_index_max` | 2 | migrations-only |  |
| `public.author_publication` | 0 | none | public.author public.publication |
| `public.average_time_per_person` | 0 | migrations-only |  |
| `public.experiment_condition_restricted` | 0 | migrations-only | public.condition_data_type public.experiment public.term |
| `public.feature_audit` | 0 | migrations-only |  |
| `public.genotype_group_member` | 0 | none |  |
| `public.genox_fish_annotation_search` | 0 | none |  |
| `public.genox_group` | 0 | none |  |
| `public.genox_group_member` | 0 | none |  |
| `public.person_author` | 0 | none | public.author public.person |
| `public.post_aud` | 0 | migrations-only | public.revinfo |
| `public.pub_correspondence_sent_email_contains_subject` | 0 | migrations-only |  |
| `public.publication_contact_tracking` | 0 | none | public.person public.publication |
| `public.temp_existing_crispr_replaced_gene` | 0 | migrations-only |  |
| `public.temp_existing_talen_fmrel` | 0 | migrations-only |  |
| `ui.chebi_zebrafish_models_evidence_association` | 0 | migrations-only | public.term ui.chebi_zebrafish_models_display |
| `zirc.phenotype_file` | 0 | none | public.person zirc.phenotype |

**Suggested next step:** go through the Clear list with whoever owns those areas. Then, in one migration:
1. dump each table to `/research/zarchive` (`pg_dump -t ...`), as an archive;
2. `drop table if exists ...` -- the ZFIN-10185 pattern.

Start with the large `*_bkup` copies and the `*_old`/`_copy` tables. The one-off load tables (`sanger_*`, `ftr*`,
`thisse.*`, ...) may still be worth archiving before they are dropped.

## Still mentioned in live code (154)

Not listed here. To regenerate the full classification, rerun the grep against `unchanged_since.csv`
rows whose bound is `at_least`.
