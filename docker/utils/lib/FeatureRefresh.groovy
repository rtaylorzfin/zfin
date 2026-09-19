// FeatureRefresh -- `z feature refresh <ticket>|--all [--dry-run]`
//
// Backfills keys a feature's docker/.env is missing. A stack's .env is written once, by
// `z feature new`, and then outlives every change to what that file should contain: keys have
// twice replaced lines that used to live in docker-compose.overlay-review-member.yml, and a stack made
// before a key existed keeps working right up until the missing key changes what compose
// renders. DOCKER_EXTERNAL_VHOST is the sharp one -- absent, it falls back to
// DOCKER_VIRTUAL_HOST, and the stack advertises itself to a proxy that cannot reach it, which
// surfaces as a 502 from someone else's nginx-proxy rather than as an error here.
//
// ONLY ADDS. An existing key is never rewritten, whatever its value: a .env is a developer's
// file, and a deliberate local override must survive a refresh. Use --dry-run to see what
// would be appended.
//
// What "should be there" is StackConfig.featureEnv, the same list `z feature new` writes from,
// so the two cannot disagree about it.
class FeatureRefresh {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def info = zfinUtil.&info
        def die  = zfinUtil.&die

        boolean all = false, dryRun = false
        def targets = []
        args.each { a ->
            switch (a) {
                case '--all':     all = true; break
                case '--dry-run': dryRun = true; break
                default:
                    if (a.startsWith('-')) die("unknown flag: $a", 2)
                    targets << a
            }
        }
        if (!all && !targets) die("name a ticket, or --all.\n" +
                                  "   z feature refresh <ticket> [--dry-run]\n" +
                                  "   z feature refresh --all", 2)

        def stacks = zfinUtil.featureStacks()
        if (!stacks) { info("no feature worktrees under ${zfinUtil.worktreesDir()}"); return }
        if (!all) {
            def known = stacks.collect { it.slug }
            targets.each { t -> if (!(t in known)) die("no such feature: $t\n   known: ${known.join(', ')}") }
            stacks = stacks.findAll { it.slug in targets }
        }

        int changed = 0
        stacks.each { st ->
            def envF = new File(new File(zfinUtil.worktreesDir(), st.worktree), 'docker/.env')
            if (!envF.isFile()) { println "  ${st.slug}: no docker/.env -- skipped"; return }
            // The hostname every other key is derived from. Without it we cannot know what this
            // stack is called, and guessing it would write a wrong value into a working stack.
            def host = zfinUtil.envField(envF, 'DOCKER_VIRTUAL_HOST')
            if (!host) { println "  ${st.slug}: no DOCKER_VIRTUAL_HOST -- skipped (cannot derive the rest)"; return }

            def present = envF.readLines().collect { (it =~ /^([A-Za-z_][A-Za-z0-9_]*)=/) }
                              .findAll { it.find() }.collect { it.group(1) } as Set
            def missing = StackConfig.featureEnv(host).findAll { k, v -> !(k in present) }

            // The one key refresh REWRITES rather than adds. It records which overlay files
            // compose this stack, so when those files are renamed the recorded value is not a
            // preference to respect, it is a wrong answer -- every stack op fails on a missing
            // -f until it is corrected.
            def lines = envF.readLines()
            def renamed = []
            lines = lines.collect { line ->
                if (!line.startsWith('ZFIN_COMPOSE_OVERLAYS=')) return line
                def vals = line.substring('ZFIN_COMPOSE_OVERLAYS='.length()).tokenize(':')
                def fixed = vals.collect { StackConfig.OVERLAY_RENAMES[it] ?: it }
                if (fixed != vals) renamed = vals.findAll { StackConfig.OVERLAY_RENAMES[it] }
                'ZFIN_COMPOSE_OVERLAYS=' + fixed.join(':')
            }

            if (!missing && !renamed) { println "  ${st.slug}: up to date"; return }

            changed++
            def did = []
            if (missing) did << "${dryRun ? 'would add' : 'adding'} ${missing.keySet().join(', ')}"
            if (renamed) did << "${dryRun ? 'would rename' : 'renaming'} overlay(s): ${renamed.join(', ')}"
            println "  ${st.slug}: ${did.join('; ')}"
            if (dryRun) return
            if (renamed) envF.text = lines.join('\n') + '\n'
            if (missing) {
                envF << "\n# added by `z feature refresh` -- see StackConfig.featureEnv\n"
                missing.each { k, v -> envF << "$k=$v\n" }
            }
        }

        if (!changed) { info("every feature .env is up to date"); return }
        info(dryRun ? "--dry-run: nothing written"
                    : "${changed} .env file(s) updated. Recreate the affected containers to pick " +
                      "them up:\n   cd <worktree> && ./z up -d")
    }
}
