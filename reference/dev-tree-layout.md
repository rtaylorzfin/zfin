# The dev tree: one parent for everything

Everything this tooling reads or writes lives under **one directory you choose**, declared
once as `ZFIN_DEV_ROOT` in `docker/.env`.

There is deliberately **no default**. An absolute default like `/opt/zfin/review` is a guess
about someone else's machine, and when it is wrong the symptom is a directory appearing
somewhere unexpected rather than an error.

Create it with:

```bash
./z scaffold --root ~/zfin-dev      # or just `./z scaffold` once ZFIN_DEV_ROOT is set
```

It creates what is missing and never clobbers, so it is safe to re-run on a partially
set-up tree, and it prints the `ZFIN_DEV_ROOT` line to add to `docker/.env`.

---

## Recommended structure

```
$ZFIN_DEV_ROOT/                  e.g. ~/zfin-dev  or  /opt/zfin-dev
├── repos/
│   └── coral/                   the checkout — name it for the INSTANCE, not "zfin.org"
├── worktrees/
│   ├── zfin-10358/              one per feature; the directory name IS the ticket
│   └── zfin-10475/
├── review/                      review-proxy state: wildcard cert, allowlist, stacks.json
│   ├── certs/
│   ├── vhost.d/
│   └── state/
├── archive/                     freeze archives + sidecar session history
│   ├── <ticket>/                a frozen stack's volumes
│   └── sessions/
├── seeds/                       (under archive/) captured stack volumes, restored into new stacks
└── mounts/                      the host paths bind-mounted into containers
    ├── unloads/{db,solr}        DOCKER_DB_UNLOADS_PATH / DOCKER_SOLR_UNLOADS_PATH
    ├── research/                DOCKER_RESEARCH_PATH
    ├── blast/                   DOCKER_BLASTSERVER_BLAST_DATABASE_PATH, DOCKER_ABBLAST_PATH
    ├── downloads/               DOCKER_DOWNLOADS_PATH
    ├── loadUp/                  DOCKER_LOADUP_PATH
    ├── gff3/                    DOCKER_GFF3_PATH
    └── hh_atlas/                DOCKER_HHATLAS_PATH
```

One thing to back up, relocate, or delete.

---

## Why `repos/coral`, not `zfin.org`

ZFIN has a long-standing convention of hosting per-host checkouts as
`/path/to/zfin.org/{cell,coral,schlapp,...}`. Naming the checkout directory `zfin.org`
inverts that and reads as though the repo *is* the site rather than one host's copy of it.

**Name the checkout for the instance it runs as** — `coral`, `cell`, `schlapp`. The tooling
does not care: it derives the checkout root from where `z` lives, so the directory can be
called anything. This is a readability convention, not a requirement.

(The container always sees the source at `/opt/zfin/source_roots/zfin.org` regardless —
that is a fixed mount *target*, unrelated to the host layout.)

---

## Worktrees: `worktrees/<ticket>`, no prefix

Earlier versions used `wt-<ticket>` directories as siblings of the checkout. With a handful
of tickets in flight that clutters the parent, and the prefix exists only to tell worktrees
apart from everything else beside them.

A dedicated `worktrees/` directory makes the prefix redundant, so the directory name is just
the ticket. A worktree is now identified by **having a provisioned `docker/.env`**, which is
a better test than a name prefix anyway — it cannot mistake a stray directory for a stack.

---

## Configuration

Required, in `docker/.env`:

```bash
ZFIN_DEV_ROOT=~/zfin-dev
```

Everything else derives from it. Override individually only when something must live
elsewhere:

| variable | defaults to | override when |
|---|---|---|
| `ZFIN_WORKTREES_DIR` | `$ZFIN_DEV_ROOT/worktrees` | rarely |
| `ZFIN_REVIEW_DIR` | `$ZFIN_DEV_ROOT/review` | rarely |
| `ZFIN_ARCHIVE_DIR` | `$ZFIN_DEV_ROOT/archive` | **archives on NFS or an external disk** |

The `DOCKER_*_PATH` mounts stay individually configured, because they are often *shared*
with other tooling — Jenkins jobs, Ant tasks, other instances — rather than owned by this
tree. On a laptop, point them under `$ZFIN_DEV_ROOT/mounts`. On a server, leave them at
whatever the organisation already uses.

---

## Published ports

Only the genuinely non-HTTP services publish a host port, and they do it as an **offset on
`127.0.0.1`** rather than a loopback address per stack:

| service | port | why it is published at all |
|---|---|---|
| db | `5432 + N` | Postgres cannot be routed by hostname; GUI clients need a port |
| tomcatdebug | `5000 + N` | JDWP carries no routing information at all |
| jenkins | `9499 + N` | convenience; also reachable at `<stack-host>/jobs` |

`N` is allocated per stack (`ZFIN_PORT_OFFSET` in its `.env`, `--port-offset` to pin it).
httpd is not published — it goes through the review proxy — and solr is reached at
`<stack-host>/solr`.

An earlier design used one port set on a per-stack `127.0.0.X`, which needed
`sudo ifconfig lo0 alias` on macOS once per stack. That was the last sudo prompt in
provisioning, and it diverged from what ZFIN's own Linux hosts already do (cell publishes
8084/8447/8988/9503 on one address). Offsets need no aliases and behave identically on both
platforms.

---

## Offloading to NFS or an external disk

Two ways, and they are not equivalent:

**Override the variable** (preferred):

```bash
ZFIN_ARCHIVE_DIR=/Volumes/backup/zfin-archive
```

**Symlink** the directory. Works on Linux. Under Docker Desktop for macOS a bind source that
symlinks outside the shared filesystem can fail — Docker resolves the link host-side and the
target may not be in its file-sharing config. Test it before relying on it.

Either way: an archive **at rest** on NFS or USB is fine; never run a database off one. I/O
latency and fsync semantics will corrupt PGDATA.

---

## What is deliberately NOT here

**Credentials.** The Claude sidecar token lives at `~/.zfin/claude-token`
(`ZFIN_CLAUDE_TOKEN_FILE`), under `$HOME` rather than in this tree. On a shared host the dev
tree is group-writable so developers can collaborate on worktrees — a credential there would
hand one person's subscription to everyone with an account.

**Docker volumes.** Per-stack volumes live wherever the Docker daemon keeps them, and cannot
be relocated to NFS -- overlayfs needs a local upper directory, and a database must never run
off NFS regardless. What *can* live there is `archive/`, which holds both the freeze archives
and `seeds/`: those are ordinary tarballs, and moving them is the whole point of the feature.

---

## Multiple checkouts on one host

`review/` and `cache/` are **per machine**, not per checkout: one proxy owns `:443`, and the
seeds are captured once. If a host carries several checkouts (`repos/coral`,
`repos/cell`), they should share one `ZFIN_DEV_ROOT` so they share that state.

Two roots on one machine means two review directories competing over a single proxy. If you
genuinely need separate trees, give only one of them the proxy.
