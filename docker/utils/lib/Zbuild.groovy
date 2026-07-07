#!/usr/bin/env groovy
// zbuild -- hands-free, phased build/deploy orchestrator for a stack.
//
// Non-interactive: the engine behind GoCD/CI *and* behind z-fresh-install. It runs
// the same sequence the GoCD "Trunk" pipeline does, but as versioned, testable Groovy
// instead of ~30 tasks of shell embedded in pipeline JSON. Keep it PHASED (not one
// monolith) so a CI pipeline can still map stage->phase and keep parallelism/gating.
//
// It targets a stack purely through the compose environment
// (COMPOSE_PROJECT_NAME / COMPOSE_FILE / COMPOSE_ENV_FILES) -- set by an activated
// .zenv, by GoCD environment_variables, or (for COMPOSE_FILE only) defaulted here to
// the base docker-compose.yml so it also works from a bare checkout.
//
// Usage:
//   z build <phase> [<phase>...] [--build] [--test]
//   z build all [--build] [--test]        # every phase, in order
//
// Phases (mirror the GoCD Trunk pipeline; each maps to one GoCD stage):
//   configure       (--build ? build : pull) images; init volumes (cert/props via
//                   the compile login shell); ant do
//   load-db         up db; gradle loaddb && make && liquibasePreBuild && liquibasePostBuild
//   load-solr       up solr; wait for the core; gradle getLatestSolrIndex
//   deploy-jenkins  ant deploy-jobs && deploy-plugins; (re)start jenkins
//   deploy          build WAR; ant deploy-catalina-base && deploy-without-tests;
//                   (re)start httpd/mailpit/tomcat; (--test ? gradle test non+smoke)
//
// Flags:  --build  build stock images in `configure` (default: pull from ghcr.io)
//         --test   run the smoke/non-smoke test tasks in `deploy`

class Zbuild {
def run(List args, ZfinUtil zfinUtil) {
    def die = zfinUtil.&die; def info = zfinUtil.&info
    def DOCKER = zfinUtil.DOCKER

    // Default COMPOSE_FILE for children when nothing set it (bare checkout / CI). An activated
    // .zenv or GoCD env_vars normally provide the full COMPOSE_PROJECT_NAME/FILE/ENV_FILES.
    // childEnv is injected into every process zfinUtil.sh spawns (compose + zc).
    if (!System.getenv('COMPOSE_FILE')) zfinUtil.childEnv['COMPOSE_FILE'] = new File(DOCKER, 'docker-compose.yml').absolutePath

    def sh      = zfinUtil.&sh
    def compose = { Object... a -> sh(['docker', 'compose'] + (a as List)) }                                        // lifecycle: up/down/stop/build/pull
    def zc      = { String script -> sh(['docker', 'compose', 'run', '--rm', 'compile', 'bash', '-l', '-c', script]) } // run in the compile container (login shell)

def buildImages = false
def runTests    = false
def requested   = []
(args as List).each { a ->
    switch (a) {
        case '--build': buildImages = true; break
        case '--test':  runTests = true;    break
        case '-h': case '--help':
            println new File(getClass().protectionDomain.codeSource.location.toURI()).readLines()
                    .findAll { it.startsWith('//') }.collect { it.replaceFirst('// ?', '') }.join('\n')
            System.exit(0)
        default:
            if (a.startsWith('-')) die("unknown flag: $a", 2)
            requested << a
    }
}

def STOCK = ['base', 'compile', 'db', 'solr', 'httpd', 'tomcat', 'jenkins', 'fail2ban']

def PHASES = [:]
PHASES['configure'] = {
    if (buildImages) {
        // Build `base` FIRST, on its own, so it's tagged locally before compile/jenkins
        // resolve `FROM ghcr.io/zfin/zfin-base:...`. In a single combined `compose build`,
        // buildx resolves that FROM from the REGISTRY (amd64) -- concurrently with base
        // building -- instead of the local native base, yielding amd64 compile/jenkins on
        // an arm64 host. Base-first makes their FROM pick up the local (native) base.
        info('configure: build base image (native, for the FROM chain)')
        compose('build', 'base')
        info('configure: build the remaining stock images')
        compose(['build'] + (STOCK - 'base') as Object[])
    } else {
        info('configure: pull stock images')
        compose(['pull'] + STOCK as Object[])
    }
    info('configure: init volumes (cert + zfin.properties via the compile login shell)')
    zc('true')          // bash -l sources .profile -> generates cert/keystore + zfin.properties
    info('configure: ant do')
    zc('ant do')
}
PHASES['load-db'] = {
    info('load-db: up db + loaddb/make/liquibase')
    compose('up', '-d', 'db')
    zc('gradle loaddb && gradle make && gradle liquibasePreBuild && gradle liquibasePostBuild')
}
PHASES['load-solr'] = {
    info('load-solr: up solr, wait for core, getLatestSolrIndex')
    compose('up', '-d', 'solr')
    // Poll, THEN re-check: the loop's last command is `sleep`, so without the final
    // ping the whole thing exits 0 even if the core never came up -- and gradle
    // getLatestSolrIndex would then run against a not-ready core (a confusing downstream
    // failure instead of a clean "solr timed out"). Triple-SINGLE quotes so Groovy leaves
    // $i / ${CORE} for bash. (zc dies on the nonzero exit.)
    zc('''
      for i in $(seq 1 60); do
        curl -sf -o /dev/null "http://solr:8983/solr/${CORE:-site_index}/admin/ping" && break
        echo "waiting for solr core ($i/60)"; sleep 5
      done
      if ! curl -sf -o /dev/null "http://solr:8983/solr/${CORE:-site_index}/admin/ping"; then
        echo "solr core did not respond after ~300s" >&2; exit 1
      fi
    '''.stripIndent())
    zc('gradle getLatestSolrIndex')
}
PHASES['deploy-jenkins'] = {
    info('deploy-jenkins: deploy jobs + plugins, (re)start jenkins')
    zc('ant deploy-jobs')
    zc('ant deploy-plugins')
    compose('up', '-d', 'jenkins')
}
PHASES['deploy'] = {
    info('deploy: build WAR, deploy catalina-base + app, (re)start app tier')
    compose('stop', 'httpd', 'tomcat')
    zc('gradle make && ant deploy-catalina-base && ant deploy-without-tests')
    compose('up', '-d', 'httpd', 'mailpit', 'tomcat')
    if (runTests) {
        info('deploy: tests')
        zc('gradle test -PnonSmokeTests')
        zc('gradle test -PsmokeTests')
    }
}
def ORDER = ['configure', 'load-db', 'load-solr', 'deploy-jenkins', 'deploy']

if (!requested) die("no phase given. Phases: ${ORDER.join(', ')} (or 'all'). See --help.", 2)
def toRun = requested == ['all'] ? ORDER : requested
toRun.each { if (!PHASES[it]) die("unknown phase '$it'. Phases: ${ORDER.join(', ')} (or 'all').", 2) }

def proj = System.getenv('COMPOSE_PROJECT_NAME') ?: '(compose default)'
info("stack=${proj}  phases=${toRun.join(' -> ')}${buildImages ? '  [build]' : ''}${runTests ? '  [test]' : ''}")
toRun.each { PHASES[it]() }
info("done: ${toRun.join(', ')}")
}
}
