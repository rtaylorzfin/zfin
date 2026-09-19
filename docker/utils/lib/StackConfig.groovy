// StackConfig -- the ONE home for ZFIN-specific policy: image names, host/naming patterns,
// which services play which role, and the warm-volume classes. Everything else in
// docker/utils/lib/ is generic mechanism (ZfinUtil + the command classes). When ZFIN's
// conventions change, they change HERE.
//
// It's all `static` -- these are constants and pure functions of tag/slug/release, not
// per-invocation state -- so commands just reference `StackConfig.X` with no wiring. This is
// the "light seam": the policy/mechanism split is expressed purely by WHERE a literal lives.
// See workbench/architecture-improvement.md for the (deferred) fuller DSL-based version.
class StackConfig {
    // Compile image used to run `tar` during capture/restore (GNU tar, root, always local).
    static String compileImage(String release) { "ghcr.io/zfin/zfin-compile:$release" }

    // Per-feature hostname (served by httpd; mapped via --hosts / dnsmasq).
    // Loopback stacks: each publishes its own 127.0.0.X:443, so the name resolves to that IP.
    static String host(String slug) { "${slug}.zfin.test" }

    // The INSTANCE every per-feature stack generates properties as (written into the feature's
    // docker/.env by NewFeature). Defined in commons/env/all-properties.yml: DOMAIN_NAME comes
    // from ${env.DOCKER_VIRTUAL_HOST} so links follow the stack's own host instead of pointing
    // at production, and it has an email_overrides entry -- an instance missing from that
    // section falls through to the REAL curator addresses.
    static final String FEATURE_INSTANCE = 'feature'

    // --- review routing (workbench/review-stacks.md) ---------------------------------------
    // Review stacks are fronted by ONE shared proxy instead of publishing :443 each, so their
    // names all live under a single zone and all resolve to the proxy.
    //
    // The zone is a VARIABLE, not a constant, and that is the point: the scheme is expected to
    // move from a local .test zone to a real zfin.org one once DNS exists, and that must be a
    // config change (ZFIN_REVIEW_ZONE on the host) rather than a code change. Each feature's
    // chosen host is frozen into its own .env at provisioning time, so moving the default
    // never renames stacks that already exist.
    // DEFAULTS only. Resolution (docker/.env -> environment -> here) is ZfinUtil.reviewZone()
    // / reviewDir() / reviewHost(), because it needs the .env reader. Call those, not these.
    static final String REVIEW_ZONE_DEFAULT = 'review.zfin.test'
    static final String REVIEW_PROJECT      = 'zfin_review'
    // Fixed container_name in docker-compose.review.yml, so the proxy can be signalled
    // without a `docker compose ps` round-trip.
    // The main checkout's own stack. A last-resort fallback only: every command that acts on a
    // stack resolves one from the working directory first.
    static final String BASE_PROJECT        = 'zfin_org'

    static final String REVIEW_PROXY_NAME   = 'zfin-review-proxy'
    static final String REVIEW_NET          = 'zfin_review_net'
    // The env var OUR proxy discovers routed stacks by. Deliberately NOT nginx-proxy's stock
    // VIRTUAL_HOST: on a host where another nginx-proxy watches the same Docker socket, a
    // routed stack carrying VIRTUAL_HOST also appears on THAT proxy, which cannot reach it --
    // and its exact vhost outranks our wildcard, so the stack's own hostname resolves to an
    // empty upstream ("no live upstreams", 502). A name the stock template never reads makes
    // our stacks invisible to it. Applied by docker/review-proxy/Dockerfile, which renames
    // that token in the template -- keep this constant and that sed in step.
    static final String REVIEW_VHOST_ENV    = 'ZFIN_VIRTUAL_HOST'

    // What a routed feature's .env carries beyond its identity keys, and the ONLY definition of
    // it: `z feature new` writes these at creation and `z feature refresh` backfills them into a
    // stack made before a key existed. Two writers, one list, so they cannot drift -- which they
    // would have, twice already, since these keys replaced lines that used to live in
    // docker-compose.overlay-review-member.yml.
    //
    // They are VALUES on purpose. Anything a value can express belongs in the stack's own .env
    // and is read by docker-compose.yml; the overlay keeps only what a value cannot say.
    // Overlay files gained an `overlay-` prefix so they group in a listing. A stack's .env
    // records which overlays compose it, so one written before the rename names files that no
    // longer exist. `z feature refresh` rewrites the key from this map; it is the one key
    // refresh rewrites rather than only adds, because it records OUR filenames rather than a
    // developer's preference, and a stale value there is simply wrong.
    static final Map<String, String> OVERLAY_RENAMES = [
        'docker-compose.review-member.yml'  : 'docker-compose.overlay-review-member.yml',
        'docker-compose.review-upstream.yml': 'docker-compose.overlay-review-upstream.yml',
        'docker-compose.shared.yml'         : 'docker-compose.overlay-shared.yml',
        'docker-compose.shared-db.yml'      : 'docker-compose.overlay-shared-db.yml',
    ]

    // What makes a seed's APP TIER usable. Checked on BOTH sides -- `z seed create` warns that
    // the seed it just wrote is thin, and `z feature new --seed` warns before restoring one --
    // because a seed outlives the session that made it and the create-time warning scrolls away
    // with it. The failure it prevents points nowhere near the cause: httpd includes
    // $TARGETROOT/server_apps/apache/inc-redirect out of www_data, so an empty www_data kills it
    // with an Apache syntax error, and an empty catalina_base kills tomcat with a missing
    // server.xml. Only these two are checked: keystore and tls_certs are legitimately a few KB,
    // so a size floor on them would cry wolf on every healthy seed.
    static final List<String> APP_TIER_VOLUMES = ['www_data', 'catalina_base']
    static final long         APP_TIER_MIN_BYTES = 1_000_000

    static final String FEATURE_SOLR_MEM  = '6g'
    static final String FEATURE_SOLR_HEAP = '4g'
    /** When this host's review proxy is the ONLY nginx-proxy (ZFIN_REVIEW_SOLE), stacks
     *  advertise the STOCK name -- there is no second proxy to hide from, and the proxy is
     *  built to read it. Otherwise they advertise ours and stay invisible to the other one. */
    static Map<String, String> featureEnv(String host, String gitCommon = null, String gitDir = null,
                                          boolean sole = false) {
        [ DOCKER_EXTERNAL_VHOST: sole ? host : '',  // sole: THIS is the stock name we serve by.
                                         // otherwise present but EMPTY: invisible to any external
                                         // nginx-proxy, and asks acme for nothing. An ABSENT key
                                         // falls back to DOCKER_VIRTUAL_HOST and the stack starts
                                         // advertising itself to a proxy that cannot reach it.
          DOCKER_REVIEW_VHOST  : sole ? '' : host,   // the review proxy routes this hostname
          DOCKER_SOLR_MEM_LIMIT: FEATURE_SOLR_MEM,   // prod sizing (16g/12g) will not start on a
          DOCKER_SOLR_HEAP     : FEATURE_SOLR_HEAP, // VM shared with other stacks
          // Where this tree's git lives. The sidecar binds both at these exact paths -- a
          // worktree's .git is a FILE pointing into the main repo, so git cannot work in a
          // container that has only the worktree. See the git mounts in docker-compose.yml.
          DOCKER_GIT_COMMON_DIR  : gitCommon,
          DOCKER_GIT_WORKTREE_DIR: gitDir ].findAll { k, v -> v != null }
    }


    // NOTE: no absolute directory defaults live here any more. Where a host keeps its review
    // state, archives and worktrees is declared by ZFIN_DEV_ROOT (see ZfinUtil.devRoot) --
    // an absolute default is a guess about someone else's machine that fails silently.

    // Default allow list when $ZFIN_REVIEW_ALLOW is unset: loopback + RFC1918, i.e. correct
    // for a laptop and safely closed on a VM until someone states the real ranges. NOT open.
    static final String REVIEW_ALLOW_DEFAULT = '127.0.0.0/8 ::1/128 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16'

    // Data-tier readiness probe. The SAME check also appears as db's `healthcheck:` in
    // docker-compose.yml, which is what the app tier's `condition: service_healthy` waits on;
    // YAML cannot import this file, so the two are kept in step by hand and both carry a note.
    // This argv form stays because it covers the case compose cannot: a postgres started
    // OUTSIDE compose (build-preloaded's throwaway container for the WAL trim) has no compose
    // healthcheck to read.
    static final List<String> DB_PROBE = ['pg_isready', '-U', 'postgres', '-d', 'zfindb']
    static List<String> dbHealthCheck(String container) {
        ['docker', 'exec', container] + DB_PROBE
    }

    // Service roles (were hardcoded lists across several commands).
    static final List<String> DATA_SERVICES  = ['db', 'solr']
    static final List<String> APP_SERVICES   = ['tomcat', 'httpd']
    // The service that answers a stack's URL. `z feature ls` calls a stack "up" only when THIS
    // is running, because that column sits next to the URL and has to agree with it.
    static final String       WEB_SERVICE    = 'httpd'
    static final String        BUILD_SERVICE = 'compile'

    // Warm-volume contract: the SINGLE source read by BuildPreloaded (producer) AND
    // NewFeature (consumer), so the lists + on-disk tarball layout can never drift apart.
    static final List<String> APP_VOLS   = ['www_data', 'catalina_base', 'keystore', 'tls_certs']
    static final List<String> CACHE_VOLS = ['gradle_cache', 'maven_cache', 'npm_cache']

    // The single-owner data volumes -- the ~19G + ~9G that make a feature stack expensive,
    // and the reason `z feature freeze` exists. A --shared-db stack has NEITHER (its data
    // lives in the zfin_shared project), which is why freeze derives its volume list from the
    // stack's composition rather than from a fixed list.
    static final List<String> DATA_VOLS  = ['pg_data', 'solr_var']

    // The agent's per-stack home, captured whenever it exists so a frozen stack keeps its
    // session history. Absent until the claude sidecar lands; freeze skips what is not there.
    static final String CLAUDE_VOL = 'claude_home'

    // Jenkins' home: jobs, plugins, and secrets/initialAdminPassword -- the credential
    // `jenkins-cli` authenticates with. Captured like CLAUDE_VOL rather than added to APP_VOLS,
    // because APP_VOLS is also the warm-app test (`haveTars(tag, APP_VOLS)` requires ALL of
    // them) and adding a member there would make every seed captured before today read as cold.
    // Freeze without it discarded a stack's whole Jenkins setup on `down -v`, and thaw could
    // not bring it back.
    static final String JENKINS_VOL = 'jenkins_data'
    static final String CLAUDE_SERVICE = 'claude'

    // Where the host keeps the sidecar's auth token (`claude setup-token`).
    //
    // Under $HOME, NOT under /opt/zfin like the review dir -- and the difference is the point.
    // Review certs and the allowlist are shared host infrastructure that every developer must
    // see identically. A Claude token is a PERSONAL credential: on a shared review host each
    // developer has their own, and putting it in a group-readable directory would hand one
    // person's subscription to everyone with an account. Override with $ZFIN_CLAUDE_TOKEN_FILE.
    // Stays under $HOME, deliberately NOT under ZFIN_DEV_ROOT: that tree is group-writable on
    // a shared host (review-stacks.md 3.6) so developers can collaborate on worktrees, and a
    // credential there would hand one person's subscription to everyone with an account.
    static String claudeTokenFile() {
        System.getenv('ZFIN_CLAUDE_TOKEN_FILE') ?: "${System.getProperty('user.home')}/.zfin/claude-token"
    }

    static final String FREEZE_MANIFEST = 'freeze.json'
    static final String SEED_MANIFEST   = 'seed.json'
}
