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
// The helpers live here rather than per-command so their semantics cannot drift:
// runCommand(List, [check:false]) honors check, and childEnv injects extra process env
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

    // Warm-volume contract + service roles + image names now live in StackConfig (policy).

    // Extra env injected into every spawned process (zbuild uses this to default COMPOSE_FILE;
    // resolveStack uses it to target an un-activated stack).
    Map<String, String> childEnv = [:]

    /** Where a stack-targeting var comes from: an ADOPTED stack (childEnv, see resolveStack)
     *  or the ambient environment. Read these instead of System.getenv so a
     *  command behaves the same whether the stack was activated or auto-detected. */
    String stackVar(String key) { childEnv[key] ?: System.getenv(key) }

    /** A stack's identity, derived from its own docker/.env plus the ORIGIN checkout's compose
     *  files. Returns null for a directory that is not a provisioned stack.
     *
     *  This replaced the .zenv bundle, which kept a frozen COPY of the tooling and compose
     *  files in every worktree so a feature survived the main checkout switching branches.
     *  The copy was real protection and a real cost: it went stale silently, and a stale
     *  bundle surfaced as "no such service", or a usage error for a flag that existed, rather
     *  than as "your copy is old". One source of truth is worth more than that protection now
     *  the tooling is on main.
     *
     *  The per-feature .env is the record. It already held the project, host and image tags;
     *  it now also records which overlays the stack was built with, so its composition is
     *  knowable without a bundle. */
    Map stackSpec(File dir) {
        if (!dir) return null
        def envF = new File(dir, 'docker/.env')
        if (!envF.isFile()) return null
        def project = envField(envF, 'COMPOSE_PROJECT_NAME')
        if (!project) return null
        // A stack that DECLARES the key gets exactly what it declares -- INCLUDING NOTHING.
        // That case is the main checkout's own base stack: it runs the stock zfin-db/zfin-solr
        // images and loads from unloads, so silently inheriting the preloaded overlay points db
        // at a data overlay the base stack must not have. (Historically that was
        // docker-compose.preloaded.yml pointing db at an image that did not exist until
        // build-preloaded had run -- the very thing the base stack was being loaded to
        // produce -- so `z build all` failed on an unset variable.)
        //
        // An ABSENT key still gets the legacy pair, so stacks provisioned before this was
        // recorded still tear down correctly. envField() cannot tell absent from empty -- both
        // are '' -- so ask the file whether the key is there at all.
        def declared = envF.readLines().any { it.startsWith('ZFIN_COMPOSE_OVERLAYS=') }
        def overlays = declared
                ? envField(envF, 'ZFIN_COMPOSE_OVERLAYS').tokenize(':').findAll { it }
                : ['docker-compose.overlay-review-member.yml']
        // An overlay this .env names but that is not on disk means the file was renamed after
        // the stack was made. Say so here: compose's own error for a missing -f arrives with no
        // hint that a one-command fix exists, and every stack op would hit it.
        def missing = overlays.findAll { !new File(DOCKER, it).isFile() }
        if (missing) {
            def slug = dir.name.replaceFirst('^wt-', '')
            System.err.println("!! ${project}: docker/.env names overlay(s) that no longer exist: " +
                               missing.join(', '))
            System.err.println("   They were renamed. Fix this stack's .env with:  z feature refresh ${slug}")
        }
        def files = ([new File(DOCKER, 'docker-compose.yml')] +
                     overlays.collect { new File(DOCKER, it) })*.absolutePath
        [project : project,
         dir     : dir.absolutePath,
         envFile : envF.absolutePath,
         compose : files.join(':'),
         overlays: overlays,
         host    : envField(envF, 'DOCKER_VIRTUAL_HOST'),
         tag     : envField(envF, 'ZFIN_SEED'),
         data    : overlays.any { it.contains('shared-db') } ? 'shared' : 'own']
    }

    /** Point this invocation at the stack whose tree the cwd is in. Compose reads
     *  COMPOSE_PROJECT_NAME / COMPOSE_FILE / COMPOSE_ENV_FILES natively, so setting them is
     *  all "targeting a stack" means. */
    void resolveStack(File cwd) {
        def here = cwd?.canonicalFile
        if (!here) return
        def top = captureOutput(['git', '-C', here.absolutePath, 'rev-parse', '--show-toplevel'])
        if (!top) return
        def spec = stackSpec(new File(top))
        if (!spec) return
        if (System.getenv('COMPOSE_FILE')) return     // an explicit environment wins
        childEnv['COMPOSE_PROJECT_NAME'] = spec.project
        childEnv['COMPOSE_FILE'] = spec.compose
        childEnv['COMPOSE_ENV_FILES'] = spec.envFile
        childEnv['ZFIN_STACK_PROJECT'] = spec.project   // what `z status` reports
        childEnv['ZFIN_STACK_DIR'] = spec.dir
        childEnv['ZFIN_STACK_HOST'] = spec.host ?: ''
        if (spec.tag) childEnv['ZFIN_SEED'] = spec.tag
        System.err.println(">> targeting '${spec.project}' (${new File(spec.dir).name})")
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

    /** Where discarded child output goes. ProcessBuilder.Redirect.DISCARD would be the
     *  obvious spelling, but it is Java 9+ and this tooling runs on whatever JVM the HOST
     *  happens to have -- the Java 21 inside the compile image says nothing about it, and
     *  ZFIN's VMs ship Java 8, where DISCARD is a MissingPropertyException on the first
     *  `which`. redirectOutput(File) is Java 7 and does the same thing at the OS level: no
     *  pipe, so nothing to drain and no way for a chatty child to block on a full buffer.
     *  Unix-only, which this tooling already is (docker.sock, bash, /etc paths). */
    private static final File DEV_NULL = new File('/dev/null')

    /** Run with stdout+stderr discarded; return exit code (never dies). */
    int runQuietly(List cmd) {
        newProcess(cmd).redirectOutput(DEV_NULL).redirectError(DEV_NULL).start().waitFor()
    }

    /** Run; return trimmed stdout (stderr discarded). Never dies. */
    String captureOutput(List cmd) {
        def p = newProcess(cmd).redirectError(DEV_NULL).start()
        def out = p.inputStream.text; p.waitFor(); out.trim()
    }

    /** Like captureOutput, but MERGES stderr instead of discarding it.
     *
     *  Needed wherever the interesting output is on stderr -- `docker logs` being the case
     *  that forced this: a container's stderr comes back on the client's stderr, and postgres
     *  logs everything there, so captureOutput() returned an empty string for it. The
     *  clean-shutdown check in `z feature freeze` / `z shared freeze` looked for a marker in
     *  that output and could therefore never pass, failing open in one place and refusing to
     *  archive a perfectly clean database in the other. */
    String captureOutputMerged(List cmd) {
        def p = newProcess(cmd).redirectErrorStream(true).start()
        def out = p.inputStream.text; p.waitFor(); out.trim()
    }

    /** True if a docker image exists locally. */
    boolean imageExists(String ref) { runQuietly(['docker', 'image', 'inspect', ref]) == 0 }

    /** True if an external tool is resolvable on PATH. The optional integrations (tmux, and
     *  mkcert for the review zone) are "use it if it's there, hint if it isn't", so they share
     *  one probe. */
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

    /** Last value of `KEY=` in a dotenv-style file, or '' when the file or the key is absent.
     *  ONE implementation because the obvious inline version is a trap: the natural
     *  `findAll{...}[-1]` throws ArrayIndexOutOfBoundsException on an empty list rather than
     *  returning null, so `?: ''` never gets a chance. That bug shipped in both FeatureList
     *  and FeatureRemove and made `z feature rm` fail outright for any stack whose .env
     *  lacked the key being read (i.e. every feature not made with --existing-branch).
     *  Last-wins matches how the per-feature .env is built: new-feature APPENDS its overrides
     *  to a copy of the base env, so a duplicated key means "the override". */
    String envField(File envFile, String key) {
        if (!envFile?.isFile()) return ''
        def vals = envFile.readLines().findAll { it.startsWith(key + '=') }.collect { it.split('=', 2)[1] }
        vals ? vals[-1] : ''
    }

    /** Step timer for the long-running commands. `mark(label)` closes the step that just ran;
     *  `report()` prints them with a total. Freeze and thaw are multi-minute operations whose
     *  cost is not evenly spread -- knowing whether the time went to the database, the index or
     *  the shutdown is the difference between tuning the right thing and guessing.
     *
     *  A map of closures rather than a class: it is three lines of state, and this file is
     *  loaded through a GroovyClassLoader where an extra top-level class earns its keep. */
    Map stepTimer() {
        def t0 = System.currentTimeMillis()
        def last = [t0]
        def marks = []
        [
            mark  : { String label ->
                def now = System.currentTimeMillis()
                marks << [label: label, secs: (now - last[0]) / 1000.0]
                last[0] = now
            },
            report: { String title ->
                def total = (System.currentTimeMillis() - t0) / 1000.0
                def w = Math.max(14, (marks.collect { (it.label as String).length() } + [0]).max())
                println ""
                println "  $title"
                marks.each { m ->
                    // Share of total, so the dominant step is obvious without doing the sums.
                    def pct = total > 0 ? (m.secs / total * 100.0) : 0
                    println String.format("    %-${w}s %7.1fs  %4.0f%%", m.label, m.secs, pct)
                }
                println String.format("    %-${w}s %7.1fs", 'TOTAL', total)
            }
        ]
    }

    /** Tar a named volume to `out` (gzip). Root + --entrypoint tar so it can read both
     *  postgres-owned and solr-owned contents and bypass the image's own entrypoint.
     *  Lifted from BuildPreloaded so the archive written by `z feature freeze` and the one
     *  written by `build-preloaded` are produced by the same code. */
    /** Is pigz (parallel gzip) present in the tar image? Cached: the probe costs a container
     *  start, and freeze asks once per volume. Null until first asked. */
    private Boolean pigzAvailable = null
    boolean hasPigz() {
        if (pigzAvailable == null)
            pigzAvailable = runQuietly(['docker', 'run', '--rm', '--entrypoint', 'sh',
                                        tarImage(), '-c', 'command -v pigz']) == 0
        pigzAvailable
    }

    void captureVolume(String vol, File out, boolean compress = true) {
        out.parentFile.mkdirs()
        def compressor = compress ? (hasPigz() ? 'pigz' : 'gzip') : null
        info("capturing $vol -> $out${compress ? " ($compressor -1)" : ' (uncompressed)'}")
        // Measured on this hardware, tarring a 5.7G solr volume:
        //     tar cf        25s   5.3G   212 MB/s
        //     gzip -1      111s   3.1G    51 MB/s
        //     gzip -6      198s   2.9G    29 MB/s   <- tar's czf default
        // gzip is single-threaded, so compression -- not disk -- is what made freeze slow.
        // When compressing at all, -1 is the level that makes sense: -6 costs another 87s to
        // save a further 0.2G. pigz (added to the base image) parallelises it across cores;
        // hasPigz() falls back to gzip on an image built before that.
        // pigz is a drop-in parallel gzip and the output is an ordinary gzip stream, so an
        // archive written with it is readable by plain tar/gzip anywhere -- the choice is a
        // speed detail, not a format decision. Falls back when the image predates it.
        def tarArgs = compress ? ['-I', "$compressor -1".toString(), '-cf'] : ['-cf']
        runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
                    '-v', "${vol}:/data:ro",
                    '-v', "${out.parentFile.absolutePath}:/out",
                    tarImage()] + tarArgs + ["/out/${out.name}", '-C', '/data', '.'])
    }

    /** The archive file for a volume in `dir`, whichever form it was written in: uncompressed
     *  `<vn>.tar` (the freeze default) or gzipped `<vn>.tgz` (build-preloaded's snapshots, and
     *  freeze --compress). Returns null when neither exists. */
    File archiveFileFor(File dir, String vn) {
        [new File(dir, "${vn}.tar"), new File(dir, "${vn}.tgz")].find { it.isFile() }
    }

    /** Create `<project>_<vn>` for each name and extract `<srcDir>/<vn>.tgz` into it, in
     *  parallel. Returns one result map per volume ([vn, vol, mb, secs, ok, err]); the caller
     *  reports and decides what a failure means. The counterpart of captureVolume, and the
     *  same code `z feature new` uses to warm an app tier -- so a freeze archive and a
     *  preloaded snapshot restore identically.
     *
     *  Parallel because the cost is container startup plus writing many small files, which
     *  overlaps well (raw gzip is ~1s). Per-index result slots, so no contention; output is
     *  printed by the caller after the join, in order, rather than interleaved. */
    List<Map> restoreVolumes(String project, List<String> vns, File srcDir) {
        // Report each volume AS IT LANDS, not all of them afterwards. These run concurrently and
        // the big one (~16G pg_data) takes minutes, so a silent block followed by four lines
        // looked identical to a hang. Order is completion order, which is the useful order --
        // the small volumes finish first and you can see it is moving.
        // synchronized because several threads print: without it two lines interleave mid-word.
        def lock = new Object()
        def done = 0
        def announce = { Map r ->
            synchronized (lock) {
                done++
                info(String.format("  [%d/%d] %-14s %7.0f MB in %5.1fs%s",
                        done, vns.size(), r.vn, r.mb, r.secs, r.ok ? '' : '   !! FAILED'))
            }
        }
        def results = new Object[vns.size()]
        def threads = []
        def img = tarImage()
        vns.eachWithIndex { vn, idx ->
            threads << Thread.start {
                def vol = "${project}_${vn}"
                def cname = "zfin-restore-${project}-${vn}"   // deterministic: always cleanable
                def tgz = archiveFileFor(srcDir, vn) ?: new File(srcDir, "${vn}.tgz")
                def mb = tgz.isFile() ? tgz.length() / 1048576.0 : 0
                def t0 = System.currentTimeMillis()
                def run2 = { List c ->
                    def pr = new ProcessBuilder(c*.toString()).redirectErrorStream(true).start()
                    def o = pr.inputStream.text          // drain (avoid pipe deadlock) + capture
                    [code: pr.waitFor(), out: o]
                }
                try {
                    run2(['docker', 'rm', '-f', cname])   // clear a stale orphan from an interrupted run
                    def cr = run2(['docker', 'volume', 'create',
                                   '--label', "com.docker.compose.project=$project",
                                   '--label', "com.docker.compose.volume=$vn", vol])
                    // No --rm: a --rm container that never STARTS (interrupt) is left "Created"
                    // and pins the volume; name it and remove it explicitly in finally instead.
                    def ex = run2(['docker', 'run', '--name', cname, '-u', '0', '--entrypoint', 'tar',
                                   '-v', "${vol}:/data",
                                   '-v', "${srcDir.absolutePath}:/in:ro",
                                   // `xf`, not `xzf`: GNU tar sniffs the compression, so one
                                   // path handles both .tar and .tgz archives.
                                   img, 'xf', "/in/${tgz.name}", '-C', '/data'])
                    def ok = cr.code == 0 && ex.code == 0
                    results[idx] = [vn: vn, vol: vol, mb: mb, file: tgz.name,
                                    secs: (System.currentTimeMillis() - t0) / 1000.0,
                                    ok: ok, err: (ok ? '' : (cr.out + ex.out).trim())]
                    announce(results[idx])
                } catch (Throwable t) {
                    results[idx] = [vn: vn, vol: vol, mb: mb, file: tgz.name, secs: 0, ok: false, err: t.toString()]
                    announce(results[idx])
                } finally {
                    run2(['docker', 'rm', '-f', cname])   // always remove (normal + error paths)
                }
            }
        }
        threads*.join()
        results as List
    }

    boolean volumeExists(String vol) { runQuietly(['docker', 'volume', 'inspect', vol]) == 0 }

    /** The feature-stack inventory: ONE derivation, rendered two ways -- as a table by
     *  `z feature ls` and counted by `z review status`. Derived from
     *  ground truth (worktrees on disk + docker) every time rather than maintained
     *  incrementally, so it is self-healing: a stack stopped or removed behind z's back shows
     *  up correctly on the next call instead of going stale.
     *
     *  Keys: slug, project, branch, host, worktree, data (own|shared), state (up|down),
     *  routed (is this host in the review zone), url. */
    List<Map> featureStacks() {
        def wtParent = new File(worktreesDir())
        // A worktree IS a directory with a provisioned docker/.env -- a better test than a
        // name prefix, which could not tell a stack from any other directory someone left here.
        def wts = ((wtParent.listFiles() ?: []) as List)
                .findAll { it.isDirectory() && new File(it, 'docker/.env').isFile() }.sort { it.name }
        // "up" means SERVING, so it is keyed on httpd -- the service that answers the URL in
        // the same row. Keyed on any container with the project label, a `z run claude` sidecar
        // or a stray `z run compile` made a stack read as up while its URL returned 503 from the
        // proxy, which is worse than no column at all: the table and the link disagreed and the
        // table was the confident one.
        def servingProjects = captureOutput(['docker', 'ps', '--filter',
                "label=com.docker.compose.service=${StackConfig.WEB_SERVICE}",
                '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        // Any RUNNING container, httpd or not -- distinguishes a stack that is merely partly up
        // (data tier, or a sidecar) from one that is genuinely stopped.
        def running = captureOutput(['docker', 'ps', '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        // `frozen` is derived, not recorded: a freeze archive exists AND the project has no
        // containers at all. That keeps it self-healing -- thawing recreates containers and
        // the state flips back on its own, with no marker to go stale. A plain `z down` has
        // no containers either, but no archive, so the two stay distinguishable.
        def anyContainers = captureOutput(['docker', 'ps', '-a', '--format', '{{.Label "com.docker.compose.project"}}'])
                .readLines().findAll { it } as Set
        def archiveRoot = new File(archiveDir())
        def zone = reviewZone()
        wts.collect { wt ->
            def envF   = new File(wt, 'docker/.env')
            def proj   = envField(envF, 'COMPOSE_PROJECT_NAME') ?: wt.name.replaceFirst('^wt-', '')
            def host   = envField(envF, 'DOCKER_VIRTUAL_HOST')
            def branch = captureOutput(['git', '-C', wt.absolutePath, 'rev-parse', '--abbrev-ref', 'HEAD']) ?: '?'
            def spec   = stackSpec(wt)
            [ slug    : wt.name.replaceFirst('^wt-', ''),
              project : proj,
              branch  : branch,
              host    : host,
              worktree: wt.name,
              data    : spec?.data ?: 'own',
              state   : servingProjects.contains(proj) ? 'up'
                        : running.contains(proj) ? 'partial'
                        : (!anyContainers.contains(proj) &&
                           new File(new File(archiveRoot, wt.name),
                                    StackConfig.FREEZE_MANIFEST).isFile() ? 'frozen' : 'down'),
              routed  : host ? host.endsWith('.' + zone) : false,
              // Every stack is routed, so its URL is its name.
              url     : host ? "https://$host".toString() : '',
              spec    : spec ]
        }
    }

    /** Which Compose project provides the shared db+solr that `--shared-db` stacks attach to.
     *
     *  Normally `zfin_shared` -- a dedicated stack booted from the preloaded images by
     *  `z shared up`. But that costs ~30G of images plus a ~31G data copy, which does not fit
     *  on every host: a VM with 60G free and a loaded instance ALREADY RUNNING can point this
     *  at that instance instead and spend nothing.
     *
     *  Doing so means feature stacks read and write a REAL instance's database. That is a
     *  bigger claim than the usual shared-data warning, so `z feature new` says it plainly
     *  when the target is not the dedicated stack.
     */
    String sharedProject() { env('ZFIN_SHARED_PROJECT', 'zfin_shared') }

    /** How to install a tool. Names BOTH platforms rather than detecting one: whoever reads
     *  this is often setting up a different machine from the one that printed it. Deliberately
     *  vague about the Linux package manager rather than guessing between apt/dnf/yum. */
    String installHint(String pkg) { "brew install $pkg (macOS) / your package manager, e.g. apt install $pkg (Linux)" }

    /** Does this hostname resolve? Used to check the review zone's wildcard DNS before
     *  provisioning a stack whose only reachable name depends on it. Goes through the OS
     *  resolver, so on macOS it honours /etc/resolver -- which `dig` and `nslookup` do NOT,
     *  and that discrepancy costs an afternoon if you debug it with those. */
    boolean resolves(String host) {
        try { InetAddress.getByName(host); true } catch (ignored) { false }
    }

    /** The feature slug for the worktree the cwd is in, or null.
     *
     *  Lets `z feature freeze|thaw|rm|refresh` be run with no argument from inside a feature
     *  worktree, the way the stack ops (run/up/down/...) already resolve their stack from the
     *  cwd. Typing the ticket while standing in its own directory is the kind of redundancy
     *  that makes a tool feel like paperwork.
     *
     *  Resolved from the worktree's own docker/.env, falling back to the wt- directory-name
     *  convention when that is missing or unreadable -- which is exactly when you are most
     *  likely to be repairing it. Returns null in the MAIN
     *  checkout: that is not a feature, and inferring one there would be a guess. */
    String featureSlugFromCwd(File cwd = new File('.').canonicalFile) {
        def top = captureOutput(['git', '-C', cwd.absolutePath, 'rev-parse', '--show-toplevel'])
        if (!top) return null
        def dir = new File(top).canonicalFile
        if (dir == REPO.canonicalFile) return null          // the checkout itself is not a feature
        def spec = stackSpec(dir)
        if (spec?.project) return spec.project
        // Fallback for a worktree whose .env is missing or unreadable -- which is exactly when
        // you are most likely to be repairing it. A directory under the worktrees dir IS a
        // feature, and its name is the slug.
        dir.parentFile?.canonicalFile == new File(worktreesDir()).canonicalFile ? dir.name : null
    }

    /** Archive the sidecar's session history for a stack, returning the tarball or null when
     *  there is nothing to archive.
     *
     *  Kept in `<archive>/sessions/`, a SIBLING of the per-stack freeze archives rather than
     *  inside one -- `z feature rm` deletes a stack's archive directory, and the session is
     *  precisely the thing you might still want afterwards: what the agent was asked, what it
     *  tried, why the branch looks the way it does. Timestamped rather than overwritten, so
     *  repeated exports build a history instead of replacing one.
     *
     *  Compressed: sessions are megabytes and long-lived, the opposite of the freeze archives
     *  where wall-clock beat size.
     *
     *  Contains no credential. The sidecar's token is a host file mounted at
     *  /run/secrets/claude-token and never enters claude_home -- which is why Option B was
     *  chosen for auth in the first place (review-stacks.md 7). */
    File archiveClaudeSession(String project, String slug) {
        def vol = "${project}_${StackConfig.CLAUDE_VOL}"
        if (!volumeExists(vol)) return null
        def stamp = new Date().format('yyyyMMdd-HHmmss')
        def dir = new File(archiveDir(), 'sessions')
        def out = new File(dir, "${slug}-${stamp}.tgz")
        captureVolume(vol, out, true)
        // ...and the transcripts on their own, beside it. The full volume is mostly things
        // nobody will read: measured on a real archive, plugins/marketplaces was 667 of ~690
        // entries and 6.5M of 10M extracted, against 4 .jsonl files holding the actual
        // conversation. Keeping both means the cheap file is the one you reach for and the
        // volume is still there if a session ever has to be resumed rather than read.
        def tx = new File(dir, "${slug}-${stamp}.transcripts.tgz")
        captureVolumePath(vol, tx, 'projects')
        out
    }

    /** Capture ONE subdirectory of a volume. Same mechanism as captureVolume, narrowed: a
     *  missing path is not an error (a stack whose agent never ran has no projects/), so the
     *  caller gets a file only when there was something to put in it. */
    void captureVolumePath(String vol, File out, String rel) {
        out.parentFile.mkdirs()
        def compressor = hasPigz() ? 'pigz' : 'gzip'
        def code = runCommand(['docker', 'run', '--rm', '-u', '0', '--entrypoint', 'tar',
                    '-v', "${vol}:/data:ro", '-v', "${out.parentFile.absolutePath}:/out",
                    tarImage(), '-I', "$compressor -1".toString(), '-cf',
                    "/out/${out.name}", '-C', '/data', rel], [check: false])
        if (code != 0 || !out.isFile() || out.length() == 0) { out.delete(); return }
        info(String.format("transcripts -> %s (%.1f MB)", out, out.length() / 1048576.0))
    }

        /** The one path a host MUST declare: the parent holding everything this tooling owns.
     *
     *  Deliberately no fallback. An absolute default like /opt/zfin/review is a guess about
     *  someone else's machine that works until it quietly does not -- and when it is wrong the
     *  failure is a directory appearing somewhere unexpected, not an error. Requiring it means
     *  a host states where its dev tree lives, once, in docker/.env.
     *
     *  The recommended layout (reference/dev-tree-layout.md) puts repo, worktrees, review
     *  state, archives, caches and the mounted data directories under this one parent, so a
     *  developer machine has a single thing to back up, relocate or delete. Individual
     *  directories can still be pointed elsewhere for offload -- see the overrides below --
     *  and a symlink works too on Linux (less reliably under Docker Desktop, which resolves
     *  bind sources host-side). */
    String devRoot() {
        def v = env('ZFIN_DEV_ROOT')
        if (!v) die("ZFIN_DEV_ROOT is not set.\n" +
                    "   It is the parent directory holding the repo, worktrees, review state,\n" +
                    "   archives and mounted data. Set it in docker/.env, e.g.\n" +
                    "     ZFIN_DEV_ROOT=${System.getProperty('user.home')}/zfin-dev\n" +
                    "   See reference/dev-tree-layout.md for the recommended structure.")
        v.replaceFirst('^~', System.getProperty('user.home'))
    }

    /** Per-directory overrides, each defaulting to a place under ZFIN_DEV_ROOT. Override one
     *  when it has to live elsewhere -- archives on NFS being the obvious case. */
    String worktreesDir() { env('ZFIN_WORKTREES_DIR', "${devRoot()}/worktrees") }
    /** The two absolute git paths the sidecar must bind, for a worktree OR a plain checkout:
     *  [common, dir]. For a worktree these differ (<repo>/.git and <repo>/.git/worktrees/<slug>);
     *  for a plain checkout both are <repo>/.git, so callers need no special case. */
    List<String> gitDirs(File tree) {
        def one = { String flag ->
            captureOutput(['git', '-C', tree.absolutePath, 'rev-parse',
                           '--path-format=absolute', flag])?.trim()
        }
        [one('--git-common-dir'), one('--git-dir')]
    }

    String reviewDir()    { env('ZFIN_REVIEW_DIR',    "${devRoot()}/review") }
    String archiveDir()   { env('ZFIN_ARCHIVE_DIR',   "${devRoot()}/archive") }

    /** Seeds: reusable captures of a loaded stack, restored into a new stack by
     *  `z feature new --seed`. A SIBLING of the per-stack freeze archives and of sessions/,
     *  never inside one -- `z feature rm` deletes a stack's freeze archive, and a seed (hours
     *  of loading, shared by every stack on the host) must survive that. */
    File seedsDir() { new File(archiveDir(), 'seeds') }
    File seedDir(String tag) { new File(seedsDir(), tag) }

    /** Newest seed tag on this host, or null. Lets `--seed` be optional the way the preloaded
     *  `--tag` was: the common case is "the one I just made". */
    String newestSeed() {
        def dirs = (seedsDir().listFiles() ?: []).findAll { it.isDirectory() && new File(it, StackConfig.SEED_MANIFEST).isFile() }
        dirs ? dirs.sort { it.name }.last().name : null
    }

    /** SHA-256 of a file, streamed. Over the COMPRESSED tarball (~5G), never the expanded
     *  data (~21G), so a whole seed costs seconds rather than minutes. */
    String sha256(File f) {
        def md = java.security.MessageDigest.getInstance('SHA-256')
        f.withInputStream { ins ->
            byte[] buf = new byte[1 << 20]
            int n
            while ((n = ins.read(buf)) > 0) md.update(buf, 0, n)
        }
        md.digest().encodeHex().toString()
    }

    /** Write <dir>/seed.json: what the seed is, what wrote it, and a checksum per tarball.
     *
     *  The checksums exist because a seed has none of an image's guarantees. Docker verifies
     *  a layer digest on every use; a tarball on NFS can be truncated by a full disk and will
     *  restore silently into a half-empty volume. `pg_major` is here for the same reason, from
     *  the other direction: PGDATA written by one postgres major cannot be opened by another,
     *  and catching that at restore beats discovering it when the server refuses to start. */
    /** The platform CONTAINERS run on -- linux/arm64 on a Mac, linux/amd64 on the VMs. Docker's
     *  server, not the host: on a Mac the host is darwin but postgres runs linux/arm64, and it is
     *  the latter that wrote the data directory. */
    String dockerPlatform() {
        captureOutput(['docker', 'version', '--format', '{{.Server.Os}}/{{.Server.Arch}}'])?.trim() ?: ''
    }

    /** Why an archive captured elsewhere must not be restored here, or null when it is fine.
     *  Shared by seeds and freeze archives because the hazard is identical: both carry a
     *  PostgreSQL DATA DIRECTORY, which Postgres does not support moving between platforms. The
     *  danger is that it usually STARTS anyway -- same major version, both little-endian LP64,
     *  so the pg_control checks pass -- and then text index ordering follows whichever glibc
     *  wrote it. That surfaces as wrong query results from a database that looks healthy, not as
     *  an error, which is the worst way for this to fail. Archives written before `platform` was
     *  recorded have none, and are allowed: they predate the check, not the hazard.
     *  @param what  how to name the archive in the message
     *  @param was   the platform recorded in its manifest, or null
     *  @param fix   the one-line remedy for this kind of archive */
    String platformProblem(String what, String was, String fix) {
        if (!was) return null
        def now = dockerPlatform()
        if (!now || was == now) return null
        "$what was captured on ${was}, this host runs ${now}.\n" +
        "   Its pg_data is a PostgreSQL data directory, which is not portable between platforms.\n" +
        "   It would probably start and then return wrong results for text comparisons, because\n" +
        "   index ordering follows the glibc that wrote it. The app tier (www_data,\n" +
        "   catalina_base) and solr_var ARE portable -- the data tier is not.\n" +
        "   ${fix}\n" +
        "   To override anyway:  ZFIN_ALLOW_PLATFORM_MISMATCH=1 <your command>"
    }

    /** True when the caller has explicitly accepted a cross-platform restore. */
    boolean allowPlatformMismatch() { System.getenv('ZFIN_ALLOW_PLATFORM_MISMATCH') as boolean }

    String seedPlatformProblem(String tag) {
        platformProblem("seed '$tag'", readSeedManifest(seedDir(tag))?.platform,
                        "Make a seed on this host instead:  z seed create")
    }

    Map writeSeedManifest(File dir, Map meta) {
        def vols = (meta.volumes ?: []).collect { String vn ->
            def f = archiveFileFor(dir, vn)
            [name: vn, file: f?.name, bytes: f?.length() ?: 0L, sha256: f ? sha256(f) : '']
        }
        def m = [tag: meta.tag, created: new Date().format('yyyy-MM-dd HH:mm:ss'),
                 source_project: meta.source_project, release: meta.release,
                 pg_major: meta.pg_major, platform: dockerPlatform(), volumes: vols]
        new File(dir, StackConfig.SEED_MANIFEST).text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(m))
        m
    }

    /** Read <dir>/seed.json, or null when it is absent or unparseable -- callers decide what a
     *  missing manifest means (ls prints "(no manifest)"; a restore refuses). */
    Map readSeedManifest(File dir) {
        def f = new File(dir, StackConfig.SEED_MANIFEST)
        if (!f.isFile()) return null
        try { return new groovy.json.JsonSlurper().parse(f) as Map } catch (ignored) { return null }
    }

    /** Which of a seed's app-tier volumes are missing or too small to be a deployed app.
     *  Empty list = the seed carries a usable app tier. Null manifest -> [] (say nothing
     *  rather than cry wolf about a seed we cannot read). */
    List<String> thinAppTier(String tag) {
        def m = readSeedManifest(seedDir(tag))
        if (!m) return []
        def byName = [:]
        (m.volumes ?: []).each { v -> byName[v.name] = (v.bytes ?: 0L) as long }
        StackConfig.APP_TIER_VOLUMES.findAll { vn ->
            !byName.containsKey(vn) || byName[vn] < StackConfig.APP_TIER_MIN_BYTES
        }
    }

    /** Stop with a useful message when <slug> is not a feature on this host. Called BEFORE any
     *  command offers a destructive flag: `z feature rm <typo>` used to reach its TTY check and
     *  answer "pass --force to confirm", which invites the destructive flag against a target
     *  that does not exist. Naming the known slugs costs one `ls` and answers the likely typo. */
    void requireFeature(String slug) {
        def known = ((new File(worktreesDir()).listFiles() ?: []) as List)
                .findAll { it.isDirectory() && new File(it, 'docker/.env').isFile() }
                *.name.collect { it.replaceFirst('^wt-', '') }.sort()
        if (slug in known) return
        die("no such feature: ${slug}\n" +
            (known ? "   known: ${known.join(', ')}" : "   none on this host yet -- z feature new <ticket>"))
    }

    String reviewZone() { env('ZFIN_REVIEW_ZONE', StackConfig.REVIEW_ZONE_DEFAULT) }
    String reviewHost(String slug) { "${slug}.${reviewZone()}" }

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
    /** Image used to run tar for volume capture/restore: GNU tar, root-runnable, already local.
     *  Overridable ($ZFIN_TAR_IMAGE) to pin it, or to try one with a faster compressor before
     *  committing the change to the base image. */
    String tarImage() { env('ZFIN_TAR_IMAGE', StackConfig.compileImage(env('ZFIN_RELEASE'))) }

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
        def src = sharedProject()
        StackConfig.DATA_SERVICES.each { svc ->
            def cid = captureOutput(['docker', 'ps', '-q',
                '--filter', "label=com.docker.compose.project=$src",
                '--filter', "label=com.docker.compose.service=$svc"])
            // Loud, on stderr: a missing shared service means this stack's `db`/`solr` will
            // not resolve and the webapp fails at runtime with confusing connection errors.
            // As an info() line in the middle of provisioning output this went unnoticed.
            if (!cid) {
                System.err.println("!! shared $svc is NOT running -- '$net' will have no '$svc' alias, " +
                                   "so this stack cannot reach it. Fix with:  z shared up")
                return
            }
            def nets = captureOutput(['docker', 'inspect', cid, '--format',
                '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}']).split() as List
            if (nets.contains(net)) return
            info("connect shared $svc -> $net (alias $svc)")
            // check:false: a redundant connect (already joined) errors harmlessly.
            runCommand(['docker', 'network', 'connect', '--alias', svc, net, cid], [check: false])
        }
    }
}
