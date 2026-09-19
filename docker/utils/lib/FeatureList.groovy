// FeatureList -- `z feature ls`: list the feature worktrees and their stacks (project,
// branch, data mode own/shared, up/down, url). Read-only.
class FeatureList {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        // The inventory itself lives in ZfinUtil.featureStacks() -- the same derivation the
        // same derivation `z review status` counts, so the table and the proxy agree
        // never disagree about what exists. This class is now purely presentation.
        def stacks = zfinUtil.featureStacks()
        if (!stacks) { println "no feature worktrees under ${zfinUtil.worktreesDir()}"; return }

        def row = { a, b, c, d, e, f -> println String.format("%-16s %-22s %-6s %-6s %-38s %s", a, b, c, d, e, f) }
        row('PROJECT', 'BRANCH', 'DATA', 'STATE', 'URL', 'WORKTREE')
        stacks.each { st -> row(st.project, st.branch, st.data, st.state, st.url, st.worktree) }
        println "\nDATA: own = this stack's own db+solr copy; shared = the zfin_shared stack"
        println "STATE: frozen = archived by `z feature freeze`, restore with `z feature thaw`"
    }
}
