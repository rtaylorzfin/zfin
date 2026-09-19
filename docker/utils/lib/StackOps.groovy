// StackOps -- the stack lifecycle commands, acting on the stack that owns the working
// directory (ZfinUtil.resolveStack: git for the checkout root, then its docker/.env):
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
                die("z $op: no stack found. Run this from inside a checkout or feature worktree\n" +
                    "   whose docker/.env names a COMPOSE_PROJECT_NAME -- that file is what makes a\n" +
                    "   directory a stack.")
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
        // The claude sidecar needs its host-side token file to exist before compose tries to
        // bind-mount it -- Docker silently creates a DIRECTORY for a missing bind source, which
        // then fails confusingly inside the container. Create it empty (0600) and let the
        // container's profile explain how to fill it, rather than failing here: entering the
        // sidecar to read the instructions is a perfectly reasonable thing to do.
        def prepareClaudeToken = {
            def f = new File(StackConfig.claudeTokenFile())
            zfinUtil.childEnv['ZFIN_CLAUDE_TOKEN_FILE'] = f.absolutePath
            if (f.isFile()) {
                if (f.length() == 0)
                    zfinUtil.info("note: ${f} is empty -- run `claude setup-token` on the HOST and save it there")
                return
            }
            if (f.isDirectory())
                die("$f is a directory, not a token file. Docker creates one when the bind source " +
                    "is missing; remove it and re-run.")
            f.parentFile.mkdirs()
            f.text = ''
            // Best effort: a credential file should not be world-readable even while empty.
            try { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false) } catch (ignored) { }
            zfinUtil.info("created an empty token file at $f -- run `claude setup-token` on the HOST and save it there")
        }

        def runExec = { boolean isExec, List a ->
            requireStack()
            def (svc, da, ba) = parseSvc(a)
            if (svc == StackConfig.CLAUDE_SERVICE) prepareClaudeToken()
            if (isExec) compose(['exec'] + (System.console() ? [] : ['-T']) + da + [svc, 'bash', '-l'] + ba)
            // --no-deps: `docker compose run` starts a service's dependencies by default, so a
            // one-off command in a service that declares any becomes a stack boot -- `z run
            // jenkins -c 'cat <file>'` waited on a 19G Postgres going healthy. Dependencies are
            // sequenced by the commands that need them (`z build` ups db before load-db), not by
            // a graph that exists for `up` and `down` ordering. Without this, no service anyone
            // might `z run` can safely declare depends_on, which is why `compile` has none.
            else        compose(['run', '--rm', '--no-deps'] + da + [svc, 'bash', '-l'] + ba)
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
                def active = zfinUtil.stackVar('ZFIN_STACK_PROJECT')
                if (!active) { println "stack: none here -- cd into a checkout or feature worktree"; return }
                def envf = zfinUtil.stackVar('COMPOSE_ENV_FILES')
                def readEnv = { String key -> zfinUtil.envField(envf ? new File(envf) : null, key) }
                def offset = readEnv('ZFIN_PORT_OFFSET')
                // Branch: the checked-out branch of this stack's worktree (empty if ZFIN_STACK_DIR
                // isn't a git worktree). The Jira issue is assumed to share the branch name
                // (ZFIN's feature branches are ticket-keyed), linked at the standard browse URL.
                def dir = zfinUtil.stackVar('ZFIN_STACK_DIR')
                def branch = dir ? zfinUtil.captureOutput(['git', '-C', dir, 'rev-parse', '--abbrev-ref', 'HEAD']) : ''
                // PR "create" link: derive the GitHub owner/repo slug from origin (SSH or HTTPS
                // form) and point at the open-a-PR page for this branch. Empty for non-GitHub origins.
                def origin = dir ? zfinUtil.captureOutput(['git', '-C', dir, 'remote', 'get-url', 'origin']) : ''
                def ghMatch = origin =~ /github\.com[:\/](.+?)(?:\.git)?\/?$/
                def ghSlug = ghMatch ? ghMatch[0][1] : ''
                println "stack: $active"
                if (dir)     println "  dir      : $dir"
                if (branch)  println "  branch   : $branch"
                if (zfinUtil.stackVar('ZFIN_STACK_HOST')) println "  url      : https://${zfinUtil.stackVar('ZFIN_STACK_HOST')}"
                if (offset) println "  ports    : db 127.0.0.1:${5432 + (offset as int)}   debug 127.0.0.1:${5000 + (offset as int)}"
                if (branch)  println "  jira     : https://zfin.atlassian.net/browse/$branch"
                if (branch && ghSlug) println "  pr       : https://github.com/$ghSlug/pull/new/$branch"
                if (zfinUtil.stackVar('ZFIN_SEED')) println "  seed     : ${zfinUtil.stackVar('ZFIN_SEED')}"
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
