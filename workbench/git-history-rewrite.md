# Git history: release-branch archival and the binary-stripping rewrite

Working analysis. **Nothing here has been executed** — no branch, tag or ref has been
modified. All figures measured 2026-09-17 against `canon/main` @ `b1e561ad7d`
(2,793 commits) after a fresh `git fetch canon --prune --tags`.

Two questions started this: can the old `release-*` branches be replaced by tags on
main, and — since a separate project will rewrite history to strip large binaries —
can we also flatten main to a straight line with release tags.

## Part 1 — Release branches

47 release branches on canon, `release-1139` … `release-1185`. Keep the newest
(`1180`–`1185`, which is **six**, not five); archive the other 41.

### The "identical commit on main" premise does not hold

The hope was that each release tip would have a content-identical commit somewhere on
main. Checked by comparing each branch tip's tree hash against all 2,793 main trees:

```bash
git rev-list canon/main --pretty=format:'%T %H' --no-commit-header > main-trees.txt
git rev-parse canon/release-1156^{tree}   # then grep
```

**0 of 47 match.** Only `release-1185` matches, and only because it is currently an
ancestor of main (0 commits ahead).

Two structural reasons, neither fixable by searching harder:

1. Release branches take hotfix commits directly on the branch. Those reach main
   separately — squash-merged PRs, re-commits — so trees and patch-IDs differ.
2. Main always moves ahead. A release tip's content is `fork point + its own changes`;
   main's content at every instant is `fork point + those changes + everything else`.
   The two lines are never simultaneously equal.

### The content is nonetheless safe

`git cherry` (patch-ID equivalence) over 1139–1179:

| | |
|---|---|
| release-only commits | 386 |
| patch-identical to a commit already in main | 308 |
| unmatched | 78 |
| branches with zero unmatched | 14 of 41 |

The 78 unmatched are mostly rebase/squash artifacts, not lost work. Verified by
auditing file content rather than patch hashes — for every path touched by an
unmatched commit, checked whether main ever held that blob at that path:

```bash
git log canon/main --pretty=format: --name-only --diff-filter=AMR | sort -u > main-paths-ever.txt
```

Files touched by release-only commits that main **never** held, across all 41 branches:

- `source/org/zfin/db/postGmakePostloaddb/1143/ZFIN-8504a.sql` (147 B)
- `source/org/zfin/db/postGmakePostloaddb/1144/ZFIN-8558.sql` (183 B)

Both are one-off Liquibase changesets applied at release time — expected for that
directory. (A third hit, `release-1170`'s `Regenerate-GenBank-BlastDBs_w/TODO.md`, was
added and deleted within the branch and is not at the tip.)

### Tag the tip, not `vNNNN`

There is already a precedent: `archive/release-1123` … `archive/release-1138` are tags
at the tips of previously deleted release branches. Continue that convention.

**Do not rely on the `vNNNN` tags.** They mark the release *cut*, and branches kept
taking hotfixes afterward:

| tag | commits on the branch after the tag |
|---|---|
| v1177 | 1 |
| v1178 | 1 |
| v1179 | 6 |
| v1180 | 2 |
| v1184 | 3 |
| **v1183** | **14 — and `v1183` is not even an ancestor of `release-1183`** |

`v1175`–`v1179` exist; `1139`–`1174` have no `vNNNN` tag at all.

### Aside: a release tip *can* be reconstructed from main, sometimes

Since the twins' diffs are often byte-identical, replaying them onto the fork point can
reproduce the branch tip exactly. Verified for `release-1156` with a scratch index (no
worktree, no refs touched):

```bash
export GIT_INDEX_FILE=/tmp/synth.index
git read-tree bf5ec84ea1                       # the fork point
for c in a4859fbea4 58ce040f55 01da52f3f5; do  # the three twins, in order
  git show --binary --pretty=format: $c | git apply --cached -
done
git write-tree
# 2fb2c01fb8f15156102ec07409f9c3c9e7441b96 == canon/release-1156^{tree}
```

Lands on the tip tree bit-for-bit; the intermediates match too. Ran the same
reconstruction across 1139–1179: **14 of 41 reconstruct exactly**, the other 27 have at
least one commit with no counterpart anywhere in main.

Note the condition is *not* that the first twin sits on the fork point — 1155, 1159,
1161, 1162, 1169, 1171, 1173 and 1175 reconstruct despite it sitting further along. All
that is required is a complete set of twins whose diffs apply cleanly in order.

## Part 2 — Repository weight

```
.git                3.0 GB      size-pack 3.00 GiB, 325,709 objects
reachable from main 3,210.1 MB  21,316 unique blobs (uncompressed)
working checkout    431.6 MB    7,473 files
```

The 47 release branches add only **158 blobs** over main alone — they are not the
problem, and archiving them costs essentially nothing.

### Where the 3,210 MB sits

| extension | MB | at main tip |
|---|---:|---|
| `.war` | 876.1 | **0 files** |
| `.jar` | 741.7 | 13 files, 14.8 MB |
| `.jpi` | 339.5 | **0 files** |
| everything else | 1,252.8 | — |

Three extensions = **61% of all history reachable from main**, and essentially none of
it exists at the tip. Sixteen `jenkins-*.war` at 60–92 MB each, `robot.jar` twice,
`logstash-1.1.13-flatjar.jar`, GWT dev jars, a Liquibase tarball.

### Projected savings

| filter | blobs | recovered | share |
|---|---:|---:|---:|
| every blob > 10 MB | 45 | 2,043.8 MB | 64% |
| build artifacts by extension | 923 | 2,071.3 MB | 65% |
| every blob > 1 MB | 319 | 2,723.5 MB | 85% |

**Removing 45 objects takes out almost two thirds of the repository.** That ratio argues
for starting narrow.

### What must survive

13 jars at the tip, 14.8 MB total — `gwt-servlet-jakarta-2.11.0.jar` (9.5 MB),
`agr_curation_api.jar`, `obo.jar`, `bbop.jar`, the eclipse-transformed set. Small enough
to stay in-tree.

Separately, ~260 MB of large *text* at the tip dominates a fresh clone once the jars go:

```
 45.22 MB  docker/solr/site_index/conf/all-term-contains-synonyms.txt
 45.22 MB  docker/solr/site_index/conf/all-term-contains-synonyms-reversed.txt
 37.10 MB  source/org/zfin/db/postGmakePostloaddb/1079/shipwreck/term
 36.60 MB  source/org/zfin/db/postGmakePostloaddb/1112/flankseq.csv
 35.07 MB  source/org/zfin/db/load/1098/ChickenDance/efs.csv
 30.28 MB  source/org/zfin/db/load/1098/ChickenDance/xpatex.csv
 26.61 MB  source/org/zfin/db/load/1098/ChickenDance/xpatres.csv
```

Worth deciding on now rather than in a second rewrite.

## Part 3 — History shape

```
all commits        2,793
first-parent only  2,670
merge commits         25   (0 octopus, 1 root)
off first-parent     123   (2021-06-14 .. 2024-01-09)
signed commits     1,360   (%G? = E; signatures will be stripped)
no .gitattributes, no submodules, no LFS
```

Main is already nearly linear. Dropping every parent after the first turns the 25 merges
into ordinary commits. Because a merge commit's tree **already is** the merged result,
this is **lossless in content at every point on the line** — it loses only the separate
identity of the 123 side-branch commits.

```bash
git filter-repo --commit-callback 'commit.parents = commit.parents[:1]'
```

Result: a strictly linear main of 2,670 commits, root to tip.

## Part 4 — The sequencing constraint

> **Tags must exist before the rewrite, not after.**

`git filter-repo` rewrites every ref it can see and remaps tags automatically. A tag
created *before* the rewrite survives it, pointing at the rewritten equivalent. A tag
created *after* would have to point at a pre-rewrite SHA that no longer exists.

The same logic condemns the intuitive first move: **deleting the 41 release branches
first**, to reduce the rewrite surface, makes their commits unreachable, so filter-repo
never sees them and the history we wanted to archive is gone.

**Archive first, prune second, rewrite third.**

## Part 5 — Plan

1. **Freeze and mirror.** `git clone --mirror`, kept offline and never pruned, plus
   `git show-ref > zfin-refs-preimage.txt`. Merge freeze on main and the six live
   release branches.
2. **Archive tags.** `archive/release-NNNN` at the tip of 1139–1179, matching the
   existing `archive/release-1123`…`1138` convention.
3. **Triage remaining branches.** Canon has 81; 47 are releases. Anything worth keeping
   gets a tag before it gets deleted.
4. **Decide the binary policy** (see Part 2 — the jars and the big CSVs).
5. **Strip binaries.** `git filter-repo --strip-blobs-with-ids big-blobs.txt` driven by
   an explicit blob list, not a glob, so the removed set is reviewable. Verify tip trees
   are byte-identical to the pre-image minus the intended paths.
6. **Flatten** (same pass as 5, so the team absorbs one hash change).
7. **Place release tags.** Options, ascending cost:
   - *Cut tags* — tag each release's fork point on the line. Available for all 41,
     trivially derived, but marks what main looked like at the cut, not what shipped.
   - *Selective reconstruction* — 14 of 41 replay exactly. Doing 14 and not 27 makes the
     record mean different things at different points.
   - *Graft the rest* — insert the 78 orphan commits at their chronological position.
     Needs per-commit conflict resolution and manufactures history that never happened.

   **Recommendation: cut tags on the line, archive tags off it.** The line then tells the
   truth about development and the tags tell the truth about releases, with neither
   pretending to be the other.

`git-filter-repo` is already installed on the workstation (`a40bce548d2c`).

## Risks

| risk | detail | handling |
|---|---|---|
| Every SHA changes | 2,793 new hashes. Jenkins configs, deploy records, ticket comments and reference docs citing a SHA stop resolving. | Keep the mirror online read-only; publish filter-repo's `commit-map`. |
| 1,360 signatures void | ~half of main is signed, mostly GitHub web-UI commits. | Accept and announce; nothing preserves signatures across a rewrite. |
| Everyone re-clones | No fetch or pull recovers. Local branches on old hashes must be rebased by hand. | Quiet window; publish the freeze date. |
| Forks diverge | `origin` and `upstream` carry 259 and 266 branches and will keep serving old objects. | Decide per fork: rewrite in step, or retire. |
| Two release-only files | The 1143/1144 changesets above. | Preserved by the archive tags; confirm they were deliberately release-only. |

## Open decisions

- Keep five branches or six? `release-1180`–`1185` is six.
- Removal threshold: 45 objects for 64%, or the 1 MB / 319-object cut for 85%?
- The Solr synonym files and ChickenDance CSVs — in-tree, LFS, or external?
- Release tags on the line: cut tags, selective reconstruction, or archive tags only?

## Suggested next step

Run the mirror + strip + flatten against a throwaway copy with **no remote configured**,
and report the true post-rewrite clone size. That validates the tooling and turns the 64%
projection into a measured number before anyone commits to a freeze window.
