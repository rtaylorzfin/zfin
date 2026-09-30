# Seed archives vs preloaded images

**Question:** the preloaded-image trick and `z feature freeze`/`thaw` both capture a loaded
stack's volumes and restore them cheaply into another stack. Is the image half redundant?

**Short answer:** yes, mostly. They already share their core, the image wrapper costs three
extra full copies of the data, and the one structural advantage an image would have is
destroyed by postgres declaring `VOLUME`. The replacement — a *seed archive* — is almost
entirely existing code.

**Status:** measured and decided; not yet built. The gate below is ANSWERED -- see 7.

---

## 1. They are already the same mechanism

`BuildPreloaded` does not have its own capture. It calls the same helper `z feature freeze`
does:

```groovy
// docker/utils/lib/BuildPreloaded.groovy
// ZfinUtil.captureVolume is the single implementation, shared with `z feature freeze`
def capture = { String vol, File out -> zfinUtil.captureVolume(vol, out) }
def pgTarball   = new File(DOCKER, 'postgresql/pgdata.tgz')
def solrTarball = new File(DOCKER, 'solr/solrvar.tgz')
```

So `pgdata.tgz` and `solrvar.tgz` — byte-for-byte the artifacts a freeze archive holds —
already exist on disk partway through `build-preloaded`. There is even a `--keep-tarballs`
flag to stop them being deleted. The `docker build` is a wrapper bolted on afterwards.

`captureVolume` / `restoreVolumes` / `archiveFileFor` live in `ZfinUtil` and are used by
`FeatureFreeze`, `FeatureThaw`, `SharedStack`, `NewFeature` **and** `BuildPreloaded`. There is
one capture/restore implementation in this codebase already; the argument is only about what
we wrap it in.

## 2. What the wrapper costs

Measured on cell, 2026-09-18, baking from the `coral` stack:

| step | db | solr |
|---|---|---|
| transferring build context | 22.5s (3.67 GB) | 20.3s (3.38 GB) |
| `ADD <tarball>` | 58.3s | 34.9s |
| exporting layers | 78.6s | 18.6s |
| **total** | **160.0s** | **74.2s** |

That is the tarball copied into the build context, again into a layer, and again when the
layer is exported. Then a **fourth** full copy when Docker seeds each stack's volume at first
`up`.

A seed archive is: volume → tarball → volume. Two copies instead of four, and ~4 minutes of
bake time that stops existing.

## 3. The image's structural advantage does not exist here

Normally an image wins because layers are shared between containers. Not here, and the
Dockerfile says so itself:

```dockerfile
# docker/postgresql/preloaded.Dockerfile
# postgres:18 declares VOLUME /var/lib/postgresql, so at runtime Docker seeds a
# fresh (anonymous or per-project) volume by COPYING this baked content ...
# NOT copy-on-write: it's a full ~32G copy per feature (the image is ~98%
# PGDATA; only the ~0.7G stock base layers are shared).
```

So the current cost is **~30 GB for the images plus ~34 GB per stack**, with essentially no
sharing. The image is a 30 GB delivery vehicle for a tarball that gets fully expanded anyway.

## 4. What a seed archive looks like

A freeze archive that is not tied to a stack:

```bash
z seed create --from coral --tag 2026-09-18   # == build-preloaded minus `docker build`
z seed ls
z seed rm 2026-09-18
z feature new zfin-99999 --seed 2026-09-18    # create volumes, restore, then up
```

Implementation is mostly deletion. `restoreVolumes` already says as much itself:

```groovy
/** Create `<project>_<vn>` for each name and extract `<srcDir>/<vn>.tgz` into it, in
 *  parallel. ... The counterpart of captureVolume, and the
 *  same code `z feature new` uses to warm an app tier -- so a freeze archive and a
 *  preloaded snapshot restore identically. */
```

It is already parallel, and `NewFeature` already calls it to warm the app tier and the build
caches. The naming already lines up: it reads `<srcDir>/<vn>.tgz` into `<project>_<vn>`, and
`StackConfig.DATA_VOLS` is `['pg_data', 'solr_var']`. So:

- **`BuildPreloaded`** writes `pg_data.tgz` / `solr_var.tgz` into `auxDir` — where the app and
  cache tarballs already go — instead of `pgdata.tgz` / `solrvar.tgz` into the build contexts,
  and then stops, skipping `docker build`.
- **`NewFeature`** becomes `vns = APP_VOLS + cachesPresent + (doSharedDb ? [] : DATA_VOLS)`,
  and drops `docker-compose.preloaded.yml` from its overlays.
- **`SharedStack`** gets the same treatment, and converges: "the shared stack" then simply IS
  a seed restored into `zfin_shared` that stays up.
- `docker-compose.preloaded.yml`, `ZFIN_DB_IMAGE` and `ZFIN_SOLR_IMAGE` are deleted.

The app/cache warming (`--app`/`--caches`) **already** works exactly this way: tarballs under
`docker/preloaded-app/<tag>/`, extracted into the new stack's volumes by `NewFeature`. Half
the mechanism is archive-based today. The db/solr half is the odd one out.

## 5. What this buys

| | |
|---|---|
| **disk** | ~30 GB of `/var/lib/docker` returned |
| **NFS** | seeds can live on NFS; images categorically cannot (overlayfs needs a local upperdir). This is the only route to offloading the one-time cost at all |
| **compose** | the preloaded overlay disappears: no `ZFIN_DB_IMAGE`/`ZFIN_SOLR_IMAGE`, no `pull_policy: never`, no `${ZFIN_DB_IMAGE:?…}` interpolation |
| **versions** | the data stops being welded to whichever base image `ZFIN_RELEASE` named *at bake time* |
| **consistency** | one capture/restore path, and the db/solr half stops differing from the app/cache half |

Two of those are not theoretical. On 2026-09-18 both bit within an hour:

- `z build all` on the main checkout inherited `docker-compose.preloaded.yml` (the overlay
  fallback in `stackSpec`), pointing `db` at an image that does not exist until
  `build-preloaded` has run — which is the thing the base stack is being loaded to produce.
  Fixed by making an empty `ZFIN_COMPOSE_OVERLAYS` mean "no overlays", but the overlay only
  needs to exist to be inheritable.
- The stack was loaded under `ZFIN_RELEASE=latest` and the images baked under `main`, so the
  PGDATA and the server that would run it came from different tags. Harmless only because
  both happened to be PostgreSQL 18. A seed carries data, not a server, so the question does
  not arise in the same form.

## 6. What it costs

| | |
|---|---|
| **laziness** | Docker seeds automatically at first start. A seed needs an explicit restore inside `z feature new`, ordered before `up` |
| **inventory** | `docker images` stops being the UI; needs `z seed ls` |
| **partial states** | `z feature new --no-up` would leave a provisioned stack with empty volumes until something restores them. Today the data appears whenever it first starts |
| **integrity** | `pull_policy: never` plus a tag is a hard guarantee you booted exactly that data. A tarball can be truncated by a full disk. A seed should carry a checksum — freeze manifests already record image IDs, so the pattern exists |
| **speed** | **unmeasured** (see below) |

## 7. The gate: measure first boot

Untar-into-volume and Docker's own volume seeding are both full copies of ~34 GB, so I expect
a wash. That is a prediction, not a result.

For reference, `captureVolume` timings measured earlier on a full stack: 9m08 with gzip,
2m12 uncompressed, 1m21 with pigz. Restore should be comparable. Docker's seeding of the same
data is unmeasured.

**Measure before committing to this.** Three surprises on this branch in one day came from
exactly this kind of reasoning-instead-of-measuring: nginx-proxy's per-vhost protocol vote,
the allowlist not being re-read without a reload, and Java 8 lacking `Redirect.DISCARD`.

**MEASURED, 2026-09-18 on cell. Seeds win.** Same stack, same disk:

| | mechanism | data | time |
|---|---|---|---|
| Docker seeding db+solr from images | daemon-side per-file copy | ~21.3 GB | **~170s** (~93 MB/s) |
| `restoreVolumes` from tarballs | parallel `tar -x` | ~21.3 GB | **79.9s** (198 MB/s) |

Restore is ~47% of the image path. The parallelism is visible: per-volume times sum to 128.4s
while the step took 79.9s, so everything overlapped inside `pg_data`'s 79.8s.

Two caveats kept for honesty. Total wall time was closer -- 172.8s for the whole thaw vs ~180s
for `feature new --up` -- but those runs started DIFFERENT service sets (the thaw also brought
up mailpit, blast, certbot, ncbiload and jenkins), so only the data-restore step is comparable,
and that is the part this change replaces. And the archive was UNCOMPRESSED, so restore was
pure I/O; a pigz seed trades decompression CPU for reading 6.9 GB instead of 21.5 GB, which may
go either way and has not been measured.

Capture, unlike restore, is still SEQUENTIAL (per-volume times summed to the total, 136.6 of
137.0s). `z seed create` should reuse restoreVolumes' threading; the pattern already exists.

The earlier half of the measurement, for reference -- creating zfin-99999 from preloaded images:

```
zfin-99999-db-1     Healthy   170.1s
zfin-99999-solr-1   Started   164.5s
```

~170s wall for Docker to seed both volumes concurrently. That is the number a parallel
`restoreVolumes` of the same data has to match. Outstanding: freeze then thaw a real stack on
cell and read the step timer's per-volume restore times.

## 8. Migration

1. **Additive.** Add `z seed create` and `z feature new --seed <tag>`. Both paths
   work. `BuildPreloaded` keeps its image half.
2. **Measure.** First-boot wall time, seed vs preloaded, on a real stack. If seeds are
   materially slower, stop here and keep both.
3. **Default.** Seeds become the default; `--preloaded-image` opts back in.
4. **Delete.** `docker-compose.preloaded.yml`, the image-building half of `BuildPreloaded`,
   `ZFIN_DB_IMAGE`/`ZFIN_SOLR_IMAGE`, and the `stackSpec` overlay fallback that referenced it.

## 9. Decisions

- **`z seed create|ls|rm`, not `z feature seed`.** `z feature <sub>` acts on ONE ticket; the
  top level owns host-wide resources (`z review` the proxy, `z shared` the data stack). A seed
  belongs to the host, not to a ticket. This adds no surface: it replaces
  `z feature build-preloaded`, which was already the odd one out in the feature family for the
  same reason. The old name stays as a stub that dies pointing at the new one.
- **Seeds live at `$ZFIN_ARCHIVE_DIR/seeds/<tag>/`**, a SIBLING of the per-stack freeze
  archives and of `sessions/` — because `z feature rm` deletes a stack's freeze archive and
  must never touch a seed.
- **The manifest carries a checksum per tarball**, over the ~4 GB compressed artifacts rather
  than the ~34 GB expanded, so it costs seconds. An image layer is checksummed by Docker; a
  tarball on NFS is not.
- **The manifest also records the postgres major version.** This is the 2026-09-18 near-miss
  made impossible rather than merely unlikely: a restore into a `db` image of a different
  major is refused up front instead of discovered when postgres will not start.

## 10. Open questions

- **Where do seeds live by default?** `$ZFIN_ARCHIVE_DIR/seeds/<tag>/` is the obvious answer
  and inherits the existing NFS override. But freeze archives and seeds have different
  lifetimes — `z feature rm` deletes a stack's freeze archive, and must never touch a seed.
  Sibling directories, like `archive/sessions/` already is.
- **Does a seed need the app/cache tarballs folded in,** or do they stay as they are? Folding
  them in makes one artifact per tag; leaving them makes `--no-app` cheaper.
- **Retention.** Images accumulate visibly in `docker images` and people prune them. A
  directory of 30 GB seeds will not be noticed. Needs `z seed rm` and probably a
  warning in `z review status` or `z feature ls`.
- **Does `z shared` want seeds too?** `SharedStack` boots from the preloaded images today. If
  those go away it needs the same treatment, and it already calls `captureVolume`.
