#!/usr/bin/env groovy
// z fresh-install -- guided day-zero setup on a BARE workstation (nothing in Docker yet).
//
// Interactive. Run it DIRECTLY -- it bootstraps the very .zenv the other z-commands need:
//   docker/utils/z fresh-install [--dry-run]
//
// Steps:
//   1. verify the machine is ZFIN-fresh (no ZFIN volumes / images / containers)
//   2. ask: build stock images locally, or pull from ghcr.io
//   3. check the init inputs exist (db dump, solr dump; optional bowtie/blast/loadup)
//   4. ask for an optional first ticket (e.g. ZFIN-789)
//   5. drive the existing tools to stand up a loaded base stack, and optionally a feature:
//        create-zenv.groovy (base .zenv) -> zbuild all -> [build-preloaded + new-feature]
//
// --dry-run runs the checks and prints the plan (using defaults, no prompts) without
// executing the heavy build/load steps.

class FreshInstall {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info; def captureOutput = zfinUtil.&captureOutput;
        def runCommand = zfinUtil.&runCommand
        def UTILS = zfinUtil.UTILS
        def DOCKER = zfinUtil.DOCKER
        def REPO = zfinUtil.REPO
        def envFile = new File(DOCKER, '.env')
        if (!envFile.exists()) die("$envFile not found -- run from a ZFIN checkout")

        def dryRun = (args as List).contains('--dry-run')
        ; (args as List).findAll { it.startsWith('-') && it != '--dry-run' }.each { die("unknown flag: $it", 2) }

// read docker/.env into a map (paths come from .env only -- no ambient fallback here)
        def dotenv = zfinUtil.dotenv()

// --- 1. fresh check (scoped to ZFIN signals; ignores unrelated Docker) -----------
        info("checking the machine is ZFIN-fresh...")
        def volumes = captureOutput(['docker', 'volume', 'ls', '--format', '{{.Name}}']).readLines().findAll { it ==~ /.*_(pg_data|solr_var)$/ }
        def images = captureOutput(['docker', 'images', '--format', '{{.Repository}}:{{.Tag}}']).readLines().findAll { it.startsWith('ghcr.io/zfin/') || it.contains('preloaded') }.unique()
        def zfinCts = captureOutput(['docker', 'ps', '-a', '--format', '{{.Names}}\t{{.Image}}']).readLines()
                .findAll { it.contains('\t') && (it.split('\t')[1].contains('zfin') || it.split('\t')[1].contains('preloaded')) }
                .collect { it.split('\t')[0] }
        def found = []
        if (volumes) found << "volumes: ${volumes.join(', ')}"
        if (images) found << "images: ${images.take(6).join(', ')}${images.size() > 6 ? ' …' : ''}"
        if (zfinCts) found << "containers: ${zfinCts.join(', ')}"
        if (found) {
            System.err.println("!! this machine is NOT ZFIN-fresh -- found:")
            found.each { System.err.println("   - $it") }
            die("z fresh-install is for a clean machine. Tear those down first (or use z feature / z build directly).")
        }
        info("  fresh ✓ (no ZFIN volumes / images / containers)")

// --- 3. init inputs (paths come from docker/.env) --------------------------------
        info("checking init inputs...")
        def home = System.getProperty('user.home')
        def expandTilde = { String s -> s?.startsWith('~') ? home + s.substring(1) : s }   // Java File doesn't expand ~
        def checkPath = { String var, boolean required ->
            def p = expandTilde(dotenv[var])
            def ok = p && new File(p).exists()
            println("   ${ok ? '✓' : (required ? '✗' : '–')} ${var.padRight(38)} ${p ?: '(unset)'}${ok ? '' : (required ? '   MISSING (required)' : '   (optional, skipped)')}")
            [required: required, ok: ok, var: var]
        }
        def inputs = [
                checkPath('DOCKER_DB_UNLOADS_PATH', true),                    // db dump
                checkPath('DOCKER_SOLR_UNLOADS_PATH', true),                  // solr dump
                checkPath('DOCKER_BOWTIE_PATH', false),
                checkPath('DOCKER_ABBLAST_PATH', false),
                checkPath('DOCKER_BLASTSERVER_BLAST_DATABASE_PATH', false),
                checkPath('DOCKER_LOADUP_PATH', false),
        ]
        def missingRequired = inputs.findAll { it.required && !it.ok }
        if (missingRequired) die("missing required init input(s): ${missingRequired*.var.join(', ')} -- set them in docker/.env and re-run.")

// loaddb (build.gradle loadDatabase) picks the db-unload subdir with the NEWEST mtime,
// then reads its last file -- so that subdir must actually contain a dump. An empty
// newer dir shadows the real one and fails deep in `gradle loaddb` ("Cannot access
// last() element from an empty Array"). Catch it here, before the long build.
        def dbUnloads = expandTilde(dotenv['DOCKER_DB_UNLOADS_PATH'])
        if (dbUnloads && new File(dbUnloads).isDirectory()) {
            def subs = (new File(dbUnloads).listFiles() ?: [] as File[]).findAll { it.isDirectory() }
            def newest = subs ? subs.max { it.lastModified() } : null   // mirrors loadDatabase's files[0]
            def dumps = newest?.listFiles()?.findAll { it.isFile() }
            if (!newest) die("db dump: no dated subdir under $dbUnloads")
            if (!dumps) die("db dump: loaddb picks the newest-mtime dir '${newest.name}', but it's EMPTY.\n" +
                    "   Remove empty dirs under $dbUnloads so the one holding the .bak is newest.")
            println("   ✓ db dump: loaddb will use ${newest.name}/${dumps.last().name}")
        }

// getLatestSolrIndex (build.gradle) restores from <solr-unloads>/<instance>/v9 and needs
// snapshot.* dirs in there (Solr 9 format, ZFIN-10171); the instance segment defaults to
// `zfindb`, the shared production snapshot everyone restores from (-PsolrSourceInstance
// overrides it, and backups are written per-instance so a dev index build can't clobber it --
// see reference/solr-reindex.md). Legacy full-SOLR_HOME dumps can't be restored at all.
// Check the SAME path the task reads: this check used to look at <solr-unloads>/v9, one
// segment short, so a machine with only the pre-ZFIN-10171 layout got a green light here and
// then failed in `z build load-solr` with "no snapshots at ..." -- after the image build and
// the full db load, i.e. the most expensive possible place to find out.
        def solrUnloads = expandTilde(dotenv['DOCKER_SOLR_UNLOADS_PATH'])
        if (solrUnloads) {
            def instance = 'zfindb'
            def v9 = new File(new File(solrUnloads, instance), 'v9')
            def snapsIn = { File d -> d.isDirectory() ? (d.listFiles() ?: [] as File[]).findAll { it.isDirectory() && it.name.startsWith('snapshot.') } : [] }
            def snaps = snapsIn(v9)
            if (!snaps) {
                // Name the legacy layout explicitly if that's what's there -- "no snapshots"
                // reads like "you have no backup" when the truth is "it's one dir too high".
                def legacy = snapsIn(new File(solrUnloads, 'v9'))
                die("solr dump: no snapshot.* dir under ${v9} (Solr 9 format).\n" +
                        (legacy ? "   Found ${legacy.size()} snapshot(s) at ${new File(solrUnloads, 'v9')} instead -- that's the\n" +
                                "   pre-ZFIN-10171 layout, one level too high. Move it into place:\n" +
                                "     mkdir -p ${v9} && mv ${legacy.max { it.lastModified() }} ${v9}/\n"
                                : "   getLatestSolrIndex needs ${instance}/v9/snapshot.<ts>/ -- not a legacy\n" +
                                "   full-SOLR_HOME dump. Fetch one, then re-run.\n"))
            }
            println("   ✓ solr dump: getLatestSolrIndex will use ${instance}/v9/${snaps.max { it.lastModified() }.name}")
        }

// --- 2 & 4. choices (prompt interactively; dry-run uses defaults) ----------------
        def project = dotenv['COMPOSE_PROJECT_NAME'] ?: 'zfin_org'
        def host = dotenv['DOCKER_VIRTUAL_HOST'] ?: 'zfin.org'
        def tag = 'dev'
        def buildImages = false
        def firstTicket = null

        if (dryRun) {
            info("--dry-run: using defaults (pull images; no first ticket)")
        } else {
            def con = System.console()
            if (!con) die("no TTY -- run z fresh-install from a terminal (or --dry-run to preview)")
            def imgChoice = con.readLine("Images -- (b)uild locally or (p)ull from ghcr.io? [p]: ")?.trim()?.toLowerCase()
            buildImages = imgChoice?.startsWith('b')
            firstTicket = con.readLine("First ticket to start a feature stack (blank = base stack only): ")?.trim() ?: null
            def go = con.readLine("Proceed to set up '$project' (${buildImages ? 'build' : 'pull'} images, full load)${firstTicket ? " + feature $firstTicket" : ''}? [y/N]: ")?.trim()?.toLowerCase()
            if (!go?.startsWith('y')) die("aborted.", 0)
        }

// --- 5. the plan (everything routes through the single front door `z`) -----------
        def zExe = new File(UTILS, 'z').absolutePath
        def baseCompose = new File(DOCKER, 'docker-compose.yml').absolutePath

        def plan = []
        plan << [zExe, 'create-zenv', '--dir', REPO.absolutePath, '--project', project, '--compose', baseCompose, '--env-file', envFile.absolutePath, '--host', host]
        plan << [zExe, 'build', 'all'] + (buildImages ? ['--build'] : [])
        if (firstTicket) {
            plan << [zExe, 'feature', 'build-preloaded', '--project', project, '--tag', tag]
            // bake preloaded from the freshly-loaded base
            plan << [zExe, 'feature', 'new', firstTicket, '--tag', tag, '--up', '--hosts']
        }

        println "\nPlan:"
        plan.each { println "  \$ ${it.join(' ')}" }

        if (dryRun) {
            info("dry-run: not executing."); return
        }

// A reused box may carry stale cross-arch layers that make `docker compose build` emit
// mixed-arch images (e.g. an amd64 `compile` on an arm64 host -> emulation). Prune so the
// build is clean + native -- but ONLY when we're actually building: if the user chose to
// pull, there's nothing to build and no reason to wipe the whole machine's build cache.
        if (buildImages) {
            info("pruning docker build cache (clean, native-arch image builds)")
            new ProcessBuilder('docker', 'builder', 'prune', '-af').inheritIO().start().waitFor()
        }

        println ""
// The base stack's compose env for `z build` (so it targets '$project', not the compose-dir
// default). z passes it through to zbuild; the other steps take their target via args.
        def baseEnv = ['COMPOSE_PROJECT_NAME': project, 'COMPOSE_FILE': baseCompose, 'COMPOSE_ENV_FILES': envFile.absolutePath]
        plan.eachWithIndex { cmd, i ->
            info("step ${i + 1}/${plan.size()}: ${cmd.join(' ')}")
            def pb = new ProcessBuilder(cmd*.toString()).inheritIO()
            if (cmd.size() > 1 && cmd[1] == 'build') baseEnv.each { k, v -> pb.environment().put(k, v) }
            def code = pb.start().waitFor()
            if (code != 0) die("step ${i + 1} failed ($code)", code)
        }
        info("fresh install complete. Activate the base stack:  source ${new File(REPO, '.zenv/activate')}")
        if (firstTicket) info("...and your feature:  cd ../wt-${firstTicket.toLowerCase()} && source .zenv/activate")
    }
}
