// SharedStack -- manage the dedicated shared data stack (Compose project `zfin_shared`):
// the preloaded db + solr run ONCE on the named network zfin_shared_net, and feature stacks
// created with `z feature new --shared-db` attach to it instead of seeding their own copies.
//   z shared up   [--tag T]        boot the shared db+solr (creates zfin_shared_net)
//   z shared down [--rm-data]      stop it (keep the shared copy; --rm-data discards it)
//   z shared status                show the shared stack + which features are attached
//
// SHARED DATA == SHARED WRITES: read-mostly features only. See reference/preloaded-dev-stacks.md.
class SharedStack {
    def run(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def DOCKER = zfinUtil.DOCKER

        def sub  = args ? args[0] : 'status'
        def rest = args.drop(1)
        def tagArg = null; def rmData = false
        for (int i = 0; i < rest.size(); i++) {
            switch (rest[i]) {
                case '--tag':               tagArg = rest[++i]; break
                case '--rm-data': case '--volumes': rmData = true; break
                default: die("z shared: unknown arg '${rest[i]}'", 2)
            }
        }

        // Tag selection mirrors new-feature: --tag > $PRELOADED_TAG > newest local image.
        def newestTag = {
            def imgs = captureOutput(['docker', 'images', 'zfin-db-preloaded', '--format', '{{.CreatedAt}}\t{{.Tag}}'])
                        .readLines().findAll { it?.trim() && !it.endsWith('\t<none>') }
            imgs ? imgs.sort().last().split('\t').last().trim() : null
        }
        def tag = tagArg ?: System.getenv('PRELOADED_TAG') ?: newestTag()

        // The shared stack = base + preloaded overlay + the shared provider overlay, run as
        // project `zfin_shared` off the base docker/.env. ZFIN_DB_IMAGE/SOLR_IMAGE are needed
        // for the preloaded overlay's interpolation; inject them via childEnv.
        def files = ['docker-compose.yml', 'docker-compose.preloaded.yml', 'docker-compose.shared.yml']
        def compose = ['docker', 'compose', '-p', 'zfin_shared', '--env-file', new File(DOCKER, '.env').absolutePath] +
                      files.collectMany { ['-f', new File(DOCKER, it).absolutePath] }
        if (tag) {
            zfinUtil.childEnv['ZFIN_DB_IMAGE']   = "zfin-db-preloaded:$tag"
            zfinUtil.childEnv['ZFIN_SOLR_IMAGE'] = "zfin-solr-preloaded:$tag"
        }

        switch (sub) {
            case 'up':
                if (!tag) die("no preloaded tag found -- build one (z feature build-preloaded) or pass --tag")
                info("shared data stack 'zfin_shared' up (tag $tag) -> seeds ONE db+solr copy on network zfin_shared_net")
                runCommand(compose + ['up', '-d', 'db', 'solr'])
                info("attach features with: z feature new <ticket> --shared-db")
                break
            case 'down':
                runCommand(compose + ['down'] + (rmData ? ['-v'] : []), [check: false])
                info(rmData ? "shared stack down; the shared copy was discarded (-v)"
                            : "shared stack down; shared copy kept (z shared down --rm-data to discard)")
                break
            case 'status':
                runCommand(compose + ['ps'], [check: false])
                def attached = captureOutput(['docker', 'network', 'inspect', 'zfin_shared_net',
                                              '--format', '{{range .Containers}}{{.Name}} {{end}}'])
                info("attached to zfin_shared_net: ${attached ?: '(none, or network not up)'}")
                break
            default: die("z shared: unknown subcommand '$sub' (up|down|status)", 2)
        }
    }
}
