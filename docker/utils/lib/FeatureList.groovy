// FeatureList -- `z feature ls`: list the feature worktrees and their stacks (project,
// branch, data mode own/shared, up/down, url). Read-only.
class FeatureList {
    def run(List args, ZfinUtil zfinUtil) {
        if (args.any { it in ['-h', '--help'] }) { zfinUtil.printHeader(this); return }
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
        def row = { a, b, c, d, e, f -> println String.format("%-16s %-14s %-6s %-5s %-26s %s", a, b, c, d, e, f) }
        row('PROJECT', 'BRANCH', 'DATA', 'STATE', 'URL', 'WORKTREE')
        wts.each { wt ->
            def env    = new File(wt, 'docker/.env')
            def proj   = field(env, 'COMPOSE_PROJECT_NAME') ?: wt.name.replaceFirst('^wt-', '')
            def host   = field(env, 'DOCKER_VIRTUAL_HOST')
            def branch = captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) ?: '?'
            def act    = new File(wt, '.zenv/activate')
            def data   = (act.isFile() && act.text.contains('shared-db')) ? 'shared' : 'own'
            def state  = running.contains(proj) ? 'up' : 'down'
            row(proj, branch, data, state, host ? "https://$host" : '', wt.name)
        }
    }
}
