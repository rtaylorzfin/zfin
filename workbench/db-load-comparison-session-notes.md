# DB load-comparison work — session notes & operational findings

_Notes from the 2026-08 sessions that built the before/after DB comparison tooling.
The tooling itself is documented in `doc/compare-database-loads.md`; the churn
follow-up is in `workbench/surrogate-id-churn-audit.md`. This file captures the
environment/operational learnings that aren't obvious from the code._

## What got built (branch `zfin-compare-db-loads`, not yet pushed as of 2026-08-17)

Commits, oldest→newest:
- `cdcb9b0f8b` base before/after table comparison (snapshot + compare + `compareLoads` gradle task + Jenkins job)
- `3a728a7f67` exact row churn (per-row hashes) + backward daily-sweep driver
- `f765fc0a39` CLI args, robust resume, unchanged-since analysis
- `b6999f79b7` per-phase progress output during the sweep
- `76c975debb` `drill-table-churn.sh` (real vs surrogate/metadata churn)

## Environment / gotchas (the non-obvious stuff)

- **`gradle` on the host clone is broken**; run gradle **inside `dazed-jenkins-1`**. The
  host `buildSrc/build` classes are owned by another user (`go`), so host gradle can't
  delete/rebuild them (`Unable to delete directory .../buildSrc/build/...`). The Jenkins
  container is the real load environment anyway (`DBNAME`, `PGHOST=db`, `SOURCEROOT`,
  gradle + pg client tools all present). Use `docker exec dazed-jenkins-1 bash -lc '…'`
  (login shell required to load env; it prints a `Loading properties…` banner that must be
  stripped when parsing psql output).
- **`build/` is container-owned.** Host-side `mkdir`/writes under `build/` fail with
  EACCES; all output writes in the sweep driver go through the container. The host only
  stages dumps to `LOCAL_UNLOADS` (`/research/zunloads/databases/zfindb`, which it owns).
- **The DB volume shares the root filesystem.** `/var/lib/docker` is on `/`, same as
  `/research/zunloads`. Disk is the binding constraint for anything storing per-row hashes
  (~3 GB per full-DB snapshot). Freed ~15.8 GB via `docker image prune -a` + build-cache
  prune (running stack + named volumes untouched); no user files or the Solr volume
  deleted. Keep an eye on `df -h /`.
- **`~/remotes/cell` (sshfs) drops.** It silently unmounted between sessions, which stalled
  the `--days 10` sweep at the first un-staged day (staging `die`s) and blocked drilling
  into August dumps. There are usable **local** dumps under `/research/zunloads/databases/
  zfindb/` (e.g. 2026.07.05, 07.06) — pass `--mount /research/zunloads/databases/zfindb`
  to work without the sshfs mount. TODO worth doing: a fail-fast mount check in the sweep
  driver so it errors clearly instead of dying mid-run.
- **`gradle loaddb` is destructive**: drops + recreates the DB (`build.gradle`
  `loadDatabase`), `pg_restore -j 8` of a ~1.3 GB custom-format `.bak`, ~15 min each. The
  sweep leaves the *oldest* day loaded when it finishes.
- **Load-Database Jenkins job** selects the dump via a free-text `BACKUP_PATH` string param
  + a `LIST_ONLY` bool that lists dated dirs — **not** a dropdown (no Active Choices
  plugin). The new `Compare-Database-Loads` job mirrors that (two `*_PATH` params).

## Bugs found & fixed this session (so they don't recur)

- **`| head` under `set -o pipefail` → SIGPIPE (exit 141).** Hit twice (compare + the
  unchanged-since console summary). `head` closes the pipe, upstream `awk` gets SIGPIPE,
  pipefail turns it into a failure, `set -e` aborts. Fix: limit rows **inside awk**
  (`++n<=40`), never `awk … | head`. Also a latent `ls *.bak | head -1` → replaced with a
  glob.
- **`ORDER BY <output-alias>` in a `\copy (SELECT md5(t::text) AS h … ORDER BY h)`** fails:
  the alias isn't resolvable there. Repeat the expression: `ORDER BY md5(t::text)`.
- **psql `\copy` does NOT interpolate `:'var'`** — only server-side `COPY` / `\echo` do.
  Since the CSVs live on the build host (client side), the comparator inlines paths into a
  heredoc via the shell instead of psql vars.
- **Login-shell banner corrupts parsed output.** `docker exec … bash -lc` emits
  `Loading properties from …`; strip it (`grep -v 'Loading properties'`) before `read`-ing
  psql results.

## Design decisions worth remembering

- **Content hash = md5 of per-row md5s aggregated in hash order** (`ORDER BY md5(row)
  COLLATE "C"`), not ordered by PK/all-columns. Deterministic, key-agnostic, and avoids
  `ORDER BY` failures on PK-less tables with unorderable types (json/xml). `COLLATE "C"`
  makes it match a plain `comm`/`LC_ALL=C sort` of the per-row hash files.
- **Exact churn needs two days' per-row hash *sets* on disk at once** (~6 GB). A compact
  sketch (MinHash) can't reliably see a 1-in-550k change, which is the whole point ("1 row
  dropped + 1 added"), so exact hashing on disk is required — hence the disk work above.
- **Resume is driven by the durable snapshot CSVs, not the prunable hash dirs.** Snapshots
  are tiny and kept forever; hash dirs are ~3 GB and pruned as the window slides. Each
  day's snapshot+hashes are promoted atomically (`.tmp`/`.partial` → rename, snapshot CSV
  last as the "day done" sentinel), so an interrupted day never looks complete.
- **`unchanged_since` is derived from the accumulated snapshots**, so it spans every sweep
  ever run; `<= oldest` lower bounds tighten into exact `= date` as history deepens, with
  no report rewriting. This is why we chose "compute from snapshots" over "maintain a
  running state file" or "rewrite the in-between reports".

## Real results captured (for reference)

- 2026.07.05→07.06 single pair (`compareLoads`): 418 unchanged / 97 changed / 8 added /
  3 removed. Many `changed` tables had delta≈0 (full rebuilds).
- 3-day sweep 08.11→08.13: ~7.5M rows_added ≈ ~7.5M rows_removed **per day**, almost all
  from fast-search/warehouse rebuild tables — the churn that motivated the surrogate-id
  audit. Genuine edits stood out clearly (e.g. `term_relationship` 6/6, `curation`
  +811/−9 real).

## Follow-ups (not yet done)

- Run the DB-heavy drill-down/FK confirmation across the 15 top churners (see the audit doc).
- Full 10-day sweep once the sshfs mount is stable (resumes from the 3 trial days).
- Fail-fast mount check in `compare-loads-series.sh`.
- Move `compareLoads` out of `build.gradle` into the `zfin-utils` command.
- Possibly fold meaningful-column hashing into the sweep so reports carry real churn
  directly (costs more per-row hash storage).
