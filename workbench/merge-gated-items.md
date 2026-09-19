# Things that resolve when this branch merges

Not bugs. Each is the same shape: a feature worktree is cut off `main` by default, and `main`
does not yet carry this branch's changes — so the worktree is missing something the tooling
expects. They need no code; they need the merge.

Collected while testing on cell, 2026-09-18.

---

## 1. A worktree has no `./z` launcher

`z feature new` defaults to `--base main`, and `main` has neither `z` nor `docker/utils/`. So
`cd` into a new worktree and `./z up httpd` is `No such file or directory`.

**Until then:** invoke the origin checkout's launcher by absolute path. `z` resolves its target
from the WORKING DIRECTORY, not from where the launcher lives, so it still acts on the right
stack:

```bash
cd $ZFIN_DEV_ROOT/worktrees/<ticket>
/path/to/checkout/z up httpd
```

A symlink into the worktree works too, and `main` has no `z` to collide with it.
`--base preloaded-dev-stacks` avoids it entirely for test stacks.

This is the one thing removing the `.zenv` bundle cost: the bundle existed so a worktree kept
working when its branch did not carry the tooling. Dropping it was still right — it went stale
silently, surfacing as "no such service" rather than "your copy is old" — but the gap is real
until the merge.

## 2. No `feature` instance, so `DOMAIN_NAME` says `zfin.org`

`z feature new` says so itself:

```
>> instance: coral (inherited) -- this branch has no 'feature' instance yet, so DOMAIN_NAME
   will still say zfin.org. Merge the instance change to fix.
```

The `feature` instance is defined in `commons/env/all-properties.yml` **on this branch**, with
`DOMAIN_NAME: "${env.DOCKER_VIRTUAL_HOST}"` so generated links follow the stack's own host
instead of pointing at production. A worktree cut off `main` generates properties without it
and inherits whatever instance the base defines.

**Symptom:** absolute links in the served app point at `zfin.org` rather than
`<slug>.<zone>`. The stack works; the links are wrong.

## 3. Images need rebuilding and pushing

Not merge-gated exactly — these are committed Dockerfile changes that no CI in this repo
builds (`.github/workflows/` has only `ant.yml` and `eslint.yml`), so whatever builds
`ghcr.io/zfin/zfin-*` has to pick them up.

- **Shared volume mountpoints** (`b36fe7c0c6`, `6dac0394c7`): `httpd`, `tomcat`, `fail2ban` and
  `filebeat` build on foreign bases and lacked the directories they mount shared volumes at.
  Whichever container touches such a volume first seeds it `root:root`, and uid 1000 can then
  never write. Cost a from-scratch build two separate failures on cell.

**Until then:** `./z build configure --build` builds the stock images locally from these
Dockerfiles under the same names, and nothing sets `pull_policy` on httpd/tomcat, so compose
prefers the local image.

(`pigz` was the same shape and is already fixed: the registry `:main` has it, cell just had an
older local copy. `docker pull ghcr.io/zfin/zfin-compile:main` took a seed from 21.5 GB
uncompressed to 6.9 GB, and made it faster.)
