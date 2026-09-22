# ZFIN-10464 — plan for the cutover loss report

_What we will lose at cutover, how we intend to measure it, and what the number looks like
before the measurement finishes. Written 2026-09-22._

The question this answers, precisely: **which rows present before the cutover have no equivalent
row after it** — not "how many rows were deleted", which is a much larger and much less
interesting number.

---

## 1. The mechanism already exists

`mgte_snapshot.sh --all` + `mgte_csvdiff.sh --all` produce **`mgte_dbdiff_ALL.xlsx`**, and its
`deletes` sheet is exactly this question. It is keyed deliberately coarser than the per-org
workbooks:

    ALL_KEY = marker,term,evidence,relation

`org`, `source`, `created_by`, `contributed_by`, `inferred_from`, `annotation_extensions` and
`noctua_model` are compared but **not matched on**, so the two big cutover movements —
the phylo re-home (`GOA` → `PAINT`, org changes only) and the `*2go` handover
(`UniProt` → `GOA`, org *and* `created_by` change) — surface as updates instead of as
delete+add pairs. The per-org key would score both as total loss.

`Load-GPAD-GO-Central_m` already wires it, and in the right order:

    snapshot before --all  →  load  →  cutover-rehome-phylo-to-paint
                                    →  cutover-purge-uniprot-2go
                                    →  cutover-purge-uniprot-kw2go   (RUN_KW2GO_PURGE)
                                    →  mgte cleanup
                                    →  snapshot after --all  →  csvdiff --all

So **one run with `RUN_CUTOVER_SCRIPTS=true` and `RUN_KW2GO_PURGE=true` captures the whole
cutover delta in one workbook.** Nothing has to be stitched together by hand.

⚠️ It has never been run that way. The only recorded `ALL` numbers anywhere are the phylo
re-home validation (0 deletes / 0 adds / 62,196 updates). This is README decision 11 / Jira
decision 6, still open.

## 2. Three corrections between the raw number and the real one

**(a) `gflag` is not in the snapshot at all.** `snapshot_mgte.sql` selects 17 columns;
`mrkrgoev_gflag_name` is not one of them. On the live database that is **98 `not` and 97
`contributes to`** rows. Because the column is absent, a `NOT` annotation is indistinguishable
from its positive twin in *every* workbook, and a `not` → null flip is invisible. Small, but a
negated statement silently becoming a positive one is the worst failure this report could have.

Deliberately **not** fixed by changing the key: the runbook warns that editing `KEY`/`ALL_KEY`
makes all numbers incomparable to the 2026-07-07 and 2026-08-07 reports. 195 rows are better
handled by a targeted SQL check against the two snapshots, reported alongside.

**(b) `evidence` is in `ALL_KEY`, so an evidence-code change reads as loss.** Any row whose
3-letter code is re-derived differently becomes a delete plus an add. This is why the ECO
mapping-file switch (`gaf-eco-mapping-derived.txt`, TODO item 3) must **not** ride along in the
measurement run — it reassigns `ECO:0000031` ISS→ISA and `ECO:0000262` ISS→ISM. Measure the
cutover first, switch second, re-measure third.

**(c) No subsumption.** The `deletes` sheet counts a `(gene, GO)` pair as gone even when the gene
retains a more specific term covering it. The kw2go analysis already showed the size of this
correction: of 40,408 pairs, 14,471 were subsumed versus 10,907 genuinely lost. Apply the
README's strict `is_a` + `part of` closure — **not** `all_term_contains`, which also encodes
`regulates` and would overstate it.

## 3. Method

1. Baseline: stack `zfin-10464`, seed `2026-09-19`. Baseline orgs **UniProt 111,089 /
   GOA 106,815 / Noctua 36,034 / FP Inferences 1,623**, PAINT empty, 255,561 total. GOA matches
   the build #6 figure exactly, so this is comparable to the earlier analysis. It is *not* the
   `2026.07.05.1` dump the 2026-07/08 reports used — say so in the write-up rather than letting
   anyone diff the two.
2. One full-cutover run: `GAF_LOAD_REPORT_ONLY=false`, `RUN_CUTOVER_SCRIPTS=true`,
   `RUN_KW2GO_PURGE=true`, `RUN_MGTE_CLEANUP=true`.
3. Take `mgte_dbdiff_ALL.xlsx` `deletes` as the raw figure.
4. Subtract evidence-code churn; subtract subsumed pairs; report `gflag` separately.
5. Track the FP-Inference orphans separately — no purge covers them, so they never appear as
   deletes at all.

Running the kw2go purge measures the **delete** branch of decision 4, i.e. the worst case. Its
contribution is isolable afterwards by source pub `ZDB-PUB-020723-1`, so the **freeze** branch is
this number minus that slice — no second run needed.

---

## 4. Ballpark, ahead of the measurement

Sources: the 2026-08-31 VM run's artifacts (measured directly, see §5), and the counts already
recorded in `README-danre-mod-consolidation.md`. **Every figure below is provisional.**

### Gross rows deleted at cutover — order of 67,800

| component | rows | note |
|---|---:|---|
| `*2go` purge | **−24,903** net | 70,062 removed, 45,159 replaced by the new load (interpro2go −24,604, ec2go −299) |
| kw2go purge | **−41,027** | only if *delete* is chosen; **0** under *freeze* |
| the load's own removals | **~1,880** | measured on the 2026-08-31 run |

### Genuine loss — order of 14,000, plus an unquantified `*2go` residue

| component | pairs with no successor | confidence |
|---|---:|---|
| kw2go (delete branch) | **10,907** | documented; 15,030 reproduced + 14,471 subsumed already netted out |
| Noctua | **~2,284** experimental | documented (5,103 total, 2,819 of them ND) |
| FP Inferences | **1,144** | documented (1,623 pairs, only 479 reproduced) |
| `*2go` residue | **≤ 24,903 rows, pairs unknown** | ⚠️ subsumption never computed for this slice |
| **offsetting gain** | **+3,157 pairs / 2,576 genes** | `GO_REF:0000108`, once this branch lands |

So: **~14,300 pairs of genuine loss that we can currently defend**, against which the `*2go`
residue is the one real unknown and could be anything from a small number to most of 24,903.
Under the kw2go *freeze* branch the defensible figure drops to **~3,400**.

Two caveats worth saying out loud to the team:

- The `*2go` residue is the gap in our knowledge, not a number we are withholding. It is also
  the single largest term. Everything else has been counted at least once.
- These components were each measured on different runs against different baselines. The point
  of the pending measurement is to produce all of them from **one** before/after pair.

## 5. What the 2026-08-31 VM run actually shows

Artifacts: `/tmp/test-load-gpad-go-central-2-jenkins-artifacts`. Read carefully — this is a
**steady-state second run, not a cutover**: PAINT was already populated (26,648), UniProt was
untouched at 111,089, so the cutover scripts did not run. It also predates `--all`, so there is
no `ALL` workbook; the figures below are reconstructed from the per-org before/after CSVs.
It finished **UNSTABLE**.

    rows: before 319,035  after 317,161  net -1,874
    lost at per-org identity key : 1,880 rows
    lost at ALL_KEY (statement)  : 1,880 rows over 1,861 distinct keys
    lost at (gene, GO) pair      : 1,880 rows over 1,859 distinct pairs

That the figure barely moves across all three keys is itself the finding: these are real
disappearances, not re-keying artifacts.

**What is being lost is upstream refinement, not knowledge.** By `created_by`: UniProt 1,224,
InterPro 625, everything else 11. By aspect: molecular_function 1,512. The top terms are the
generic parents you would expect GOA to replace with something specific:

    GO:0016740 transferase activity   517      GO:0016020 membrane            111
    GO:0016301 kinase activity        423      GO:0005524 ATP binding          54
    GO:0016787 hydrolase activity     143      GO:0008233 peptidase activity   49

Strongly suggestive of subsumption rather than loss — but **not yet proven**, because the
closure check of §2(c) has not been run against them. Do not quote this as "no real loss" until
it has.

**The error profile also prices two open decisions** (10,871 errors total):

| count | category | bearing |
|---:|---|---|
| 7,811 | `Goref ID is not known or loaded` | `GO_REF:0000108` — **fixed on this branch**, drops to ~45 |
| 2,409 | `Cannot add root-term annotation … non-root annotations existent` | **decision 13, ND filtering** — far bigger than the comment-10 example suggested |
| 521 | Duplicate annotation entry | the file's own row duplication (finding 8) |
| 24 | `invalid evidence code: ECO:0005547` | **decision 6** — fixed by the derived-file switch |
| 38 / 23 / 18 | PMID not found / gene not found / Do Not Annotate | residual |

⚠️ The 2,409 root-term rejections matter beyond their own count. Rejected rows reach none of
`existingEntries`/`newEntries`/`updateEntries`, so `findOutdatedEntries` cannot distinguish them
from rows the file never contained — which is the removal hazard TODO items 1–2 exist for. They
are a candidate cause of removals elsewhere in the diff, and the guard that was supposed to
catch exactly this does not work.

## 6. The tables this ticket touches

Row counts from the `zfin-10464` stack (seed `2026-09-19`), before the rehearsal.

**The annotation itself**

| table | rows | role |
|---|---:|---|
| `marker_go_term_evidence` | 255,561 | **the table the whole ticket is about.** One row per GO annotation (19 columns). Every loss number counts rows or `(gene, GO)` pairs here |
| `marker_go_term_evidence_annotation_organization` | 6 | owning-org lookup: `ZFIN`, `FP Inferences`, `GOA`, `PAINT`, `UniProt`, `Noctua`. `PAINT` is pk 4. This is what the phylo re-home rewrites and what every per-org report groups by |
| `inference_group_member` | 540,364 | the with/from column (col 7). Snapshotted as `inferred_from` |
| `marker_go_term_annotation_extension_group` | 1,413 | annotation-extension grouping. Was 328,727 before migration 0040 collapsed it |
| `marker_go_term_annotation_extension` | 1,786 | the extensions themselves |
| `noctua_model_annotation` | 36,034 | links an annotation to its Noctua model |
| `noctua_model` | 10,043 | the models |

**Lookups the load resolves through**

| table | rows | role |
|---|---:|---|
| `eco_go_mapping` | **41** | ECO term → 3-letter GO evidence code. `GpadParser.postProcessing` hits it once per row. **The derived-file switch takes this to ~1,426** (TODO item 3). 41 not 39 because this branch's migration added `ECO:0000364`/`ECO:0000366` |
| `term` | — | both the GO terms annotated and the ECO terms mapped; joined on `term_ont_id` |
| `marker` | — | the gene each annotation is on |
| `publication` | — | `GO_REF:*` resolve here via `GoDefaultPublication`; `ZDB-PUB-260903-15` is the new `GO_REF:0000108` pub |

**Only for the subsumption pass**

| table | rows | role |
|---|---:|---|
| `term_relationship` | 585,900 | the source for a **strict `is_a` + `part of` closure**, which is what §2(c) requires |
| `all_term_contains` | 6,703,237 | the prebuilt closure. ⚠️ **Do not use it for subsumption** — it also encodes `regulates` and `positively regulates`, which overstates the correction. Named here so nobody reaches for it as the convenient option |

Not involved, despite the name: `marker_go_term_evidence_annotation_created_by_source` (6 rows: ZFIN, BHF-UCL,
HGNC, MGI, UniProtKB, IntAct). The `mrkrgoev_annotation_organization_created_by` column is free
text carrying `InterPro`, `GO_Central`, `GOC` and similar, none of which appear in that lookup.

## 7. Where the FP Inferences rows came from

Answering it properly, because decision 7 (freeze vs delete) reads differently once you know.

**Provenance.** Jenkins job `Load-GAF-FP-Inference_m` — still enabled, but with an empty cron
`spec`, so it only runs when triggered. It calls Ant `load-gaf-fpinference`, i.e. `GafLoadJob`
with the organization hardcoded to `FP Inferences` and `FpInferenceGafParser`, against:

    https://current.geneontology.org/products/upstream_and_raw_data/zfin-prediction.gaf

Note the path: **`upstream_and_raw_data`**. This is GO's raw PANTHER prediction file for
zebrafish, a different pipeline stage from the released `DANRE-mod` product. Still served as of
2026-09-22 (HTTP 200, 563 KB) but **last modified 2026-05-28**.

The file is 1,809 rows and completely uniform: 100% IBA / `GO_REF:0000033` / `assigned_by=GOC`,
with `PANTHER:PTN…` in with/from, subjects keyed on UniProtKB accessions. The stored rows match:
all 1,623 are `ZDB-PUB-110330-1` / IBA / GOC, first entered **2017-02-28**, last external load
**2026-06-22**. The org's own definition still describes them as "annotations made by a Chris
Mungall script".

The UniProt → ZFIN mapping is not the problem, incidentally: despite the file's `LOC*` symbols the
stored rows are 1,491 named genes, 84 clone-named and 48 `zgc:` — no `LOC*` at all.

**Why 1,143 have no successor.** Measured 2026-09-22 against the current published `DANRE-mod`
(62,255 distinct phylo pairs): **480 of the 1,623 reproduced, 1,143 FP-only** — reproducing the
recorded 479/1,144, the one-pair drift being the file moving. It is **not** a gene-coverage
problem:

| FP-only: 1,143 pairs over 1,003 genes | |
|---|---:|
| genes present in `DANRE-mod` at all | **976** |
| genes that already carry phylo annotations in `DANRE-mod` | **785** |
| genes absent from `DANRE-mod` entirely | **27** |

GO knows these genes, and for 785 of them is already making phylo calls — just not these. The
orphaned terms are generic: `GO:0007165` signal transduction (60), `GO:0007186` GPCR signaling
(58), `GO:0055085` transmembrane transport (51), `GO:0045202` synapse (40), `GO:0006357`
regulation of transcription (40), `GO:0005739` mitochondrion (35). The same generic-parent
signature as the 1,880 per-run losses in §5.

**Reading:** these are stale raw predictions GO has since refined or dropped from its released
product. That shifts decision 7. "Freeze in the dead org" does not preserve 1,143 unique facts —
it preserves 1,143 superseded predictions sitting beside fresh GO phylo content on the same 785
genes, with nothing marking them stale and no load to refresh or prune them. Delete is more
defensible than the raw count suggests.

⚠️ Not yet proven: the subsumption closure of §2(c) has not been run against these 1,143. The
burden of proof has shifted, but "no real loss" is not yet a claim we can make.

## 8. MEASURED — full-cutover rehearsal, 2026-09-22

Stack `zfin-10464`, seed `2026-09-19`, real writes, load + all three cutover scripts inside one
before/after window. **This supersedes the §4 ballpark.**

**Org movement**

| org | before | after |
|---|---:|---:|
| GOA | 106,815 | 117,350 |
| UniProt | 111,089 | **0** |
| Noctua | 36,034 | 31,942 |
| PAINT | 0 | 62,263 |
| FP Inferences | 1,623 | 1,623 *(untouched — the standing check)* |
| **total** | **255,561** | **213,178** |

**Loss, counted as keys present before with nothing after**

| | before | after | gone entirely | wholly new |
|---|---:|---:|---:|---:|
| statement (`marker,term,evidence,relation`) | 193,647 | 184,305 | **80,229** | 70,887 |
| **`(gene, GO)` pair** | 159,151 | 153,822 | **31,818** | 26,489 |

The pair figure is the curator-meaningful one. Its composition:

| source | pairs | what it is |
|---|---:|---|
| `ZDB-PUB-020723-1` | **24,544** | **kw2go** — the decision-4 *delete* branch. **0 under freeze** |
| `ZDB-PUB-031118-1` | **2,831** | **Noctua ND** — matches the documented 2,819 almost exactly |
| `ZDB-PUB-020724-1` | **1,827** | **the `*2go` residue** |
| `ZDB-PUB-110330-1` | 327 | phylo |
| `ZDB-PUB-031118-3` | 558 | |

By org: UniProt 26,929, Noctua 4,128, GOA 761. By evidence: IEA 27,087, ND 2,831, IMP 860.

### The `*2go` residue is 1,827 pairs, not ~24,903

§4 flagged this as the largest unknown and put it at "≤ 24,903 rows, pairs unknown". Measured, it
is **1,827 pairs** — because the purge deletes ~70,062 UniProt-org rows while the load supplies
GOA replacements covering nearly the same `(gene, GO)` ground. The row count was never the pair
count. **The §4 ballpark was pessimistic on its own biggest term; correct the record with the
team.**

### Headline

**31,818 pairs lost under kw2go-delete; 7,274 under kw2go-freeze** — before subsumption, which
will lower both. kw2go alone is 77% of the loss, so decision 4 dominates everything else.

Cross-checks that the method is sound: kw2go's 24,544 sits where the documented 40,408 pairs
minus ~15,030 reproduced predicts; Noctua ND lands on 2,831 against a documented 2,819; and
`FP Inferences` shows **zero** loss, confirming the load still does not touch it.

### Two things this run also proved

- **The `GO_REF:0000108` fix works in a real write run.** `Goref ID is not known or loaded` went
  **7,811 → 45**, exactly as the commit message predicted.
- **The broken guard is reproduced on current code.** It logged *"Removal-safety guard withheld
  deletions for at least one organization"*, exited 2 — and the summary still reported
  `removed: 47,129`, within nine rows of build #6's 47,138. TODO item 1 confirmed.

Caveats: `191,600 Duplicate annotation entry` errors are the file's own row duplication colliding
with rows this same first run inserted (521 on a second run). `ECO:0005547` still errors 24 times,
as intended — the derived-file switch is deliberately not in this run. The load exits non-zero via
the guard, so any driver with `set -e` stops before the cutover scripts.

## 9. Status

- [x] Branch rebased onto main, compiles and deploys clean
- [x] Stack `zfin-10464` provisioned from seed `2026-09-19`, migration verified applied
- [x] Baseline org counts captured
- [x] 2026-08-31 artifacts mined for the ballpark
- [x] Full-cutover rehearsal run — done 2026-09-22, see §8 (load took 59m36s)
- [ ] Subsumption pass over the `ALL` deletes sheet
- [ ] `gflag` blind-spot check against the two snapshots
- [x] `*2go` residue quantified — 1,827 pairs, far below the ballpark
