---
marp: true
theme: default
paginate: true
header: "Preloaded per-feature dev stacks"
footer: "branch: preloaded-dev-stacks"
---
<!-- _class: lead -->
<!-- _paginate: false -->

# Preloaded per-feature dev stacks

### Spin up a full, data-loaded ZFIN in ~minutes — one per feature branch

A tour of the new dev workflow, how it works, and what each file does.

---

## The problem

Our stack is big: **Postgres + Solr + Tomcat + httpd + Jenkins**, all in Docker.

Bringing one up the old way meant:

- **Loading the DB from unloads + reindexing Solr** — *hours*.
- **One stack per machine** — two feature branches fought over the DB, ports `:443`/`:5432`, and the hostname.
- Every teammate re-did the same slow load.

We want **several feature branches running side-by-side**, each with real data, **now**.

---

## The idea in one picture

```
        SLOW, ONCE                          FAST, PER FEATURE
   ┌──────────────────────┐          ┌───────────────────────────┐
   │  loaded base stack   │  bake →  │ zfin-db-preloaded:<tag>    │
   │  (db + solr + app)   │          │ zfin-solr-preloaded:<tag>  │
   └──────────────────────┘          │ preloaded-app/<tag>/*.tgz  │
                                      └────────────┬──────────────┘
                                                   │ seed (copy)
                    z feature new ZFIN-1234        ▼
              ┌──────────────────────────────────────────────┐
              │ wt-zfin-1234/  ·  project zfin-1234           │
              │ own network · volumes · 127.0.0.X · <host>    │
              │ https://zfin-1234.zfin.test  ← data already in│
              └──────────────────────────────────────────────┘
```

**Bake the slow work into images once; every feature *copies* from them.**

---

## Mental model: three layers

| Layer | What it is | Built by |
|-------|-----------|----------|
| **Preloaded data images** | `zfin-db-preloaded` / `zfin-solr-preloaded` — a frozen, fully-loaded DB & Solr index | `z feature build-preloaded` |
| **Warm app snapshot** | `preloaded-app/<tag>/*.tgz` — deployed webapp, tomcat base, TLS certs, gradle/maven/npm caches | `z feature build-preloaded --app --caches` |
| **Feature stack** | A git worktree + its own isolated Compose project that *seeds* from the two above | `z feature new <ticket>` |

Everything below is machinery to make those three layers cheap to create and use.

---

## Quick start

```bash
# 0. once per machine: bake the images from a loaded base stack
z feature build-preloaded --app --caches

# 1. provision a feature (prompts for a ticket if omitted)
z feature new ZFIN-1234 --up

# 2. work in it
cd ../wt-zfin-1234
source .zenv/activate            # 'z' + zrun/zup/... now target THIS stack
zrun -c "gradle dirtydeploy"     # deploy your branch's changes on top
#   → https://zfin-1234.zfin.test

# 3. tear it down when the PR merges
z feature rm ZFIN-1234
```

---

## `.zenv` — venv-style activation

Each stack (base + every feature worktree) has a generated, git-ignored **`.zenv/`**:

```bash
source .zenv/activate     # ... work ...     deactivate
```

Activation exports the three variables Compose reads natively, so every
`docker compose` acts on **this** stack:

```
COMPOSE_PROJECT_NAME   COMPOSE_FILE   COMPOSE_ENV_FILES
```

…and puts **`z`** on `PATH` plus shell functions `zrun` `zexec` `zup` `zdown`
`zpull` `zlog` `zrestart` `zstatus` `zfeature` `zbuild`.

> No `.zenv` active → stack commands refuse and tell you to activate one.

---

## The `z` command family

| Command | Does |
|---------|------|
| `z run` / `zrun` | one-off command in a fresh `compile` container (login shell) |
| `z exec` / `zexec` | exec into the **running** container |
| `z up/down/pull/log/restart` | stack lifecycle (active stack) |
| `z status` / `zstatus` | active stack: name, url, containers (+ shared db/solr) |
| `z feature new / ls / rm` | provision / list / tear down feature stacks |
| `z feature build-preloaded` | bake the preloaded images + app/cache snapshots |
| `z shared up/down/status` | the shared `zfin_shared` data stack (see later) |
| `z build` / `zbuild` | hands-free phased build/deploy (the CI engine) |
| `z create-zenv` / `z fresh-install` | make a `.zenv` / guided day-zero setup |

Default service is `compile`. `zrun` with no args → interactive shell.

---

## How preloaded images work

The `postgres`/`solr` images declare a `VOLUME`. When a feature starts, Docker
**seeds** that volume by **copying the baked data** into a fresh per-project volume.

- It's a **full copy**, *not* copy-on-write: db copy ≈ **~20 GB**, solr ≈ **~10 GB**.
- So each feature is fully independent — write freely, break freely.
- `build-preloaded` runs **`pg_resetwal`** to drop retained WAL before freezing
  (dead weight in a frozen snapshot — reclaimed ~13 GB here).

> **Local-only images.** They carry real ZFIN data — bare tag names, **never pushed**.

---

## The warm app snapshot

Some volumes are **shared by multiple containers** (`www_data`, `catalina_base`,
`keystore`, `tls_certs`) so they can't use image-seeding (first-mounter-wins).
`--app`/`--caches` instead tar them to restore tarballs:

```
docker/preloaded-app/<tag>/
├── www_data.tgz        # the deployed webapp
├── catalina_base.tgz   # tomcat's working dir
├── keystore.tgz        # tomcat TLS keystore
├── tls_certs.tgz       # httpd cert + key
├── gradle_cache.tgz    # ~/.gradle  (skip re-downloading the world)
├── maven_cache.tgz     # ~/.m2
└── npm_cache.tgz       # ~/.npm   (warms `npm ci` for the first dirtydeploy)
```

`z feature new` **tar-extracts** these into the feature's volumes → the app is
already deployed and the build caches are warm on first boot.

---

## Anatomy of a feature stack

`z feature new ZFIN-1234` creates a fully isolated unit:

| Piece | Value |
|-------|-------|
| **Worktree** | `../wt-zfin-1234` (own branch, own source) |
| **Compose project** | `zfin-1234` |
| **Network** | `zfin-1234_default` |
| **Volumes** | seeded copies of db/solr + extracted app/cache tarballs |
| **Loopback IP** | `127.0.0.X` (auto-allocated, avoids `:443`/`:5432` clashes) |
| **Hostname** | `zfin-1234.zfin.test` (via `--hosts` / dnsmasq) |
| **`.env` + `.zenv`** | its own, derived from the base |

Multiple features coexist because **nothing is shared** — different IPs, networks, volumes.

---

## Sharing db+solr across features (`--shared-db`)

For **read-mostly** parallel work you can skip the ~30 GB copy and share one data tier:

```bash
z shared up                          # boot the ONE shared db+solr (project zfin_shared)
z feature new ZFIN-A --shared-db --up
z feature new ZFIN-B --shared-db --up   # both hit the SAME db/solr
```

**How:** the shared `db`/`solr` containers are **connected *into* each feature's
own network** (aliases `db`/`solr`). The feature's app tier stays *single-homed*.

> **Shared data = shared writes.** A migration/reindex/edit by one feature hits
> all sharers. Read-mostly only; needs its own schema? use a normal copy stack.

---

## Gotcha we hit: don't multi-home tomcat

The *obvious* design — put the feature's tomcat on the shared network too — **breaks it**:

- catalina sets `-Djava.rmi.server.hostname=$(container ip)`
- a container on **two** networks has **two** IPs → the second leaks in as a bare
  java arg → `Could not find or load main class 172.x` → tomcat exits.

**Fix:** keep the app tier single-homed; attach the *stateless* db/solr to the
feature's network instead (Postgres/Solr don't care about being multi-homed).
`ZfinUtil.connectSharedData()` does this on `z feature new` and `z up`.

---

## Networking & ports

Every published port is pinned to the stack's **loopback IP**, so stacks never collide:

```
127.0.0.2:443   base (zfin_org)      127.0.0.3:443   zfin-1234
127.0.0.2:5432  base db              127.0.0.3:5432  zfin-1234 db
127.0.0.2:9499  base jenkins         127.0.0.3:9499  zfin-1234 jenkins
```

- The compose default for each port is loopback-scoped (`127.0.0.1:PORT`), so an
  **unpinned** service can never grab `0.0.0.0:PORT` and block every other stack.
  *(This was the `zup jenkins` "port already allocated" bug.)*
- macOS needs `sudo ifconfig lo0 alias 127.0.0.X` per IP (Linux has them already).

---

## TLS: `*.zfin.test` (dev opt-in)

Feature hosts are `<slug>.zfin.test`, so the self-signed cert needs a matching SAN:

- `generate_base.sh` adds `DNS:*.zfin.test` **only when `ZFIN_DEV_CERT_SAN=true`**.
- Default is `false` → **staging/prod keep their real `*.zfin.org` certs untouched.**
- The wildcard cert is **baked into `preloaded-app`**, so every feature serves the
  *same* cert → trust it on your host **once**:

```bash
tar xzOf docker/preloaded-app/<tag>/tls_certs.tgz ./certs/zfin.org.crt > /tmp/zfin-dev.crt
sudo security add-trusted-cert -d -r trustRoot -p ssl \
     -k /Library/Keychains/System.keychain /tmp/zfin-dev.crt
```

---

## File map — the front door

```
docker/utils/
├── z                      ← the ONE executable (Groovy)
└── lib/
    ├── ZfinUtil.groovy    ← generic MECHANISM (runners, env, roots, helpers)
    ├── StackConfig.groovy ← ZFIN POLICY (image names, host, service roles, volumes)
    └── *.groovy           ← one class per command
```

**`docker/utils/z`** — self-locates, loads `ZfinUtil` + every command class through
one `GroovyClassLoader`, builds one `zfinUtil`, and calls `cmd.run(args, zfinUtil)`
**in-process** (no forked JVM, no env plumbing). Pure routing.

**`lib/ZfinUtil.groovy`** — generic *mechanism*: process/logging helpers,
`env()`/`dotenv()` readers, canonical roots, `connectSharedData()`, `tarImage()`,
`printHeader()`, `helpRequested()`. Every command receives it as a typed param.

**`lib/StackConfig.groovy`** — the ZFIN *policy* home: image names, `host(slug)`,
service roles, warm-volume classes (`APP_VOLS`/`CACHE_VOLS`), db health check. The one
file to change when ZFIN's conventions change (the "light seam").

---

## File map — command classes (`lib/`)

| File | Command | Purpose |
|------|---------|---------|
| `NewFeature.groovy` | `z feature new` | provision worktree + `.env` + `.zenv` + volumes, optional up |
| `BuildPreloaded.groovy` | `z feature build-preloaded` | bake db/solr images + app/cache tarballs from a loaded stack |
| `StackOps.groovy` | `z run/exec/up/down/…` | lifecycle ops on the active `.zenv` stack |
| `SharedStack.groovy` | `z shared up/down/status` | the shared `zfin_shared` data stack |
| `FeatureList.groovy` | `z feature ls` | list feature worktrees + state |
| `FeatureRemove.groovy` | `z feature rm` | tear a feature down (down -v, worktree, hosts) |
| `CreateZenv.groovy` | `z create-zenv` | generate a `.zenv/` |
| `Zbuild.groovy` | `z build` | phased build/deploy orchestrator (CI) |
| `FreshInstall.groovy` | `z fresh-install` | guided day-zero bootstrap |
| `z-completion.bash` | — | bash tab-completion |

---

## File map — Compose files

```
docker/
├── docker-compose.yml            base: db, solr, tomcat, httpd, compile, jenkins…
├── docker-compose.preloaded.yml  boot db+solr from the preloaded images
├── docker-compose.shared.yml     PROVIDER: run the shared zfin_shared db+solr
└── docker-compose.shared-db.yml  CONSUMER: suppress local db/solr, share zfin_shared
```

A stack is `base.yml` **+ one data overlay**, chosen at `z feature new` time:

- normal feature → `preloaded.yml` (its own copy)
- `--shared-db` feature → `shared-db.yml` (attaches to `zfin_shared`)

`COMPOSE_FILE` (set by `.zenv`) is the `:`-joined list; overlays merge onto the base.

---

## Everyday workflow

```bash
source .zenv/activate

zrun -c "gradle compileJava"       # compile
zrun -c "gradle dirtydeploy"       # compile + hot-deploy to tomcat
zrun -c "gradle liquibasePostBuild"# apply THIS branch's schema/solr deltas

zstatus                            # what's up, at what URL
zlog tomcat                        # tail logs
zrun                               # interactive shell in compile

deactivate
```

The app is already serving the base deploy from the warm snapshot — you only
deploy your branch's **delta** on top (fast, incremental).

---

## Teardown & gotchas

```bash
z feature rm ZFIN-1234    # down -v + worktree + branch + hostctl + lo0 alias
```

- **`--shared-db` teardown** also disconnects the shared db/solr from the feature
  network and removes it (foreign endpoints would otherwise block `down -v`).
- **sudo steps** (hostctl, `lo0` alias) prompt for your password.
- **Preloaded images / snapshots are local** — re-bake, never pull.
- **Feature branches fork from `main`**, so they only get the newest tooling/scripts
  after `preloaded-dev-stacks` merges (or if branched from it).

---

## Cheat sheet

```bash
# bootstrap (once)
z feature build-preloaded --app --caches

# per feature
z feature new ZFIN-1234 --up          # own copy of db/solr + warm app
z feature new ZFIN-9 --shared-db --up # share the zfin_shared data tier
z feature ls                          # list them
z feature rm  ZFIN-1234               # remove one

# inside an activated stack
zrun -c "gradle dirtydeploy"   zstatus   zlog tomcat   zup / zdown

# help
z feature help new            # per-flag help for any subcommand
```

---
<!-- _class: lead -->

## Learn more

- **`reference/preloaded-dev-stacks.md`** — the full written guide
- **`reference/build-and-docker.md`** — services, image layering, pipeline
- **`reference/deploying-changes.md`** — "what to run after editing X"
- **`CLAUDE.md`** — the `z` / `.zenv` tooling reference

### Questions? Every command self-documents:
```bash
z feature help new     z shared --help     z status
```
