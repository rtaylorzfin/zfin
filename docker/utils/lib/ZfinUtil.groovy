// ZfinUtil -- shared helpers, canonical roots, and the preloaded-volume contract for the
// dev-stack command classes in docker/utils/lib/. The single front door `docker/utils/z`
// loads ZfinUtil AND every command class through ONE GroovyClassLoader (with lib/ on its
// classpath, so `ZfinUtil` resolves to a single Class everywhere), builds one instance, and
// calls `cmd.run(args, zfinUtil)` in-process. So command classes get the helpers + roots as
// a typed parameter -- no self-location, no env plumbing, one JVM:
//
//   class NewFeature {
//     def run(List args, ZfinUtil zfinUtil) {
//       def die = zfinUtil.&die; def runCommand = zfinUtil.&runCommand; def DOCKER = zfinUtil.DOCKER
//       ...
//     }
//   }
//
// The helper semantics unify what used to be copy-pasted (and had drifted) across the
// scripts: runCommand(List, [check:false]) honors check, and childEnv injects extra process env
// (zbuild sets it to default COMPOSE_FILE).
class ZfinUtil {
    // Canonical roots, derived from the one path z hands us -- no .parentFile depth-counting
    // scattered across scripts; if the tree ever moves, only this constructor changes.
    //
    // Exception: a BUNDLED z (a copy under <stack>/.zenv/utils/, see CreateZenv) can't derive
    // DOCKER/REPO from its own location -- its parent is .zenv/, not a checkout. create-zenv
    // records where it came from in .zenv/zenv.properties, so the bundle READS the origin roots
    // instead of deriving them. Day-to-day stack ops need neither; the origin-side commands
    // (feature new/rm, build-preloaded) do, and they get the real checkout.
    final File UTILS, LIB, DOCKER, REPO
    ZfinUtil(File utils) {
        UTILS  = utils.canonicalFile      // docker/utils (or <stack>/.zenv/utils when bundled)
        LIB    = new File(UTILS, 'lib')   // docker/utils/lib
        def props = new File(UTILS.parentFile, 'zenv.properties')
        def p = new Properties()
        if (props.isFile()) props.withInputStream { p.load(it) }
        DOCKER = p.'origin.docker' ? new File(p.'origin.docker') : UTILS.parentFile   // docker
        REPO   = p.'origin.repo'   ? new File(p.'origin.repo')   : DOCKER.parentFile  // checkout root
    }

    // Warm-volume contract + service roles + image names now live in StackConfig (policy).
    File auxDir(String tag) { new File(DOCKER, "preloaded-app/$tag") }

    // Extra env injected into every spawned process (zbuild uses this to default COMPOSE_FILE;
    // resolveStack uses it to target an un-activated stack).
    Map<String, String> childEnv = [:]

    /** Where a stack-targeting var comes from: an ADOPTED stack (childEnv, see resolveStack)
     *  or an activated .zenv (the real environment). Read these instead of System.getenv so a
     *  command behaves the same whether the stack was activated or auto-detected. */
    String stackVar(String key) { childEnv[key] ?: System.getenv(key) }

    /** Nothing activated -> target THIS checkout's .zenv for this ONE invocation: its COMPOSE_*
     *  vars go into childEnv, so every `docker compose` we spawn acts on that stack. That's what
     *  makes `./z run -c "..."` work straight out of a checkout with no
     *  `source .zenv/activate >/dev/null &&` in front of it.
     *
     *  One stack per checkout, and its .zenv sits at the repo/worktree ROOT -- so there is
     *  nothing to search for: ask git where the root is and look there. Works from any
     *  subdirectory. Non-git directories still work (create-zenv accepts any dir); they just
     *  have to BE the stack dir.
     *
     *  `source .zenv/activate` remains the explicit path and always WINS -- but if a .zenv is
     *  active while you stand in a different stack's tree, say so: silently acting on the wrong
     *  stack is the one failure worth shouting about (`zdown` in the wrong worktree). */
    void resolveStack(File cwd) {
        def here = cwd?.canonicalFile
        if (!here) return
        def top = captureOutput(['git', '-C', here.absolutePath, 'rev-parse', '--show-toplevel'])
        def zenv = new File(top ?: here.absolutePath, '.zenv')
        def spec = zenvVars(zenv)
        if (!spec) return

        def active = System.getenv('ZENV_ACTIVE')
        if (System.getenv('COMPOSE_FILE')) {
            if (active && active != spec.project)
                System.err.println("!! note: '$active' is activated but you're inside ${spec.project}'s tree" +
                        " -- commands act on '$active'.  (source ${zenv.absolutePath}/activate to switch)")
            return
        }
        childEnv['COMPOSE_PROJECT_NAME'] = spec.project
        childEnv['COMPOSE_FILE'] = spec.compose
        childEnv['COMPOSE_ENV_FILES'] = spec.envFile ?: ''
        childEnv['ZENV_ACTIVE'] = spec.project
        childEnv['ZENV_DIR'] = spec.dir ?: zenv.parentFile.absolutePath
        childEnv['ZENV_HOST'] = spec.host ?: ''
        if (spec.tag) childEnv['PRELOADED_TAG'] = spec.tag
        System.err.println(">> targeting '${spec.project}' from $zenv (no .zenv activated)")
    }

    /** What a .zenv describes, read from the zenv.properties create-zenv writes. The one reader,
     *  used by auto-detection, `feature ls`, `feature refresh` and `feature rm`. Uniform keys:
     *  mode, project, host, tag, dir, envFile, compose (what to run: the bundled copies),
     *  composeSource (what to copy FROM), hash. Null if the directory holds no usable .zenv --
     *  including a pre-bundle one, which is regenerated by re-running create-zenv, not read. */
    Map zenvVars(File zenvDir) {
        def props = new File(zenvDir, 'zenv.properties')
        if (!props.isFile()) return null
        def p = new Properties()
        props.withInputStream { p.load(it) }
        if (!p.project || !p.'compose.active') return null
        [mode: p.mode ?: 'copy', project: p.project, host: p.host, tag: p.tag, dir: p.dir,
         envFile: p.'env.file', compose: p.'compose.active', composeSource: p.'compose.source',
         hash: p.'tooling.hash']
    }

    /** Content fingerprint of what a .zenv bundle was copied FROM: the front door, lib/, and
     *  the compose files. create-zenv records it; `z feature ls` recomputes it against the
     *  origin to flag a bundle that has fallen behind (refresh with `z feature refresh`). */
    String bundleHash(File utilsDir, List<File> composeSources) {
        def md = java.security.MessageDigest.getInstance('MD5')
        def files = [new File(utilsDir, 'z')] +
                (((new File(utilsDir, 'lib').listFiles() ?: []) as List).sort { it.name }) +
                (composeSources ?: [])
        def any = false
        files.each { f ->
            if (f?.isFile()) { any = true; md.update(f.name.bytes); md.update(f.bytes) }
        }
        any ? md.digest().encodeHex().toString() : ''
    }

    void die(String m, int code = 1) { System.err.println("!! $m"); System.exit(code) }
    void info(String m) { println(">> $m") }

    private ProcessBuilder newProcess(List cmd) {
        def p = new ProcessBuilder(cmd*.toString())
        childEnv.each { k, v -> p.environment().put(k.toString(), v.toString()) }  // env map is String,String (values may be GStrings)
        p
    }

    /** Run a command, streaming stdio. Dies on nonzero unless [check:false]. Returns exit code. */
    int runCommand(List cmd, Map opts = [:]) {
        def code = newProcess(cmd).inheritIO().start().waitFor()
        if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
        code
    }

    /** Run with stdout+stderr discarded; return exit code (never dies). */
    int runQuietly(List cmd) {
        newProcess(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
               .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    }

    /** Run; return trimmed stdout (stderr discarded). Never dies. */
    String captureOutput(List cmd) {
        def p = newProcess(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        def out = p.inputStream.text; p.waitFor(); out.trim()
    }

    /** True if a docker image exists locally. */
    boolean imageExists(String ref) { runQuietly(['docker', 'image', 'inspect', ref]) == 0 }

    /** True if an external tool is resolvable on PATH. The optional integrations (hostctl,
     *  tmux) are all "use it if it's there, hint if it isn't", so they share one probe. */
    boolean onPath(String cmd) { runQuietly(['which', cmd]) == 0 }

    private Map<String, String> dotenvCache = null
    /** Parse docker/.env into a map (KEY=value lines). Parsed once per ZfinUtil instance
     *  (the base .env doesn't change mid-run). The single .env parser. */
    Map<String, String> dotenv() {
        if (dotenvCache != null) return dotenvCache
        def m = [:]
        def f = new File(DOCKER, '.env')
        if (f.isFile()) f.eachLine { line ->
            def mm = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
            if (mm.find()) m[mm.group(1)] = mm.group(2)
        }
        dotenvCache = m
    }

    /** Resolve a config value like compose's `${KEY:-dflt}`: docker/.env if set to a
     *  NON-EMPTY value, else the ambient environment, else `dflt`. A blank `KEY=` in .env
     *  falls back (matching compose `:-` and the prior release reader). */
    String env(String key, String dflt = null) {
        def v = dotenv()[key]
        v ?: (System.getenv(key) ?: dflt)
    }

    /** Print a command's own file header (its leading `//` comment block) as --help text.
     *  Pass the command instance; reads its .groovy source (works for gcl-loaded classes). */
    void printHeader(cmd) {
        def f = new File(cmd.getClass().protectionDomain.codeSource.location.toURI())
        println f.readLines()
                 .takeWhile { it.startsWith('//') || it.startsWith('#!') || it.trim().isEmpty() }
                 .findAll { it.startsWith('//') }
                 .collect { it.replaceFirst('^// ?', '') }
                 .join('\n')
    }

    /** -h/--help guard: if requested, print the command's header and return true (so the
     *  caller can `if (zfinUtil.helpRequested(args, this)) return`). */
    boolean helpRequested(List args, cmd) {
        if (args.any { it in ['-h', '--help'] }) { printHeader(cmd); return true }
        false
    }

    /** The canonical tar-capable container: the compile image (GNU tar, runs as root, always
     *  local, no Docker Hub pull). One source both the warm-restore and the capture use.
     *  Reads ZFIN_RELEASE from docker/.env (falling back to the environment). */
    String tarImage() { StackConfig.compileImage(env('ZFIN_RELEASE')) }

    // Connect the shared `zfin_shared` db/solr containers INTO the given feature project's
    // default network (`<project>_default`) with aliases db/solr, so a --shared-db feature's
    // app tier resolves `db`/`solr` to the shared containers. This is how a --shared-db
    // feature reaches shared data WITHOUT multi-homing its own tomcat -- catalina sets
    // `-Djava.rmi.server.hostname=$(container ip)`, and a two-network tomcat expands that to
    // two IPs, the second leaking in as a bare java arg (fatal "Could not find or load main
    // class 172.x"). Postgres/solr don't care about being on several networks, so we attach
    // THEM to the feature's network instead. Idempotent: skips a container already joined.
    // The network must already exist (compose creates it on up / up --no-start).
    void connectSharedData(String project) {
        def net = "${project}_default".toString()   // String, not GString: List.contains below
        StackConfig.DATA_SERVICES.each { svc ->
            def cid = captureOutput(['docker', 'ps', '-q',
                '--filter', 'label=com.docker.compose.project=zfin_shared',
                '--filter', "label=com.docker.compose.service=$svc"])
            if (!cid) { info("warning: shared $svc not running -- run 'z shared up' first"); return }
            def nets = captureOutput(['docker', 'inspect', cid, '--format',
                '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}']).split() as List
            if (nets.contains(net)) return
            info("connect shared $svc -> $net (alias $svc)")
            // check:false: a redundant connect (already joined) errors harmlessly.
            runCommand(['docker', 'network', 'connect', '--alias', svc, net, cid], [check: false])
        }
    }
}
