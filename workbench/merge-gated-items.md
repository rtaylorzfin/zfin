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

---

## `z build deploy` calls an ant target `main` does not have

`z build deploy` runs `ant deploy-catalina-base && ant deploy-no-tests-no-restart`. That second
target is this branch's rename of `deploy-without-tests-and-tomcat-restart`. A feature worktree
is cut from `main` by default, so its `build.xml` has only the old name and the phase dies:

```
BUILD FAILED
Target "deploy-no-tests-no-restart" does not exist in the project "ZFIN".
```

The tooling comes from this checkout and the build file comes from the worktree, so they
disagree until the branch lands. Observed on zf-10418, 2026-09-19.

**Until then:** run the deploy with the old target name, or cut the worktree from this branch.

```bash
z run -c "gradle make && ant deploy-catalina-base && ant deploy-without-tests-and-tomcat-restart"
```

Worth noting the rename kept the old target as an alias on THIS branch, so once `main` carries
it both names work and nothing has to be un-done.

---

## The published images predate the cache-mountpoint fix

`npm ci` in a stack built from `ghcr.io/zfin/zfin-*:main` fails:

```
npm error To permanently fix this problem, please run:
npm error   sudo chown -R 1000:1000 "/home/gradle/.npm"
```

`docker/base/Dockerfile` on this branch pre-creates `/home/gradle/.npm` and `.m2` as `gradle`,
because Docker seeds a fresh named volume from the image directory's metadata -- and creates
the mountpoint `root:root` when the directory does not exist in the image. Verified against the
published image:

```
$ docker run --rm --entrypoint sh ghcr.io/zfin/zfin-base:main -c 'ls -ld /home/gradle/.npm'
ls: cannot access '/home/gradle/.npm': No such file or directory
```

So the fix is in the Dockerfile but not in anything anyone pulls. Nothing in this repo builds
and publishes images, so it lands when `main` carries this branch AND the images are rebuilt --
the second is not automatic.

**Until then:** build locally rather than pulling (`z build configure --build`), or repair an
existing volume in place:

```bash
docker run --rm -u 0 -v <project>_npm_cache:/x alpine chown -R 1000:1000 /x
```

The same applies to `_m2` if it was created by an older image.

