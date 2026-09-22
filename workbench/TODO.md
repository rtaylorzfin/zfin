# TODO before this branch becomes a PR

Draft PR: **rtaylorzfin/zfin#55** (fork, `zfin-10464-go-load-cutover` → `main`).
Open against the fork only; nothing is filed upstream.

~~**The removal-safety guard on this branch does not work.**~~ ✅ **FIXED 2026-09-22.**
Items 1 and 2 were addressed together, because fixing either alone makes things
worse: a working guard on the old threshold would have blocked the cutover
itself. See "How it was fixed" below. The two sections are kept as the record of
what was wrong.

Items 3 and 4 came out of the Jira thread after this file was written; they are
not blockers for the guard, but they are cutover work that lives nowhere else.

---

## How it was fixed

Withholding is now driven by **attribution, not volume**.

`GafService.findRemovalKeysAttributableToRejections` identifies the removals a
rejected row could actually account for, matched on (marker ZDB id, GO id), and
**only those are withheld**. Removals with no corresponding rejection are rows
the file genuinely dropped, and they are applied — so a legitimate first cutover
is no longer blocked, while the ZFIN-10358 case (1.3% of one organization) now
trips regardless of how small it is.

- **Item 1** — the owning organization is recorded on each `GafJobEntry` when the
  removal pass creates it (`GafJobData.addRemoved(mgte, org)`), so withholding no
  longer infers ownership from `organizationCreatedBy`.
- **Item 2** — `GAF_MAX_REMOVAL_FRACTION` survives as a purely **advisory** volume
  warning: it marks the build for review and never withholds.
- `GAF_ALLOW_LARGE_REMOVAL` is now a Jenkins parameter, and **no longer suppresses
  UNSTABLE** — forcing a prune is a reason to look harder, not a reason to go green.

Matching deliberately ignores the evidence code: a row rejected *because* its ECO
term had no mapping still carries the raw ECO id, so keying on evidence would miss
exactly the rejections most likely to cause a spurious delete.

Rejections that never resolved a gene (`Gene not found for ID`, or a GAF-path
UniProtKB accession) cannot be attributed to any removal. They are counted and
reported, never acted on — guessing would withhold arbitrary rows.

**Tested.** `RemovalAttributionUnitTest`, 8 cases, no database. Verified by
mutation: reintroducing the `organizationCreatedBy` comparison fails
`matchingDoesNotDependOnOrganizationCreatedBy` and
`aRemovalMatchingARejectedRowIsWithheld`. The original defect's whole character
was that it looked correct at runtime, so a passing build proves nothing on its
own.

---

## 1. The guard blocks nothing (bug) — FIXED, kept as the record

`GafLoadJob.removalOwnedBy` compares two different namespaces:

```java
private boolean removalOwnedBy(GafJobEntry entry, GafOrganization org) {
    return org.getOrganization().equals(entry.getOrganizationCreatedBy());
}
```

- `org.getOrganization()` is the **owning organization**: `GOA`, `Noctua`,
  `PAINT`, `UniProt`, `FP Inferences`.
- `entry.getOrganizationCreatedBy()` is the GPAD **`assigned_by`** column, carried
  through from `MarkerGoTermEvidence.organizationCreatedBy`: `UniProt`,
  `InterPro`, `ZFIN`, `GO_Central`, `GOC`, …

The two sets never overlap for the cases that matter, so `removeIf` matches
nothing. The guard logs `REMOVAL BLOCKED`, sets `removalBlocked = true` (so the
job exits non-zero), and then leaves every annotation in the removal list to be
deleted.

Observed on `Load-GPAD-GO-Central_m` build #6 (2026-09-04, 2026.09.03.1 snapshot):

```
REMOVAL BLOCKED — GOA:    removing 42326 of 106815 annotations (39.6%); 189671 rejected
REMOVAL BLOCKED — Noctua: removing  4812 of  36034 annotations (13.4%);   2819 rejected
```

42,326 + 4,812 = 47,138, and the run summary reported `removed: 47138`. Every
annotation it announced it was protecting was deleted.

**Fix direction:** partition the removal set by owning organization when it is
built, rather than filtering it afterwards by a field that does not identify the
owner. `GafService.generateRemovedEntries` already runs per organization, so the
owning org is known at the point each entry is added — `GafJobData.addRemoved`
could record it, or `generateRemovedEntriesReport` could return that org's
entries as a list the caller keeps separate.

## 2. The 10% threshold cannot tell a first run from a runaway (design) — FIXED, kept as the record

`GAF_MAX_REMOVAL_FRACTION` defaults to 0.10. Had the guard worked, build #6 would
have blocked **47,138 legitimate removals** — a first run against a freshly
restored database always produces a large diff and always has rejected rows, so
it looks exactly like the disaster the guard exists to prevent.

Conversely the guard is useless for the case that actually bit during ZFIN-10358
testing: the second-run removals were 1,920 of ~150,000 (1.3%), well under the
threshold, yet those were the wrong ones.

So fraction-of-owned is the wrong signal in both directions. Worth considering
instead:

- trip on removals **attributable to rejected rows**, regardless of fraction —
  this requires knowing which existing annotation a rejected row would have
  matched, which is often unknowable (the lookup that failed is the one needed to
  identify it), so it may only be possible for rejections that got far enough to
  resolve a marker and publication;
- or make the guard advisory by default (report and exit non-zero, never withhold)
  and require an explicit opt-in to actually block.

## Why the guard is still worth having

The underlying hazard is real and unchanged. `GafService.findOutdatedEntries`
computes:

```
outdated = getEvidencesForGafOrganization(org)
           - (existingEntries u newEntries u updateEntries)
```

A row that throws during validation reaches none of those three sets, so it is
indistinguishable from a row the file never contained, and `removeEntries`
deletes its database counterpart. Any parsing or lookup bug in this load is
therefore silent data loss rather than merely a failure to add. ZFIN-10358 was
the worked example: an unresolvable DOI deleted the annotation an earlier Noctua
load had created under a `ZFIN:ZDB-PUB-…` citation.

Note this hazard predates the branch — `main` has always behaved this way. The
guard is an improvement that does not yet work, not a regression.

## 3. Switch the ECO→GO mapping to GO's derived file — ✅ DONE 2026-09-22

Per ZFIN-10464 comments 14–16 (2026-09-21). `ECO:0005547` (66 rows / 65 pairs
from ComplexPortal) was originally read as a violation of GO's
`allowed_reference: GO_REF:0000114` constraint in `eco-usage-constraints.yaml`.
Pascale, via Doug, said it should not error — and on re-tracing it is a **mapping
gap, not the constraint**: the term is simply absent from the flat file we
consume. `ECO:0005547 → NAS` is present in the derived file.

`getECOGOMapping.groovy` currently pulls the GitHub raw copy of the flat
`gaf-eco-mapping.txt`. Switch it to the permanent PURL of the derived file,
which GO's own header says to prefer:

    http://purl.obolibrary.org/obo/eco/gaf-eco-mapping-derived.txt

**Measured 2026-09-22** against the current published files and a live ZFIN
database:

- The derived file is a strict **superset**: all 26 flat mappings appear in it
  verbatim, and it carries 1,422. Every one of its ECO terms already exists in
  ZFIN's `term` table, so the join in `insert_eco_go_map.sql` drops nothing.
  `eco_go_mapping` goes **39 → 1,426 rows** (1,387 new).
- The load is additive by construction — `on conflict … do nothing`, no delete —
  so no currently-loaded mapping can be lost. "Additive only" is therefore safe
  at the file level. It is **not** safe at the lookup, see below.

Two things have to change with the URL:

**(a) The columns are reversed.** Flat is `CODE ⇥ Default ⇥ ECO`; derived is
`ECO ⇥ CODE ⇥ [Default]`. So `evidence_code = line.split()[0]` /
`eco_term = line.split()[2]` must become `[1]` / `[0]`. `[2]` would break
outright: 1,396 of the 1,422 rows have an empty third column.
⚠️ The derived file's own header comment still describes the **old** column
order ("1. GAF evidence code, 2. ECO ID"). It is wrong; go by the data.

**(b) `uniqueResult()` cannot survive a dual-coded ECO term.**
`HibernateOntologyRepository.getEcoEvidenceCode` ends in `criteria.uniqueResult()`,
called per row from `GpadParser.postProcessing`, so two mappings for one term
throw `NonUniqueResultException` and fail the load. Five terms are dual-coded
after the switch:

| ECO term | today | after | |
|---|---|---|---|
| `ECO:0000255` | ISM + ISS | unchanged | **already dual in prod** |
| `ECO:0000320` | IKR + IMR | unchanged | **already dual in prod** |
| `ECO:0000031` | ISS | + ISA | new |
| `ECO:0000262` | ISS (curated, DLOAD-672) | + ISM | new |
| `ECO:0007295` | — | EXP + IEA | both rows from the file |

Two of those are live in the database **today**, so this is a latent bug the
switch widens rather than one it creates. The derived file's `Default` marker is
no tiebreaker — `ECO:0007295` carries it on neither row. Decide a policy
(deterministic pick at lookup, or one row per term at load time) rather than
letting it throw.

**Done, and verified against the live stack.** `eco_go_mapping` went 41 → 1,422 rows over 1,420
ECO terms, and every DANRE-mod evidence code now resolves. Outcomes:

| check | result |
|---|---|
| `ECO:0005547` — the whole point | **NAS** |
| `ECO:0000031` (had ISS; file says ISA) | **ISS** — unchanged |
| `ECO:0000262` (curated ISS; file says ISM) | **ISS** — unchanged |
| `ECO:0007295` (EXP + IEA, no Default) | unmapped, skipped and reported |
| ECO terms with >1 code | **2** — the two that were already dual; none added |
| DANRE-mod codes still unmapped | **none** |

Three things the implementation had to handle that were not in the original plan:

- **The PURL 302s to https, and Java will not follow a cross-protocol redirect.**
  `new URL(...).openStream()` returned the 9-line HTML redirect page; four of its lines split
  into two whitespace-separated fields, so they parsed as mappings and the old `mappingCount == 0`
  guard passed. The load would have replaced the table with junk. Caught by running it, not by
  reading it. Now follows redirects explicitly, requires an `ECO:` CURIE in column 1, and refuses
  fewer than 500 mappings.
- **The loader only fills gaps.** An ECO term that already resolves is never touched, so no
  stored annotation's evidence code can change underneath us and the curated mappings
  (DLOAD-672, ZFIN-9426) survive. This is what keeps `ECO:0000031` on ISS and `ECO:0000262` on
  its curated ISS.
- **A term the file maps several ways with no `Default` is skipped, not guessed.** Leaving it
  unmapped surfaces as a visible "invalid eco code"; picking one silently mislabels every row.

`getEcoEvidenceCode` no longer uses `uniqueResult()`, which would throw
`NonUniqueResultException` on `ECO:0000255` or `ECO:0000320` — both already dual in the database
today. It now orders by id, takes the oldest and warns. That was a live latent bug independent
of this switch.

Note the switch also subsumes this branch's
`1185/…/0030-ZFIN-10464-eco-goref-0000108-mappings.sql` (`ECO:0000364`,
`ECO:0000366` → IEA) and the already-merged
`1184/…/0010-ZFIN-10025-eco-0007322-subcell-iea-mapping.sql` (`ECO:0007322` →
IEA) — all three are in the derived file. **Keep the migrations anyway:** the
mapping load only fires from `LoadOntology/build.xml`, not from the GO load, so
on a restored database the migration is what puts them there in time.

## 4. ND filtering (new, unbuilt)

Doug, ZFIN-10464 comment 12 (2026-09-19), relaying Pascale: GO/GOA know about
ND annotations coexisting with real ones on the same aspect and **plan** a filter
upstream, but it is not in place. He asks ZFIN to implement one — "either a
filter on the incoming file or after the load."

The worked example from comment 10 is a root term: `GO:0008150`
(`biological_process`, ND, ZFIN/`GO_REF:0000015`, Noctua) sitting alongside
`GO:0002088` and `GO:0007601` (IBA, GO_Central/`GO_REF:0000033`, PAINT) on the
same gene. ZFIN's own database constraints already disallow root terms with
descendants, which makes this look less like load policy than a schema rule.

This is separate from the descendant filtering removed in this branch — that was
removed on Doug's instruction (comment 8) because ZFIN is now purely a consumer.
ND filtering is narrower and is **added** policy, not restored policy. Nothing is
built for it yet, and Doug's comment 13 ("I'll check with Pascale on this") is
still unanswered, so scope it before writing code.

## Related

- ZFIN-10358 — where this was found; that PR does **not** contain the guard
- ZFIN-10025 — the unified DANRE-mod load
- ZFIN-10464 comments 5–8 — Doug's decisions that this branch implements:
  `GO_REF:0000108` → `ZDB-PUB-260903-15` with both ECO codes as IEA (comment 6),
  and dropping descendant filtering entirely (comment 8)
- The cutover purge scripts are unaffected either way: they delete via SQL, not
  via the load's removal diff.
