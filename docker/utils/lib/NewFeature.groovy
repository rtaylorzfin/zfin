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
// runs standalone too. On a TTY it PROMPTS for the whole plan -- existing branch, base, tag,
// shared db, boot, hosts, npm, dirtydeploy, liquibase, tmux -- whether or not <name> was given:
// provisioning has more knobs than anyone wants to remember as flags. Every prompt is
// Enter-able, a flag on the command line SKIPS its own prompt, and `-y` (or no TTY, i.e.
// a script) takes the defaults wholesale. What you answer is then executed, not printed
// as homework: `--up --deploy --liquibase --tmux` leaves a booted, deployed stack and a
// tmux session sitting in the worktree with its .zenv already activated.
//
// Usage:
//   zfeature new [<name>] [-y] [--base BRANCH] [--branch NAME] [--existing-branch]
//                         [--tag TAG] [--ip 127.0.0.X] [--up] [--no-hosts]
//                         [--shared-db] [--deploy] [--liquibase] [--tmux]
//
//   <name>          Feature id, e.g. ZFIN-9002 -> project "zfin-9002",
//                   worktree "wt-zfin-9002", host "zfin-9002.zfin.test".
//                   Omit it (from a terminal) to be prompted.
//   -y, --yes       Don't prompt: take the defaults (and the flags given). What a script
//                   or a non-TTY invocation gets automatically.
//   --base BRANCH   Start point for the new branch (default: main; a warning fires
//                   if invoked from a secondary worktree so you don't base off it).
//                   Ignored with --existing-branch -- there's no new branch to start.
//   --branch NAME   Branch to create, or to check out with --existing-branch
//                   (default: <name>)
//   --existing-branch
//                   Set up the stack on a branch that ALREADY exists instead of cutting
//                   a new one: the worktree checks it out as-is. A branch that exists
//                   only on origin counts -- the worktree gets a local branch tracking
//                   it. Without this flag an existing branch is an error, so a typo'd
//                   ticket can't quietly land you on someone else's work. Interactively
//                   you're asked whenever the branch already exists, so the flag is
//                   really for -y / scripted runs.
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
//   --no-hosts      Skip mapping <host> -> <ip> in /etc/hosts. By default new-feature
//                   maps it via a hostctl profile named after the feature slug (uses
//                   sudo), so <host> resolves immediately; teardown is a clean
//                   `sudo hostctl remove <slug>`. If hostctl isn't installed the mapping
//                   is skipped with a hint (not fatal) -- e.g. when a dnsmasq
//                   *.zfin.test wildcard already resolves the host. (--hosts is accepted
//                   as a back-compat no-op since this is now the default.)
//   --shared-db     Share the `zfin_shared` stack's db+solr instead of seeding this
//                   feature's own copy (needs `z shared up` first). READ-MOSTLY only:
//                   writes/migrations/reindex are shared with every other --shared-db
//                   feature. Uses docker-compose.shared-db.yml (not the preloaded overlay).
//   --no-node       Skip the one-time `gradle npmInstall` (npm ci) that populates the
//                   worktree's node_modules. By default it runs so the first `gradle
//                   dirtydeploy` (npmBuild -> webpack) works; skip if you'll build yourself.
//   --deploy        After bringing the stack up, run `gradle dirtydeploy` so the app tier
//                   serves THIS branch instead of the snapshot's. Only meaningful with a warm
//                   app tier (a cold one needs the full first build -- see the next: block).
//   --liquibase     After bringing the stack up, run `gradle liquibasePostBuild` to apply this
//                   branch's schema deltas on top of preloaded. Needs the data tier up; with
//                   --shared-db it migrates the copy EVERY shared feature is using.
//   --tmux          Leave a detached tmux session named after the slug, rooted in the worktree
//                   with .zenv/activate already sourced, and attach to it at the end. This is
//                   the one part of the next: block a child process cannot do for you: `cd`
//                   and `source` change the CALLING shell, which z can't reach into.
//   Each of the above has a --no-<flag> twin (--no-up/--no-deploy/--no-tmux/...), so you can
//   pin an answer off as well as on and still be prompted for the rest.
//
// After provisioning, `source <worktree>/.zenv/activate` (venv-style) makes
// zrun/zup/zstop/zexec -- and bare `docker compose` -- resolve to this feature;
// `deactivate` restores the shell. The printed next: block shows the full sequence.
//
// The .zenv is a self-contained BUNDLE (copies of the tooling + compose files, see
// CreateZenv), so the feature doesn't break when this checkout switches branches -- the
// tooling and the preloaded overlay live only on the branch that adds them. It's a snapshot:
// `z feature refresh <ticket>` re-copies it after the tooling changes.

// --- shared helpers + roots (via ZfinUtil, passed in by z) --------------------
// Roots anchor to the PRIMARY checkout (z resolves them once), so running from inside a
// worktree is fine -- provisioning never depends on where you happen to be.
class NewFeature {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand;
        def captureOutput = zfinUtil.&captureOutput
        def imageExists = zfinUtil.&imageExists
        def DOCKER = zfinUtil.DOCKER   // docker
        def REPO = zfinUtil.REPO     // primary checkout
        def WT_PARENT = REPO.parentFile   // worktrees live alongside it

// --- defaults + args ---------------------------------------------------------
        def base = 'main'
        def baseArg = false
        def tagArg = null
        def ip = ''
        def ipBase = null
        def branch = ''
        def useExisting = false
        def doUp = false
        def doHosts = true
        def doApp = true
        def doCaches = true
        def doSharedDb = false
        def doNode = true
        def doDeploy = false
        def doLiquibase = false
        def doTmux = false
        def assumeYes = false
        def name = ''

// Which decisions the COMMAND LINE already made. Every prompt below is skipped for a
// decision that's in here, so a flag always wins over the interactive plan and
// `z feature new ZFIN-1 --shared-db` still asks about everything else. That's also why
// each yes/no flag has a --no- twin: without one you could only pin an answer to `true`,
// and pinning it to `false` would be indistinguishable from not answering.
        def explicit = [] as Set

        def argv = args as List
        for (int i = 0; i < argv.size(); i++) {
            switch (argv[i]) {
                case '--base': base = argv[++i]; baseArg = true; explicit << 'base'; break
                case '--branch': branch = argv[++i]; break
                case '--existing-branch': case '--existing': useExisting = true; explicit << 'existing'; break
                case '--no-existing-branch': case '--no-existing': useExisting = false; explicit << 'existing'; break
                case '--tag': tagArg = argv[++i]; explicit << 'tag'; break
                case '--ip': ip = argv[++i]; break
                case '--ip-base': ipBase = argv[++i]; break
                case '--up': doUp = true; explicit << 'up'; break
                case '--no-up': doUp = false; explicit << 'up'; break
                case '--no-app': doApp = false; break
                case '--no-caches': doCaches = false; break
                case '--hosts': doHosts = true; explicit << 'hosts'; break   // back-compat: on by default
                case '--no-hosts': doHosts = false; explicit << 'hosts'; break
                case '--shared-db': doSharedDb = true; explicit << 'shared'; break
                case '--no-shared-db': doSharedDb = false; explicit << 'shared'; break
                case '--node': doNode = true; explicit << 'node'; break
                case '--no-node': doNode = false; explicit << 'node'; break
                case '--deploy': doDeploy = true; explicit << 'deploy'; break
                case '--no-deploy': doDeploy = false; explicit << 'deploy'; break
                case '--liquibase': doLiquibase = true; explicit << 'liquibase'; break
                case '--no-liquibase': doLiquibase = false; explicit << 'liquibase'; break
                case '--tmux': doTmux = true; explicit << 'tmux'; break
                case '--no-tmux': doTmux = false; explicit << 'tmux'; break
                case '-y': case '--yes': case '--no-prompt': assumeYes = true; break
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
            def imgs = captureOutput(['docker', 'images', StackConfig.DB_IMAGE_REPO, '--format', '{{.CreatedAt}}\t{{.Tag}}'])
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
// Branch existence is asked of the PRIMARY checkout -- worktrees share its refs. A branch
// that lives only on origin counts as existing: `git worktree add <path> <branch>` DWIMs a
// local branch tracking it, which is exactly what you want when picking up someone's PR.
        def gitRef = { String ref -> zfinUtil.runQuietly(['git', '-C', REPO.absolutePath, 'rev-parse', '--verify', '--quiet', ref]) == 0 }
        def localBranch = { String b -> b && gitRef("refs/heads/$b") }
        def remoteBranch = { String b -> b && gitRef("refs/remotes/origin/$b") }
        def branchExists = { String b -> localBranch(b) || remoteBranch(b) }

        def cwdTop = captureOutput(['git', '-C', new File('.').absolutePath, 'rev-parse', '--show-toplevel'])
        def inWorktree = cwdTop && new File(cwdTop).canonicalFile != REPO.canonicalFile
        def cwdBranch = inWorktree ? captureOutput(['git', '-C', new File('.').absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) : ''

// Warm-snapshot probe, by tag: build-preloaded --app/--caches leaves one tarball per volume
// under docker/preloaded-app/<tag>/. Defined up here (not just at the restore below) because
// the interactive plan has to know whether the app tier WILL be warm -- `gradle dirtydeploy`
// only makes sense on top of a warm snapshot, and the boot prompt names the services it'll
// actually start. Takes the tag as a parameter since the tag is still being decided.
        def haveTars = { String t, List vns -> vns.every { new File(zfinUtil.auxDir(t), "${it}.tgz").isFile() } }

// The interactive plan. A <name> on the command line no longer suppresses the prompts:
// provisioning has enough knobs -- where the data comes from, whether to boot, whether to
// deploy this branch on top, where to leave you afterwards -- that answering nine Enter-able
// questions beats memorising nine flags. `-y` and a non-TTY (a script, a pipe) take the
// defaults instead, so nothing that used to run unattended starts blocking on stdin.
        def con = System.console()
        def interactive = con != null && !assumeYes
        if (!name && !con) die("usage: z feature new <name> [-y] [--base B] [--branch B] [--tag T] " +
                "[--ip 127.0.0.X] [--up] [--no-hosts] [--shared-db] [--deploy] [--liquibase] [--tmux]", 2)

        if (interactive) {
            println "New feature stack -- press Enter to accept [defaults]."
            while (!name) {
                name = con.readLine("  ticket / feature id (e.g. ZFIN-789): ")?.trim()
                if (!name) System.err.println("    (required)")
            }
            // Existing branch: only worth a question when there IS one to reuse. Asked before
            // the base prompt because reusing a branch makes the base moot.
            if (!('existing' in explicit) && branchExists(branch ?: name)) {
                def b = branch ?: name
                def whereB = localBranch(b) ? 'exists' : 'exists on origin'
                useExisting = askYesNo(con, "  branch '$b' $whereB -- set the stack up on it (no new branch)?", true)
            }
            if (inWorktree && !baseArg && !useExisting)
                println "  (you're in worktree '${new File(cwdTop).name}' on '$cwdBranch'; new features usually base on 'main')"
            if (!('base' in explicit) && !useExisting) base = (con.readLine("  base branch [$base]: ")?.trim()) ?: base
            if (!('tag' in explicit)) {
                def tIn = con.readLine("  preloaded tag [${tag ?: 'none built yet'}]: ")?.trim()
                if (tIn) tag = tIn
            }
            if (!('shared' in explicit))
                doSharedDb = askYesNo(con, "  share the zfin_shared db+solr instead of your own copy (read-mostly)?", false)
            // From here the questions are about what to RUN, so they're phrased in terms of
            // what will actually happen for this tag: a warm snapshot means db+solr+app come
            // up together and dirtydeploy is the natural next step; a cold one means neither.
            def warmPeek = doApp && haveTars(tag, StackConfig.APP_VOLS)
            def bootDesc = doSharedDb ? (warmPeek ? 'tomcat+httpd (data is shared)' : 'nothing yet (cold app tier)')
                                      : (warmPeek ? 'db+solr+tomcat+httpd' : 'db+solr')
            if (!('up' in explicit)) doUp = askYesNo(con, "  bring the stack up now ($bootDesc)?", true)
            if (!('hosts' in explicit))
                doHosts = askYesNo(con, "  map ${StackConfig.host(name.toLowerCase())} via hostctl (sudo)?", true)
            if (!('node' in explicit))
                doNode = askYesNo(con, "  run npm ci now (gradle npmInstall; once per worktree)?", true)
            // dirtydeploy is only offered against a warm app tier -- with a cold one the first
            // deploy is the full ant/gradle sequence in the next: block, not this task.
            if (!('deploy' in explicit) && warmPeek)
                doDeploy = askYesNo(con, "  deploy this branch on top of the snapshot (gradle dirtydeploy)?", true)
            // ...and liquibase only when there'll be a database to migrate. Default N either
            // way: most branches carry no schema delta, and on a shared db it hits everyone.
            if (!('liquibase' in explicit) && (doUp || doSharedDb))
                doLiquibase = askYesNo(con, "  apply this branch's schema deltas (gradle liquibasePostBuild)" +
                        (doSharedDb ? " -- WRITES TO THE SHARED DB" : "") + "?", false)
            if (!('tmux' in explicit))
                doTmux = askYesNo(con, "  spawn a tmux session '${name.toLowerCase()}' (worktree + .zenv activated)?", true)
        } else if (inWorktree && !baseArg && !useExisting) {
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
            [StackConfig.DB_IMAGE_REPO, StackConfig.SOLR_IMAGE_REPO].each { repo ->
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
        def appVols = StackConfig.APP_VOLS   // shared volume contract (single source, also read by BuildPreloaded)
        def cacheVols = StackConfig.CACHE_VOLS
        def auxDir = zfinUtil.auxDir(tag)
        def warmApp = doApp && haveTars(tag, appVols)
        // Caches are independent (gradle/maven/npm) -> warm whichever tarballs are present, so a
        // snapshot that predates a newly-added cache still warms the rest. (App stays
        // all-or-nothing: a partial deploy is broken.)
        def cachesPresent = doCaches ? cacheVols.findAll { new File(auxDir, "${it}.tgz").isFile() } : []
        def warmCaches = !cachesPresent.isEmpty()
        if (doApp && !warmApp && auxDir.isDirectory())
            info("note: $auxDir exists but is missing app tarballs -- app tier will NOT be warmed")
        info(warmApp ? "warm app tier: yes (from $auxDir)"
                : "warm app tier: no" + (doApp ? " (build-preloaded --app --tag $tag to enable)" : " (--no-app)"))
        if (warmCaches) info("warm build caches: yes (${cachesPresent.collect { it - '_cache' }.join(' + ')}, from $auxDir)")

        def slug = name.toLowerCase()          // Compose projects must be lowercase
        def project = slug
        branch = branch ?: name
        def host = StackConfig.host(slug)
        def wt = new File(WT_PARENT, "wt-${slug}")
        def wtPath = wt.absolutePath

// Settle the branch BEFORE anything is created (loopback alias, worktree, .env, volumes),
// so a wrong answer here costs nothing to retry. Skipped when the worktree already exists:
// that's the documented re-run path below, and its branch is checked out by definition.
        if (!wt.isDirectory()) {
            if (useExisting) {
                if (!branchExists(branch))
                    die("--existing-branch: no branch '$branch' locally or on origin.\n" +
                            "   drop --existing-branch to create it, or name it:  --branch <name>")
                // A branch can only be checked out in one worktree; git would refuse at
                // `worktree add`, after the loopback alias is already claimed.
                def holder = null, wtPathSeen = null
                captureOutput(['git', '-C', REPO.absolutePath, 'worktree', 'list', '--porcelain']).eachLine { line ->
                    if (line.startsWith('worktree ')) wtPathSeen = line.substring(9)
                    else if (line == "branch refs/heads/$branch") holder = wtPathSeen
                }
                if (holder) die("branch '$branch' is already checked out in $holder\n" +
                        "   use that tree, or pick another branch:  --branch <name>")
                info(localBranch(branch) ? "branch: existing '$branch' (no new branch, --base ignored)"
                        : "branch: new local '$branch' tracking origin/$branch (--base ignored)")
            } else if (localBranch(branch)) {
                die("branch '$branch' already exists.\n" +
                        "   set the stack up on it:  --existing-branch\n" +
                        "   or cut a different one:  --branch <name>")
            }
        }

// --- the plan, and progress against it ---------------------------------------
// Everything is decided by now, so settle the two answers that depend on the tag (you can
// ask for a deploy the app tier can't use, or a migration with no database to run it
// against) and turn the rest into a phase list. Several phases are slow and silent for
// minutes -- npm ci, dirtydeploy -- so the list gets printed up front and each phase
// stamps itself as it starts. Going quiet inside a gradle build with no idea which step
// you're on is the thing this is for.
        def services = (doSharedDb ? [] : StackConfig.DATA_SERVICES) + (warmApp ? StackConfig.APP_SERVICES : [])
        if (doDeploy && !warmApp)
            info("note: the app tier is cold -- `gradle dirtydeploy` alone won't produce a servable deploy (see the next: block for the first-time sequence)")
        // node_modules is git-ignored and absent from a fresh worktree, and dirtydeploy runs
        // npmBuild WITHOUT npmInstall -- so this combination fails deep inside gradle with a
        // bare `sh: 1: webpack: not found`. Say so here instead, while it still reads as a
        // choice you made.
        if (doDeploy && !doNode && !new File(wt, 'node_modules').isDirectory())
            info("note: dirtydeploy without npm ci in a fresh worktree fails at `webpack: not found` -- drop --no-node, or run `zrun -c \"gradle npmInstall\"` first")
        if (doLiquibase && !(doUp || doSharedDb)) {
            info("note: skipping liquibasePostBuild -- it needs the data tier up (re-run with --up, or run it yourself after zup db)")
            doLiquibase = false
        }
        def plan = [useExisting ? 'worktree (existing branch)' : 'worktree + branch', 'per-feature .env + .zenv']
        if (doHosts) plan << 'hostname mapping'
        if (warmApp || warmCaches) plan << 'warm volumes'
        if (doNode) plan << 'npm ci'
        if (doUp && services) plan << "start ${services.join(' ')}"
        if (doDeploy) plan << 'gradle dirtydeploy'
        if (doLiquibase) plan << 'gradle liquibasePostBuild'
        if (doTmux) plan << "tmux session '$slug'"
        info("plan: ${plan.join('  ->  ')}")
        int stepNo = 0
        def step = { String label -> println "\n>> [${++stepNo}/${plan.size()}] $label" }

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

        info("feature=$name project=$project host=$host ip=$ip tag=$tag " +
                (useExisting ? "branch=$branch (existing)" : "base=$base"))

// 1. worktree + branch (separate host path => its own mounted source tree)
        step(useExisting ? 'worktree (existing branch)' : 'worktree + branch')
        if (!wt.isDirectory()) {
            runCommand(useExisting ? ['git', '-C', REPO.absolutePath, 'worktree', 'add', wtPath, branch]
                                   : ['git', '-C', REPO.absolutePath, 'worktree', 'add', wtPath, '-b', branch, base])
        } else {
            info("worktree $wtPath already exists, reusing")
        }


// 2. per-feature .env: start from the base env, strip the keys we own, append our
//    overrides. Published ports bind to $ip with standard ports so URLs are clean
//    (https://$host, no port suffix). The preloaded images are LOCAL-ONLY bare
//    names (no registry prefix) -- built per-machine by build-preloaded.groovy and
//    never pushed (they carry real data).
        step('per-feature .env + .zenv')
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
ZFIN_DB_IMAGE=${StackConfig.dbImage(tag)}
ZFIN_SOLR_IMAGE=${StackConfig.solrImage(tag)}
"""
// A branch we didn't create isn't ours to delete: teardown force-deletes the feature's
// branch, which would be a nasty surprise for work that predates the stack. Record the
// provenance here (the .env is the per-feature record `z feature rm` already reads) so
// rm keeps the branch instead.
        if (useExisting) outEnv << "ZFIN_FEATURE_BRANCH_PREEXISTING=1\n"

// 2b. venv-style activation. create-zenv writes .zenv/{activate,bin/z}: `source .zenv/activate`
//     puts `z` on PATH + defines the zrun/zexec/zup/... shell functions and points them
//     (and bare `docker compose`) at THIS feature, collapsing the long -p/--env-file/-f
//     invocation. `deactivate` restores the shell. create-zenv also keeps .zenv/ out of git.
//     Called in-process (same JVM, same zfinUtil) rather than spawned.
// --shared-db uses the consumer overlay (suppress local db/solr) INSTEAD of the preloaded
// overlay -- no local db/solr, so no preloaded images to point at. The shared db/solr are
// connected into this feature's network at up time (see connectSharedData), not via compose.
// create-zenv COPIES the tooling + compose file(s) into <wt>/.zenv/ (a self-contained bundle),
// so this feature survives the primary checkout switching branches or moving; it returns the
// bundled compose files, which everything below drives compose through.
        def dataOverlay = doSharedDb ? 'docker-compose.shared-db.yml' : 'docker-compose.preloaded.yml'
        def composeFiles = "${new File(DOCKER, 'docker-compose.yml').absolutePath}:${new File(DOCKER, dataOverlay).absolutePath}"
        def zenv = new CreateZenv().run(['--dir', wtPath, '--project', project, '--compose', composeFiles,
                                         '--env-file', outEnv.absolutePath, '--tag', tag, '--host', host], zfinUtil)

// 3. name resolution. On Linux all of 127.0.0.0/8 is already loopback, so no
//    interface alias is needed -- only a name -> IP mapping. (On macOS you also
//    need: sudo ifconfig lo0 alias $ip.) We manage the mapping with hostctl in a
//    per-feature profile (named after the slug), so teardown is a clean
//    `sudo hostctl remove $slug` -- no orphaned lines accumulating in /etc/hosts.
//    A dnsmasq *.zfin.test wildcard is the zero-touch alternative to --hosts.
        if (doHosts) {
            step('hostname mapping')
            if (!zfinUtil.onPath('hostctl'))
                // Not fatal: hostctl is one of several ways to resolve $host (a dnsmasq
                // *.zfin.test wildcard is the zero-touch alternative). Skip with a hint
                // rather than aborting a stack that's otherwise fully provisioned.
                info("skipping host mapping: hostctl not installed -- add manually with " +
                     "`sudo hostctl add domains $slug $host --ip $ip` " +
                     "(https://github.com/guumaster/hostctl; `brew install guumaster/tap/hostctl`)")
            else {
                info("mapping $host -> $ip via hostctl profile '$slug' (sudo)")
                runCommand(['sudo', 'hostctl', 'add', 'domains', slug, host, '--ip', ip, '--quiet'])
            }
        }

// 4. Compose command: the .zenv's OWN compose files (the bundled copies) with the worktree's
//    .env + source -- i.e. byte-for-byte what `source .zenv/activate` then `zup` will use.
//    Taking them from the .zenv is what keeps the two in step: they can't drift into
//    different overlays (a --shared-db feature seeding its own db/solr, say) or different
//    compose content after a later `z feature refresh`.
        def compose = ['docker', 'compose',
                       '--project-name', project,
                       '--env-file', outEnv.absolutePath] +
                      zenv.composeFiles.collectMany { ['-f', it.absolutePath] }

// Warm volumes: populate the SHARED deploy volumes (and, with --caches, the build
// caches) from the tarballs BEFORE any container mounts them. Docker only seeds an
// EMPTY volume from an image on first mount, and no image carries this content, so
// these would otherwise come up empty (and tomcat/httpd would crash-loop). We
// pre-create each volume (with compose's labels so compose adopts it as a project
// volume) and extract the tarball into it, using the compile image as a tar-capable,
// root-runnable container (see tarImage below). Done regardless of --up so the volumes
// are warm whenever their services first start.
// The volumes are independent, so extract them CONCURRENTLY -- wall-clock drops toward the
// slowest single volume (gradle_cache) instead of the sum, and the per-container startups
// overlap. Each thread creates its volume + untars into it, capturing exit + merged output;
// results land in per-index slots (no contention). Timing/errors are printed after join, in
// order, so the concurrent output doesn't interleave. (Raw gzip is ~1s; the cost is
// container startup + writing many small files, which parallelism overlaps.)
        def tarImage = zfinUtil.tarImage()   // compile image: GNU tar, root-runnable, local (no pull)
        def restore = { List vns ->
            def results = new Object[vns.size()]
            def threads = []
            vns.eachWithIndex { vn, idx ->
                threads << Thread.start {
                    def vol = "${project}_${vn}"
                    def cname = "zfin-warm-${project}-${vn}"   // deterministic name so we can always clean it up
                    def tgz = new File(auxDir, "${vn}.tgz")
                    def mb = tgz.isFile() ? tgz.length() / 1048576.0 : 0
                    def t0 = System.currentTimeMillis()
                    def run2 = { List c ->
                        def p = new ProcessBuilder(c*.toString()).redirectErrorStream(true).start()
                        def out = p.inputStream.text          // drain (avoid pipe deadlock) + capture
                        [code: p.waitFor(), out: out]
                    }
                    try {
                        run2(['docker', 'rm', '-f', cname])   // clear a stale orphan from a prior interrupted run
                        def cr = run2(['docker', 'volume', 'create',
                                       '--label', "com.docker.compose.project=$project",
                                       '--label', "com.docker.compose.volume=$vn", vol])
                        // No --rm: a --rm container that never STARTS (interrupt) is left "Created" and
                        // pins the volume; we name it and remove it explicitly in finally instead.
                        def ex = run2(['docker', 'run', '--name', cname, '-u', '0', '--entrypoint', 'tar',
                                       '-v', "${vol}:/data",
                                       '-v', "${auxDir.absolutePath}:/in:ro",
                                       tarImage, 'xzf', "/in/${vn}.tgz", '-C', '/data'])
                        def ok = cr.code == 0 && ex.code == 0
                        results[idx] = [vn: vn, vol: vol, mb: mb, secs: (System.currentTimeMillis() - t0) / 1000.0,
                                        ok: ok, err: (ok ? '' : (cr.out + ex.out).trim())]
                    } catch (Throwable t) {
                        results[idx] = [vn: vn, vol: vol, mb: mb, secs: 0, ok: false, err: t.toString()]
                    } finally {
                        run2(['docker', 'rm', '-f', cname])   // always remove (normal + error paths)
                    }
                }
            }
            threads*.join()
            results.each { r ->
                info(String.format("warmed %s from %s.tgz (%.0f MB gz) in %.1fs%s",
                        r.vol, r.vn, r.mb, r.secs, r.ok ? '' : '  !! FAILED'))
            }
            def failed = results.findAll { !it.ok }
            if (failed) die("warm restore failed: ${failed.collect { it.vn }.join(', ')}\n" + failed.collect { it.err }.join('\n'))
        }
        if (warmApp || warmCaches) step('warm volumes')
        def warmT0 = System.currentTimeMillis()
        if (warmApp) restore(appVols)
        if (warmCaches) restore(cachesPresent)
        if (warmApp || warmCaches) info(String.format("warm restore total: %.1fs", (System.currentTimeMillis() - warmT0) / 1000.0))

// node_modules is git-ignored (absent in a fresh worktree) and NOT in the warm TARGETROOT,
// and `gradle dirtydeploy` runs npmBuild (webpack) WITHOUT npmInstall -- so a fresh worktree
// needs `npm ci` once or dirtydeploy fails with "webpack: not found". Do it in the compile
// container (no db/solr needed); npmInstall is up-to-date-skipped on later runs. --no-node skips.
        if (doNode) {
            step('npm ci')
            info("installing node deps in compile (gradle npmInstall / npm ci) -- one-time for this worktree...")
            runCommand(compose + ['run', '--rm', StackConfig.BUILD_SERVICE, 'bash', '-l', '-c', 'gradle npmInstall'])
        }

// Bring up the preloaded data tier (instantly ready). With a warm app tier, also start
// tomcat/httpd -> the stack comes up serving. Without one, leave the app tier down: its
// volumes (TARGETROOT/CATALINA_BASE) are empty until the webapp is built+deployed, so it
// would crash-loop. Services like ncbiload/jenkins/blast are irrelevant to a feature.
// The compile container's first run sets up the TLS cert + tomcat config.
// --shared-db: the data tier is the external shared stack, so bring up only the app tier
// (and only if it's warm). Otherwise this feature's own preloaded db/solr + app tier.
        if (doUp && services) step("start ${services.join(' ')}")
        if (doUp && services && doSharedDb) {
            // Shared-db: create the app tier + this feature's default network WITHOUT starting, connect
            // the shared db/solr into that network (so `db`/`solr` resolve at tomcat startup), THEN
            // start. The app tier stays single-homed -- see ZfinUtil.connectSharedData for why.
            info("${compose.join(' ')} up --no-start ${services.join(' ')}")
            runCommand(compose + ['up', '--no-start'] + services)
            zfinUtil.connectSharedData(project)
            info("${compose.join(' ')} start ${services.join(' ')}")
            runCommand(compose + ['start'] + services)
        } else if (doUp && services) {
            info("${compose.join(' ')} up -d ${services.join(' ')}")
            runCommand(compose + ['up', '-d'] + services)
        } else if (doUp) {
            info("nothing to auto-up yet (shared data tier is external; build+deploy, then zup tomcat httpd)")
        }

// The two build steps the next: block used to just recommend. Both are NON-FATAL on
// purpose (check:false): by this point the worktree, .env, .zenv, hosts entry, volumes and
// containers all exist and are usable, so a gradle failure is something to REPORT -- letting
// runCommand's die() unwind here would kill the summary that tells you where all of it is,
// and leave you with a provisioned stack and no idea of its url or how to activate it.
        def failed = []
        def gradleIn = { String task ->
            def code = runCommand(compose + ['run', '--rm', StackConfig.BUILD_SERVICE, 'bash', '-l', '-c', "gradle $task"],
                    [check: false])
            if (code != 0) failed << task
        }
        if (doDeploy) { step('gradle dirtydeploy'); gradleIn('dirtydeploy') }
        if (doLiquibase) { step('gradle liquibasePostBuild'); gradleIn('liquibasePostBuild') }

// tmux: the one line of the next: block a child process can never run for you. `cd` and
// `source .zenv/activate` mutate the CALLING shell, and z is a JVM the shell forked -- it
// can't reach back up. So instead of asking you to type them, hand over a shell that has
// already done both: a DETACHED session named after the slug, rooted in the worktree with
// the .zenv sourced. Detached-then-attach (rather than exec'ing into tmux) is what keeps
// the provisioning log on your terminal's scrollback and lets the session outlive z --
// Ctrl-b d gets out, `tmux attach -t <slug>` gets back in, `z feature rm` kills it.
        def tmuxReady = false
        if (doTmux) {
            step("tmux session '$slug'")
            if (!zfinUtil.onPath('tmux')) {
                // Same posture as hostctl: an optional integration missing is a hint, not a
                // failure -- the stack is fine, you just get the cd/source lines to type.
                info("skipping: tmux is not installed (brew install tmux) -- use the next: block by hand")
            } else if (zfinUtil.runQuietly(['tmux', 'has-session', '-t=' + slug]) == 0) {
                // '-t=' is tmux's EXACT-match form for a target-SESSION; plain '-t' also
                // prefix-matches, so re-provisioning 'zfin-10' would find a running 'zfin-104'.
                info("tmux session '$slug' already exists -- reusing it")
                tmuxReady = true
            } else {
                runCommand(['tmux', 'new-session', '-d', '-s', slug, '-c', wtPath])
                // The `cd` is not redundant with new-session's -c. tmux sets the pane's start
                // directory, then the LOGIN shell runs .bash_profile on top -- and a profile
                // that ends in `cd ~/zfin` (this one does) silently lands you in the primary
                // checkout, where zrun would build the wrong tree. Sending the cd re-asserts it
                // after the profile has had its say.
                // send-keys takes a target-PANE, and '=' is not part of that grammar ("can't
                // find pane: =<slug>") -- a bare session name is the right target here, and it
                // is unambiguous anyway: has-session just told us this exact name was free.
                runCommand(['tmux', 'send-keys', '-t', slug,
                            "cd '$wtPath' && source '$wtPath/.zenv/activate'", 'C-m'])
                info("tmux session '$slug' ready: cwd $wtPath, .zenv activated")
                tmuxReady = true
            }
        }
        def willAttach = tmuxReady && con != null

// The printed next: block adapts to what this run actually DID. Telling you to
// `source .zenv/activate` when a tmux session is already sitting there activated, or to
// run dirtydeploy a second after we ran it, is the kind of noise that trains people to
// skip the block entirely -- so every line below is either something still left to do or
// something that failed and needs re-running.
        def imagesLine = doSharedDb
                ? "     data     : SHARED zfin_shared db/solr (connected into ${project}_default)" + (warmApp ? "  + warm app (preloaded-app/$tag)" : "")
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

// Built as a line list rather than one interpolated heredoc: which lines belong here now
// depends on warmApp x doDeploy x doLiquibase x did-it-fail, and nesting that many ternaries
// inside a multi-line GString is how the two variants silently drift apart.
        def dl = []
        if (warmApp) {
            dl << "  # the app tier is already serving $base's code from the warm snapshot."
            if (failed.contains('dirtydeploy')) dl << "  # !! dirtydeploy FAILED above -- fix, then re-run:"
            else if (doDeploy)                  dl << "  # THIS branch is deployed on top of it. Re-run after each edit:"
            else                                dl << "  # Deploy THIS branch's changes on top (fast, incremental):"
            dl << '  zrun -c "gradle dirtydeploy"'
        } else {
            dl << "  # first-time build + deploy. Preloaded bakes in DB/Solr, so SKIP the load steps;"
            dl << "  # the compile container's first run also provisions the TLS cert (reference/build-and-docker.md §1,§5):"
            dl << '  zrun -c "ant do && gradle make && ant deploy-catalina-base && ant deploy-without-tests"'
            dl << "  zup tomcat httpd                     # app tier -> https://$host"
            dl << "  # fast edit -> see loop thereafter:"
            dl << '  zrun -c "gradle dirtydeploy"'
        }
        if (doLiquibase && !failed.contains('liquibasePostBuild')) {
            dl << "  # this branch's schema/solr deltas are already applied (liquibasePostBuild ran)."
        } else {
            dl << (failed.contains('liquibasePostBuild')
                    ? "  # !! liquibasePostBuild FAILED above -- fix, then re-run:"
                    : "  # this branch's schema/solr deltas on top of preloaded (only if it changes them):")
            dl << '  zrun -c "gradle liquibasePostBuild"'
        }
        def deploySteps = dl.join('\n')

// How you get INTO the stack: a live tmux session if we made one (nothing to type), the
// cd + source pair otherwise.
        def enterLines = tmuxReady
                ? (willAttach ? "  # tmux session '$slug' is live (worktree + .zenv activated) -- attaching below."
                              : "  tmux attach -t $slug                 # worktree + .zenv already activated")
                : "  cd $wtPath\n  source .zenv/activate                # activate -> commands resolve to '$project' ('deactivate' to exit)"

        def ran = []
        if (doNode) ran << 'npm ci'
        if (doUp && services) ran << "up ${services.join('+')}"
        if (doDeploy) ran << 'dirtydeploy' + (failed.contains('dirtydeploy') ? ' (FAILED)' : '')
        if (doLiquibase) ran << 'liquibasePostBuild' + (failed.contains('liquibasePostBuild') ? ' (FAILED)' : '')

        println """
>> provisioned $name
     worktree : $wtPath
     branch   : $branch  ${useExisting ? '(existing)' : "(off $base)"}
     project  : $project
     url      : https://$host   (-> $ip)
$imagesLine
     activate : source $wtPath/.zenv/activate   (then zrun/zup/zstop/zexec target '$project')${tmuxReady ? "\n     tmux     : tmux attach -t $slug   (session left running; Ctrl-b d to detach)" : ''}${ran ? "\n     ran      : ${ran.join('  ->  ')}" : ''}

next:
$enterLines
$bringUp
$deploySteps

teardown:
  z feature rm $slug                   # all of the below, automated (prompts first)
  # ...or by hand:
  zstop                                # just pause it: containers stopped, data kept (zup resumes)
  zdown -v                             # remove containers + THIS stack's DB/Solr/app copy
  deactivate
  git worktree remove $wtPath${tmuxReady ? "\n  tmux kill-session -t $slug           # drop this feature's shell" : ''}
  sudo hostctl remove $slug            # drop this feature's hosts profile
  sudo ifconfig lo0 -alias $ip         # (macOS) drop the loopback alias
"""
        if (failed)
            System.err.println("!! ${failed.size()} post-provision step(s) failed: ${failed.join(', ')} -- the stack itself is provisioned; re-run them from the next: block above.")

// Attach LAST, after the summary has been printed, so it stays on the terminal's scrollback
// behind the tmux screen. Inside tmux already (z run from a pane), attach-session would
// refuse to nest -- switch-client moves the existing client to the new session instead.
        if (willAttach) {
            if (System.getenv('TMUX')) {
                info("switching this tmux client to '$slug'")
                runCommand(['tmux', 'switch-client', '-t=' + slug], [check: false])
            } else {
                info("attaching to '$slug' (Ctrl-b d to detach; the stack keeps running)")
                runCommand(['tmux', 'attach-session', '-t=' + slug], [check: false])
            }
        } else if (tmuxReady) {
            info("no TTY to attach to -- `tmux attach -t $slug` when you're back at a terminal")
        }
    }
}
