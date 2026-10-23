# PR drafts (fill in the ticket number)

---

## ncbi-load-stable-dblink-ids

**Title:** ZFIN-XXXX NCBI gene load: keep db_link IDs for unchanged links, stop replacing curated GenPept links

### Summary
Every Friday the NCBI gene load deleted all ~444k links it manages and re-inserted them with new ZDB-DBLINK IDs, even when nothing changed. On 10.08 → 10.09, for example, 443,929 links were replaced for a net gain of 67. That churns `db_link`, `zdb_active_data` and `record_attribution` by ~444k rows a week.

- **Stable IDs (`loadNCBIgeneAccs.sql`):**
  - A link that comes back unchanged keeps its row and ID. "Unchanged" means the same gene, accession and fdbcont, attributed only to load pubs.
  - Only links that are gone are deleted, and only new links get an ID.
  - `dblink_info` is left as is on kept links, so it records when the link was first loaded.
  - De-duplication now runs before ID assignment, so dropped rows no longer mint orphan IDs.
- **Curated GenPept links:**
  - When NCBI confirms a curated link, the load now keeps it with its ID and curator pub, and adds the load pub. Previously it deleted the link and reloaded it attributed only to the load pub.
  - The curated lookup is keyed by gene and accession, not accession alone. The old key deleted another gene's curated link when the two genes shared an accession.
- **GRCz12tu cleanup:** the ZFIN-9952 `marker_assembly` delete never deleted anything, because it ran after the links were gone. It now captures the genes first, and drops the row only if the gene has no NCBI Gene ID left.
- **Test:** the characterization test now finds its input archive inside the compile container (`NCBI_ARCHIVE_DIR`, or `/research`, or `/mnt/research`).

### Testing
The characterization load (DB 2026.01.14, inputs 2026-01-09) was compared with the same run on the original code.

**ID fix:**
- 0 content differences.
- Links keeping their ID: 263,880 → 523,206.
- New IDs: 290,342 → 31,016.

**Curated GenPept fix:** 9 differences, all curated links preserved.
- 4 keep their ID and curator pub and gain the load pub.
- 5 are no longer deleted by the accession-only lookup.

**GRCz12tu:** verified in a rolled-back transaction.

**Baseline:** the test's stored baseline (`after_load.csv.gz`) is stale and fails with the original code too. Refresh it after merge.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## ui-indexer-sorted-ancestor-ids

**Title:** ZFIN-XXXX Sort ancestor term ID arrays so UI display regens stop rewriting rows

### Summary
`getAncestorTermIdsBulk` built `*_ancestor_term_ids` with `array_agg` and no `ORDER BY`. Each array's order therefore followed the physical row order of `all_term_contains`, which shifts whenever that table is rebuilt. On those days the display regens rewrote nearly every row with only the order changed. On 10.08 → 10.09, 211,455 of 228,817 `ui.term_phenotype_display` rows differed only in that order, and `ui.chebi_phenotype_display` did the same.

This sorts the aggregate and the single-term variant. The only reader, `findIdsByAncestor`, matches with `@>` containment, so order never mattered to it.

### Testing
Small change, one file. Verified from the daily table snapshots: the changed rows differ only in array order.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## regen-cleanup-phenotype-old-tables

**Title:** ZFIN-XXXX Regen cleanup: drop leftover phenotype_*_generated_old_ tables

### Summary
ZFIN-10350 moved the `phenotype_*_generated` tables to an incremental apply, so they no longer make `_old_` copies. The 12 copies made before the switch are still in every dump, with data, ~380 MB. `regenPhenotypeMartCleanup.sh` now drops them too, and is a no-op once they're gone.

**Note:** this cleanup hasn't run since the Solr 9 upgrade. Its job, `Index-Faceted-Search-Step-3-Cleanup_d`, fails at the removed ant target `cleanup-solr-backup-files`. Since then, `_old_` tables have grown by 7 each regen day: 562 on 09.13, 771 on 10.13. The solr-snapshot-retention PR fixes the job, and this change takes effect once that lands.

### Testing
Not run against a database. The change is a shell loop over the existing `regen_cleanup_renamed_tables()` prefixes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## panther-load-stable-dblink-ids

**Title:** ZFIN-XXXX PANTHER load: keep db_link IDs for unchanged links

### Summary
`LoadPanther.groovy` deleted every PANTHER `db_link` and re-inserted all ~19.4k with new IDs on each run. It deleted `db_link` directly, so the old IDs stayed in `zdb_active_data` / `record_attribution` until the next day's orphan cleanup. Duplicate file rows also got IDs and attributions with no link.

Now:
- Links no longer in the file are deleted through `zdb_active_data`, which cascades.
- Only new (gene, PANTHER ID) pairs get IDs, one per pair.
- Every PANTHER link is attributed to ZDB-PUB-170810-14.

### Testing
- **Evidence:** the 2026.09.28 → 09.29 dumps differ by 19,412 PANTHER links that are identical except for the ID.
- **Scratch DB:** tested kept, changed, dropped, duplicate and non-gene rows. A second run is a no-op.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## ui-indexer-merge-stable-ids

**Title:** ZFIN-XXXX UI indexers: merge on natural key so tpd_id / cpd_id / zmd_id / opd_id stay stable

### Summary
`TermPhenotypeIndexer` and `ChebiPhenotypeIndexer` deleted their display table and association tables, then re-inserted every row. Each run therefore gave all ~227k `tpd_id` and ~22k `cpd_id` values a new number, and rewrote about 810k rows. Both IDs appear in client URLs (`/api/ontology/{termID}/phenotype`, `/phenotype-chebi`).

- **New hook:** `UiIndexer` gets an opt-in merge-in-place hook. Both indexers use it.
- **How the merge works:** the computed rows are batch-inserted into temp staging tables. One transaction then:
  - deletes rows whose key is gone, association rows first since there's no FK cascade;
  - updates only rows whose content changed;
  - inserts new keys;
  - adds or removes association pairs.
- **Keys:** term_phenotype_display uses (term, fish). chebi_phenotype_display uses (fish, experiment, term, has_chebi_in_phenotype), with a NULL term matched as a value.
- **Result:** unchanged rows keep their ID and `created_at`. Duplicate chebi association pairs left by the old bag mapping (131 on 10.15) are collapsed once.

**Also converted with the same hook:**
- **`FishModelIndexer`** (`zmd_id`):
  - Key: fish + condition + disease.
  - The representative fish experiment is now the lowest ID. It used to be whatever a HashSet returned first, so it changed between runs.
- **`GenesInvolvedIndexer`** (`opd_id`):
  - Key: term + human gene + OMIM accession + name, matched NULL-safely.
  - The gene-symbol search text is now sorted. Its order also changed between runs.
- **Sequences:** both insert using the entity's own sequence. The column-default sequences lag far behind and would collide.

### Testing
- **FishModel and GenesInvolved, end to end:** on the 10.15 database, starting from production's tables, every `zmd_id` and `opd_id` was kept, and repeat runs change 0 rows.

**Merge SQL:** tested on scratch copies of the 08.12 tables, with 08.13 as the computed output.
- The result equals 08.13.
- Every surviving key kept its ID.
- The second run changes nothing.
- A synthetic test with vanished keys left no orphans.

**End to end:** `gradle runPhenotypeIndexer`, twice, on a loaded 2026.01.26 database.
- Run 1 kept all 222,757 `tpd_id` values.
- Run 2 changed 0 rows in all five tables.
- A run that failed mid-merge left every table untouched.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## regen-fast-search-incremental

**Title:** ZFIN-XXXX Regenerate pheno_term / expression_term / genotype_figure fast_search incrementally

### Summary
These three regens rebuilt their whole table and swapped it in. Every row got a new surrogate ID each run (~4.3M, ~309k and ~186k rows), and each run left an `_old_` table behind, plus a sequence for gffs. Yet the real change is usually a few thousand rows or none.

- **`pheno_term_regen.sql`:** the same queries fill a temp table, and the result is applied as a diff in one transaction. Rows are counted per (psg_id, term, tag, is_direct), because keys legitimately repeat. Surplus rows are deleted (newest IDs first) and missing ones inserted. This resolves the ZFIN-10350 TODO in the file header.
- **`regen_expression_term_fast_search()`:** same pattern, counted per (xpatres, term), with the flag updated in place. It drops leftover `xpatfs_*` tables, and creates the xpatres index the live table had been missing, because the swap left it on `xpatfs_old`.
- **`regen_genofig_finish('f')`:** still builds `genotype_figure_fast_search_new` as before, then applies it as a set diff on a NULL-safe tuple md5, and drops `_new`.
- **Readers:** plain DELETE/INSERT doesn't block readers, which was the reason for the rename pattern. `reference/table-regeneration.md` is updated.

### Testing
**Scratch restores of two consecutive days:** each result equals the next day's table, all surviving rows keep their ID, and a rerun is a no-op.

**End to end:** on a loaded 2026.01.26 database, each run twice.

| Regen | Run time | IDs kept | Rerun |
|---|---|---|---|
| `pheno_term_regen.sql` | 42 s | 4,145,481 | 0 changes |
| `regen_expression_term_fast_search()` | 5 s | 306,962 | 0 changes |
| `regen_genox()` | 11 s | 180,689 | 0 changes; no `_old_`/`_new` left |

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## unload-fail-cleanup

**Title:** ZFIN-XXXX unload_production: bash, and remove a partial dump when pg_dump fails

### Summary
The script ran under `tcsh -e`, so a failing `pg_dump` stopped it at that line. The cleanup branch never ran, and a partial `.bak` was left behind in a correctly named `YYYY.MM.DD.N` folder. Anything that loads the newest unload would treat it as a good dump: `gradle loadDatabase`, and the table-churn jobs.

- **Rewritten in bash,** with each step checked and `exit 1` on any failure:
  - a failed `pg_dump` removes its dump folder;
  - a failed `pg_dumpall` removes its partial file;
  - a missing `INSTANCE`/`SOURCEROOT`/`PGBINDIR`/`DBNAME` fails up front.
- **File names follow the folder.** The `.bak` and `.dumpall` are now named after the dump folder. A second unload on the same day writes `.2.*`; before, it overwrote the first `.dumpall` and put a `.1.bak` in the `.2` folder.
- **Cleanup:** removed the duplicated `chgrp`/`chmod` and the misleading success message.

### Testing
Ran in the jenkins container with stub `pg_dump`/`pg_dumpall`, invoked as `./unload_production.sh` the way the job runs it.

The old script, with a failing `pg_dump`: exit 1, and the partial folder is left behind.

The new script:

| Case | Result |
|---|---|
| Failing `pg_dump` | exit 1, folder removed |
| Success | exit 0 |
| Same-day rerun | `.2` folder and `.2` files |
| Failing `pg_dumpall` | exit 1, nothing left |
| Variable unset | exit 1 |

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## frontend-stable-row-keys

**Title:** ZFIN-XXXX Data tables: stable row keys instead of Math.random()

### Summary
Four tables passed `rowKey={() => Math.random()}`, so every row got a new React key on every render. React therefore rebuilt every row on each re-render, and lost any per-row state. Each now uses a key that is unique within a response and stable across renders:

| Table | Key | Where it comes from |
|---|---|---|
| `FigureExpressionTable` | `'id'` | `ExpressionTableRow` serializes its composite `getUniqueKey()` as `"id"` |
| `FigurePhenotypeTable` | `'rowKey'` | `PhenotypeTableRow.getRowKey()`, which already existed for this |
| `TermGeneTable` | `'id'` | `OmimPhenotypeDisplay.id` |
| `FigureExpressionAntibodyTable` | `'rowKey'` | new on `AntibodyTableRow`: figure-stage ID + expression-result ID, one row per pair |

### Testing
- All four render through `DataTable`/`Table`, which accept a property name.
- `AntibodyTableRow` compiles with the new accessor.
- Still to do: click through the figure, publication and term pages.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
