// CreateZenv -- generate a ".zenv/" (venv-style activation) for a Docker Compose stack.
//
// Creates <dir>/.zenv/{activate,bin/,compose/,zenv.properties}. Then:
//     source <dir>/.zenv/activate
// puts `z` on PATH and defines the short-name shell functions (zrun/zexec/zup/zdown/zpull/
// zlog/zrestart/zstatus/zhelp/zfeature/zbuild -> `z run`/`z exec`/...), and points them --
// plus bare `docker compose` -- at the given stack via COMPOSE_PROJECT_NAME / COMPOSE_FILE /
// COMPOSE_ENV_FILES, so you never type -p/--env-file/-f. `deactivate` restores the shell.
//
// COPY vs LINK -- a feature's .zenv is a SELF-CONTAINED BUNDLE. The tooling (z + lib/) and
// the compose file(s) are COPIED into .zenv/, not linked back to the checkout that generated
// them: that checkout is a moving target (the tooling only exists on the branch that adds it,
// so switching the primary to main used to dangle every feature's `z` symlink and delete the
// preloaded overlay out from under COMPOSE_FILE). The primary checkout's OWN .zenv links
// instead (--link is the default when --dir is the primary checkout), so edits to
// docker/utils/ take effect immediately while you work on the tooling.
//
// A bundle is a frozen snapshot: re-copy it after tooling changes with `z feature refresh`
// (it replays the args recorded in .zenv/zenv.properties).
//
// bin/ is the PATH entry: a copy of `z` + lib/ (copy mode), or a symlink to docker/utils/
// (link mode). The short names stay shell FUNCTIONS (not per-name copies) because a Groovy
// `z` can't see which name invoked it.
// Run via `z create-zenv ...` (bootstrap) -- roots come from z.
//
// Usage:
//   z create-zenv --dir DIR --project NAME --compose F1[:F2...] --env-file ENV [--tag TAG]
//                 [--host HOST] [--copy|--link]
//
//   --dir DIR        Where to create .zenv/ (worktree or repo root)
//   --project NAME   COMPOSE_PROJECT_NAME for the stack
//   --compose LIST   ':'-separated compose file(s) (base[:overlay])
//   --env-file ENV   env file for the stack (COMPOSE_ENV_FILES)
//   --tag TAG        PRELOADED_TAG to export (optional; omit for the base stack)
//   --host HOST      hostname for the activation banner URL (optional)
//   --copy           Force a self-contained bundle (default for a worktree)
//   --link           Force live links back to the source checkout (default for the primary)

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class CreateZenv {
    /** Returns [zenv: File, composeFiles: List<File>, mode: 'copy'|'link'] so callers
     *  (NewFeature) drive compose through the SAME files the .zenv activates. */
    def run(List args, ZfinUtil zfinUtil) {
        if (zfinUtil.helpRequested(args, this)) return null
        def die = zfinUtil.&die
        def info = zfinUtil.&info
        def captureOutput = zfinUtil.&captureOutput
        def LIB = zfinUtil.LIB                      // docker/utils/lib (holds z-completion.bash)

// --- args --------------------------------------------------------------------
        def opt = [:]
        def modeArg = null
        def argv = args as List
        for (int i = 0; i < argv.size(); i++) {
            switch (argv[i]) {
                case '--dir': opt.dir = argv[++i]; break
                case '--project': opt.project = argv[++i]; break
                case '--compose': opt.compose = argv[++i]; break
                case '--env-file': opt.envFile = argv[++i]; break
                case '--tag': opt.tag = argv[++i]; break
                case '--host': opt.host = argv[++i]; break
                case '--copy': modeArg = 'copy'; break
                case '--link': modeArg = 'link'; break
                default: die("unknown arg: ${argv[i]}", 2)
            }
        }
        ['dir', 'project', 'compose', 'envFile'].each {
            if (!opt[it]) die("--dir, --project, --compose, --env-file are required", 2)
        }
        def dir = new File(opt.dir.toString()).absoluteFile
        if (!dir.isDirectory()) die("--dir '$dir' is not a directory")

// The tooling to bundle always comes from the ORIGIN checkout, never from whatever .zenv
// happens to be running us -- otherwise `z feature refresh` from inside a bundled feature
// would "refresh" a bundle from itself.
        def originUtils = new File(zfinUtil.REPO, 'docker/utils')
        if (!new File(originUtils, 'z').isFile()) {
            info("note: no tooling at $originUtils (origin on a branch without it?) -- bundling from ${zfinUtil.UTILS}")
            originUtils = zfinUtil.UTILS
        }
        def ZFRONT = new File(originUtils, 'z')      // the one executable every stack activates
        if (!ZFRONT.isFile()) die("front door not found at $ZFRONT")

// Default mode: the primary checkout stays LIVE (you edit the tooling there), every other
// stack dir (a feature worktree) gets a self-contained COPY.
        def mode = modeArg ?: (dir.canonicalFile == zfinUtil.REPO.canonicalFile ? 'link' : 'copy')

        def zenv = new File(dir, '.zenv')
        zenv.mkdirs()
// Recreate bin/ + compose/ fresh: clear stale entries left by an older tooling layout (e.g.
// the per-short-name symlinks that predate the single-front-door model, or a link-mode bin/
// when this stack is being switched to a copy).
        def rmTree
        rmTree = { File f -> if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) f.listFiles().each(rmTree); f.delete() }
        rmTree(new File(zenv, 'bin'))
        rmTree(new File(zenv, 'compose'))

// bin/ is the PATH entry, and z self-locates from it (UTILS = bin/, LIB = bin/lib/) -- so in
// copy mode the whole tooling lives under it, and in link mode it's one symlink to
// docker/utils/. Keeping the name means an already-activated shell survives a regeneration.
        def utils = new File(zenv, 'bin')
        def srcComposeFiles = opt.compose.toString().tokenize(':').collect { new File(it).absoluteFile }
        srcComposeFiles.each { if (!it.isFile()) die("compose file not found: $it") }
        def composeFiles = srcComposeFiles            // rewritten below in copy mode
        def libDir = LIB

        if (mode == 'copy') {
// 1. the tooling: z + lib/ copied into bin/, mirroring docker/utils/ so z's own
//    self-location (UTILS = <me>/.., LIB = UTILS/lib) still resolves inside the bundle.
//    Just those two -- anything else that happens to sit in docker/utils/ isn't the tooling.
            utils.mkdirs()
            Files.copy(ZFRONT.toPath(), new File(utils, 'z').toPath(), StandardCopyOption.REPLACE_EXISTING)
            new File(utils, 'z').setExecutable(true)
            copyTree(new File(originUtils, 'lib'), new File(utils, 'lib'))
            libDir = new File(utils, 'lib')

// 2. the compose file(s). Compose resolves a relative `build:`/`context:` against the
//    compose FILE's directory, so a copy sitting in .zenv/compose/ would look for
//    ./base/, ./compile/ ... beside itself. Re-anchor those to a real docker/ tree while
//    copying (see reanchorContexts) rather than duplicating ~100M of build context.
            def composeDir = new File(zenv, 'compose')
            composeDir.mkdirs()
            def stackDocker = new File(dir, 'docker')
            composeFiles = srcComposeFiles.collect { src ->
                def dst = new File(composeDir, src.name)
                dst.text = reanchorContexts(src, stackDocker, zfinUtil.DOCKER)
                dst
            }
        } else {
// link mode: bin/ is a live symlink to the origin's docker/utils/ and the compose files are
// used where they sit -- edits land immediately, at the cost of following the origin's branch.
            Files.createSymbolicLink(utils.toPath(), Paths.get(originUtils.absolutePath))
        }

// 3. what this bundle IS, in one file: enough for `z feature refresh` to replay
//    create-zenv, and for a bundled `z` to find the origin checkout (ZfinUtil reads
//    origin.* instead of deriving roots from its own location).
        new File(zenv, 'zenv.properties').text = """\
# Generated by z create-zenv -- describes this .zenv. Don't hand-edit; re-run create-zenv
# (or `z feature refresh`) instead.
mode=$mode
dir=${dir.absolutePath}
project=${opt.project}
host=${opt.host ?: ''}
tag=${opt.tag ?: ''}
env.file=${new File(opt.envFile.toString()).absolutePath}
compose.source=${srcComposeFiles*.absolutePath.join(':')}
compose.active=${composeFiles*.absolutePath.join(':')}
origin.repo=${zfinUtil.REPO.absolutePath}
origin.docker=${zfinUtil.DOCKER.absolutePath}
tooling.hash=${zfinUtil.bundleHash(originUtils, srcComposeFiles)}
"""

// activate: comment + a switch-guard + baked vars + a literal bash body. ORDER MATTERS:
// the switch-guard (deactivate a currently-active .zenv) must run BEFORE the baked vars
// are set -- the old deactivate() unsets the _ZENV_* names, so if it ran after, it would
// wipe the fresh values and activation would come up empty (broke re-sourcing/switching).
        def comment = """\
# .zenv/activate for '${opt.project}'. Source me:  source .zenv/activate
# Generated by create-zenv.groovy -- re-run it to regenerate; don't hand-edit.
"""
        def switchGuard = '''\
if command -v deactivate >/dev/null 2>&1 && [ -n "${ZENV_ACTIVE:-}" ]; then deactivate; fi
'''
        def vars = """\
_ZENV_PROJECT='${opt.project}'
_ZENV_HOST='${opt.host ?: ''}'
_ZENV_DIR='${dir.absolutePath}'
_ZENV_BIN='${utils.absolutePath}'
_ZENV_LIB='${libDir.absolutePath}'
_ZENV_COMPOSE_FILE='${composeFiles*.absolutePath.join(':')}'
_ZENV_ENV_FILE='${new File(opt.envFile.toString()).absolutePath}'
_ZENV_TAG='${opt.tag ?: ''}'
"""
        def body = '''\
_ZENV_OLD_PS1="${PS1:-}"
_ZENV_OLD_PATH="$PATH"
_ZENV_OLD_CPN="${COMPOSE_PROJECT_NAME:-}"
_ZENV_OLD_CF="${COMPOSE_FILE:-}"
_ZENV_OLD_CEF="${COMPOSE_ENV_FILES:-}"
_ZENV_OLD_TAG="${PRELOADED_TAG:-}"

export PATH="$_ZENV_BIN:$PATH"
export COMPOSE_PROJECT_NAME="$_ZENV_PROJECT"
export COMPOSE_FILE="$_ZENV_COMPOSE_FILE"
export COMPOSE_ENV_FILES="$_ZENV_ENV_FILE"
[ -n "$_ZENV_TAG" ] && export PRELOADED_TAG="$_ZENV_TAG"
export ZENV_ACTIVE="$_ZENV_PROJECT"
export ZENV_HOST="$_ZENV_HOST"
export ZENV_DIR="$_ZENV_DIR"

PS1="($_ZENV_PROJECT) ${PS1:-}"

# Short-name shell functions -> the one `z` on PATH.
zrun()     { z run "$@"; }
zexec()    { z exec "$@"; }
zup()      { z up "$@"; }
zdown()    { z down "$@"; }
zpull()    { z pull "$@"; }
zlog()     { z log "$@"; }
zrestart() { z restart "$@"; }
zstatus()  { z status "$@"; }
zhelp()    { z help "$@"; }
zfeature() { z feature "$@"; }
zbuild()   { z build "$@"; }

# bash tab-completion for z + the short names (no-op in other shells / if missing)
if [ -n "${BASH_VERSION:-}" ] && [ -r "$_ZENV_LIB/z-completion.bash" ]; then
  . "$_ZENV_LIB/z-completion.bash"
fi

deactivate() {
  PATH="$_ZENV_OLD_PATH"; export PATH
  PS1="$_ZENV_OLD_PS1"
  if [ -n "$_ZENV_OLD_CPN" ]; then export COMPOSE_PROJECT_NAME="$_ZENV_OLD_CPN"; else unset COMPOSE_PROJECT_NAME; fi
  if [ -n "$_ZENV_OLD_CF" ];  then export COMPOSE_FILE="$_ZENV_OLD_CF";          else unset COMPOSE_FILE;         fi
  if [ -n "$_ZENV_OLD_CEF" ]; then export COMPOSE_ENV_FILES="$_ZENV_OLD_CEF";    else unset COMPOSE_ENV_FILES;    fi
  if [ -n "$_ZENV_OLD_TAG" ]; then export PRELOADED_TAG="$_ZENV_OLD_TAG";        else unset PRELOADED_TAG;        fi
  unset -f zrun zexec zup zdown zpull zlog zrestart zstatus zhelp zfeature zbuild 2>/dev/null || true
  complete -r z zrun zexec zup zdown zpull zlog zrestart zstatus zhelp zfeature zbuild 2>/dev/null || true
  unset ZENV_ACTIVE ZENV_HOST ZENV_DIR
  unset _ZENV_PROJECT _ZENV_HOST _ZENV_DIR _ZENV_BIN _ZENV_LIB _ZENV_COMPOSE_FILE _ZENV_ENV_FILE _ZENV_TAG
  unset _ZENV_OLD_PS1 _ZENV_OLD_PATH _ZENV_OLD_CPN _ZENV_OLD_CF _ZENV_OLD_CEF _ZENV_OLD_TAG
  unset -f deactivate
  echo "zenv: deactivated (restored previous shell environment)"
}

if [ -n "$_ZENV_HOST" ]; then
  echo "zenv: activated $_ZENV_PROJECT  ->  https://$_ZENV_HOST   (z-commands on PATH; 'deactivate' to exit)"
else
  echo "zenv: activated $_ZENV_PROJECT   (z-commands on PATH; 'deactivate' to exit)"
fi
'''
        new File(zenv, 'activate').text = comment + switchGuard + vars + body

// Keep .zenv/ out of git without touching a tracked .gitignore (works on any branch).
        def common = captureOutput(['git', '-C', dir.absolutePath, 'rev-parse', '--git-common-dir'])
        if (common) {
            def cdir = new File(common).isAbsolute() ? new File(common) : new File(dir, common)
            def excl = new File(cdir, 'info/exclude')
            def existing = excl.exists() ? excl.text : ''
            if (!(existing =~ /(?m)^\.zenv\/\s*$/)) {
                excl.parentFile.mkdirs()
                excl << (existing && !existing.endsWith('\n') ? '\n' : '')
                excl << '.zenv/\n'
            }
        }

        println "create-zenv: wrote $zenv ($mode)  ->  source ${new File(zenv, 'activate')}"
        [zenv: zenv, composeFiles: composeFiles, mode: mode]
    }

    /** Recursive file copy (replacing), preserving the executable bit. */
    private void copyTree(File src, File dst) {
        dst.mkdirs()
        (src.listFiles() ?: [] as File[]).each { f ->
            def out = new File(dst, f.name)
            if (f.isDirectory()) copyTree(f, out)
            else {
                Files.copy(f.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (f.canExecute()) out.setExecutable(true)
            }
        }
    }

    /** Read a compose file, rewriting relative `build:`/`context:` values to absolute paths
     *  so the copy works from .zenv/compose/. Preference order for each context:
     *    1. the stack's OWN docker/ tree (a worktree has one, checked out from git), so a
     *       feature builds from its own branch's Dockerfiles;
     *    2. the origin docker/ tree, when the stack dir has no such context.
     *  `dockerfile:` is deliberately untouched -- it resolves against the CONTEXT, not the
     *  compose file -- as are non-paths (`!reset null`, `${VAR}`, urls, already-absolute). */
    private String reanchorContexts(File src, File stackDocker, File originDocker) {
        def srcDir = src.absoluteFile.parentFile
        src.readLines().collect { line ->
            def m = (line =~ /^(\s*)(build|context):[ \t]+(\S+)[ \t]*$/)
            if (!m.find()) return line
            def (indent, key, val) = [m.group(1), m.group(2), m.group(3)]
            if (val.startsWith('!') || val.startsWith('/') || val.startsWith('$') ||
                val.startsWith('"') || val.startsWith("'") || val.contains('://') || val.startsWith('git@')) return line
            def resolved = new File(srcDir, val).canonicalFile
            def target = resolved
            def originPath = originDocker.canonicalFile.toPath()
            if (resolved.toPath().startsWith(originPath)) {
                def rel = originPath.relativize(resolved.toPath()).toString()
                def own = new File(stackDocker, rel)
                if (own.isDirectory() || own.isFile()) target = own.canonicalFile
            }
            "$indent$key: ${target.absolutePath}"
        }.join('\n') + '\n'
    }
}
