# Feature branch lifecycle

How a feature branch is worked and wound down. The goal: every branch leaves
the codebase **cleaner and, where possible, smaller** than it found it.

## During development

- **`TODO.txt`** — a per-branch checklist of what must happen before the PR
  merges. Living scratch; not meant to survive. The last item is always
  *"delete this file."*
- **Scratch journey notes** — markdown files documenting the exploration: what
  was tried, why decisions were made, dead ends. These are for *you*, mid-flight.
  Before merge they get removed — but first **harvest the highlights** into real
  docs (code comments, `reference/`, the PR description, or an ADR).

Both are intentionally throwaway. Keeping them out of the merge is part of the
definition of done below.

## Parallel feature stacks

Work several tickets at once without branch-switching or reloading data. Each
feature gets its own git worktree **and** its own Docker Compose project, booted
from preloaded images. Provision with `docker/new-feature.sh <name>` (see
`docker/build-preloaded.sh` for how the preloaded images are made).

Mechanics, and the facts that are easy to get wrong:

- **Worktree per feature** — a worktree is a separate host directory, so each
  stack mounts *its own* source path. This is what removes the "source is
  mounted at one path" constraint.
- **The branch name is just a name.** `git worktree add ../wt-zfin-9002 -b
  ZFIN-9002 main` creates a local branch `ZFIN-9002` off `main`. A `feature/`
  prefix is only a naming convention (a legal slash in the ref name) — it is
  **not** a remote. `origin/main` is a remote-tracking ref; `feature/x` is not.
  We don't use a prefix here; name the branch whatever the ticket is.
- **`db`/`solr` resolve per Compose project, not per directory.** Compose gives
  each project its own network (`<project>_default`) with embedded DNS, so the
  hostname `db` resolves to *that project's* Postgres. What selects the project
  is `COMPOSE_PROJECT_NAME` / `-p` (which the worktree's `.env` sets) — not the
  filesystem path. So when the `compile` container is launched within the
  project (`docker compose -p zfin-9002 run --rm compile ...`), its `PGHOST=db`
  already points at that project's DB — **no gradle/task changes needed** for DB
  host resolution. Two projects can both have a service literally named `db`
  with no collision; the networks are isolated.
- **Loopback IP + hostname per feature.** Published ports bind to a dedicated
  `127.0.0.X`, and `<name>.zfin.test` maps to that IP, so URLs are clean
  (`https://zfin-9002.zfin.test`, no port). On Linux the whole `127.0.0.0/8` is
  already loopback — you only need name resolution (`/etc/hosts` or a dnsmasq
  `*.zfin.test` wildcard). On macOS you also need an interface alias:
  `sudo ifconfig lo0 alias 127.0.0.X`.
- **Preloaded gets you 99%; the branch delta layers on top.** The stack comes up
  already populated. If the branch changes schema, apply only its new changesets
  on this stack: `docker compose -p <proj> run --rm compile bash -lc 'gradle
  liquibasePostBuild'` (liquibase skips already-applied levels baked into the
  image). If it changes the Solr schema, reindex just the affected entity:
  `gradle solrIndex -PsolrEntities=<entity>` — not the ~36 min full rebuild.
- **Resource crunch?** `docker compose -p <proj> stop` frees RAM but keeps the
  stack's state; `start` resumes it. `down -v` discards its DB/Solr copy.

## Definition of done (before checking off the feature)

- [ ] **Tests exist and are good tests** — they'd actually fail if the behavior
      regressed, not just exercise happy paths for coverage.
- [ ] **Code is documented and existing docs are updated** — no stale docs left
      pointing at the old behavior.
- [ ] **Formatted, follows the style guide** — reads like the surrounding code.
- [ ] **Temporary artifacts removed from the branch** — `TODO.txt`, scratch
      notes, debug scripts, screenshots, stray logs, commented-out code. (Check
      `git status` for untracked/added cruft before opening the PR.)
- [ ] **I understand the code well enough to explain it to someone else** — no
      "it works but I'm not sure why" merges. (Candidate for a Claude *quiz*
      skill: have Claude question you on the diff.)
- [ ] **Adversarial review run** — `/code-review` (and `/security-review` when
      the change touches auth, input handling, or data exposure).
- [ ] **Left it cleaner than we found it** — opportunistic refactors near the
      change.
- [ ] **Left it smaller than we found it, if possible** — removed duplication /
      dead code; net-negative diffs are a win.

## End-of-session retrospective

A development session inevitably hits friction — a confusing setup step, a
convention Claude didn't know, a preference that came up mid-work. Before
wrapping a session, reflect briefly and **turn recurring friction into durable
convention** so it doesn't happen again:

- What caused friction this session?
- Does it warrant a change to docs (`reference/`), Claude's memory, or a
  project-wide `CLAUDE.md`?
- Capture it concretely, then apply it.

Examples of conventions captured this way:

- **Don't scatter scripts.** New scripts go in the established location for
  their domain (Docker ops in `docker/`, build logic in `buildfiles/`/`buildSrc/`,
  or a gradle task in `build.gradle`) — never left loose at the repo root.

## Related

- Preloaded per-feature dev stacks (instant DB + Solr, no ZFS):
  `docker/new-feature.sh`, `docker/build-preloaded.sh`,
  `docker/docker-compose.preloaded.yml`, and
  `docker/{postgresql,solr}/preloaded.Dockerfile`.
