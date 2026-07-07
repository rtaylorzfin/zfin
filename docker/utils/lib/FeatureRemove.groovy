// FeatureRemove -- `z feature rm <ticket> [--force]`: tear down a feature stack (the
// teardown block new-feature prints, automated): `docker compose down -v` its stack +
// per-feature volumes, remove the worktree + branch, and drop its hostctl profile + macOS
// lo0 alias. DESTRUCTIVE -- discards the stack's copies AND any uncommitted work in the
// worktree -- so it prompts unless --force. Do it once the feature's PR is merged.
class FeatureRemove {
    def run(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def REPO = zfinUtil.REPO

        def force = false; def name = null
        args.each {
            if (it in ['--force', '-f']) force = true
            else if (it.startsWith('-')) die("z feature rm: unknown arg '$it'", 2)
            else name = it
        }
        if (!name) die("usage: z feature rm <ticket> [--force]", 2)

        def slug = name.toLowerCase()
        def wt   = new File(REPO.parentFile, "wt-$slug")
        def env  = new File(wt, 'docker/.env')
        def field = { String k -> env.isFile() ? (env.readLines().findAll { it.startsWith(k + '=') }.collect { it.split('=', 2)[1] }[-1] ?: '') : '' }
        def ip     = field('LOOPBACK_IP')
        def branch = wt.isDirectory() ? captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) : slug
        def isMac  = System.getProperty('os.name')?.toLowerCase()?.contains('mac')

        if (!force) {
            def con = System.console()
            if (!con) die("z feature rm: no TTY -- pass --force to confirm", 2)
            println "About to REMOVE feature '$slug' (destructive):"
            println "  - docker compose down -v            (containers + this feature's volumes/copies)"
            if (wt.isDirectory())
                println "  - git worktree remove --force $wt + branch -D $branch  (drops uncommitted work)"
            println "  - hostctl remove $slug" + (ip && isMac && ip != '127.0.0.1' ? " + ifconfig lo0 -alias $ip" : '')
            def a = con.readLine("Proceed? [y/N]: ")?.trim()?.toLowerCase()
            if (!(a?.startsWith('y'))) die("aborted.", 0)
        }

        // 1. tear down the Docker stack. Use the feature's own compose file list + env (from its
        //    .zenv) so down -v resolves exactly this stack; check:false so a partial/missing
        //    stack doesn't abort the rest of the teardown.
        def act = new File(wt, '.zenv/activate')
        def compose = ['docker', 'compose', '-p', slug]
        if (env.isFile()) compose += ['--env-file', env.absolutePath]
        if (act.isFile()) {
            def cf = act.readLines().findAll { it.startsWith('_ZENV_COMPOSE_FILE=') }
                        .collect { it.replaceFirst(/^_ZENV_COMPOSE_FILE=/, '').replaceAll("'", '') }[-1]
            if (cf) compose += cf.split(':').collectMany { ['-f', it] }
        }
        info("down -v the '$slug' stack")
        runCommand(compose + ['down', '-v'], [check: false])

        // 2. worktree + branch
        if (wt.isDirectory()) {
            info("removing worktree $wt")
            runCommand(['git', '-C', REPO.absolutePath, 'worktree', 'remove', '--force', wt.absolutePath], [check: false])
        }
        if (branch && branch != 'HEAD') {
            info("deleting branch $branch")
            runCommand(['git', '-C', REPO.absolutePath, 'branch', '-D', branch], [check: false])
        }

        // 3. hostctl profile + macOS loopback alias (sudo -- you'll be prompted)
        if (zfinUtil.runQuietly(['which', 'hostctl']) == 0) {
            info("removing hostctl profile '$slug' (sudo)")
            runCommand(['sudo', 'hostctl', 'remove', slug], [check: false])
        }
        if (ip && ip != '127.0.0.1' && isMac) {
            info("removing lo0 alias $ip (sudo)")
            runCommand(['sudo', 'ifconfig', 'lo0', '-alias', ip], [check: false])
        }
        info("removed feature '$slug'")
    }
}
