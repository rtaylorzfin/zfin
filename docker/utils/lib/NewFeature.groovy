#!/usr/bin/env groovy
// Provision an isolated feature dev stack: a git worktree plus its own Compose
// project (own network, volumes, loopback IP, hostname) booted from preloaded
// DB + Solr images. Several feature branches can then run in parallel without
// branch-switching or reloading data. See workbench/feature-lifecycle.md.
//
// What Compose already handles per-project (no work here): the private network,
// per-project volumes, and intra-network DNS (`db`/`solr` resolve to THIS
// project's containers). What this script handles are the HOST-side concerns
// that escape the Docker network -- published ports and the loopback/hostname
// mapping -- plus the worktree + per-feature .env.
//
// Normally invoked as `zfeature new [<ticket>] [opts]` from an activated .zenv;
// runs standalone too. With no <name> and a TTY it prompts interactively.
//
// Usage:
//   zfeature new [<name>] [--base BRANCH] [--branch NAME]
//                         [--tag TAG] [--ip 127.0.0.X] [--up] [--hosts]
//
//   <name>          Feature id, e.g. ZFIN-9002 -> project "zfin-9002",
//                   worktree "wt-zfin-9002", host "zfin-9002.zfin.test".
//                   Omit it (from a terminal) to be prompted.
//   --base BRANCH   Start point for the new branch (default: main; a warning fires
//                   if invoked from a secondary worktree so you don't base off it)
//   --branch NAME   Branch to create (default: <name>)
//   --tag TAG       Preloaded image tag (default: newest local zfin-db-preloaded;
//                   override via $PRELOADED_TAG)
//   --ip 127.0.0.X  Pin the loopback IP for published ports (default: auto)
//   --ip-base N     Start auto-allocation at 127.0.0.N (or $ZFIN_FEATURE_IP_BASE;
//                   default 2). Allocation skips the base stack's IP, other features'
//                   IPs, and anything currently bound -- picking the first free octet.
//   --up            Bring up the preloaded data tier (db + solr) after provisioning.
//                   If a warm-app snapshot exists for the tag (build-preloaded --app),
//                   ALSO populate the deploy volumes and start tomcat/httpd so the stack
//                   comes up serving the source branch's deploy. Otherwise the app tier
//                   stays down until the webapp is built+deployed (see the next: block).
//   --no-app        Skip the warm app tier even if a snapshot exists (cold app tier:
//                   build + deploy yourself). No effect without a snapshot.
//   --no-caches     Skip restoring the gradle/maven build caches even if captured
//                   (build-preloaded --caches). No effect without them.
//   --hosts         Map <host> -> <ip> in /etc/hosts via a hostctl profile named
//                   after the feature slug (uses sudo). Teardown is a clean
//                   `sudo hostctl remove <slug>`.
//   --shared-db     Share the `zfin_shared` stack's db+solr instead of seeding this
//                   feature's own copy (needs `z shared up` first). READ-MOSTLY only:
//                   writes/migrations/reindex are shared with every other --shared-db
//                   feature. Uses docker-compose.shared-db.yml (not the preloaded overlay).
//
// After provisioning, `source <worktree>/.zenv/activate` (venv-style) makes
// zrun/zup/zdown/zexec -- and bare `docker compose` -- resolve to this feature;
// `deactivate` restores the shell. The printed next: block shows the full sequence.

// --- shared helpers + roots (via ZfinUtil, passed in by z) --------------------
// Roots anchor to the PRIMARY checkout (z resolves them once), so running from inside a
// worktree is fine -- provisioning never depends on where you happen to be.
class NewFeature {
def run(List args, ZfinUtil zfinUtil) {
    def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
    def imageExists = zfinUtil.&imageExists
    def DOCKER    = zfinUtil.DOCKER   // docker
    def REPO      = zfinUtil.REPO     // primary checkout
    def WT_PARENT = REPO.parentFile   // worktrees live alongside it

// --- defaults + args ---------------------------------------------------------
def base    = 'main'
def baseArg = false
def tagArg  = null
def ip      = ''
def ipBase  = null
def branch  = ''
def doUp     = false
def doHosts  = false
def doApp    = true
def doCaches = true
def doSharedDb = false
def name     = ''

def argv = args as List
for (int i = 0; i < argv.size(); i++) {
    switch (argv[i]) {
        case '--base':    base = argv[++i]; baseArg = true; break
        case '--branch':  branch = argv[++i]; break
        case '--tag':     tagArg = argv[++i]; break
        case '--ip':      ip = argv[++i];     break
        case '--ip-base': ipBase = argv[++i]; break
        case '--up':        doUp = true;        break
        case '--no-app':    doApp = false;      break
        case '--no-caches': doCaches = false;   break
        case '--hosts':     doHosts = true;     break
        case '--shared-db': doSharedDb = true;  break
        default:
            if (argv[i].startsWith('-')) die("unknown arg: ${argv[i]}", 2)
            name = argv[i]
    }
}

def baseEnvFile = new File(DOCKER, '.env')
if (!baseEnvFile.exists()) die("$baseEnvFile not found (needed as the base env)")

// Tag selection: --tag > $PRELOADED_TAG > newest local preloaded image. The tag
// picks WHICH preloaded snapshot to boot from (dated builds, full vs lean, a
// branch-specific bake) -- a selector, not a constant; the default grabs the newest
// you've built, so the common case needs no --tag / env var.
def newestPreloadedTag = { ->
    def imgs = captureOutput(['docker', 'images', 'zfin-db-preloaded', '--format', '{{.CreatedAt}}\t{{.Tag}}'])
                .readLines().findAll { it?.trim() && !it.endsWith('\t<none>') }
    imgs ? imgs.sort().last().split('\t').last().trim() : null
}
def tag = tagArg ?: System.getenv('PRELOADED_TAG') ?: newestPreloadedTag()

// No <name> given -> interactive prompts (needs a TTY; piped input errors with usage).
def askYesNo = { con, String prompt, boolean dflt ->
    def line = con.readLine("$prompt [${dflt ? 'Y/n' : 'y/N'}]: ")?.trim()?.toLowerCase()
    line ? line.startsWith('y') : dflt
}
// Worktree awareness: a new feature bases on `main` off the PRIMARY checkout, NOT
// the current worktree's branch. If we're invoked from a secondary worktree, that's
// easy to forget -- so prompt (interactive) or warn (non-interactive) about the base.
def cwdTop     = captureOutput(['git', '-C', new File('.').absolutePath, 'rev-parse', '--show-toplevel'])
def inWorktree = cwdTop && new File(cwdTop).canonicalFile != REPO.canonicalFile
def cwdBranch  = inWorktree ? captureOutput(['git', '-C', new File('.').absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) : ''

if (!name) {
    def con = System.console()
    if (!con) die("usage: new-feature.groovy <name> [--base B] [--branch B] [--tag T] [--ip 127.0.0.X] [--up] [--hosts]", 2)
    println "New feature stack -- press Enter to accept [defaults]."
    while (!name) { name = con.readLine("  ticket / feature id (e.g. ZFIN-789): ")?.trim(); if (!name) System.err.println("    (required)") }
    if (inWorktree) println "  (you're in worktree '${new File(cwdTop).name}' on '$cwdBranch'; new features usually base on 'main')"
    base = (con.readLine("  base branch [$base]: ")?.trim()) ?: base
    def tIn = con.readLine("  preloaded tag [${tag ?: 'none built yet'}]: ")?.trim()
    if (tIn) tag = tIn
    doUp    = askYesNo(con, "  bring up db+solr now?", true)
    doHosts = askYesNo(con, "  map hostname via hostctl (sudo)?", true)
} else if (inWorktree && !baseArg) {
    System.err.println("!! note: invoked from worktree '${new File(cwdTop).name}' (branch $cwdBranch) -- basing the new feature on '$base' off the primary checkout (pass --base to override)")
}

if (!tag) die("no zfin-db-preloaded images found locally. Build one: z feature build-preloaded [--tag TAG]")
info("preloaded tag: $tag")

// --shared-db: this feature runs NO local db/solr (see docker-compose.shared-db.yml), so it
// doesn't need the preloaded images -- it needs the shared data stack up (its external network).
if (doSharedDb) {
    if (zfinUtil.runQuietly(['docker', 'network', 'inspect', 'zfin_shared_net']) != 0)
        die("--shared-db needs the shared data stack up first:  z shared up")
    info("shared db+solr: using the zfin_shared stack (no per-feature copy)")
} else {
    // Fail fast if the preloaded images for this tag aren't built locally. Otherwise
    // compose (pull_policy:never) errors partway through `up` -- AFTER we've already
    // created the worktree, .env, hosts entry, and empty volumes that then won't
    // re-seed. Checking here means a tag mismatch leaves no partial state behind.
    ['zfin-db-preloaded', 'zfin-solr-preloaded'].each { repo ->
        if (!imageExists("$repo:$tag")) {
            def have = captureOutput(['docker', 'images', '--format', '{{.Repository}}:{{.Tag}}'])
                        .readLines().findAll { it.contains('preloaded') }
            die("preloaded image '$repo:$tag' not found locally.\n" +
                "   build it:      z feature build-preloaded --tag $tag\n" +
                "   or set a tag:  --tag <tag>  /  export PRELOADED_TAG=<tag>" +
                (have ? "\n   have locally:  ${have.join(', ')}" : "\n   (no preloaded images built yet)"))
        }
    }
}

// Warm app tier: build-preloaded --app leaves the deployed-app volumes as tarballs
// under docker/preloaded-app/<tag>/. If all four are present (and not --no-app), we
// extract them into this feature's fresh volumes so tomcat/httpd come up already
// serving the source branch's deploy -- the feature then dirtydeploys just its delta.
def appVols   = zfinUtil.APP_VOLS      // shared volume contract (single source, also read by BuildPreloaded)
def cacheVols = zfinUtil.CACHE_VOLS
def auxDir    = zfinUtil.auxDir(tag)
def haveTars  = { List vns -> vns.every { new File(auxDir, "${it}.tgz").isFile() } }
def warmApp    = doApp    && haveTars(appVols)
def warmCaches = doCaches && haveTars(cacheVols)
if (doApp && !warmApp && auxDir.isDirectory())
    info("note: $auxDir exists but is missing app tarballs -- app tier will NOT be warmed")
info(warmApp ? "warm app tier: yes (from $auxDir)"
             : "warm app tier: no" + (doApp ? " (build-preloaded --app --tag $tag to enable)" : " (--no-app)"))
if (warmCaches) info("warm build caches: yes (gradle + maven, from $auxDir)")

def slug    = name.toLowerCase()          // Compose projects must be lowercase
def project = slug
branch      = branch ?: name
def host    = "${slug}.zfin.test"
def wt      = new File(WT_PARENT, "wt-${slug}")
def wtPath  = wt.absolutePath

// Allocate a free 127.0.0.X for this feature's published ports, unless pinned with
// --ip. Compose can't do this for us: published ports must land on a per-feature IP so
// stacks don't fight over :443/:5432 and <name>.zfin.test resolves to exactly one stack.
// "Taken" is gathered from three places so we never collide with the base stack, another
// feature, or whatever is bound right now:
//   - the base docker/.env's reserved IPs (any 127.0.0.N in it, e.g. DOCKER_DB_PORT),
//   - every existing feature worktree's LOOPBACK_IP,
//   - any 127.0.0.N currently published by a running container.
// Scan upward from a configurable start (--ip-base / $ZFIN_FEATURE_IP_BASE, default 2)
// to the first free octet.
if (!ip) {
    def taken = [1] as Set          // .1 is the loopback default; never hand it out
    def addOctets = { String s -> (s =~ /127\.0\.0\.(\d+)/).each { taken << (it[1] as int) } }
    baseEnvFile.readLines().each(addOctets)
    (WT_PARENT.listFiles() ?: [] as File[]).findAll { it.isDirectory() && it.name.startsWith('wt-') }.each { d ->
        def f = new File(d, 'docker/.env')
        if (f.isFile()) f.readLines().findAll { it.startsWith('LOOPBACK_IP=') }.each(addOctets)
    }
    captureOutput(['docker', 'ps', '--format', '{{.Ports}}']).readLines().each(addOctets)

    def startStr = ipBase ?: System.getenv('ZFIN_FEATURE_IP_BASE')
    int start = 2
    if (startStr) {
        def raw = startStr.contains('.') ? startStr.tokenize('.').last() : startStr
        if (!(raw ==~ /\d+/)) die("--ip-base must be a number 2-254 or a 127.0.0.X address (got '$startStr')", 2)
        start = raw as int
        if (start < 2 || start > 254) die("--ip-base out of range: $start (usable octets are 2-254)", 2)
    }
    int octet = start
    while (octet <= 254 && taken.contains(octet)) octet++
    if (octet > 254) die("no free 127.0.0.X in [$start, 254] (taken: ${taken.sort().join(', ')})")
    ip = "127.0.0.$octet"
    def skipped = taken.findAll { it >= start }.sort()
    info("allocated $ip" + (skipped ? "  (skipped in-use: ${skipped.join(', ')})" : ''))
}

// macOS needs an explicit loopback alias for any 127.0.0.X other than .1, or the
// stack's published ports can't bind (Linux treats all of 127/8 as loopback, so this
// is a no-op there). Add it with sudo -- otherwise `--up` fails with "can't assign
// requested address". Idempotent: skip if already present. (lo0 aliases don't survive
// a reboot; teardown can drop it with `sudo ifconfig lo0 -alias <ip>`.)
if (System.getProperty('os.name')?.toLowerCase()?.contains('mac') && ip != '127.0.0.1') {
    if (captureOutput(['ifconfig', 'lo0']).contains("inet $ip ")) {
        info("loopback alias $ip already present")
    } else {
        info("adding loopback alias $ip (macOS, sudo) so published ports can bind")
        runCommand(['sudo', 'ifconfig', 'lo0', 'alias', ip])
    }
}

info("feature=$name project=$project host=$host ip=$ip tag=$tag base=$base")

// 1. worktree + branch (separate host path => its own mounted source tree)
if (!wt.isDirectory()) {
    runCommand(['git', '-C', REPO.absolutePath, 'worktree', 'add', wtPath, '-b', branch, base])
} else {
    info("worktree $wtPath already exists, reusing")
}


// 2. per-feature .env: start from the base env, strip the keys we own, append our
//    overrides. Published ports bind to $ip with standard ports so URLs are clean
//    (https://$host, no port suffix). The preloaded images are LOCAL-ONLY bare
//    names (no registry prefix) -- built per-machine by build-preloaded.groovy and
//    never pushed (they carry real data).
def owned = ['COMPOSE_PROJECT_NAME', 'DOCKER_SOURCE_ROOTS_PATH', 'DOCKER_VIRTUAL_HOST',
             'DOCKER_HTTPD_HTTP_PORT', 'DOCKER_HTTPD_HTTPS_PORT', 'DOCKER_DB_PORT',
             'DOCKER_SOLR_PORT', 'DOCKER_JENKINS_HTTP_PORT', 'DOCKER_TOMCATDEBUG_PORT',
             'ZFIN_DB_IMAGE', 'ZFIN_SOLR_IMAGE', 'LOOPBACK_IP'] as Set

def keyOf = { String line ->
    def m = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=/)
    m.find() ? m.group(1) : null
}
def kept = baseEnvFile.readLines().findAll { !(keyOf(it) in owned) }

new File(wt, 'docker').mkdirs()
def outEnv = new File(wt, 'docker/.env')
outEnv.text = kept.join('\n') + '\n'
outEnv << """
# --- added by new-feature.groovy for $name ---
COMPOSE_PROJECT_NAME=$project
DOCKER_SOURCE_ROOTS_PATH=$wtPath
DOCKER_VIRTUAL_HOST=$host
LOOPBACK_IP=$ip
DOCKER_HTTPD_HTTP_PORT=$ip:80
DOCKER_HTTPD_HTTPS_PORT=$ip:443
DOCKER_DB_PORT=$ip:5432
DOCKER_SOLR_PORT=$ip:8983
DOCKER_JENKINS_HTTP_PORT=$ip:9499
DOCKER_TOMCATDEBUG_PORT=$ip:5000
ZFIN_DB_IMAGE=zfin-db-preloaded:$tag
ZFIN_SOLR_IMAGE=zfin-solr-preloaded:$tag
"""

// 2b. venv-style activation. create-zenv writes .zenv/{activate,bin/z}: `source .zenv/activate`
//     puts `z` on PATH + defines the zrun/zexec/zup/... shell functions and points them
//     (and bare `docker compose`) at THIS feature, collapsing the long -p/--env-file/-f
//     invocation. `deactivate` restores the shell. create-zenv also keeps .zenv/ out of git.
//     Called in-process (same JVM, same zfinUtil) rather than spawned.
// --shared-db uses the consumer overlay (attach to zfin_shared_net, suppress local db/solr)
// INSTEAD of the preloaded overlay -- no local db/solr, so no preloaded images to point at.
def dataOverlay = doSharedDb ? 'docker-compose.shared-db.yml' : 'docker-compose.preloaded.yml'
def composeFiles = "${new File(DOCKER, 'docker-compose.yml').absolutePath}:${new File(DOCKER, dataOverlay).absolutePath}"
new CreateZenv().run(['--dir', wtPath, '--project', project, '--compose', composeFiles,
    '--env-file', outEnv.absolutePath, '--tag', tag, '--host', host], zfinUtil)

// 3. name resolution. On Linux all of 127.0.0.0/8 is already loopback, so no
//    interface alias is needed -- only a name -> IP mapping. (On macOS you also
//    need: sudo ifconfig lo0 alias $ip.) We manage the mapping with hostctl in a
//    per-feature profile (named after the slug), so teardown is a clean
//    `sudo hostctl remove $slug` -- no orphaned lines accumulating in /etc/hosts.
//    A dnsmasq *.zfin.test wildcard is the zero-touch alternative to --hosts.
if (doHosts) {
    def hostctlOnPath = new ProcessBuilder('which', 'hostctl')
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start().waitFor() == 0
    if (!hostctlOnPath)
        die("--hosts needs hostctl (https://github.com/guumaster/hostctl; `brew install guumaster/tap/hostctl`)")
    info("mapping $host -> $ip via hostctl profile '$slug' (sudo)")
    runCommand(['sudo', 'hostctl', 'add', 'domains', slug, host, '--ip', ip, '--quiet'])
}

// 4. Compose command. Use THIS repo's compose files (so the preloaded overlay is
//    found regardless of the worktree's branch), but the worktree's .env + source.
def compose = ['docker', 'compose',
               '--project-name', project,
               '--env-file', outEnv.absolutePath,
               '-f', new File(DOCKER, 'docker-compose.yml').absolutePath,
               '-f', new File(DOCKER, 'docker-compose.preloaded.yml').absolutePath]

// Warm volumes: populate the SHARED deploy volumes (and, with --caches, the build
// caches) from the tarballs BEFORE any container mounts them. Docker only seeds an
// EMPTY volume from an image on first mount, and no image carries this content, so
// these would otherwise come up empty (and tomcat/httpd would crash-loop). We
// pre-create each volume (with compose's labels so compose adopts it as a project
// volume) and extract the tarball into it, using the local preloaded db image as a
// tar-capable, root-runnable container. Done regardless of --up so the volumes are
// warm whenever their services first start.
def restore = { List vns ->
    vns.each { vn ->
        def vol = "${project}_${vn}"
        def tgz = new File(auxDir, "${vn}.tgz")
        def mb  = tgz.isFile() ? tgz.length() / 1048576.0 : 0
        def t0  = System.currentTimeMillis()
        runCommand(['docker', 'volume', 'create',
            '--label', "com.docker.compose.project=$project",
            '--label', "com.docker.compose.volume=$vn", vol])
        runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
            '-v', "${vol}:/data",
            '-v', "${auxDir.absolutePath}:/in:ro",
            "zfin-db-preloaded:$tag", 'xzf', "/in/${vn}.tgz", '-C', '/data'])
        info(String.format("warmed %s from %s.tgz (%.0f MB gz) in %.1fs", vol, vn, mb, (System.currentTimeMillis() - t0) / 1000.0))
    }
}
def warmT0 = System.currentTimeMillis()
if (warmApp)    restore(appVols)
if (warmCaches) restore(cacheVols)
if (warmApp || warmCaches) info(String.format("warm restore total: %.1fs", (System.currentTimeMillis() - warmT0) / 1000.0))

// Bring up the preloaded data tier (instantly ready). With a warm app tier, also start
// tomcat/httpd -> the stack comes up serving. Without one, leave the app tier down: its
// volumes (TARGETROOT/CATALINA_BASE) are empty until the webapp is built+deployed, so it
// would crash-loop. Services like ncbiload/jenkins/blast are irrelevant to a feature.
// The compile container's first run sets up the TLS cert + tomcat config.
// --shared-db: the data tier is the external shared stack, so bring up only the app tier
// (and only if it's warm). Otherwise this feature's own preloaded db/solr + app tier.
def services = (doSharedDb ? [] : ['db', 'solr']) + (warmApp ? ['tomcat', 'httpd'] : [])
if (doUp && services) {
    info("${compose.join(' ')} up -d ${services.join(' ')}")
    runCommand(compose + ['up', '-d'] + services)
} else if (doUp) {
    info("nothing to auto-up yet (shared data tier is external; build+deploy, then zup tomcat httpd)")
}

// The printed next: block adapts to whether the app tier was warmed. Warm -> the stack
// already serves $base's deploy, so the only step is dirtydeploy for this branch's delta.
// Cold -> the full first-time build+deploy sequence.
def imagesLine = doSharedDb
    ? "     data     : SHARED zfin_shared stack (net zfin_shared_net)" + (warmApp ? "  + warm app (preloaded-app/$tag)" : "")
    : (warmApp ? "     images   : zfin-{db,solr}-preloaded:$tag  + warm app (preloaded-app/$tag)"
               : "     images   : zfin-{db,solr}-preloaded:$tag")
def bringUp = doSharedDb
    ? (warmApp
        ? (doUp ? "  # tomcat+httpd up, serving $base's deploy on the SHARED db/solr at https://$host"
                : "  zup tomcat httpd                     # app tier (data is the shared zfin_shared stack)")
        : "  # data tier is the shared zfin_shared stack (needs `z shared up`); build+deploy, then zup tomcat httpd")
    : (doUp
        ? (warmApp ? "  # db+solr+tomcat+httpd already up -- serving $base's deploy at https://$host"
                   : "  # data tier (db + solr) is already up.")
        : (warmApp ? "  zup db solr tomcat httpd             # full stack: instant (data + warm app tier)"
                   : "  zup db solr                          # data tier: instant, from preloaded images"))
def deploySteps = warmApp ? """\
  # the app tier is already serving $base's code from the warm snapshot. Deploy THIS
  # branch's changes on top (fast, incremental):
  zrun -c "gradle dirtydeploy"
  # this branch's schema/solr deltas on top of preloaded (only if it changes them):
  zrun -c "gradle liquibasePostBuild\"""" : """\
  # first-time build + deploy. Preloaded bakes in DB/Solr, so SKIP the load steps;
  # the compile container's first run also provisions the TLS cert (reference/build-and-docker.md §1,§5):
  zrun -c "ant do && gradle make && ant deploy-catalina-base && ant deploy-without-tests"
  zup tomcat httpd                     # app tier -> https://$host
  # fast edit -> see loop thereafter:
  zrun -c "gradle dirtydeploy"
  # this branch's schema/solr deltas on top of preloaded (only if it changes them):
  zrun -c "gradle liquibasePostBuild\""""

println """
>> provisioned $name
     worktree : $wtPath
     branch   : $branch  (off $base)
     project  : $project
     url      : https://$host   (-> $ip)
$imagesLine
     activate : source $wtPath/.zenv/activate   (then zrun/zup/zdown/zexec target '$project')

next:
  cd $wtPath
  source .zenv/activate                # activate -> commands resolve to '$project' ('deactivate' to exit)
$bringUp
$deploySteps

teardown:
  docker compose down -v               # while activated: discards this stack's DB/Solr/app copy
  deactivate
  git worktree remove $wtPath
  sudo hostctl remove $slug            # drop this feature's hosts profile
  sudo ifconfig lo0 -alias $ip         # (macOS) drop the loopback alias
"""
}
}
