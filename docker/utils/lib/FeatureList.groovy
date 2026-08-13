// FeatureList -- `z feature ls`: list the feature worktrees and their stacks (project,
// branch, data mode own/shared, up/down, url). Read-only.
class FeatureList {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def captureOutput = zfinUtil.&captureOutput
        def wtParent = zfinUtil.REPO.parentFile
        def wts = ((wtParent.listFiles() ?: []) as List).findAll { it.isDirectory() && it.name.startsWith('wt-') }.sort { it.name }
        if (!wts) { println "no feature worktrees (wt-*) under $wtParent"; return }

        // Compose projects that currently have a running container.
        def running = captureOutput(['docker', 'ps', '--format', '{{.Label "com.docker.compose.project"}}'])
                        .readLines().findAll { it } as Set
        def field = { File env, String k ->
            env.isFile() ? (env.readLines().findAll { it.startsWith(k + '=') }.collect { it.split('=', 2)[1] }[-1] ?: '') : ''
        }
        // .zenv health: a bundle is a frozen copy of the origin tooling + compose files, so
        // compare its recorded fingerprint against the origin's current one -- `stale` means
        // `z feature refresh` has something to do. `legacy` is a .zenv from before bundling
        // (still symlinked back to the origin); `?` means the origin has no tooling to compare
        // against (primary checked out on a branch without it).
        def originUtils = new File(zfinUtil.REPO, 'docker/utils')
        def zenvState = { File wt ->
            def spec = zfinUtil.zenvVars(new File(wt, '.zenv'))
            if (!spec) return '-'
            if (spec.legacy) return 'legacy'
            if (spec.mode == 'link') return 'link'
            def now = zfinUtil.bundleHash(originUtils, (spec.composeSource ?: '').tokenize(':').collect { new File(it) })
            if (!now) return 'copy(?)'
            now == spec.hash ? 'copy' : 'copy(stale)'
        }
        def row = { a, b, c, d, e, f, g -> println String.format("%-16s %-14s %-6s %-11s %-5s %-26s %s", a, b, c, d, e, f, g) }
        row('PROJECT', 'BRANCH', 'DATA', 'ZENV', 'STATE', 'URL', 'WORKTREE')
        wts.each { wt ->
            def env    = new File(wt, 'docker/.env')
            def proj   = field(env, 'COMPOSE_PROJECT_NAME') ?: wt.name.replaceFirst('^wt-', '')
            def host   = field(env, 'DOCKER_VIRTUAL_HOST')
            def branch = captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) ?: '?'
            def act    = new File(wt, '.zenv/activate')
            def data   = (act.isFile() && act.text.contains('shared-db')) ? 'shared' : 'own'
            def state  = running.contains(proj) ? 'up' : 'down'
            row(proj, branch, data, zenvState(wt), state, host ? "https://$host" : '', wt.name)
        }
        println "\nZENV: copy = self-contained bundle, in step with the origin tooling;" +
                " copy(stale)/legacy -> z feature refresh <ticket>"
    }
}
