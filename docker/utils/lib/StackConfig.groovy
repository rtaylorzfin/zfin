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

    // Service roles (were hardcoded lists across several commands).
    static final List<String> DATA_SERVICES  = ['db', 'solr']
    static final List<String> APP_SERVICES   = ['tomcat', 'httpd']
    static final String        BUILD_SERVICE = 'compile'

    // Warm-volume contract: the SINGLE source read by BuildPreloaded (producer) AND
    // NewFeature (consumer), so the lists + on-disk tarball layout can never drift apart.
    static final List<String> APP_VOLS   = ['www_data', 'catalina_base', 'keystore', 'tls_certs']
    static final List<String> CACHE_VOLS = ['gradle_cache', 'maven_cache']
}
