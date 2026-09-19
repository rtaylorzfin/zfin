// ReviewStack -- manage the shared review proxy (Compose project `zfin_review`): ONE
// nginx-proxy per host that routes https://<slug>.<zone> to the right feature stack's httpd.
// Every `z feature new` stack is routed: its httpd joins zfin_review_net and publishes no
// :443 of its own. (Routing was once opt-in via --review; it is now the only mode.)
//
//   z review up      [--bind ADDR] boot the proxy (regenerates the allowlist; makes a cert)
//   z review up      --upstream    ...behind an existing nginx-proxy that owns :80/:443
//                                  (ZFIN's `ngproxy` on the shared VMs). Publishes no host
//                                  port and claims *.<zone> as a vhost on the upstream, so
//                                  the instance it already fronts is untouched. Needs
//                                  ZFIN_REVIEW_UPSTREAM_NET, and ZFIN_REVIEW_TRUSTED_PROXY
//                                  or the allowlist sees the upstream as every client.
//   z review down                  stop it (feature stacks stay up, just unreachable by name)
//   z review status                proxy state + every vhost currently routed
//   z review cert    [--force]     (re)issue the wildcard cert for the zone
//
// Everything host-specific is env, so the same command works on a laptop and a review VM:
//   ZFIN_REVIEW_ZONE   the DNS zone            (default review.zfin.test)
//   ZFIN_REVIEW_HTTP_BIND / _HTTPS_BIND   addr:port to publish on (default 0.0.0.0:80 and
//                      0.0.0.0:443; use 127.0.0.1:... on a laptop). --bind rewrites the
//                      ADDRESS of both, keeping their ports.
//   ZFIN_REVIEW_ALLOW  space/comma CIDR allow list for EVERY vhost (default loopback+RFC1918)
//   ZFIN_REVIEW_TRUSTED_PROXY  CIDR of an upstream proxy, if one fronts us (see below)
//   ZFIN_REVIEW_DIR    state dir           (default $ZFIN_DEV_ROOT/review; shared, not $HOME)
//
// Each is read from docker/.env FIRST (per host, git-ignored -- the right home for "how is
// THIS box set up"), then the ambient environment, then the StackConfig default. So a review
// VM configures itself by adding ZFIN_REVIEW_* lines to its .env, once.
//
// SECURITY: these stacks serve a REAL loaded ZFIN database (reference/dev-stacks.md
// keeps the preloaded images local-only for exactly that reason). The allowlist written into
// vhost.d/default is what replaces "only reachable at 127.0.0.X on one laptop", so it is
// regenerated on every `up` rather than being set up once and trusted to still be there.
// See workbench/review-stacks.md 3.5.
class ReviewStack {
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return
        def die = zfinUtil.&die; def info = zfinUtil.&info
        def runCommand = zfinUtil.&runCommand; def captureOutput = zfinUtil.&captureOutput
        def runQuietly = zfinUtil.&runQuietly
        def DOCKER = zfinUtil.DOCKER

        def sub  = args ? args[0] : 'status'
        def rest = args.drop(1)
        def bindArg = null; def force = false; def upstream = false
        for (int i = 0; i < rest.size(); i++) {
            switch (rest[i]) {
                case '--bind':  bindArg = rest[++i]; break
                case '--upstream': upstream = true; break
                case '--force': force = true; break
                default: die("z review: unknown arg '${rest[i]}'", 2)
            }
        }

        // All review config resolves docker/.env -> environment -> default, so a host is
        // configured the same way everything else in this project is: by its own .env.
        def zone     = zfinUtil.reviewZone()
        def reviewDir = new File(zfinUtil.reviewDir())
        def certsDir = new File(reviewDir, 'certs')
        def vhostDir = new File(reviewDir, 'vhost.d')
        // One value per protocol, each a complete addr:port -- a single shared variable cannot
        // serve both lines without publishing the same host port twice. --bind is a convenience
        // that swaps only the address, since that is the part anyone actually varies.
        def httpBind  = zfinUtil.env('ZFIN_REVIEW_HTTP_BIND',  '0.0.0.0:80')
        def httpsBind = zfinUtil.env('ZFIN_REVIEW_HTTPS_BIND', '0.0.0.0:443')
        if (bindArg) {
            httpBind  = bindArg + ':' + httpBind.substring(httpBind.lastIndexOf(':') + 1)
            httpsBind = bindArg + ':' + httpsBind.substring(httpsBind.lastIndexOf(':') + 1)
        }
        def bind      = httpsBind.substring(0, httpsBind.lastIndexOf(':'))
        def httpsPort = httpsBind.substring(httpsBind.lastIndexOf(':') + 1)

        // nginx-proxy resolves a vhost's cert by name, falling back to the wildcard formed by
        // dropping the leftmost label: zfin-1234.review.zfin.test -> review.zfin.test.crt.
        // So ONE file pair covers every stack that will ever exist in the zone.
        def crt = new File(certsDir, "${zone}.crt")
        def key = new File(certsDir, "${zone}.key")

        def compose = ['docker', 'compose', '-p', StackConfig.REVIEW_PROJECT,
                       '--env-file', new File(DOCKER, '.env').absolutePath,
                       '-f', new File(DOCKER, 'docker-compose.review.yml').absolutePath] +
                      (upstream ? ['-f', new File(DOCKER, 'docker-compose.overlay-review-upstream.yml').absolutePath] : [])
        zfinUtil.childEnv['ZFIN_REVIEW_DIR']  = reviewDir.absolutePath
        zfinUtil.childEnv['ZFIN_REVIEW_ZONE'] = zone
        zfinUtil.childEnv['ZFIN_REVIEW_HTTP_BIND']  = httpBind
        zfinUtil.childEnv['ZFIN_REVIEW_HTTPS_BIND'] = httpsBind
        // `up --build` runs every time so the image cannot drift from the Dockerfile. buildx
        // stamps a fresh attestation manifest on each build though, so a fully CACHED rebuild
        // still yields a new image id -- and compose then recreates the proxy, dropping every
        // routed stack's connections for a couple of seconds on a command that changed nothing.
        // Without the default attestations a cached build reproduces the same id, so compose
        // leaves the container alone.
        zfinUtil.childEnv['BUILDX_NO_DEFAULT_ATTESTATIONS'] = '1'

        // ---- the allowlist -------------------------------------------------------------
        // nginx-proxy includes /etc/nginx/vhost.d/default into EVERY generated server block,
        // which is what lets one file protect every stack at once with no per-stack config.
        // The state dir is shared host infrastructure (ZfinUtil.reviewDir): create it if we
        // can, but never silently relocate -- a per-user fallback is the bug this default
        // exists to prevent, so failing with the fix is better than succeeding in $HOME.
        def ensureDir = { File d ->
            if (d.isDirectory()) return
            if (!d.mkdirs()) die("cannot create ${d}\n" +
                "   On a shared host, create it once with group ownership so every developer\n" +
                "   (and the proxy) sees the SAME certs and allowlist:\n" +
                "     sudo install -d -g fishadmin -m 2775 ${reviewDir.absolutePath}\n" +
                "   Or point somewhere writable -- ZFIN_REVIEW_DIR in docker/.env, or:\n" +
                "     ZFIN_REVIEW_DIR=<dir> z review ...")
        }

        def writeAllowlist = {
            ensureDir(vhostDir)
            def raw = zfinUtil.env('ZFIN_REVIEW_ALLOW', StackConfig.REVIEW_ALLOW_DEFAULT)
            def cidrs = raw.split(/[,\s]+/).findAll { it }
            def trusted = zfinUtil.env('ZFIN_REVIEW_TRUSTED_PROXY', '').split(/[,\s]+/).findAll { it }
            def out = new StringBuilder()
            out << "# GENERATED by `z review up` -- edits are overwritten.\n"
            out << "# Source: \$ZFIN_REVIEW_ALLOW (default StackConfig.REVIEW_ALLOW_DEFAULT).\n"
            out << "# Included by nginx-proxy into every server block; see workbench/review-stacks.md 3.5.\n"
            if (trusted) {
                // Behind an upstream proxy the peer address is THAT proxy, so an allowlist on
                // the raw peer would allow everyone or no one. Trust its X-Forwarded-For only
                // for the hops we name here -- never a blanket `real_ip_header` with no
                // set_real_ip_from, which would let a client forge its own source address.
                out << "\n# upstream proxy (ZFIN_REVIEW_TRUSTED_PROXY): take the client IP from XFF\n"
                trusted.each { out << "set_real_ip_from ${it};\n" }
                out << "real_ip_header X-Forwarded-For;\nreal_ip_recursive on;\n"
            }
            out << "\n"
            cidrs.each { out << "allow ${it};\n" }
            out << "deny all;\n"
            new File(vhostDir, 'default').text = out.toString()
            [cidrs: cidrs, trusted: trusted]
        }

        // ---- the wildcard cert ---------------------------------------------------------
        // Keep default.crt/key a copy of the zone cert. SEPARATE from generating it, because
        // makeCert returns early when a zone cert is already present: fold the two together and
        // a host that has one but no default.crt never gets one, which presents as a 502 from
        // the upstream (see below) rather than as a missing file.
        def syncDefaultCert = {
            if (!crt.isFile() || !key.isFile()) return
            def dc = new File(certsDir, 'default.crt'); def dk = new File(certsDir, 'default.key')
            if (dc.isFile() && dk.isFile() && dc.bytes == crt.bytes) return
            // An upstream nginx-proxy reaches us over TLS without SNI -- nginx's
            // proxy_ssl_server_name defaults to off and nginx-proxy sets no proxy_ssl_*
            // directives -- so our FALLBACK server, not the vhost, supplies the certificate for
            // that connection. With no default.crt the fallback is `ssl_reject_handshake on`,
            // which the upstream reports as 502. The name is irrelevant (it does not verify)
            // and the request still reaches the right server block: SNI picks the certificate,
            // the Host header picks the vhost.
            dc.bytes = crt.bytes; dk.bytes = key.bytes
            info("default cert synced from ${zone} (the upstream connects without SNI)")
        }

        def makeCert = { boolean replace ->
            ensureDir(certsDir)
            if (crt.isFile() && !replace) { syncDefaultCert(); return false }
            if (zfinUtil.onPath('mkcert')) {
                info("issuing *.${zone} with mkcert -> ${certsDir}")
                runCommand(['mkcert', '-cert-file', crt.absolutePath, '-key-file', key.absolutePath,
                            "*.${zone}".toString(), zone])
                info("mkcert: if browsers still warn, run `mkcert -install` once on this machine")
            } else {
                // Fallback so routing is testable on a box without mkcert. It works, but the
                // browser will warn: nothing trusts this CA. Say so rather than let someone
                // conclude the cert plumbing is broken.
                info("mkcert not found -- falling back to a self-signed *.${zone} (browsers WILL warn)")
                runCommand(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-sha256',
                            '-days', '825', '-nodes',
                            '-keyout', key.absolutePath, '-out', crt.absolutePath,
                            '-subj', "/CN=*.${zone}".toString(),
                            '-addext', "subjectAltName=DNS:*.${zone},DNS:${zone}".toString()])
                info("for a cert browsers trust:  ${zfinUtil.installHint('mkcert')}, then " +
                     "mkcert -install && z review cert --force")
            }
            syncDefaultCert()
            true
        }

        // ---- preflight: who else owns :443 ---------------------------------------------
        // Anything else already publishing :443 -- a pre-routing feature stack, an instance,
        // another proxy -- blocks a 0.0.0.0 bind here. Binding the
        // proxy on 0.0.0.0:443 then fails with an EADDRINUSE from deep inside compose. Name
        // the actual containers instead, since the fix is per-stack.
        def portConflicts = { String p ->
            captureOutput(['docker', 'ps', '--format', '{{.Names}}\t{{.Ports}}'])
                .readLines().findAll { it.contains(":${p}->") }
                .findAll { !it.startsWith('zfin-review-proxy\t') }
                .collect { it.split('\t')[0] }
        }

        switch (sub) {
            case 'up':
                def clash = upstream ? [] : portConflicts(httpsPort)
                if (clash && bind == '0.0.0.0') {
                    die("port ${httpsPort} is already published by: ${clash.join(', ')}\n" +
                        "   A 0.0.0.0 bind cannot coexist with a publisher of the same port, whether\n" +
                        "   that is an older unrouted stack, an instance, or another proxy. Either:\n" +
                        "     z stop httpd        (in each of those stacks), or\n" +
                        "     z review up --bind 127.0.0.1   (laptop: coexist on a different address)")
                }

                def al = writeAllowlist()
                makeCert(false)
                info("review proxy: zone=${zone} bind=${bind} state=${reviewDir}")
                info("access: allow ${al.cidrs.join(', ')}${al.trusted ? "  (client IP via XFF from ${al.trusted.join(', ')})" : ''}")

                // --remove-orphans: compose identifies containers by SERVICE LABEL, so a service
                // that is renamed or dropped leaves its container behind, still holding the
                // fixed container_name the new one wants -- and `down` cannot clean it up
                // either, because down reads the same current compose file and no longer sees
                // it. The result is a project wedged until someone runs `docker rm -f` by
                // hand. We own every container in this project, so sweeping orphans here is
                // simply making the project match its compose files.
                // --remove-orphans is not enough on its own: compose CREATES before it sweeps,
                // so a container still holding one of our fixed container_names under an older
                // service label fails the run with "Conflict. The container name ... is already
                // in use" before the sweep happens. Renaming the service from `ngproxy` to
                // `proxy` produced exactly that, twice. Clear only containers whose service
                // label disagrees with the compose file -- never a correctly-labelled one.
                [(StackConfig.REVIEW_PROXY_NAME): 'proxy'].each { cname, svc ->
                    def lbl = captureOutput(['docker', 'inspect', cname, '--format',
                            '{{index .Config.Labels "com.docker.compose.service"}}'])?.trim()
                    if (lbl && lbl != svc) {
                        info("removing stale '$cname' (service '$lbl', now '$svc')")
                        runQuietly(['docker', 'rm', '-f', cname])
                    }
                }
                runCommand(compose + ['up', '-d', '--build', '--remove-orphans'])

                // `up -d` leaves an ALREADY-RUNNING proxy untouched, and nothing else will make
                // it notice the allowlist we just rewrote: nginx `include`s vhost.d/default when
                // it loads its config, and docker-gen re-renders on DOCKER EVENTS -- editing a
                // bind-mounted file is not one. Without this signal, "regenerated on every run"
                // is a guarantee about a file nobody re-read, and a widened or tightened
                // $ZFIN_REVIEW_ALLOW appears to apply while the old rules are still live.
                // Best-effort: on a first `up` the container may not be accepting exec yet, and
                // it is about to read the file on startup anyway.
                runQuietly(['docker', 'exec', StackConfig.REVIEW_PROXY_NAME, 'nginx', '-s', 'reload'])
                info("unclaimed names in the zone fall through to the proxy's fallback page")

                // DISABLE_OIDC is a per-host choice in the git-ignored docker/.env; the shipped
                // fallback (compose ${DISABLE_OIDC:-false}) is the SAFE one. If this host has
                // opted out, say so -- on a routed stack that means /jobs, /solr, /logs and
                // /mailpit are reachable by anyone inside the allowlist.
                def baseEnv = new File(DOCKER, '.env')
                if (baseEnv.isFile() && baseEnv.readLines().any { it =~ /^\s*DISABLE_OIDC\s*=\s*true\b/ }) {
                    System.err.println("!! note: docker/.env sets DISABLE_OIDC=true, so /jobs (Jenkins), /solr,")
                    System.err.println("   /logs and /mailpit are UNAUTHENTICATED on every stack this proxy routes.")
                    System.err.println("   The allowlist above is then the only control. Prefer DISABLE_OIDC=false")
                    System.err.println("   on a review host -- see workbench/review-stacks.md 3.5.")
                }
                info("every `z feature new` stack is routed through this proxy")
                break

            case 'down':
                runCommand(compose + ['down', '--remove-orphans'], [check: false])
                info("review proxy down. Feature stacks keep running; they are just no longer reachable by name.")
                break

            case 'cert':
                if (crt.isFile() && !force) {
                    info("cert already present: $crt  (z review cert --force to replace)")
                    // Still sync the default: a host whose zone cert predates default.crt has
                    // the zone cert and nothing else, and that is exactly the state that makes
                    // an upstream's SNI-less connection fail. Reaching `break` here would leave
                    // it broken with no way short of --force to repair it.
                    syncDefaultCert()
                } else {
                    makeCert(true)
                }
                // The proxy reads certs from disk at (re)start, so an in-place replacement
                // needs a reload to take effect.
                if (runQuietly(['docker', 'inspect', 'zfin-review-proxy']) == 0) {
                    info("reloading nginx to pick up the new cert")
                    runCommand(['docker', 'exec', 'zfin-review-proxy', 'nginx', '-s', 'reload'], [check: false])
                }
                break

            case 'status':
                if (runQuietly(['docker', 'network', 'inspect', StackConfig.REVIEW_NET]) != 0) {
                    info("review proxy not running (z review up)")
                    info("  zone: ${zone}   state: ${reviewDir}")
                    break
                }
                runCommand(compose + ['ps'], [check: false])
                info("zone      : ${zone}")
                info("state dir : ${reviewDir}  (cert: ${crt.isFile() ? crt.name : 'MISSING -- z review cert'})")
                def allow = new File(vhostDir, 'default')
                info("allow     : " + (allow.isFile()
                        ? allow.readLines().findAll { it.startsWith('allow ') }.collect { it - 'allow ' - ';' }.join(', ')
                        : 'NO vhost.d/default -- every vhost is OPEN (z review up to regenerate)'))

                // A routed feature is exactly a container on the review network carrying a
                // ZFIN_VIRTUAL_HOST -- read it back rather than trusting any local bookkeeping.
                info("stacks     : ${zfinUtil.featureStacks().size()} (z feature ls for detail)")

                def names = captureOutput(['docker', 'network', 'inspect', StackConfig.REVIEW_NET,
                        '--format', '{{range .Containers}}{{.Name}} {{end}}']).split().findAll { it }
                def routed = names.findAll { it != 'zfin-review-proxy' }.collect { n ->
                    def env = captureOutput(['docker', 'inspect', n, '--format',
                            '{{range .Config.Env}}{{println .}}{{end}}']).readLines()
                    def vhKey = StackConfig.REVIEW_VHOST_ENV + '='   // not `key` -- that is the cert file above
                    def vh = env.find { it.startsWith(vhKey) }?.substring(vhKey.length())
                    vh ? "  https://${vh}   (${n})" : "  (no ${StackConfig.REVIEW_VHOST_ENV})   ${n}"
                }
                println "\nrouted vhosts:"
                println(routed ? routed.join('\n') : "  (none -- z feature new <ticket>)")
                break

            default: die("z review: unknown subcommand '$sub' (up|down|status|cert)", 2)
        }
    }
}
