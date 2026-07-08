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
    final File UTILS, LIB, DOCKER, REPO
    ZfinUtil(File utils) {
        UTILS  = utils.canonicalFile      // docker/utils
        LIB    = new File(UTILS, 'lib')   // docker/utils/lib
        DOCKER = UTILS.parentFile         // docker
        REPO   = DOCKER.parentFile        // checkout root
    }

    // Preloaded warm-volume contract: the SINGLE source both BuildPreloaded (producer) and
    // NewFeature (consumer) read, so the lists + on-disk layout can never drift apart.
    static final List<String> APP_VOLS   = ['www_data', 'catalina_base', 'keystore', 'tls_certs']
    static final List<String> CACHE_VOLS = ['gradle_cache', 'maven_cache']
    File auxDir(String tag) { new File(DOCKER, "preloaded-app/$tag") }

    // Extra env injected into every spawned process (zbuild uses this to default COMPOSE_FILE).
    Map<String, String> childEnv = [:]

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

    /** Run, feeding `input` to stdin; stream stdout/stderr. Dies on nonzero unless [check:false]. */
    int runWithInput(List cmd, String input, Map opts = [:]) {
        def p = newProcess(cmd).redirectOutput(ProcessBuilder.Redirect.INHERIT)
                       .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        p.outputStream.withWriter('UTF-8') { it << input }
        def code = p.waitFor()
        if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
        code
    }

    /** True if a docker image exists locally. */
    boolean imageExists(String ref) { runQuietly(['docker', 'image', 'inspect', ref]) == 0 }

    /** Parse docker/.env into a map (KEY=value lines). The single .env parser. */
    Map<String, String> dotenv() {
        def m = [:]
        def f = new File(DOCKER, '.env')
        if (f.isFile()) f.eachLine { line ->
            def mm = (line =~ /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/)
            if (mm.find()) m[mm.group(1)] = mm.group(2)
        }
        m
    }

    /** Resolve a config value the way `docker compose` does: docker/.env is authoritative
     *  (honored even if blank), else the ambient environment, else `dflt`. */
    String env(String key, String dflt = null) {
        def de = dotenv()
        de.containsKey(key) ? de[key] : (System.getenv(key) ?: dflt)
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

    /** The canonical tar-capable container: the compile image (GNU tar, runs as root, always
     *  local, no Docker Hub pull). One source both the warm-restore and the capture use.
     *  Reads ZFIN_RELEASE from docker/.env (falling back to the environment). */
    String tarImage() { "ghcr.io/zfin/zfin-compile:${env('ZFIN_RELEASE')}" }

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
        ['db', 'solr'].each { svc ->
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
