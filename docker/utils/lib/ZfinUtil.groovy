// ZfinUtil -- shared helpers, canonical roots, and the preloaded-volume contract for the
// dev-stack command classes in docker/utils/lib/. The single front door `docker/utils/z`
// loads ZfinUtil AND every command class through ONE GroovyClassLoader (with lib/ on its
// classpath, so `ZfinUtil` resolves to a single Class everywhere), builds one instance, and
// calls `cmd.run(args, zfinUtil)` in-process. So command classes get the helpers + roots as
// a typed parameter -- no self-location, no env plumbing, one JVM:
//
//   class NewFeature {
//     def run(List args, ZfinUtil zfinUtil) {
//       def die = zfinUtil.&die; def sh = zfinUtil.&sh; def DOCKER = zfinUtil.DOCKER
//       ...
//     }
//   }
//
// The helper semantics unify what used to be copy-pasted (and had drifted) across the
// scripts: sh(List, [check:false]) honors check, and childEnv injects extra process env
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

    private ProcessBuilder pb(List cmd) {
        def p = new ProcessBuilder(cmd*.toString())
        childEnv.each { k, v -> p.environment().put(k, v) }
        p
    }

    /** Run a command, streaming stdio. Dies on nonzero unless [check:false]. Returns exit code. */
    int sh(List cmd, Map opts = [:]) {
        def code = pb(cmd).inheritIO().start().waitFor()
        if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
        code
    }

    /** Run with stdout+stderr discarded; return exit code (never dies). */
    int quiet(List cmd) {
        pb(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
               .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    }

    /** Run; return trimmed stdout (stderr discarded). Never dies. */
    String capOut(List cmd) {
        def p = pb(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        def out = p.inputStream.text; p.waitFor(); out.trim()
    }

    /** Run, feeding `input` to stdin; stream stdout/stderr. Dies on nonzero unless [check:false]. */
    int shIn(List cmd, String input, Map opts = [:]) {
        def p = pb(cmd).redirectOutput(ProcessBuilder.Redirect.INHERIT)
                       .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        p.outputStream.withWriter('UTF-8') { it << input }
        def code = p.waitFor()
        if (code != 0 && opts.check != false) die("command failed ($code): ${cmd.join(' ')}", code)
        code
    }

    /** True if a docker image exists locally. */
    boolean imageExists(String ref) { quiet(['docker', 'image', 'inspect', ref]) == 0 }
}
