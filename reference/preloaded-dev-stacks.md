# Preloaded per-feature dev stacks

Run several ZFIN feature branches at once, each in its own isolated Docker stack
that boots in seconds from **preloaded** images (a baked-in loaded DB + Solr index,
and optionally a warm app tier) — no `getdb`/`loaddb`/`getsolr`/`loadsolr` and no
first-time full deploy per feature. This doc is the map to the whole system built on
the `preloaded-dev-stacks` branch: what the pieces are, how they fit, and how to use
them.

> Orientation only — the authoritative details live in each script's header comment
> and in [build-and-docker.md](build-and-docker.md) / [deploying-changes.md](deploying-changes.md).

---

## The idea in one picture

```
  build-preloaded  ──bakes──▶  zfin-db-preloaded:<tag>     (loaded PGDATA)
  (run once per        │        zfin-solr-preloaded:<tag>   (built Solr index)
   machine, from a     └──▶     docker/preloaded-app/<tag>/ (deployed app, --app)
   loaded base stack)

  z feature new ZFIN-1234  ──▶  git worktree  +  per-feature .env  +  .zenv
                               +  loopback IP  +  /etc/hosts entry
                               +  a Compose project that boots from those images

  source .zenv/activate   ──▶  zrun/zup/zdown/… and bare `docker compose`
                               all target THIS feature; `deactivate` to exit
```

Each feature is a **separate Compose project**, so Docker already gives it its own
network, volumes, and intra-network DNS (`db`/`solr` resolve to *this* feature's
containers). The tooling supplies the host-side pieces Compose can't: which images to
boot from, a free loopback IP + hostname for clean URLs, and venv-style ergonomics.

---

## Why "preloaded" is a full copy, not copy-on-write

The preloaded images bake the whole PGDATA / Solr index into their layers. But
`postgres`/`solr` declare `VOLUME`, so on a feature's first `up` Docker **seeds the
per-project named volume by COPYING** the image's baked data into it — a plain
filesystem copy at volume-create time, **not** copy-on-write. So disk cost is roughly:

```
  preloaded images (once)   ~20G db  + ~10G solr
    stock base layers          0.7G       1.3G   (shared read-only, NOT copied per feature)
    baked data (PGDATA/index)  ~19G       ~9G    (~97% of the db image)
  + per feature stack        ~19G db  +  ~9G solr  (a full copy of the baked data)
  + warm app (optional)      ~0.8G www_data + ~0.25G catalina_base
                             + ~1-2G gradle cache (--caches; incl. the Maven Central jars)
```

Note the db image (~20G) and a feature's copy (~19G) are nearly equal: the image is
almost entirely PGDATA, so the seed copies essentially all of it — only the ~0.7G stock
base layers are shared. These are *measured* numbers after the always-on WAL trim:
`build-preloaded` `pg_resetwal`s on **every** build, which took this host's db image
**32.6G → 19.7G** — the reclaimed ~13G was retained WAL (dead weight in a frozen
snapshot; it had overshot the 10G soft `max_wal_size` during the load). `--slim` drops
reloadable jobs-only tables for ~3.8G more (→ ~16G). The remaining ~19G is real data.

Three features ≈ 90G+. The win is **instant boot + full isolation on plain Docker**,
paid for in disk. See [../workbench/db-slim-candidates.md](../workbench/db-slim-candidates.md)
for the `--slim` levers and `TODO.txt` for the CoW-filesystem / shared-DB / archive
directions that would cut this.

---

## The pieces

**`docker/utils/z`** is the single front door (Groovy) — the only executable. Everything
else lives in **`docker/utils/lib/`**; you invoke it as `z <cmd>` (or a short-name
function like `zrun` after activation).

| File | Role |
|------|------|
| `z` | The front door (Groovy). Self-locates, builds one `ZfinUtil`, and routes each command to its class — run in-process. |
| `lib/ZfinUtil.groovy` | Shared class: process/logging helpers, canonical roots, and the preloaded-volume contract (`APP_VOLS`/`CACHE_VOLS`) — the single source both producer and consumer read. |
| `lib/StackOps.groovy` | The stack lifecycle family: `run`/`exec`/`up`/`down`/`pull`/`log`/`restart`/`status`. |
| `lib/CreateZenv.groovy` | Generate a `.zenv/` (venv-style activation). Bootstrap via `z create-zenv`. |
| `lib/NewFeature.groovy` | Provision a feature: worktree + `.env` + `.zenv` + IP + hosts + boot (`z feature new`). |
| `lib/BuildPreloaded.groovy` | Bake the preloaded images (+ `--slim`/`--app`/`--caches`); WAL trimmed on every build (`z feature build-preloaded`). |
| `lib/Zbuild.groovy` | Non-interactive, phased build/deploy orchestrator — the CI engine (`z build`; what GoCD stages should call). |
| `lib/FreshInstall.groovy` | Guided day-zero setup on a bare workstation (`z fresh-install`). |
| `lib/z-completion.bash` | bash tab-completion for `z` + the short names. |

No command self-locates: `z` resolves its install dir once, then loads `ZfinUtil` + every
command class through one `GroovyClassLoader` (so `ZfinUtil` is a single `Class`), and calls
`cmd.run(args, zfinUtil)` in-process — the helpers + roots arrive as a typed parameter. Stack
ops require an active `.zenv`; `z build`/`z create-zenv`/`z fresh-install` don't (CI/bootstrap).

Compose overlay: **`docker/docker-compose.preloaded.yml`** points `db`/`solr` at the
preloaded images (`pull_policy: never`, `build: !reset null` so a missing image fails
loudly), and shrinks solr for feature stacks (`SOLR_HEAP: 4g` / `mem_limit: 6g`).

Preloaded image Dockerfiles: `docker/postgresql/preloaded.Dockerfile`,
`docker/solr/preloaded.Dockerfile` (`ADD` the captured tarball onto the stock base).

---

## venv-style activation (`.zenv`)

There is no `z` on `PATH` by default. A stack's `.zenv/` (generated, git-ignored) carries
an `activate` script and a `bin/` with a single `z` symlink → `docker/utils/z`:

```bash
source .zenv/activate     # z on PATH + short-name functions; this stack targeted; `deactivate` to exit
```

`activate` puts `z` on `PATH`, defines the short-name shell functions (`zrun() { z run
"$@"; }`, …), and exports the three env vars Docker Compose reads natively, so bare
`docker compose` — and every z-command — resolves to this stack with no `-p`/`-f`/
`--env-file`:

| env var | controls | flag it replaces |
|---|---|---|
| `COMPOSE_PROJECT_NAME` | which stack | `-p` |
| `COMPOSE_FILE` | which compose file(s), `:`-separated | `-f … -f …` |
| `COMPOSE_ENV_FILES` | the env file for substitution | `--env-file` |

The main checkout has a `.zenv` for the base `zfin_org` stack; feature worktrees get
theirs from `z feature new` (which calls `create-zenv`).

---

## Everyday workflow

```bash
# 0. once per machine: bake the preloaded images from a loaded base stack
z feature build-preloaded --tag dev            # WAL always trimmed; + --slim (drop jobs-only tables)
                                              #   + --app (warm app tier) + --caches (warm gradle cache)

# 1. provision a feature (prompts if no ticket)
z feature new ZFIN-1234 --up --hosts           # worktree, .env, .zenv, IP, /etc/hosts, boot

# 2. work in it
cd ../wt-zfin-1234
source .zenv/activate                         # commands now target zfin-1234
zrun -c "gradle dirtydeploy"                  # deploy this branch's delta (warm app)
# ...edit / dirtydeploy loop...
deactivate

# 3. tear it down once the PR is merged (see below)
```

### Warm app tier (`--app`)

By default a feature boots only the **data tier** (db + solr, instant) and you build +
deploy the webapp once (~10–20 min the first time). `build-preloaded --app` removes
that step too: it captures the base's **deployed** app volumes so a feature's
`tomcat`/`httpd` come up already serving.

Why this needs a different mechanism than db/solr: `pg_data`/`solr_var` are
**single-owner** volumes, so baking them into the db/solr image and letting Docker
seed on first mount works cleanly. The deploy volumes are **shared** across many
services at the same path:

| volume | mount | services |
|---|---|---|
| `www_data` | `/opt/zfin/www_homes/zfin.org` (TARGETROOT) | compile, db, httpd, certbot, tomcat, tomcatdebug, jenkins |
| `catalina_base` | `/opt/zfin/catalina_bases/zfin.org` | compile, tomcat, tomcatdebug, jenkins, filebeat |
| `keystore` | `/opt/apache/apache-tomcat/conf` | compile, tomcat, tomcatdebug |
| `tls_certs` | `/opt/zfin/tls` | compile, httpd |

Docker's implicit image→volume seeding is **first-mounter-wins** and copies from *that*
container's image — for a shared volume the seeder is ambiguous and usually has nothing
at the path, so it seeds empty. So instead of baking these into an image,
`build-preloaded --app` tars them to **restore tarballs** under
`docker/preloaded-app/<tag>/`, and `new-feature` **explicitly** pre-creates each
per-feature volume and extracts the tarball into it *before* `up`. Deterministic, and
every sharing container sees the deployed app.

**Version skew / warm start:** the baked deploy is the *base* branch's code at capture
time. A feature boots serving that, then `gradle dirtydeploy` overlays just its branch
delta (and `gradle liquibasePostBuild` any schema/Solr delta). It's a warm start, not
the final state — rebake as the base moves. Opt out per-feature with `--no-app`.

**Warm build caches (`--caches`):** the first `gradle dirtydeploy` on a fresh feature
otherwise re-resolves/downloads every dependency into an empty `gradle_cache`/
`maven_cache`. `build-preloaded --caches` captures those two volumes (into the same
`docker/preloaded-app/<tag>/`) and `new-feature` restores them by the identical
pre-create-and-extract path — so the first deploy is fast too. They're larger and
churnier than the deploy volumes, so it's a distinct opt-in; skip per-feature with
`--no-caches`. Restored regardless of `--up` (the `compile` container mounts them
on-demand, not as part of the served stack).

---

## Teardown

Do this only once the feature's PR is merged — `down -v` discards the stack's DB/Solr/app
copy and `git worktree remove` discards uncommitted work. From the activated `.zenv`:

```bash
docker compose down -v            # containers + network + per-feature volumes
deactivate
git worktree remove <worktree>    # add --force if dirty
sudo hostctl remove <slug>        # drop the /etc/hosts profile
sudo ifconfig lo0 -alias <ip>     # (macOS only) drop the loopback alias
git branch -d <branch>            # optional; refuses if unmerged
```

(`new-feature` prints these exact steps, filled in, at the end of provisioning. A
`z feature rm` command to automate this is on the `TODO.txt` backlog.)

---

## Platform notes

- **Architecture:** preloaded images inherit the arch of their stock base. On Apple
  Silicon build the stock base arm64-native, and let `configure`/`zbuild` build the
  `base` image **first, alone**, so `compile`/`jenkins` (which `FROM` the registry
  base) resolve the local arm64 base instead of pulling amd64 (the "grapefruit"
  file-watcher errors are the amd64-under-emulation tell).
- **macOS loopback:** any `127.0.0.X` other than `.1` needs `sudo ifconfig lo0 alias
  <ip>` before published ports can bind (Linux treats all of `127/8` as loopback, so
  it's a no-op there). `new-feature` adds it automatically.
- **Memory:** each feature solr is capped lean (`4g` heap / `6g` limit) via the
  overlay; the base `zfin_org` keeps prod sizing (`12g`/`16g`). Running several
  prod-sized solr at once OOM-kills them (exit 137) — stop idle stacks' solr or lower
  the cap. See `TODO.txt` (shared solr).

---

## Data-sensitivity guardrail

Preloaded images carry a **real loaded ZFIN database**. They are deliberately
**local-only**: bare image names (no `ghcr.io/` prefix), no push capability, baked
per-machine by `build-preloaded`. The warm-app tarballs (`docker/preloaded-app/`) are
git-ignored for the same reason. Do not add a push path — keeping loaded data off any
registry is a structural choice, not an oversight.

---

## Related docs

- [build-and-docker.md](build-and-docker.md) — Docker services, image layering, deploy pipeline
- [deploying-changes.md](deploying-changes.md) — what to run after editing X
- [../workbench/feature-lifecycle.md](../workbench/feature-lifecycle.md) — earlier lifecycle notes
- [../workbench/parallel-features-walkthrough.md](../workbench/parallel-features-walkthrough.md) — narrative walkthrough
- [../workbench/db-slim-candidates.md](../workbench/db-slim-candidates.md) — slimming the preloaded DB
- `TODO.txt` — the open backlog (CoW clones, shared DB, `z feature rm`, …)
