#!/usr/bin/env groovy
// Seed -- capture a loaded stack's volumes as a reusable SEED ARCHIVE, and manage the seeds.
//
//   z seed new|create [--from PROJECT] [--tag TAG] [--no-app] [--caches]
//   z seed ls
//   z seed rm <tag> [--force]
//
//   --from PROJECT   Compose project to capture from (default: $COMPOSE_PROJECT_NAME).
//   --tag TAG        Seed name (default: today, YYYY-MM-DD).
//   --no-app         Skip the deploy-target volumes. They are small (~0.7G) and without them
//                    every feature comes up cold, so they are captured by DEFAULT.
//   --caches         Also capture gradle/maven/npm. Several GB, and regenerable, so OFF by
//                    default -- worth it on a host that makes many stacks.
//
// WHAT A SEED IS. A directory of per-volume tarballs plus a manifest, at
// $ZFIN_ARCHIVE_DIR/seeds/<tag>/. `z feature new --seed <tag>` restores it into a new stack.
// It is the same artifact shape `z feature freeze` writes, produced by the same
// ZfinUtil.captureVolume and consumed by the same ZfinUtil.restoreVolumes -- one capture and
// one restore implementation for the whole tool.
//
// A SIBLING of the per-stack freeze archives, never inside one: `z feature rm` deletes a
// stack's freeze archive, and a seed must survive that.
//
// WHY NOT PRELOADED IMAGES. Baking the same tarballs into
// zfin-db-preloaded:<tag> cost three extra full copies of the data -- into the build context,
// into a layer, out again on export -- for an image whose layers cannot be shared anyway,
// because postgres declares VOLUME and Docker therefore COPIES the baked content into every
// stack's volume. Measured on cell 2026-09-18: restoring ~21.3G from tarballs takes 79.9s
// against ~170s for Docker to seed the same data from images, because tar streams where the
// daemon copies per file. Seeds are also files, so they can live on NFS; an image store
// cannot (overlayfs needs a local upperdir). See workbench/seed-archives-vs-preloaded-images.md.
//
// The data is REAL ZFIN DATA. Seeds stay on storage you control. There is deliberately no
// upload path, for the same reason the preloaded images were local-only.
class Seed {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die

        def sub = args ? args[0] : ''
        def rest = args.drop(1)
        switch (sub) {
            // `new` and `create` both, because `z feature new` reads as the verb for this and
            // guessing which noun takes which verb is not a thing anyone should have to do.
            case 'create': case 'new': create(rest, zfinUtil); break
            case 'ls': case 'list': list(rest, zfinUtil); break
            case 'rm': case 'remove': remove(rest, zfinUtil); break
            default: die("z seed: unknown '${sub}' (new|create|ls|rm). See z seed --help.", 2)
        }
    }

    // ---- z seed ls ---------------------------------------------------------------------
    private void list(List args, ZfinUtil zfinUtil) {
        def dir = zfinUtil.seedsDir()
        if (!dir.isDirectory()) { zfinUtil.info("no seeds yet ($dir)"); return }
        def seeds = (dir.listFiles() ?: []).findAll { it.isDirectory() }.sort { it.name }
        if (!seeds) { zfinUtil.info("no seeds yet ($dir)"); return }
        println String.format("%-16s %10s  %-19s %s", 'TAG', 'SIZE', 'CREATED', 'VOLUMES')
        seeds.each { d ->
            def m = zfinUtil.readSeedManifest(d)
            def bytes = (d.listFiles() ?: []).findAll { it.isFile() }.sum { it.length() } ?: 0L
            def vols = m?.volumes?.collect { it.name }?.join(' ') ?: '(no manifest)'
            println String.format("%-16s %9.1fG  %-19s %s", d.name, bytes / 1073741824.0,
                    m?.created ?: '?', vols)
        }
        println "\nin $dir -- use one with:  z feature new <ticket> --seed <tag>"
    }

    // ---- z seed rm ---------------------------------------------------------------------
    private void remove(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def force = args.contains('--force') || args.contains('-f')
        def tag = args.find { !it.startsWith('-') }
        if (!tag) die("usage: z seed rm <tag> [--force]", 2)
        def d = zfinUtil.seedDir(tag)
        if (!d.isDirectory()) die("no seed '$tag' at $d")
        def bytes = (d.listFiles() ?: []).findAll { it.isFile() }.sum { it.length() } ?: 0L
        // A seed is the ONLY copy of a load that took hours. Confirm unless told not to, and
        // refuse without a TTY rather than guessing -- same stance as `z feature rm`.
        if (!force) {
            def con = System.console()
            if (!con) die("refusing to delete seed '$tag' without a TTY. Pass --force if you mean it.")
            def a = con.readLine(String.format("delete seed '%s' (%.1fG) from %s? [y/N]: ", tag, bytes / 1073741824.0, d))
            if (a?.trim()?.toLowerCase() != 'y') { info("kept"); return }
        }
        if (!d.deleteDir()) die("could not delete $d")
        info(String.format("deleted seed '%s' (%.1fG)", tag, bytes / 1073741824.0))
    }

    // ---- z seed create -----------------------------------------------------------------
    private void create(List args, ZfinUtil zfinUtil) {
        def die = zfinUtil.&die; def info = zfinUtil.&info; def runCommand = zfinUtil.&runCommand
        def runQuietly = zfinUtil.&runQuietly
        def captureOutput = zfinUtil.&captureOutput

        // The stack `z` resolved from the working directory wins. It has to be read from
        // childEnv rather than env(): env() treats THIS checkout's docker/.env as authoritative,
        // and that file names the base stack -- so standing in a feature worktree would still
        // capture zfin_org, which is exactly the trap this default exists to avoid. Falls back
        // to the ambient environment, then to the base stack when the cwd owns no stack at all.
        def project = zfinUtil.childEnv['COMPOSE_PROJECT_NAME'] ?:
                      zfinUtil.env('COMPOSE_PROJECT_NAME', StackConfig.BASE_PROJECT)
        def tag = java.time.LocalDate.now().toString()   // YYYY-MM-DD
        def app = true       // small, and a cold stack is a worse default than a bigger seed
        def caches = false   // several GB and regenerable

        for (int i = 0; i < args.size(); i++) {
            switch (args[i]) {
                case '--from': case '--project': project = args[++i]; break
                case '--tag': tag = args[++i]; break
                case '--app': app = true; break
                case '--no-app': app = false; break
                case '--caches': caches = true; break
                case '--no-caches': caches = false; break
                default: die("z seed create: unknown arg '${args[i]}'", 2)
            }
        }

        def release = zfinUtil.env('ZFIN_RELEASE')
        if (!release) die("ZFIN_RELEASE must be set (from docker/.env or the environment)")

        // Stock db image: the pg engine matching this data, used for the WAL-trim throwaway
        // postgres (which MUST be the db image). The tar capture below uses zfinUtil.tarImage()
        // (the compile image) -- the same source the restore uses. Both are local (no pull).
        def stockDb = "ghcr.io/zfin/zfin-db:$release"

        def pgVol = "${project}_pg_data"
        def solrVol = "${project}_solr_var"
        def out = zfinUtil.seedDir(tag)

        info("seed create: from=$project release=$release tag=$tag -> $out")
        if (out.isDirectory() && (out.listFiles() ?: []).any { it.isFile() })
            die("seed '$tag' already exists at $out\n" +
                "   Pick another --tag, or remove it:  z seed rm $tag")

        // Both named volumes must exist, or the capture would silently produce empties.
        [pgVol, solrVol].each { v ->
            if (runQuietly(['docker', 'volume', 'inspect', v]) != 0)
                die("volume '$v' not found -- is project '$project' loaded? (try --from)")
        }

        def appVols = StackConfig.APP_VOLS
        def cacheVols = StackConfig.CACHE_VOLS
        def present = { List vns -> vns.findAll { runQuietly(['docker', 'volume', 'inspect', "${project}_${it}"]) == 0 } }
        def appPresent = app ? present(appVols) : []
        def cachesPresent = caches ? present(cacheVols) : []
        if (app && appPresent.size() < appVols.size())
            info("note: --app skipping absent volumes: ${(appVols - appPresent).join(', ')}")

        // Timings, reported at the end like freeze and thaw. Capture dominates, but the WAL
        // trim is a minute of throwaway-postgres that is invisible without this, and the split
        // is what tells you whether a slow run was disk or postgres.
        def timer = zfinUtil.stepTimer()
        def volSecs = [:]

        // Failure-recovery state shared with the shutdown hook (registered below): the trim
        // container to force-remove, and a flag that a clean run has finished.
        def state = [completed: false, trim: null]

        // Trim the captured snapshot before tarring it.
        //   ALWAYS: shed recycled WAL. In a frozen snapshot the pg_wal segments are
        //     pre-allocated for reuse -- near-zero real data, yet they cost their full ~16MB
        //     each on disk (hundreds of segments = several GB). Collapsing them is a safe,
        //     unconditional win: the copy is started fresh in every feature, so there's
        //     nothing to recover. A plain CHECKPOINT won't shrink them (segment-retention
        //     decays only ~2%/checkpoint), so we pg_resetwal.
        // Mechanism: briefly run a throwaway postgres on the volume so the clean docker-stop
        // afterward gives pg_resetwal its required clean-shutdown precondition. Runs while the
        // real db is stopped (single writer on the volume). The throwaway start skips initdb
        // (data dir is non-empty).
        def trimSnapshot = {
            def img = stockDb
            def name = "seed-trim-${ProcessHandle.current().pid()}"
            info("[trim] throwaway postgres on $pgVol (WAL reset)")
            runCommand(['docker', 'run', '-d', '--name', name,
                        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                        '-v', "${pgVol}:/var/lib/postgresql", img])
            state.trim = name    // track so the shutdown hook can force-remove it if we die below

            def ready = false
            for (int i = 0; i < 60 && !ready; i++) {
                if (runQuietly(StackConfig.dbHealthCheck(name)) == 0) ready = true else sleep(1000)
            }
            if (!ready) {
                runCommand(['docker', 'logs', '--tail', '30', name], [check: false])
                runQuietly(['docker', 'rm', '-f', name]); state.trim = null
                die("[trim] postgres not ready")
            }

            // -t 60: give postgres time for a clean fast-shutdown (the default 10s grace risks a
            // SIGKILL -> unclean state, which pg_resetwal -f would then paper over).
            runCommand(['docker', 'stop', '-t', '60', name])
            runQuietly(['docker', 'rm', name]); state.trim = null
            info("[trim] pg_resetwal to shed recycled WAL segments (safe after the clean shutdown above)")
            runCommand(['docker', 'run', '--rm', '-u', 'postgres', '-v', "${pgVol}:/var/lib/postgresql",
                        '--entrypoint', 'bash', img, '-c', 'pg_resetwal -f "$PGDATA"'])
        }

        // Quiesce db/solr so the tarred on-disk state is consistent (not mid-write).
        // Stop ONLY the services that are actually running, remember their container IDs,
        // and restart exactly those after capture -- a down stack stays down, and the trim
        // step always gets a stopped db regardless of starting state. Detected via compose
        // labels so no compose file / -f flags are needed.
        def runningContainer = { String svc ->
            captureOutput(['docker', 'ps', '-q',
                           '--filter', "label=com.docker.compose.project=$project",
                           '--filter', "label=com.docker.compose.service=$svc"])
        }
        def stopped = [:]   // service -> container id we stopped

        // Restart-on-failure net. From the first stop until the restart loop below, db/solr are
        // down; any die (trim, pg_resetwal, a capture() failing on low disk mid-tar) would leave
        // the dev's stack down -- and die() calls System.exit, which skips try/finally. So a JVM
        // shutdown hook (registered BEFORE we stop anything) restarts exactly what we stopped and
        // force-removes a leaked trim container, unless state.completed says we finished cleanly.
        Runtime.runtime.addShutdownHook(new Thread({
            if (state.completed) return
            if (state.trim) runQuietly(['docker', 'rm', '-f', state.trim])
            stopped.each { svc, cid ->
                System.err.println("!! [cleanup] restarting $svc after an incomplete seed")
                runQuietly(['docker', 'start', cid])
            }
        } as Runnable))

        StackConfig.DATA_SERVICES.each { svc ->
            def cid = runningContainer(svc)
            if (cid) {
                info("stopping $svc ($cid) for a consistent capture")
                runCommand(['docker', 'stop', cid])
                stopped[svc] = cid
            }
        }

        timer.mark('stop data tier')
        trimSnapshot()   // sheds recycled WAL from the frozen snapshot
        timer.mark('trim WAL')

        // Read the postgres major straight out of the trimmed data, so the manifest can refuse a
        // restore into an engine that cannot open it. This is the 2026-09-18 near-miss made
        // impossible: PGDATA written by one release, baked onto a server from another.
        def pgMajor = captureOutput(['docker', 'run', '--rm', '-u', '0', '-v', "${pgVol}:/d:ro",
                                     '--entrypoint', 'sh', zfinUtil.tarImage(),
                                     '-c', 'find /d -name PG_VERSION -exec cat {} + | head -1'])?.trim()
        if (!pgMajor) info("note: could not read PG_VERSION -- the manifest will not record a major version")

        out.mkdirs()
        // The SAME capture `z feature freeze` uses, so a seed and a freeze archive are the same
        // artifact. Names match StackConfig's volume names, because restoreVolumes reads
        // <dir>/<vn>.tgz into <project>_<vn> -- that symmetry is what makes a seed restorable
        // with no translation layer.
        def vns = StackConfig.DATA_VOLS + appPresent + cachesPresent
        vns.each { vn ->
            def t = System.currentTimeMillis()
            zfinUtil.captureVolume("${project}_${vn}", new File(out, "${vn}.tgz"))
            volSecs[vn] = (System.currentTimeMillis() - t) / 1000.0
        }
        timer.mark('capture volumes')

        // Restart exactly what we stopped (leave an already-down stack down), then mark the run
        // complete so the shutdown hook stands down -- the stack is back up from here.
        stopped.each { svc, cid ->
            info("restarting $svc")
            runCommand(['docker', 'start', cid])
        }
        state.completed = true
        timer.mark('restart source')

        def manifest = zfinUtil.writeSeedManifest(out, [
                tag: tag, source_project: project, release: release, pg_major: pgMajor ?: '',
                volumes: vns])
        timer.mark('checksum + manifest')
        def total = (out.listFiles() ?: []).findAll { it.isFile() }.sum { it.length() } ?: 0L
        info(String.format("seed '%s' written: %.1fG across %d volume(s) -> %s", tag, total / 1073741824.0, vns.size(), out))
        info("use it with:  z feature new <ticket> --seed $tag")

        // A seed whose APP TIER is empty makes stacks that cannot serve until they are built,
        // and the failure points nowhere near the cause: httpd includes
        // $TARGETROOT/server_apps/apache/inc-redirect (out of www_data) at startup, so an empty
        // www_data kills it with "Syntax error on line 52 ... Could not open configuration
        // file". Observed capturing from zfin_shared, which holds data and no deployed app.
        // Only www_data and catalina_base are checked: keystore and tls_certs are legitimately
        // a few KB, so a size floor would cry wolf on every healthy seed.
        if (app) {
            def thin = StackConfig.APP_TIER_VOLUMES.findAll { vn ->
                def f = zfinUtil.archiveFileFor(out, vn)
                !f || f.length() < StackConfig.APP_TIER_MIN_BYTES
            }
            if (thin) {
                System.err.println("!! this seed has no usable app tier (${thin.join(', ')} empty or absent).")
                System.err.println("   Stacks made from it come up with db+solr only: httpd cannot start until")
                System.err.println("   \$TARGETROOT is populated, and fails with an Apache config error that does")
                System.err.println("   not mention the cause. They need the full first build:")
                System.err.println("     ./z run -c \"ant do && gradle make && ant deploy-catalina-base && ant deploy-no-tests-no-restart\"")
                System.err.println("   To avoid that, capture from a stack that has been DEPLOYED -- an instance, or")
                System.err.println("   a feature stack you have built -- rather than from a data-only project.")
            }
        }

        timer.report("seed '$tag' timing")
        if (volSecs) {
            println "    per volume:"
            volSecs.sort { -it.value }.each { vn, vs ->
                def f = zfinUtil.archiveFileFor(out, vn)
                def mb = (f?.length() ?: 0L) / 1048576.0
                println String.format("      %-16s %7.1fs  %8.0f MB  %6.0f MB/s", vn, vs, mb, vs > 0 ? mb / vs : 0)
            }
        }
    }
}
