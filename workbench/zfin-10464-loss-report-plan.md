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

1. Baseline: a pre-cutover database. Record the per-org counts before starting — every figure
   below is read against them, and it is the cheapest guard against measuring the wrong thing.
   State which dump or seed was used in the write-up: figures from different baselines are not
   comparable, and will be compared if you do not say.
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

## 4. The tables this ticket touches

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

## 5. Where the FP Inferences rows came from

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

## 6. Measured loss

From a full rehearsal: stack `zfin-10464`, seed `2026-09-19`, real writes, load plus all three
cutover scripts inside one before/after window. Re-derive rather than quoting — every figure here
moves with the input file.

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

### The `*2go` residue is small

**1,827 pairs**, despite the purge deleting ~70,062 UniProt-org rows: the load supplies GOA
replacements covering nearly the same `(gene, GO)` ground. The row count is not the pair count,
and estimating this from rows overstates it by more than an order of magnitude.

### Subsumption applied — the final number

Run with `mgte_subsumption.sh` (committed tooling, §2(c) closed). The 31,818 lost pairs partition:

| bucket | pairs | share |
|---|---:|---:|
| `true_loss` — nothing in that lineage survives | **15,852** | 49.8% |
| `subsumed` — gene retains a **more specific** term | **14,644** | 46.0% |
| `specificity_lost` — gene retains only a **more general** term | **1,322** | 4.2% |

**Nearly half the apparent loss is not loss.** The raw `deletes` figure overstates by about 2×.

True loss by source:

| source | pairs | |
|---|---:|---|
| `ZDB-PUB-020723-1` | **9,889** | kw2go — **0 under freeze** |
| `ZDB-PUB-031118-1` | **2,528** | Noctua ND |
| `ZDB-PUB-020724-1` | **1,447** | interpro2go |
| `ZDB-PUB-110330-1` | 351 | phylo |
| `ZDB-PUB-031118-3` | 150 | ec2go |

By org: UniProt 11,427, Noctua 3,787, GOA 670. (Sums to 15,884, 32 above the total: a pair held
by two organizations before the cutover is counted under each.)

### Headline

| scenario | pairs genuinely lost |
|---|---:|
| kw2go **delete** | **15,852** |
| kw2go **freeze** | **5,963** |

kw2go is 62% of the true loss, so decision 4 still dominates every other open question combined.

Corroboration: an independent earlier analysis put kw2go's true loss at 10,907 on a different
baseline, against 9,889 here — close enough to trust the method, far enough apart that the figure
has to be re-derived per run rather than quoted.

Cross-checks that the method is sound: kw2go's 24,544 is what 40,408 pairs minus ~15,030
reproduced predicts; Noctua ND lands within a dozen of the 2,819 counted independently; and
`FP Inferences` shows **zero** loss, confirming the load does not touch it.

### What else a first-cutover run looks like

`Duplicate annotation entry` dominates the error summary — the file's own row duplication
colliding with rows the same run just inserted, so it is far larger on a first run than a second.
The load exits non-zero, so any driver using `set -e` stops before the cutover scripts.

## 7. Status

- [x] Branch rebased onto main, compiles and deploys clean
- [x] Stack `zfin-10464` provisioned from seed `2026-09-19`, migration verified applied
- [x] Baseline org counts captured
- [x] Full-cutover rehearsal run — see §6
- [x] Subsumption pass — built as `mgte_subsumption.sh`/`.sql`, wired into the job, run
- [ ] `gflag` blind-spot check against the two snapshots
- [x] `*2go` residue quantified — 1,827 pairs, far below the ballpark
