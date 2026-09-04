// StackOps -- the stack lifecycle commands that operate on the active .zenv stack:
// run / exec / up / stop / down / pull / log / restart / status. z routes the whole family here
// (args[0] is the op), so z itself stays pure routing. All but `status` need a stack -- either
// activated (COMPOSE_FILE in the environment) or auto-detected from cwd by
// ZfinUtil.resolveStack, which z calls for this family; status reports gracefully with neither.
//
// Read stack vars through zfinUtil.stackVar() (not System.getenv) and spawn compose through
// zfinUtil.runCommand() (which injects childEnv), so activated and auto-detected stacks behave
// identically.
class StackOps {
    def run(List args, ZfinUtil zfinUtil) {
        def op   = args ? args[0] : 'status'
        def rest = args.drop(1)
        def die  = zfinUtil.&die

        def compose = { List a -> System.exit(zfinUtil.runCommand(['docker', 'compose'] + a, [check: false])) }
        def requireStack = {
            if (!zfinUtil.stackVar('COMPOSE_FILE'))
                die("z $op: no stack found -- run this from inside a stack's directory tree, or activate one:\n" +
                    "     source <repo-or-worktree>/.zenv/activate")
        }

        // run/exec: split docker flags (-u root, before OR after the service) from the service
        // name and the bash args. First bare word = service (default compile); first bash flag
        // (e.g. -c) ends parsing and it + the rest go to bash.
        def parseSvc = { List a ->
            def dockerArgs = []; def service = null; int i = 0
            while (i < a.size()) {
                def t = a[i]
                if (t in ['-u', '--user']) {
                    if (i + 1 >= a.size()) die("$t requires an argument")
                    dockerArgs += [t, a[i + 1]]; i += 2
                } else if (t.startsWith('-')) break
                else if (service == null) { service = t; i++ }
                else break
            }
            [service ?: StackConfig.BUILD_SERVICE, dockerArgs, a.drop(i)]
        }
        def runExec = { boolean isExec, List a ->
            requireStack()
            def (svc, da, ba) = parseSvc(a)
            if (isExec) compose(['exec'] + (System.console() ? [] : ['-T']) + da + [svc, 'bash', '-l'] + ba)
            else        compose(['run', '--rm'] + da + [svc, 'bash', '-l'] + ba)
        }

        switch (op) {
            case 'run':     runExec(false, rest); break
            case 'exec':    runExec(true, rest); break
            case 'up':
                requireStack()
                // A --shared-db stack keeps its app tier single-homed and reaches shared data
                // by connecting the shared db/solr into this feature's network -- so up must
                // create the network, connect them, THEN start (see ZfinUtil.connectSharedData).
                // Idempotent: on the normal stop/start cycle the network + connect persist, so
                // this just re-confirms them; it matters after a full `docker compose down`.
                def proj = zfinUtil.stackVar('COMPOSE_PROJECT_NAME')
                if (zfinUtil.stackVar('COMPOSE_FILE')?.contains('shared-db.yml') && proj) {
                    zfinUtil.runCommand(['docker', 'compose', 'up', '--no-start'] + rest)
                    zfinUtil.connectSharedData(proj)
                    compose(['start'] + rest)
                } else {
                    compose(['up', '-d'] + rest)
                }
                break
            // stop vs down, matching compose's own meaning of the words: `stop` halts the
            // containers and keeps everything (this is what `z down` used to do, misleadingly);
            // `down` removes containers + network, and only discards this stack's ~26G db/solr
            // copy if you pass -v yourself. `z feature rm` is the guided full teardown.
            case 'stop':    requireStack(); compose(['stop'] + rest); break
            case 'down':    requireStack(); compose(['down'] + rest); break
            case 'pull':    requireStack(); compose(['pull'] + rest); break
            case 'log':     requireStack(); compose(['logs', '-f'] + rest); break
            case 'restart': requireStack(); compose(['restart'] + rest); break

            case 'status':
                def active = zfinUtil.stackVar('ZENV_ACTIVE')
                if (!active) { println "zenv: no stack found here. cd into a stack's tree, or:  source <repo-or-worktree>/.zenv/activate"; return }
                def envf = zfinUtil.stackVar('COMPOSE_ENV_FILES')
                def readEnv = { String key ->
                    def f = envf ? new File(envf) : null
                    if (!f?.isFile()) return ''
                    def m = f.readLines().findAll { it.startsWith(key) }
                    m ? m.last().substring(key.length()) : ''
                }
                def ip = readEnv('LOOPBACK_IP='); def dbimg = readEnv('ZFIN_DB_IMAGE=')
                // Branch: the checked-out branch of this stack's worktree (empty if ZENV_DIR
                // isn't a git worktree). The Jira issue is assumed to share the branch name
                // (ZFIN's feature branches are ticket-keyed), linked at the standard browse URL.
                def dir = zfinUtil.stackVar('ZENV_DIR')
                def branch = dir ? zfinUtil.captureOutput(['git', '-C', dir, 'rev-parse', '--abbrev-ref', 'HEAD']) : ''
                // PR "create" link: derive the GitHub owner/repo slug from origin (SSH or HTTPS
                // form) and point at the open-a-PR page for this branch. Empty for non-GitHub origins.
                def origin = dir ? zfinUtil.captureOutput(['git', '-C', dir, 'remote', 'get-url', 'origin']) : ''
                def ghMatch = origin =~ /github\.com[:\/](.+?)(?:\.git)?\/?$/
                def ghSlug = ghMatch ? ghMatch[0][1] : ''
                println "zenv: $active"
                if (dir)     println "  dir      : $dir"
                if (branch)  println "  branch   : $branch"
                if (zfinUtil.stackVar('ZENV_HOST')) println "  url      : https://${zfinUtil.stackVar('ZENV_HOST')}${ip ? "  ($ip)" : ''}"
                if (branch)  println "  jira     : https://zfin.atlassian.net/browse/$branch"
                if (branch && ghSlug) println "  pr       : https://github.com/$ghSlug/pull/new/$branch"
                if (dbimg) println "  images   : $dbimg (+ solr)"
                if (zfinUtil.stackVar('PRELOADED_TAG')) println "  tag      : ${zfinUtil.stackVar('PRELOADED_TAG')}"
                println "  compose  : ${zfinUtil.stackVar('COMPOSE_FILE') ?: '<none>'}"
                println "  env-file : ${envf ?: '<none>'}"
                println "\ncontainers:"
                zfinUtil.runCommand(['docker', 'compose', 'ps'], [check: false])
                // A --shared-db stack's data tier lives in the separate `zfin_shared` project,
                // so `docker compose ps` above won't list it. Show it explicitly.
                if (zfinUtil.stackVar('COMPOSE_FILE')?.contains('shared-db.yml')) {
                    def proj = zfinUtil.stackVar('COMPOSE_PROJECT_NAME')
                    println "\nshared data (project zfin_shared, connected into ${proj}_default):"
                    new ProcessBuilder(['docker', 'ps', '-a',
                        '--filter', 'label=com.docker.compose.project=zfin_shared',
                        '--format', 'table {{.Names}}\t{{.Status}}\t{{.Image}}']).inheritIO().start().waitFor()
                }
                break

            default: die("StackOps: unknown op '$op'", 2)
        }
    }
}
