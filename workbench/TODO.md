# TODO before this branch becomes a PR

**The removal-safety guard on this branch does not work.** It was written and
committed during ZFIN-10358, split out of that PR as out of scope, and only
afterwards found to be non-functional. Do not open a PR from this branch until
both problems below are addressed.

---

## 1. The guard blocks nothing (bug)

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

## 2. The 10% threshold cannot tell a first run from a runaway (design)

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

## Related

- ZFIN-10358 — where this was found; that PR does **not** contain the guard
- ZFIN-10025 — the unified DANRE-mod load
- The cutover purge scripts are unaffected either way: they delete via SQL, not
  via the load's removal diff.
