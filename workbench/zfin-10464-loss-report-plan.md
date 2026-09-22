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

## 6. Status

- [x] Branch rebased onto main, compiles and deploys clean
- [x] Stack `zfin-10464` provisioned from seed `2026-09-19`, migration verified applied
- [x] Baseline org counts captured
- [x] 2026-08-31 artifacts mined for the ballpark
- [ ] Full-cutover rehearsal run — **in progress**, load step is ~74 min
- [ ] Subsumption pass over the `ALL` deletes sheet
- [ ] `gflag` blind-spot check against the two snapshots
- [ ] `*2go` residue quantified — the one real gap
