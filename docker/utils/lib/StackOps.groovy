// StackOps -- the stack lifecycle commands that operate on the active .zenv stack:
// run / exec / up / down / pull / log / restart / status. z routes the whole family here
// (args[0] is the op), so z itself stays pure routing. All but `status` require an active
// stack (COMPOSE_FILE from .zenv); status reports gracefully when none is active.
class StackOps {
    def run(List args, ZfinUtil zfinUtil) {
        def op   = args ? args[0] : 'status'
        def rest = args.drop(1)
        def die  = zfinUtil.&die

        def compose = { List a -> System.exit(new ProcessBuilder((['docker', 'compose'] + a)*.toString()).inheritIO().start().waitFor()) }
        def requireStack = {
            if (!System.getenv('COMPOSE_FILE'))
                die("z $op: no stack active -- activate one first:\n     source <repo-or-worktree>/.zenv/activate")
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
            [service ?: 'compile', dockerArgs, a.drop(i)]
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
                def proj = System.getenv('COMPOSE_PROJECT_NAME')
                if (System.getenv('COMPOSE_FILE')?.contains('shared-db.yml') && proj) {
                    zfinUtil.runCommand(['docker', 'compose', 'up', '--no-start'] + rest)
                    zfinUtil.connectSharedData(proj)
                    compose(['start'] + rest)
                } else {
                    compose(['up', '-d'] + rest)
                }
                break
            case 'down':    requireStack(); compose(['stop'] + rest); break
            case 'pull':    requireStack(); compose(['pull'] + rest); break
            case 'log':     requireStack(); compose(['logs', '-f'] + rest); break
            case 'restart': requireStack(); compose(['restart'] + rest); break

            case 'status':
                def active = System.getenv('ZENV_ACTIVE')
                if (!active) { println "zenv: no feature active. Activate one:  source <repo-or-worktree>/.zenv/activate"; return }
                def envf = System.getenv('COMPOSE_ENV_FILES')
                def readEnv = { String key ->
                    def f = envf ? new File(envf) : null
                    if (!f?.isFile()) return ''
                    def m = f.readLines().findAll { it.startsWith(key) }
                    m ? m.last().substring(key.length()) : ''
                }
                def ip = readEnv('LOOPBACK_IP='); def dbimg = readEnv('ZFIN_DB_IMAGE=')
                println "zenv: $active"
                if (System.getenv('ZENV_DIR'))  println "  dir      : ${System.getenv('ZENV_DIR')}"
                if (System.getenv('ZENV_HOST')) println "  url      : https://${System.getenv('ZENV_HOST')}${ip ? "  ($ip)" : ''}"
                if (dbimg) println "  images   : $dbimg (+ solr)"
                if (System.getenv('PRELOADED_TAG')) println "  tag      : ${System.getenv('PRELOADED_TAG')}"
                println "  compose  : ${System.getenv('COMPOSE_FILE') ?: '<none>'}"
                println "  env-file : ${envf ?: '<none>'}"
                println "\ncontainers:"
                new ProcessBuilder(['docker', 'compose', 'ps']).inheritIO().start().waitFor()
                break

            default: die("StackOps: unknown op '$op'", 2)
        }
    }
}
