# Walkthrough: two feature stacks in parallel (temp / throwaway)

Scratch instructions for trying out the preloaded per-feature dev stacks. Delete
when done. Repo = `/opt/zfin/source_roots/coral/zfin.org`; run everything from
there unless noted.

```bash
cd /opt/zfin/source_roots/coral/zfin.org
```

## Current state (already checked)

- **DB + Solr are already loaded** — `zfin_org_pg_data` (26G) + `zfin_org_solr_var`
  (5.3G). No getdb/loaddb/getsolr/loadsolr needed; `build-preloaded.groovy` tars
  the volumes directly. **Skip the load; go to Step 0b.**
- **Step 0a (cleanup) is done.**
- **No preloaded images on this machine yet** → Step 0b is required.
- `127.0.0.2/.3/.4` are already loopback-aliased and free.

---

## Step 0a — clean up stale zfh-123 leftovers  ✅ DONE

(kept for reference — filter by compose PROJECT LABEL, not name: the stack's
mailpit container is named `mailpit`, not `zfh-123-*`, so a name filter misses it)
```bash
docker rm -f $(docker ps -aq --filter label=com.docker.compose.project=zfh-123) 2>/dev/null || true
docker volume rm zfh-123_pg_data zfh-123_solr_var 2>/dev/null || true
git worktree remove ../wt-zfh-123 --force 2>/dev/null || true
sudo hostctl remove domains default zfh-123.zfin.test 2>/dev/null || true
```

## Step 0 (prereq) — activate the base env so ops commands are on PATH

```bash
cd /opt/zfin/source_roots/coral/zfin.org
source .zenv/activate               # puts zrun/zfeature/... on PATH, targets zfin_org
```
(The base `.zenv` was made once with `docker/utils/create-zenv.groovy` — the only
script run directly. Everything else goes through `zfeature` / the z-commands.)

## Step 0b — build the preloaded images (one-time; multi-GB build)

```bash
zfeature build-preloaded --tag dev
docker image ls | grep preloaded    # expect zfin-db-preloaded:dev + zfin-solr-preloaded:dev
```
build-preloaded auto-detects whether db/solr are running, stops only those, and
restarts exactly those — so a down `zfin_org` stack is left down and no flag is
needed. (The old `--no-stop` knob is gone.)

Optional lean image: add `--slim` — it now works regardless of starting state
(it auto-stops db). NOTE it mutates `zfin_org`'s jobs-only tables (reloadable, not
read by the webapp). Skip for a first run.

---

## Step 1 — start feature ZFH-123

```bash
zfeature new ZFH-123 --up --hosts
```
Creates worktree `../wt-zfh-123` (branch `ZFH-123` off `main`), writes its `.env`
(project `zfh-123`, IP `127.0.0.2`, images `zfin-{db,solr}-preloaded:dev`), maps
`zfh-123.zfin.test → 127.0.0.2` via a hostctl profile named `zfh-123`, and brings
up **only the data tier (db + solr) — instant, already populated**. The app tier
(tomcat/httpd) stays down on purpose: it crash-loops until the webapp is deployed.

Then **activate** the feature (venv-style) — this puts `zrun`/`zup`/`zexec`/… on PATH
and targets `zfh-123`, so no more long `docker compose -p … -f … -f …` handles:
```bash
cd ../wt-zfh-123          # edit here; this worktree is its own mounted source
source .zenv/activate     # prompt shows (zfh-123); 'deactivate' to exit
docker compose ps         # db + solr already up from --up (bare compose resolves via the activated env)
```

## Step 2 — first-time build + deploy, then app tier

Preloaded bakes in db+solr, so **skip the load steps** (§2/§3 of build-and-docker.md).
The first compile-container run also provisions the TLS cert.
```bash
# (activated in the worktree)
zrun -c "ant do"
zrun -c "gradle make && ant deploy-catalina-base && ant deploy-without-tests"
zup tomcat httpd          # app tier, now that TARGETROOT/CATALINA_BASE are populated
```
App: **https://zfh-123.zfin.test**. Thereafter the fast loop is:
```bash
zrun -c "gradle dirtydeploy"
# schema change? apply only this branch's new changesets on top of preloaded:
zrun -c "gradle liquibasePostBuild"
```

## Step 3 — start ZFH-456 in parallel

```bash
deactivate                                         # (optional) leave the zfh-123 env
cd /opt/zfin/source_roots/coral/zfin.org
zfeature new ZFH-456 --up --hosts     # auto-allocates 127.0.0.3
cd ../wt-zfh-456 && source .zenv/activate          # now targeting zfh-456
```
→ **https://zfh-456.zfin.test**. Both stacks run at once, each with its own DB/Solr
copy. `source .zenv/activate` in each worktree switches which one your commands hit
(activating one auto-deactivates the other).

## Step 4 — juggle under resource pressure

```bash
# in the zfh-123 worktree (activated):
zdown            # stop: frees RAM, keeps state   (zup to resume)
```

## Step 5 — wind down a ticket

```bash
# (activated in the worktree)
docker compose down -v             # -v discards this stack's DB/Solr copy
deactivate
git worktree remove ../wt-zfh-123
sudo hostctl remove zfh-123        # drop this feature's hosts profile
# git push -u origin ZFH-123       # you run the push manually
```

---

## Watch for

1. **Tag** — `zfeature new` defaults to the **newest local `zfin-db-preloaded`** image,
   so you normally set nothing. Pin a specific snapshot with `--tag` or `$PRELOADED_TAG`.
   If the chosen tag has no local image, it **fails fast before creating anything** and
   lists the tags you do have. (If a stack ever reaches `up` with a bad tag, `pull_policy:
   never` stops it building stock — then drop the empty `<proj>_pg_data`/`_solr_var`
   volumes before retrying, or they won't re-seed.)
2. **Beyond 127.0.0.4** — a 4th+ feature gets `127.0.0.5`, not yet loopback-aliased
   on macOS. If ports won't bind: `sudo ifconfig lo0 alias 127.0.0.5`. (The one
   macOS bit `new-feature.groovy` doesn't do yet — candidate to fold into `--hosts`.)
3. **Stale volume = no re-seed** — the preloaded data seeds a named volume only on
   first creation. If you re-run a feature name whose `<proj>_pg_data` volume still
   exists, it won't re-seed. `down -v` (or `docker volume rm`) before reusing a name.
```
