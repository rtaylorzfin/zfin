#!/usr/bin/env groovy
// Build "preloaded" images from an already-loaded dev stack.
//
// Prereq: a normal dev stack that has been loaded the usual way, e.g.
//   gradle getdb && gradle loaddb && gradle liquibasePreBuild && gradle liquibasePostBuild
//   gradle getsolr && gradle loadsolr
//
// This captures that stack's pg_data + solr_var volumes and bakes them into
//   zfin-db-preloaded:<tag>  and  zfin-solr-preloaded:<tag>
// which a feature stack boots instantly with per-container copy-on-write data
// (no ZFS required). See docker/postgresql/preloaded.Dockerfile and
// docker/solr/preloaded.Dockerfile.
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
// exactly those afterward -- so a stack that was already down is left down, and
// --slim works regardless of the starting state. No --no-stop knob needed.
//
// Usage:
//   docker/build-preloaded.groovy [--project NAME] [--tag TAG] [--slim] [--keep-tarballs]
//
//   --project NAME   Compose project whose volumes to capture (default: $COMPOSE_PROJECT_NAME or "dazed")
//   --tag TAG        Preloaded image tag (default: today's date, YYYY-MM-DD)
//   --slim           Before capture, empty jobs-only tables + reset WAL for a leaner
//                    image. NOTE: mutates the source stack's DB, but only tables proven
//                    NOT read by the webapp (reloadable), and WAL auto-regrows. See
//                    workbench/db-slim-candidates.md.
//   --keep-tarballs  Leave the intermediate .tgz files in the build contexts

// --- tiny shell helpers -----------------------------------------------------
def die(String msg, int code = 1) { System.err.println("!! $msg"); System.exit(code) }
def info(String msg) { println(">> $msg") }

/** Run a command, streaming its stdout/stderr/stdin to the console.
 *  Dies on a nonzero exit unless check:false is passed. */
int sh(List cmd, Map opts = [:]) {
    def code = new ProcessBuilder(cmd*.toString()).inheritIO().start().waitFor()
    if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
    code
}

/** Run a command with output/err discarded; return its exit code (never dies). */
int quiet(List cmd) {
    new ProcessBuilder(cmd*.toString())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start().waitFor()
}

/** Run a command; return its trimmed stdout (stderr discarded). Never dies. */
String capOut(List cmd) {
    def p = new ProcessBuilder(cmd*.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    def out = p.inputStream.text
    p.waitFor()
    out.trim()
}

/** Run a command, feeding `input` to its stdin; stdout/stderr stream to console. */
int shIn(List cmd, String input, Map opts = [:]) {
    def p = new ProcessBuilder(cmd*.toString())
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    p.outputStream.withWriter('UTF-8') { it << input }
    def code = p.waitFor()
    if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
    code
}

// --- locate self + load docker/.env -----------------------------------------
// script lives in docker/utils/; DOCKER is the docker/ dir (one up)
def DOCKER = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile.parentFile.absoluteFile

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
def project = envOf('COMPOSE_PROJECT_NAME', 'dazed')
def tag     = java.time.LocalDate.now().toString()   // YYYY-MM-DD
def keep    = false
def slim    = false

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
        case '--keep-tarballs': keep = true;         break
        default: die("unknown arg: ${argv[i]}", 2)
    }
}

def release = envOf('ZFIN_RELEASE')
if (!release) die("ZFIN_RELEASE must be set (from docker/.env or the environment)")

// Stock db image: the pg engine matching this data, AND the image we tar the
// volumes with (it has GNU tar + gzip and runs as root) -- so capture never
// depends on pulling alpine from Docker Hub.
def stockDb = "ghcr.io/zfin/zfin-db:$release"

def pgVol   = "${project}_pg_data"
def solrVol = "${project}_solr_var"

info("preloaded build: project=$project release=$release tag=$tag")

// Both named volumes must exist, or the capture would silently produce empties.
[pgVol, solrVol].each { v ->
    if (quiet(['docker', 'volume', 'inspect', v]) != 0)
        die("volume '$v' not found -- is project '$project' loaded? (try --project)")
}

// Empty jobs-only tables and shed stale WAL so the captured snapshot is lean.
// Safe by design: the truncated tables are not read by the webapp
// (workbench/db-slim-candidates.md) and are reloadable. WAL is collapsed with
// pg_resetwal (a single CHECKPOINT won't -- PostgreSQL's segment-retention
// estimate decays only ~2%/checkpoint). pg_resetwal is safe ONLY after a clean
// shutdown, which we guarantee by stopping the throwaway server first. Runs only
// while the real db container is stopped (single writer on the volume).
def slimPg = {
    def img  = stockDb
    def name = "preloaded-slim-${ProcessHandle.current().pid()}"
    info("[slim] throwaway postgres on $pgVol: TRUNCATE jobs-only tables")
    sh(['docker', 'run', '-d', '--name', name,
        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
        '-v', "${pgVol}:/var/lib/postgresql", img])

    def ready = false
    for (int i = 0; i < 60 && !ready; i++) {
        if (quiet(['docker', 'exec', name, 'pg_isready', '-U', 'postgres', '-d', 'zfindb']) == 0) ready = true
        else sleep(1000)
    }
    if (!ready) {
        sh(['docker', 'logs', '--tail', '30', name], check: false)
        quiet(['docker', 'rm', '-f', name])
        die("[slim] postgres not ready")
    }

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
    shIn(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-d', 'zfindb', '-v', 'ON_ERROR_STOP=1'], sql)

    sh(['docker', 'stop', name])            // clean shutdown: required precondition for pg_resetwal
    quiet(['docker', 'rm', name])
    info("[slim] pg_resetwal to shed stale WAL segments (safe after the clean shutdown above)")
    sh(['docker', 'run', '--rm', '-u', 'postgres', '-v', "${pgVol}:/var/lib/postgresql",
        '--entrypoint', 'bash', img, '-c', 'pg_resetwal -f "$PGDATA"'])
    info("[slim] emptied: ${slimTables.join(' ')}; WAL reset")
}

// Quiesce db/solr so the tarred on-disk state is consistent (not mid-write).
// Stop ONLY the services that are actually running, remember their container IDs,
// and restart exactly those after capture -- a down stack stays down, and --slim
// always gets a stopped db regardless of starting state. Detected via compose
// labels so no compose file / -f flags are needed.
def runningContainer = { String svc ->
    capOut(['docker', 'ps', '-q',
            '--filter', "label=com.docker.compose.project=$project",
            '--filter', "label=com.docker.compose.service=$svc"])
}
def stopped = [:]   // service -> container id we stopped
['db', 'solr'].each { svc ->
    def cid = runningContainer(svc)
    if (cid) {
        info("stopping $svc ($cid) for a consistent capture")
        sh(['docker', 'stop', cid])
        stopped[svc] = cid
    }
}

if (slim) slimPg()

def capture = { String vol, File out ->
    info("capturing $vol -> $out")
    // --entrypoint tar bypasses the postgres entrypoint; -u 0 (root) so we can
    // read both the pg (postgres-owned) and solr (solr-owned) volume contents.
    sh(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
        '-v', "${vol}:/data:ro",
        '-v', "${out.parentFile.absolutePath}:/out",
        stockDb, 'czf', "/out/${out.name}", '-C', '/data', '.'])
}

def pgTarball   = new File(DOCKER, 'postgresql/pgdata.tgz')
def solrTarball = new File(DOCKER, 'solr/solrvar.tgz')
capture(pgVol, pgTarball)
capture(solrVol, solrTarball)

// Restart exactly what we stopped (leave an already-down stack down).
stopped.each { svc, cid ->
    info("restarting $svc")
    sh(['docker', 'start', cid])
}

// Local-only images: bare names (no registry prefix), built for the host's
// native arch and loaded straight into the local docker image store. These
// carry real data and must never be pushed (see header).
def dbImage   = "zfin-db-preloaded:$tag"
def solrImage = "zfin-solr-preloaded:$tag"

info("building $dbImage (local only; arch follows the base image)")
sh(['docker', 'build',
    '--build-arg', "ZFIN_RELEASE=$release",
    '--build-arg', 'PGDATA_TARBALL=pgdata.tgz',
    '-f', new File(DOCKER, 'postgresql/preloaded.Dockerfile').absolutePath,
    '-t', dbImage,
    new File(DOCKER, 'postgresql').absolutePath])

info("building $solrImage (local only; arch follows the base image)")
sh(['docker', 'build',
    '--build-arg', "ZFIN_RELEASE=$release",
    '--build-arg', 'SOLRVAR_TARBALL=solrvar.tgz',
    '-f', new File(DOCKER, 'solr/preloaded.Dockerfile').absolutePath,
    '-t', solrImage,
    new File(DOCKER, 'solr').absolutePath])

if (!keep) { pgTarball.delete(); solrTarball.delete() }

info("done:")
println "     $dbImage"
println "     $solrImage"
info("point a feature .env at these via ZFIN_DB_IMAGE / ZFIN_SOLR_IMAGE (see docker-compose.preloaded.yml).")
