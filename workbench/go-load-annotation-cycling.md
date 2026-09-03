# GO annotation cycling between the Noctua and GO Central GPAD loads

Notes from investigating ZFIN-10358 (accept DOIs as pub IDs in GO loads),
which turned up a larger problem belonging to ZFIN-10025: two scheduled
loads can add and remove the *same* annotations on an alternating
schedule, so a row's presence in the database depends on which job ran
most recently.

**Status: mechanism established by code reading and file comparison;
NOT yet demonstrated empirically.** The four-run experiment described in
§5 has not been completed. Treat the numbers in §3 as an upper bound on
the blast radius, not a measurement.

---

## 1. The mechanism

`GafService.findOutdatedEntries` computes what a load removes:

```
outdated = getEvidencesForGafOrganization(org)          // everything the org owns in the DB
           - (existingEntries ∪ newEntries ∪ updateEntries)   // what this file produced
```

`GafService.removeEntries` then deletes each one via
`deleteActiveDataByZdbID`. Two consequences follow, and the second is
the one that bites:

1. An annotation the file does not mention is removed. That is the
   intended diff behaviour.
2. An annotation the file *does* mention, but whose row failed
   validation, is **also** removed — because a row that threw in
   `getPublication` (or anywhere else in `generateAnnotation`) never
   reaches any of the three sets. A validation failure is therefore
   indistinguishable from an absence, and silently deletes data.

Point 2 is why the DOI bug mattered more than it first appeared. It was
not merely "these annotations fail to load"; on any run where the pub
could not be resolved, the pre-existing annotation was **deleted**.

## 2. Why two loads collide

Ownership is per-organization, and add/update/remove is scoped to the
owning org (`getEvidencesForGafOrganization`). The collision is that two
different jobs both write the **Noctua** bucket:

| job | schedule | organization arg | file |
|---|---|---|---|
| `Load-GPAD-Noctua_w` | weekly | `Noctua` | `noctua_zfin.gpad.gz` |
| `Load-GPAD-GO-Central_m` | monthly | `DANRE-mod` | `DANRE-mod.gpad.gz` |

The monthly job passes `DANRE-mod`, but `DanreModGpadParser`
`usesPerSourceOwnership()` is true, so `GafLoadJob` runs the removal diff
once per resolved owning org, and `DanreModSourceOrganization` maps
`assigned_by=ZFIN` to `OrganizationEnum.NOCTUA`. That mapping is
deliberate — it reproduces legacy ownership so the first real cutover is
a near no-op — but it means the monthly load's removal scope includes
every row the weekly load created.

So for any annotation present in one file and not the other:

- weekly runs → present
- monthly runs → removed
- weekly runs → re-added, under a **new** `ZDB-MRKRGOEV` id

The new id on each re-add is what makes this easy to miss. Anything that
tracks annotations by ZDB ID (attributions, external references, caches,
downstream diffs) sees churn even where the biological content is
unchanged.

## 3. Blast radius (estimated, not measured)

Comparing the two published files directly, keyed on
`(gene, relation, GO term, reference, ECO)`, restricted to rows the
Noctua bucket owns:

| | rows |
|---|---|
| `noctua_zfin.gpad` (all rows; legacy load owns them all) | 31,681 |
| `DANRE-mod` `assigned_by=ZFIN` | 27,608 |
| in both — stable | 27,426 |
| **only in `noctua_zfin`** → monthly removes, weekly re-adds | **4,255** |
| **only in `DANRE-mod`** → weekly removes, monthly re-adds | **182** |

Dropping the reference column from the key changes this only to
4,216 / 172, so it is genuine content divergence rather than citation
formatting.

Two reasons the true figure is **lower** than 4,255:

- 2,658 of the 4,255 are `GO_REF`-cited. `DanreModSourceOrganization`
  re-homes `GO_REF:0000033` to `PAINT`, so some of that difference is an
  annotation changing owner rather than being deleted and restored.
- Rows rejected for unrelated reasons (see §4) inflate the removal count
  on any run made before those are fixed.

Only the load's own diff can separate these, which is what §5 is for.

## 4. Two confounders that invalidate measurements

Both were hit during this investigation and both produced badly wrong
numbers before being noticed.

### 4.1 `gradle loaddb` does not run migrations

`loaddb` runs `loadDatabase` only. The task that also applies liquibase
is `reload` (`loadDatabase + liquibasePreBuild + liquibasePostBuild`).
Restoring an unload with `loaddb` and going straight to a load run leaves
release migrations unapplied — 26 changesets pending in our case,
2,636,502 rows affected. Three of them are ZFIN-10025 changesets written
for this load:

- `1184/migrations/0010-ZFIN-10025-eco-0007322-subcell-iea-mapping.sql`
- `1184/migrations/0030-ZFIN-10025-add-exp-go-evidence-code.sql`
- `1184/migrations/0040-ZFIN-10025-dedupe-annotation-extension-groups.sql`

Without the first, `ECO:0007322` is unmapped and ~66,882 rows reject —
which, by §1 point 2, converts directly into spurious removals. The
changeset's own comment predicts "~17,350 load errors" and "~24,283
spurious removals".

**Always run `liquibasePostBuild` after `loaddb`, or use `reload`.**

### 4.2 Duplicated annotation extension groups make the load appear to hang

Before `0040-…-dedupe-annotation-extension-groups` is applied, a restored
unload can carry pathological duplication:

| annotation | gene | extension groups |
|---|---|---|
| `ZDB-MRKRGOEV-221010-24689` | `syn1` | 1,048,464 |
| `ZDB-MRKRGOEV-230822-69` | `syn2b` | 262,140 |

1,310,604 of 1,312,015 groups (99.9%) sat on two annotations. Hibernate
initializing `syn1`'s collection is a `HashSet.add` of ~1M near-identical
elements whose `hashCode`s collide, which is O(n²).

Observed symptom: the load runs at ~97% CPU for **13+ hours** with no
writes and no report output. `jstack` shows
`PersistentSet.injectLoadedState` → `HashMap$TreeNode.find`; `jstat`
shows total GC time of ~3s, so it is emphatically **not** a memory
problem, which is the wrong diagnosis to chase. After the migration:
1,413 groups total, max 2 per annotation, and the same load completes in
53 minutes.

If a GO load appears hung, check this first:

```sql
select mgtaeg_mrkrgoev_zdb_id, count(*)
from marker_go_term_annotation_extension_group
group by 1 order by 2 desc limit 5;
```

## 5. How to measure the real number

Snapshot on annotation **content**, never on ZDB ID — a re-added row has
a new id, so an id-keyed snapshot hides exactly the effect being
measured.

```sql
copy (select e.mrkrgoev_mrkr_zdb_id, e.mrkrgoev_term_zdb_id,
             e.mrkrgoev_evidence_code, e.mrkrgoev_source_zdb_id
      from marker_go_term_evidence e
      join marker_go_term_evidence_annotation_organization o
           on o.mrkrgoevas_pk_id = e.mrkrgoev_annotation_organization
      where o.mrkrgoevas_annotation_organization = 'Noctua'
      order by 1,2,3,4) to stdout csv;
```

Sequence, all runs in **write** mode:

1. `reload` (or `loaddb` + `liquibasePostBuild`) → snap 0
2. `Load-GPAD-GO-Central_m` → snap 1
3. `Load-GPAD-Noctua_w` → snap 2
4. `Load-GPAD-GO-Central_m` again → snap 3

The cycling claim predicts **snap 3 ≈ snap 1** and **snap 2 ≈ snap 0**,
with the same content keys leaving and returning under fresh ZDB IDs.
`comm -23` / `comm -13` between consecutive snapshots gives lost/gained
per step.

Pin both input files first. `DANRE_MOD_GPAD_URL` is a job parameter;
`NOCTUA_GPAD_URL` is read by the `load-noctua-gpad` ant target. Note that
`file:` URLs do **not** work — `DownloadService.getLastModifiedOnServer`
casts to `HttpURLConnection` and is called before anything else — so
serve pinned copies over HTTP.

Prerequisite: resolve the still-unmapped ECO codes first, or every
removal count remains inflated. As of the 2026.08.27.1 unload with
release 1184 applied, still unmapped:

| ECO | occurrences in DANRE-mod |
|---|---|
| `ECO:0000366` | 22,456 |
| `ECO:0000364` | 8,608 |
| `ECO:0005547` | 264 |

## 6. Open questions

- **Should two jobs own one bucket at all?** The cleanest fix is
  probably that only one load owns the Noctua bucket — i.e. retiring
  `Load-GPAD-Noctua_w` at cutover, which ZFIN-10025 already contemplates.
  Until then the two are in a tug-of-war.
- **Should a validation failure be allowed to cause a removal?** Treating
  "row rejected" as "row absent" makes any parsing or lookup bug into
  silent data loss. Excluding rejected rows' existing annotations from
  the outdated set would make failures non-destructive, and is a much
  smaller change than re-architecting ownership.
- **Is `_details.txt` usable?** The report-only run produced a **328MB**
  file, which is neither readable nor practical to archive per build.
- **Session growth.** `GafLoadJob` never calls `session.clear()` during
  validation; the persistence context accumulates every entity for all
  459,621 rows. This was NOT the cause of the 13-hour hang (§4.2 was),
  but it remains a latent scaling concern.

## 7. Reference

- `GafService.findOutdatedEntries`, `removeEntries`, `getPublication`
- `GafLoadJob.execute` — per-source removal loop
- `DanreModSourceOrganization` — `assigned_by` → owning org
- `GpadParser.postProcessing` — ECO → GO evidence code
- ZFIN-10358 (DOI resolution), ZFIN-10025 (unified DANRE-mod load)
