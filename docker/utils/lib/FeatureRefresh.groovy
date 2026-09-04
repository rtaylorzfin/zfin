// FeatureRefresh -- `z feature refresh [<ticket>|<dir>]... | --all`: re-copy the bundled
// tooling + compose files into an existing stack's .zenv/.
//
// A copy-mode .zenv (every feature; see CreateZenv) is a FROZEN snapshot of docker/utils/ and
// the compose file(s) -- that's what makes a feature independent of the primary checkout, and
// it's also why fixes to the tooling don't reach stacks that already exist. Refresh replays
// create-zenv from the args recorded in .zenv/zenv.properties, so the bundle is re-copied from
// the origin checkout in place: same project, host, tag, env file, overlays.
//
// Re-source the activate script afterwards (`source .zenv/activate`, or a new shell) so the
// running shell picks up the new PATH entry + completion.
//
// Usage:
//   z feature refresh <ticket>...    refresh those features (wt-<ticket>, or a path to a dir)
//   z feature refresh --all          refresh every copy-mode .zenv under the worktree parent
class FeatureRefresh {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def REPO = zfinUtil.REPO

        def all = false
        def names = []
        args.each {
            if (it == '--all') all = true
            else if (it.startsWith('-')) die("z feature refresh: unknown arg '$it'", 2)
            else names << it
        }
        if (!all && !names) die("usage: z feature refresh <ticket>... | --all", 2)

        // A target is a stack DIRECTORY: a path if you give one, else the feature's worktree.
        def dirs = []
        if (all) {
            def parent = REPO.parentFile
            dirs = ((parent.listFiles() ?: []) as List)
                    .findAll { it.isDirectory() && it.name.startsWith('wt-') && new File(it, '.zenv/zenv.properties').isFile() }
                    .sort { it.name }
            if (!dirs) die("no .zenv found under $parent (nothing to refresh)")
        } else {
            dirs = names.collect { n ->
                def asPath = new File(n).absoluteFile
                asPath.isDirectory() ? asPath : new File(REPO.parentFile, "wt-${n.toLowerCase()}")
            }
        }

        dirs.each { dir ->
            // composeSource, not compose: re-copy from where the originals live, so refreshing a
            // bundle twice doesn't end up copying a bundle's own copies onto themselves.
            def spec = zfinUtil.zenvVars(new File(dir, '.zenv'))
            if (!spec) die("$dir has no .zenv -- provision it with `z feature new`, or generate one with `z create-zenv --dir $dir ...`")
            if (spec.mode == 'link' || dir.canonicalFile == REPO.canonicalFile) {
                info("skipping ${dir.name}: linked .zenv (the primary checkout tracks the tooling live)"); return
            }

            def a = ['--dir', dir.absolutePath, '--project', spec.project,
                     '--compose', spec.composeSource, '--env-file', spec.envFile, '--copy']
            if (spec.tag) a += ['--tag', spec.tag]
            if (spec.host) a += ['--host', spec.host]
            info("refreshing ${dir.name} (project ${spec.project})")
            new CreateZenv().run(a, zfinUtil)
        }
        info("done -- re-source each stack's activate script to pick it up:  source <dir>/.zenv/activate")
    }

}
