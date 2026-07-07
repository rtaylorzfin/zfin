#!/usr/bin/env groovy
// Build "preloaded" images from an already-loaded dev stack.
//
// Prereq: a normal dev stack that has been loaded the usual way, e.g.
//   gradle getdb && gradle loaddb && gradle liquibasePreBuild && gradle liquibasePostBuild
//   gradle getsolr && gradle loadsolr
//
// This captures that stack's pg_data + solr_var volumes and bakes them into
//   zfin-db-preloaded:<tag>  and  zfin-solr-preloaded:<tag>
// which a feature stack boots instantly (no getdb/loaddb/reindex). See
// docker/postgresql/preloaded.Dockerfile and docker/solr/preloaded.Dockerfile.
//
// NOTE: NOT copy-on-write. postgres/solr declare VOLUME, so on first `up` Docker
// SEEDS each feature's named volume by COPYING the image's baked data -- a full
// per-feature copy (~26G db + ~10G solr each), not a shared/CoW clone. The win is
// instant boot + isolation, at the cost of disk. See workbench/db-slim-candidates.md.
//
// These images bake in a REAL loaded database + Solr index, so they are
// deliberately LOCAL-ONLY: bare names (no ghcr.io/ registry prefix) and no
// push capability. Every dev bakes their own on their own machine, so there is
// no cross-arch distribution problem to solve. Do not add a push here --
// pushing loaded ZFIN data to a registry is the thing we are preventing
// structurally.
//
// Arch note: the built image inherits the architecture of its base (the stock
// zfin-db / zfin-solr image it FROMs). For a truly native image on Apple
// Silicon the stock base must also be arm64 -- see the multi-arch stock-image
// TODO (TODO.txt).
//
// For a consistent (not mid-write) capture, db/solr must be quiescent. This
// script auto-detects which of them are running, stops ONLY those, and restarts
// exactly those afterward -- so a stack that was already down is left down, and the
// WAL/table trim always gets a stopped db regardless of starting state. No --no-stop knob needed.
//
// With --app, ALSO warm the CODE build/deploy (not just db/solr data): capture the
// deployed-app volumes so a feature's tomcat/httpd come up already serving. Unlike
// pg_data/solr_var -- each single-owner, so baked into an image and seeded on first
// mount -- the deploy volumes (www_data/catalina_base/keystore/tls_certs) are SHARED
// across services, where Docker's first-mounter-wins seeding can't populate them
// reliably. So --app tars them to RESTORE tarballs under docker/preloaded-app/<tag>/,
// which new-feature extracts into each feature's fresh volumes before `up`. The baked
// deploy is the SOURCE branch at capture time -- a WARM START; the feature dirtydeploys
// its own delta on top. See TODO.txt (warm build/deploy) and reference/preloaded-dev-stacks.md.
//
// Usage:
//   z feature build-preloaded [--project NAME] [--tag TAG] [--slim] [--app] [--caches] [--keep-tarballs]
//
//   --project NAME   Compose project whose volumes to capture (default: $COMPOSE_PROJECT_NAME or "zfin_org")
//   --tag TAG        Preloaded image tag (default: today's date, YYYY-MM-DD)
//   --slim           Additionally TRUNCATE jobs-only tables (proven NOT read by the
//                    webapp, reloadable) for an even leaner image -- slightly riskier,
//                    so opt-in. NOTE: WAL is collapsed on EVERY build regardless (it's
//                    dead weight in a frozen snapshot and safe to shed), so --slim only
//                    governs the extra table data. See workbench/db-slim-candidates.md.
//   --app            Also capture the deployed-app volumes (www_data, catalina_base,
//                    keystore, tls_certs) to docker/preloaded-app/<tag>/ so feature stacks
//                    boot with a warm app tier. Requires the source stack to be DEPLOYED.
//   --caches         Also capture the gradle_cache + maven_cache volumes (same dir) so a
//                    feature's first `gradle dirtydeploy` skips re-downloading deps. These
//                    are LARGE/churnier than the deploy volumes -- opt in when you want it.
//   --keep-tarballs  Leave the intermediate db/solr .tgz files in the build contexts
//                    (the --app/--caches tarballs are always kept -- they're restore sources)

// --- shared helpers + roots (via ZfinUtil, passed in by z) --------------------
class BuildPreloaded {
def run(List args, ZfinUtil zfinUtil) {
    if (args.any { it in ['-h', '--help'] }) { zfinUtil.printHeader(this); return }
    def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand; def runQuietly = zfinUtil.&runQuietly
    def captureOutput = zfinUtil.&captureOutput; def runWithInput = zfinUtil.&runWithInput

    // --- load docker/.env --------------------------------------------------------
    def DOCKER = zfinUtil.DOCKER

// Mirror bash's `set -a; . .env`: values in docker/.env take precedence over the
// ambient environment; anything absent from .env falls back to the environment.
def dotenv = [:]
def envFile = new File(DOCKER, '.env')
if (envFile.exists()) envFile.eachLine { line ->
    def m = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
    if (m.find()) dotenv[m.group(1)] = m.group(2)
}
def envOf = { String k, String dflt = null -> dotenv.containsKey(k) ? dotenv[k] : (System.getenv(k) ?: dflt) }

// --- defaults + args ---------------------------------------------------------
def project = envOf('COMPOSE_PROJECT_NAME', 'zfin_org')
def tag     = java.time.LocalDate.now().toString()   // YYYY-MM-DD
def keep    = false
def slim    = false
def app     = false
def caches  = false

// Tables proven jobs-only (NOT read by the webapp) -> safe to empty for a lean
// image. Evidence (ORM + call-site trace): workbench/db-slim-candidates.md.
def slimTables = ['gff3', 'gff3_ncbi', 'gff3_ncbi_attribute',
                  'expression_search_anatomy_generated', 'feature_stats_old']

def argv = args as List
for (int i = 0; i < argv.size(); i++) {
    switch (argv[i]) {
        case '--project':       project = argv[++i]; break
        case '--tag':           tag = argv[++i];     break
        case '--slim':          slim = true;         break
        case '--app':           app = true;          break
        case '--caches':        caches = true;       break
        case '--keep-tarballs': keep = true;         break
        default: die("unknown arg: ${argv[i]}", 2)
    }
}

def release = envOf('ZFIN_RELEASE')
if (!release) die("ZFIN_RELEASE must be set (from docker/.env or the environment)")

// Stock db image: the pg engine matching this data, used for the WAL-trim throwaway
// postgres (which MUST be the db image). The tar capture below uses zfinUtil.tarImage()
// (the compile image) -- same source new-feature's restore uses. Both are local (no pull).
def stockDb = "ghcr.io/zfin/zfin-db:$release"

def pgVol   = "${project}_pg_data"
def solrVol = "${project}_solr_var"

info("preloaded build: project=$project release=$release tag=$tag")

// Both named volumes must exist, or the capture would silently produce empties.
[pgVol, solrVol].each { v ->
    if (runQuietly(['docker', 'volume', 'inspect', v]) != 0)
        die("volume '$v' not found -- is project '$project' loaded? (try --project)")
}

// --app / --caches: extra volumes captured as RESTORE tarballs under
// docker/preloaded-app/<tag>/ (NOT baked into an image -- they're shared across
// services, so image seeding can't populate them; see header). --app warms the
// deployed app tier; --caches warms the build caches so the first dirtydeploy is fast.
def appVols   = zfinUtil.APP_VOLS      // shared volume contract (single source, also read by NewFeature)
def cacheVols = zfinUtil.CACHE_VOLS
def auxDir    = zfinUtil.auxDir(tag)
def requireVols = { List vns, String flag, String hint ->
    vns.each { vn ->
        def v = "${project}_${vn}"
        if (runQuietly(['docker', 'volume', 'inspect', v]) != 0)
            die("$flag: volume '$v' not found -- $hint")
    }
}
if (app)    requireVols(appVols, '--app',
                "has project '$project' been DEPLOYED? (--app captures the deployed webapp; build+deploy the base first)")
if (caches) requireVols(cacheVols, '--caches',
                "has project '$project' run a build yet? (--caches captures the gradle/maven build caches)")
if (app || caches) auxDir.mkdirs()

// Failure-recovery state shared with the shutdown hook (registered below): the trim
// container to force-remove, and a flag that a clean run has finished.
def state = [completed: false, trim: null]

// Trim the captured snapshot before tarring it.
//   ALWAYS: shed recycled WAL. In a frozen snapshot the pg_wal segments are
//     pre-allocated for reuse -- near-zero real data, yet they cost their full ~16MB
//     each on disk (hundreds of segments = several GB). Collapsing them is a safe,
//     unconditional win: the copy is started fresh in every feature, so there's
//     nothing to recover. A plain CHECKPOINT won't shrink them (segment-retention
//     decays only ~2%/checkpoint), so we pg_resetwal.
//   WITH --slim (opt-in, slightly riskier): also TRUNCATE tables proven jobs-only +
//     reloadable (workbench/db-slim-candidates.md) for an even smaller image.
// Mechanism: briefly run a throwaway postgres on the volume, so (a) any --slim TRUNCATE
// has a live server and (b) the clean docker-stop afterward gives pg_resetwal its
// required clean-shutdown precondition. Runs while the real db is stopped (single
// writer on the volume). The throwaway start skips initdb (data dir is non-empty).
def trimSnapshot = {
    def img  = stockDb
    def name = "preloaded-trim-${ProcessHandle.current().pid()}"
    info("[trim] throwaway postgres on $pgVol" + (slim ? " (TRUNCATE jobs-only tables + WAL reset)" : " (WAL reset)"))
    runCommand(['docker', 'run', '-d', '--name', name,
        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
        '-v', "${pgVol}:/var/lib/postgresql", img])
    state.trim = name    // track so the shutdown hook can force-remove it if we die below

    def ready = false
    for (int i = 0; i < 60 && !ready; i++) {
        if (runQuietly(['docker', 'exec', name, 'pg_isready', '-U', 'postgres', '-d', 'zfindb']) == 0) ready = true
        else sleep(1000)
    }
    if (!ready) {
        runCommand(['docker', 'logs', '--tail', '30', name], [check: false])
        runQuietly(['docker', 'rm', '-f', name]); state.trim = null
        die("[trim] postgres not ready")
    }

    if (slim) {
        def sql = """
            DO \$\$
            DECLARE t text;
            BEGIN
              FOREACH t IN ARRAY ARRAY[${slimTables.collect { "'$it'" }.join(', ')}]
              LOOP
                IF to_regclass(t) IS NOT NULL THEN
                  EXECUTE format('TRUNCATE TABLE %I CASCADE', t);
                  RAISE NOTICE 'slimmed %', t;
                END IF;
              END LOOP;
            END \$\$;
        """.stripIndent()
        runWithInput(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-d', 'zfindb', '-v', 'ON_ERROR_STOP=1'], sql)
        info("[slim] emptied jobs-only tables: ${slimTables.join(' ')}")
    }

    // -t 60: give postgres time for a clean fast-shutdown (the default 10s grace risks a
    // SIGKILL -> unclean state, which pg_resetwal -f would then paper over).
    runCommand(['docker', 'stop', '-t', '60', name])
    runQuietly(['docker', 'rm', name]); state.trim = null
    info("[trim] pg_resetwal to shed recycled WAL segments (safe after the clean shutdown above)")
    runCommand(['docker', 'run', '--rm', '-u', 'postgres', '-v', "${pgVol}:/var/lib/postgresql",
        '--entrypoint', 'bash', img, '-c', 'pg_resetwal -f "$PGDATA"'])
}

// Quiesce db/solr so the tarred on-disk state is consistent (not mid-write).
// Stop ONLY the services that are actually running, remember their container IDs,
// and restart exactly those after capture -- a down stack stays down, and the trim
// step always gets a stopped db regardless of starting state. Detected via compose
// labels so no compose file / -f flags are needed.
def runningContainer = { String svc ->
    captureOutput(['docker', 'ps', '-q',
            '--filter', "label=com.docker.compose.project=$project",
            '--filter', "label=com.docker.compose.service=$svc"])
}
def stopped = [:]   // service -> container id we stopped

// Restart-on-failure net. From the first stop until the restart loop below, db/solr are
// down; any die (trim, pg_resetwal, a capture() failing on low disk mid-tar) would leave
// the dev's stack down -- and die() calls System.exit, which skips try/finally. So a JVM
// shutdown hook (registered BEFORE we stop anything) restarts exactly what we stopped and
// force-removes a leaked trim container, unless state.completed says we finished cleanly.
Runtime.runtime.addShutdownHook(new Thread({
    if (state.completed) return
    if (state.trim) runQuietly(['docker', 'rm', '-f', state.trim])
    stopped.each { svc, cid ->
        System.err.println("!! [cleanup] restarting $svc after an incomplete build")
        runQuietly(['docker', 'start', cid])
    }
} as Runnable))

['db', 'solr'].each { svc ->
    def cid = runningContainer(svc)
    if (cid) {
        info("stopping $svc ($cid) for a consistent capture")
        runCommand(['docker', 'stop', cid])
        stopped[svc] = cid
    }
}

trimSnapshot()   // always sheds WAL; also TRUNCATEs jobs-only tables when --slim

def capture = { String vol, File out ->
    info("capturing $vol -> $out")
    // --entrypoint tar bypasses the postgres entrypoint; -u 0 (root) so we can
    // read both the pg (postgres-owned) and solr (solr-owned) volume contents.
    runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
        '-v', "${vol}:/data:ro",
        '-v', "${out.parentFile.absolutePath}:/out",
        zfinUtil.tarImage(), 'czf', "/out/${out.name}", '-C', '/data', '.'])
}

def pgTarball   = new File(DOCKER, 'postgresql/pgdata.tgz')
def solrTarball = new File(DOCKER, 'solr/solrvar.tgz')
capture(pgVol, pgTarball)
capture(solrVol, solrTarball)

// --app: capture the deploy-target volumes to persistent restore tarballs. The app
// tier can stay running -- these hold deploy artifacts (stable when no deploy is in
// flight) plus throwaway logs, so a live read-only tar is consistent enough.
if (app) {
    info("capturing deploy-target volumes for a warm app tier -> $auxDir")
    appVols.each { vn -> capture("${project}_${vn}", new File(auxDir, "${vn}.tgz")) }
}
if (caches) {
    info("capturing gradle/maven build caches (large) -> $auxDir")
    cacheVols.each { vn -> capture("${project}_${vn}", new File(auxDir, "${vn}.tgz")) }
}

// Restart exactly what we stopped (leave an already-down stack down), then mark the run
// complete so the shutdown hook stands down -- the stack is back up from here.
stopped.each { svc, cid ->
    info("restarting $svc")
    runCommand(['docker', 'start', cid])
}
state.completed = true

// Local-only images: bare names (no registry prefix), built for the host's
// native arch and loaded straight into the local docker image store. These
// carry real data and must never be pushed (see header).
def dbImage   = "zfin-db-preloaded:$tag"
def solrImage = "zfin-solr-preloaded:$tag"

info("building $dbImage (local only; arch follows the base image)")
runCommand(['docker', 'build',
    '--build-arg', "ZFIN_RELEASE=$release",
    '--build-arg', 'PGDATA_TARBALL=pgdata.tgz',
    '-f', new File(DOCKER, 'postgresql/preloaded.Dockerfile').absolutePath,
    '-t', dbImage,
    new File(DOCKER, 'postgresql').absolutePath])

info("building $solrImage (local only; arch follows the base image)")
runCommand(['docker', 'build',
    '--build-arg', "ZFIN_RELEASE=$release",
    '--build-arg', 'SOLRVAR_TARBALL=solrvar.tgz',
    '-f', new File(DOCKER, 'solr/preloaded.Dockerfile').absolutePath,
    '-t', solrImage,
    new File(DOCKER, 'solr').absolutePath])

if (!keep) { pgTarball.delete(); solrTarball.delete() }

info("done:")
println "     $dbImage"
println "     $solrImage"
if (app)    println "     $auxDir/  ->  ${appVols.join(', ')}  (warm app tier)"
if (caches) println "     $auxDir/  ->  ${cacheVols.join(', ')}  (warm build caches)"
info("point a feature .env at these via ZFIN_DB_IMAGE / ZFIN_SOLR_IMAGE (see docker-compose.preloaded.yml).")
if (app || caches) info("new-feature auto-detects these tarballs for tag '$tag' and warms the feature accordingly.")
}
}
