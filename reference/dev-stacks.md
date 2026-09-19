# Per-feature dev stacks

Run several ZFIN feature branches at once, each in its own isolated Docker stack that boots in
minutes from a **seed** — a captured copy of a loaded DB, Solr index and deployed app tier — with
no `getdb`/`loaddb`/`getsolr`/`loadsolr` and no first-time full deploy per feature. Each stack is
reachable at its own URL through a shared review proxy. This doc is the map: what the pieces are,
how they fit, and how to use them.

> Orientation only — the authoritative details live in each script's header comment
> and in [build-and-docker.md](build-and-docker.md) / [deploying-changes.md](deploying-changes.md).

---

## The idea in one picture

```
  z seed create    ──captures──▶  $ZFIN_ARCHIVE_DIR/seeds/<tag>/
  (once per host, from            pg_data.tgz  solr_var.tgz   (loaded DB + Solr index)
   a DEPLOYED stack)              www_data.tgz catalina_base.tgz ...  (the app tier)
                                  seed.json     (checksums + the postgres major)

  z review up      ──▶  ONE proxy per host, routing https://<slug>.<zone> to the
                        right stack. Standalone, or behind an existing nginx-proxy.

  z feature new ZFIN-1234  ──▶  git worktree  +  per-feature docker/.env
                               +  a hostname under the review zone (ONE wildcard DNS record,
                                  not an entry per stack)
                               +  a Compose project whose volumes are RESTORED from the seed
                                  before anything starts

  ./z <cmd>  in that tree ──▶  every stack op targets THIS feature, read from its
                               docker/.env; nothing to activate, nothing to deactivate
```

Each feature is a **separate Compose project**, so Docker already gives it its own
network, volumes, and intra-network DNS (`db`/`solr` resolve to *this* feature's
containers). The tooling supplies the host-side pieces Compose can't: the data to restore,
a hostname under the shared review zone, and a proxy that routes to it.

---

## What a stack costs, and why

A stack's data is restored from a seed rather than seeded by Docker from an image. `z seed create`
captures a loaded stack's volumes as compressed tarballs plus a manifest; `z feature new --seed`
extracts them into the new stack's volumes, in parallel, before anything starts.

```
  seed (once per host)      ~3.5G db + ~3.2G solr + ~0.3G app tier, compressed
  + per feature stack       ~17G db  + ~6G solr   (expanded, a full private copy)
  + build caches (optional) ~1-2G gradle/maven/npm
```

The per-stack copy is unavoidable on plain Docker: postgres and solr each own their data
directory, so two stacks cannot share one. What the seed changes is the *one-time* cost and where
it lives. The seed is ~7G of ordinary files, so it can sit on NFS; the preloaded images it
replaced were ~30G that could only live in the local Docker image store, because overlayfs needs
a local upper directory.

It is also faster. Measured on a shared VM restoring the same ~21G: **79.9s** from tarballs
against **~170s** for Docker seeding a volume from an image, because tar streams where the daemon
copies file by file. The images had no offsetting advantage — layer sharing does not apply when
`VOLUME` forces a full copy into every stack anyway.

`z seed create` trims WAL before capturing (`pg_resetwal` after a clean shutdown), which on this
host took a snapshot from 32.6G to 19.7G. The reclaimed space was retained WAL: dead weight in a
frozen snapshot, having overshot `max_wal_size` during the load.

To reclaim a stack's disk without losing it, `z feature freeze` archives its volumes and
`z feature thaw` restores them — the same capture and restore code the seed uses.

---

## The pieces

**`docker/utils/z`** is the single front door (Groovy) — the only executable. Everything
else lives in **`docker/utils/lib/`**; you invoke it as `./z <cmd>` from anywhere inside a
checkout or worktree.

| File | Role |
|------|------|
| `z` | The front door (Groovy). Self-locates, builds one `ZfinUtil`, and routes each command to its class — run in-process. |
| `lib/ZfinUtil.groovy` | Shared class: process/logging helpers, canonical roots, and the volume capture/restore contract (`APP_VOLS`/`CACHE_VOLS`/`DATA_VOLS`) — the single source seed, freeze, thaw and new-feature all read. |
| `lib/StackOps.groovy` | The stack lifecycle family: `run`/`exec`/`up`/`stop`/`down`/`pull`/`log`/`restart`/`status`. |
| `lib/SharedStack.groovy` | The shared data stack (`z shared up`/`down`/`status`) that `--shared-db` features attach to. |
| `lib/NewFeature.groovy` | Provision a feature: worktree + `docker/.env` + boot (`z feature new`). |
| `lib/FeatureFreeze.groovy` / `FeatureThaw.groovy` | Park a stack's volumes to an archive, and restore them (`z feature freeze`/`thaw`). |
| `lib/FeatureSession.groovy` | Archive the Claude sidecar's history so it outlives the stack (`z feature session`). |
| `lib/ReviewStack.groovy` | The shared routing proxy (`z review up`/`down`/`status`/`cert`). |
| `lib/Scaffold.groovy` | Create the recommended dev-tree layout (`z scaffold`). |
| `lib/StackConfig.groovy` | ZFIN-specific policy: image names, volume contracts, service roles. |
| `lib/FeatureList.groovy` | List feature stacks (`z feature ls`). |
| `lib/FeatureRemove.groovy` | Tear a feature down: down -v + worktree/branch/hosts (`z feature rm`). |
| `lib/Seed.groovy` | Capture a loaded stack's volumes as a reusable seed, and list/remove them (`z seed create\|ls\|rm`); WAL trimmed on every capture. |
| `lib/Zbuild.groovy` | Non-interactive, phased build/deploy orchestrator — the CI engine (`z build`; what GoCD stages should call). |
| `lib/FreshInstall.groovy` | Guided day-zero setup on a bare workstation (`z fresh-install`). |
| `lib/z-completion.bash` | bash tab-completion for `z`; source it from `~/.bashrc`. |
| `check` | Static checks: compiles every command class, and flags helper calls that are neither aliased nor qualified. Run it after editing `lib/`. |

No command self-locates: `z` resolves its install dir once, then loads `ZfinUtil` + every
command class through one `GroovyClassLoader` (so `ZfinUtil` is a single `Class`), and calls
`cmd.run(args, zfinUtil)` in-process — the helpers + roots arrive as a typed parameter. Stack
ops auto-detect their target from the cwd; `z build`/`z scaffold`/`z fresh-install` need none (CI/bootstrap).

**Compose files.** The base `docker-compose.yml` defines every service; five small overlays
each encode one orthogonal choice, and no stack uses more than two:

| file | decides |
|---|---|
| `docker-compose.review.yml` | the proxy stack itself (its own project, `zfin_review`) |
| `docker-compose.review-upstream.yml` | that proxy runs behind another nginx-proxy |
| `docker-compose.review-member.yml` | this stack is routed through the proxy |
| `docker-compose.shared.yml` | this project is the shared data **provider** (`zfin_shared`) |
| `docker-compose.shared-db.yml` | this stack **consumes** shared data instead of its own |

They cannot collapse into profiles: `ports: !reset null` targets a key while profiles gate whole
services, and an `external` network named by a variable cannot be conditionally attached.
`shared-db.yml` is the one case where the service *is* the unit, which is why it is five lines.

---

## How a stack is targeted

There is nothing to activate. Stack ops (`run`/`exec`/`up`/`stop`/`down`/`pull`/`log`/
`restart`/`status`) resolve the checkout root via git, read its **`docker/.env`**, and target
the stack that file describes for that one invocation — announced on stderr:

```bash
./z run -c "gradle dirtydeploy"    # >> targeting 'zfin-10454' (zfin-10454)
```

One stack per checkout or worktree, so there is nothing to search for. An explicit
`COMPOSE_PROJECT_NAME`/`COMPOSE_FILE` in the environment (as GoCD sets) still wins.

### The per-feature `.env` is the whole record

A stack is described entirely by its own `docker/.env`:

| key | what it fixes |
|---|---|
| `COMPOSE_PROJECT_NAME` | which Docker project — and what makes the directory a stack at all |
| `ZFIN_COMPOSE_OVERLAYS` | which compose overlays it was built with, `:`-separated |
| `ZFIN_SEED` | the seed its volumes were restored from |
| `DOCKER_VIRTUAL_HOST` | its hostname under the review zone |
| `ZFIN_PORT_OFFSET` | its offset for the few genuinely non-HTTP published ports |

`ZFIN_COMPOSE_OVERLAYS=` (present but **empty**) means base compose only — what the main
checkout wants. Omitting the key entirely falls back to the feature-stack overlay pair, so
stacks provisioned before it was recorded still tear down correctly.

This replaced a `.zenv/` bundle that kept a frozen **copy** of the tooling and compose files in
every worktree, so a feature survived the main checkout switching branches. The copy was real
protection and a real cost: it went stale silently, and a stale bundle surfaced as "no such
service", or a usage error for a flag that existed, rather than as "your copy is old". One
source of truth is worth more than that protection now the tooling is on `main`.

---

## Everyday workflow

```bash
# 0. once per host: capture a seed from a DEPLOYED stack (an instance, or a built feature)
z seed create --from coral --tag dev           # WAL always trimmed; --app is ON by default
                                               #   add --caches for warm gradle/maven/npm
z review up                                    # the proxy every stack is routed through

# 1. provision a feature -- on a TTY this PROMPTS for the whole plan (base, tag, shared
#    db, boot, hosts, npm ci, dirtydeploy, liquibase, tmux) and then RUNS it. Every
#    prompt is Enter-able and any flag you pass skips its own question.
z feature new ZFIN-1234                       # worktree, docker/.env, hostname, boot, deploy

# ...or say it all on the command line and skip the questions:
z feature new ZFIN-1234 -y --up --deploy --tmux

# a branch that already exists (yours, or a colleague's PR you're reviewing): --existing-branch
# checks it out instead of cutting a new one, and --base is moot. Interactively you're asked
# this whenever the branch exists, so the flag matters for -y / scripted runs. A branch known
# only to origin counts -- the worktree gets a local branch tracking it.
z feature new ZFIN-1234 -y --existing-branch --up
z feature new review-pr -y --existing-branch --branch someones-branch --up

# 2. work in it -- with --tmux you are ALREADY here: z attaches you to a session named
#    after the slug, sitting in the worktree. (This is the one step z can't do for your
#    current shell: `cd` changes the CALLING shell, which a child process cannot reach.
#    Hence the session.)
./z run -c "gradle dirtydeploy"               # deploy this branch's delta (warm app)
# ...edit / dirtydeploy loop...

# ...without --tmux, just cd there -- every ./z command then targets this stack:
cd $ZFIN_DEV_ROOT/worktrees/zfin-1234

# 3. tear it down once the PR is merged (see below) -- `z feature rm` also kills the
#    tmux session, so nothing is left pointing at a worktree that no longer exists.
```

### The app tier, and why every volume restores the same way

A seed captures the **deployed** app volumes alongside the data, so a feature's `tomcat` and
`httpd` come up already serving rather than needing a 10-20 minute first build. `--app` is on by
default for exactly that reason; `--no-app` skips it.

This matters more than convenience, because Docker's implicit image-to-volume seeding could never
have done it. That mechanism is **first-mounter-wins** and copies from *that* container's image —
and these volumes are shared by many services at the same path:

| volume | mount | services |
|---|---|---|
| `www_data` | `/opt/zfin/www_homes/zfin.org` (TARGETROOT) | compile, db, httpd, certbot, tomcat, tomcatdebug, jenkins |
| `catalina_base` | `/opt/zfin/catalina_bases/zfin.org` | compile, tomcat, tomcatdebug, jenkins, filebeat |
| `keystore` | `/opt/apache/apache-tomcat/conf` | compile, tomcat, tomcatdebug |
| `tls_certs` | `/opt/zfin/tls` | compile, httpd |

For a shared volume the seeder is ambiguous and usually has nothing at that path, so it seeds
empty. Restoring from tarballs sidesteps the question entirely: `z feature new` creates each
volume and extracts into it *before* `up`, so every sharing container sees the same deployed app.
Once db and solr restore the same way, one mechanism covers all of them — and the images that
used to carry db/solr stop being a special case.

**A seed with no app tier still works, but costs you the first build.** Capturing from a
data-only project (`zfin_shared`, say) yields empty `www_data`, and httpd then dies at startup
with an Apache config error naming nothing useful — it includes
`$TARGETROOT/server_apps/apache/inc-redirect` and cannot find it. `z seed create` warns when it
sees this, and the fix is to capture from a stack that has been deployed.

**Version skew:** the captured deploy is the source branch's code at capture time. A feature boots
serving that, then `gradle dirtydeploy` overlays its own delta (and `gradle liquibasePostBuild`
any schema or Solr delta). It is a warm start, not the final state — re-capture as the base moves.

**Build caches (`--caches`):** the first `dirtydeploy` on a fresh feature otherwise re-downloads
every dependency into an empty `gradle_cache`/`maven_cache`. Capturing them makes the first deploy
fast too, at the cost of a larger seed — so it is opt-in, and restored regardless of `--up`, since
`compile` mounts them on demand rather than as part of the served stack.

---

## Sharing db+solr across features (`--shared-db`)

Read-mostly features (UI/JSP/React work, browsing) don't each need their own ~19-32G db +
~9G solr copy. `--shared-db` points them at one shared copy:

```bash
z shared up                             # restore a seed into db+solr ONCE (project zfin_shared,
                                        #   net zfin_shared_net) -- one copy for all sharers
z feature new ZFIN-1 --shared-db --up
z feature new ZFIN-2 --shared-db --up   # both reach the SAME db/solr
z shared status                         # what's attached;  z shared down [--rm-data] to stop
```

How it works: the dedicated `zfin_shared` stack runs db+solr once. A `--shared-db` feature
uses `docker-compose.shared-db.yml`, which
profile-suppresses its own db/solr so no per-feature copy is seeded. To reach the shared
data, `z feature new --shared-db` (and `z up`) **connect the shared db/solr containers into
the feature's own default network** with aliases `db`/`solr`, so the webapp's `db`/`solr`
hostnames resolve to them. The feature's app tier stays *single-homed*: an earlier design
attached the app tier to the shared network instead, but multi-homing the tomcat container
is fatal — catalina sets `-Djava.rmi.server.hostname=$(container ip)`, and with two networks
that expands to two IPs, the second leaking in as a bare java arg (`Could not find or load
main class 172.x`). Postgres/solr don't care about being on several networks, so we attach
*them* to each feature's network instead.

**The hard constraint — shared data == shared writes.** All sharers read/write the *same*
`zfindb` and solr index, so a `liquibasePostBuild` migration, a reindex, or a curation edit
by one feature is instantly visible to (and can break) the others. There is no cheap
write-isolation (a per-feature `DATABASE`/`TEMPLATE` is a full copy, which negates the
saving). So `--shared-db` is for **read-mostly** parallel work; a feature that needs its own
schema/writes should be a normal per-feature-copy stack.

## Listing + teardown

```bash
z feature ls                      # all feature stacks: project, branch, data (own/shared), up/down, url
z feature rm <ticket>             # tear one down (prompts; --force to skip)
```

`z feature rm` automates the full teardown: `docker compose down -v` (containers + per-feature
volumes/copies), `git worktree remove --force`, `git branch -D` (skipped for a branch that
predates the stack, i.e. one provisioned with `--existing-branch`), the freeze archive if one
exists, and
(macOS) `ifconfig lo0 -alias <ip>` — deriving the project/ip/host/compose-files from the
worktree's `docker/.env`. It's **destructive** (discards the stack's copies *and* any
uncommitted work), so do it only once the feature's PR is merged; it prompts for confirmation
unless `--force` (and refuses without a TTY).

The equivalent by hand, if you prefer (or `new-feature` prints these at the end of provisioning):

```bash
docker compose down -v            # containers + network + per-feature volumes
deactivate
git worktree remove <worktree>    # add --force if dirty
sudo ifconfig lo0 -alias <ip>     # (macOS only) drop the loopback alias
```

---

## Platform notes

- **Architecture:** a seed carries data, not an engine, so it is far more portable than a baked
  image was. The server comes from this host's `zfin-db` image, and `seed.json` records the
  postgres major so a restore into an incompatible engine is refused rather than discovered when
  postgres will not start. Moving a seed between arm64 and amd64 works in practice — both are
  little-endian with the same alignment, and the database runs in the same Debian container on
  both, so the usual collation hazard does not apply — but it is not something postgres
  guarantees, so verify with a query that uses a text index.
- **Ports:** stacks publish an offset on `127.0.0.1` (`db 5432+N`, `tomcatdebug 5000+N`,
  `jenkins 9499+N`) rather than a loopback address each, so nothing needs `ifconfig lo0 alias` on
  macOS. httpd publishes nothing at all — it is reached by name through the review proxy.
- **Memory:** each feature's solr is capped lean (`4g` heap / `6g` limit) by
  `docker-compose.review-member.yml`; the base stack keeps prod sizing (`12g`/`16g`). Running
  several prod-sized solr at once OOM-kills them (exit 137).
- **Ownership (Linux):** things the `compile` container writes into a worktree — `node_modules`,
  `build/` — belong to uid 1000 on the host too, so a developer with a different uid cannot
  delete them. `z feature rm` reclaims them in a root container first. Docker Desktop maps
  ownership, so this never arises on macOS.

---

## Data-sensitivity guardrail

Seeds carry a **real loaded ZFIN database**. They live on storage you control
(`$ZFIN_ARCHIVE_DIR/seeds/`), and there is deliberately **no upload path** — the same structural
choice that kept the preloaded images local-only, bare-named and unpushable. Do not add one.

Freeze archives and sidecar session archives sit beside them under the same root and carry the
same expectation. Putting that root on NFS is supported and encouraged; putting it anywhere
publicly reachable is not.

---

## Related docs

- [build-and-docker.md](build-and-docker.md) — Docker services, image layering, deploy pipeline
- [deploying-changes.md](deploying-changes.md) — what to run after editing X
- [../workbench/feature-lifecycle.md](../workbench/feature-lifecycle.md) — earlier lifecycle notes
- [../workbench/parallel-features-walkthrough.md](../workbench/parallel-features-walkthrough.md) — narrative walkthrough
- `TODO.txt` — the open backlog (CoW clones, shared DB, `z feature rm`, …)
