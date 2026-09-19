# Claude sidecar container (future strategy)

Status: **not built** — a design we may adopt later. Captured so the reasoning
(and the safety gotchas) aren't lost.

## Decision (2026-08-14): harden `compile` first, sidecar later (maybe never)

Rather than build the sidecar, we're removing the dangerous capabilities from `compile`. Step 1
is done: **`/var/run/docker.sock` is no longer mounted** (`docker/docker-compose.yml`), and the
`sudo chown` of it is gone from `docker/compile/more_bash_profile`.

That cost almost nothing, because the socket had exactly one consumer in the whole repo:
`buildfiles/tomcat.xml`'s `docker-tomcat-start` / `docker-tomcat-stop` (reached via `ant
restart-tomcat`). Nothing else referenced them — no Jenkins job, no GoCD stage, no Ant/Gradle
target, no docs, and no Testcontainers anywhere in the test tree. They were also **already
broken for feature stacks**: they ran `docker compose -f <abs path> stop tomcat` with no `-p`,
and the container gets no `COMPOSE_PROJECT_NAME`, so compose derived the project from the compose
file's directory (`docker`) and silently acted on nothing. Those targets now `<fail>` with a
message pointing at the host command.

**Tomcat lifecycle is a host operation**: `z restart tomcat` (the `.zenv`, or `z`'s cwd
auto-detection, already targets the right stack). `z` itself refuses to run inside a container,
which is the same stance from the other direction.

### Rejected: restarting tomcat from inside compile via the shutdown port

Tempting — it would keep an in-container restart button without the socket — but it needs three
changes, and the last two are worse than what they replace:

1. **Unreachable as configured.** `lib/Java/tomcat/conf/server.xml` has
   `<Server port="@SERVER-SHUTDOWN-PORT@" shutdown="SHUTDOWN">` with no `address`, and Tomcat
   defaults that socket to `localhost` — i.e. loopback *inside the tomcat container*. Reaching it
   from `compile` needs `address="0.0.0.0"`, and this template also deploys to staging/prod, so
   it would need a new dev-only token.
2. **Nothing restarts it.** SHUTDOWN stops the JVM, the container's main process exits, and it
   stays down: `mailpit` is the only service in the compose file with a `restart:` policy. Adding
   `restart: unless-stopped` to tomcat would be required (and `unless-stopped`, not `always`, so
   a deliberate `zstop`/`zdown` isn't resurrected).
3. **It's an unauthenticated kill switch** on the feature's compose network — the magic word is
   the only credential, and any container on that network can send it.

### Also considered: the Tomcat Manager app

The standard-conventions way to get an in-container button: `GET /manager/text/reload?path=/`.
Needs (a) the app deployed — the image stages it at `/usr/local/tomcat/webapps.dist/manager`, and
`$CATALINA_BASE`'s `<Host>` is `autoDeploy="false"`, so the clean route is a `manager.xml` context
file beside `ROOT.xml` with `docBase` pointing at the dist path; (b) a `manager-script` user in
`tomcat-users.xml`, which is comment-only today; (c) an override of manager's shipped
`RemoteCIDRValve allow="127.0.0.0/8,::1/128"`, since a call from `compile` arrives from the
compose bridge subnet; (d) instance-conditional wiring so it never reaches prod.

Not adopted, for two reasons. It buys little: `ROOT.xml` already sets `reloadable="true"`, so
Tomcat's background scanner reloads the webapp when `WEB-INF/classes`/`lib` change — which is what
`gradle dirtydeploy` does — and a reload still re-initializes Spring (root + 3 dispatcher
contexts) and Hibernate (290 mappings), i.e. most of ZFIN's startup cost. And it *increases* the
capability we're trying to shrink: `manager-script` can deploy an arbitrary WAR, which is RCE
inside the container, where the shutdown port could only stop the JVM.

### Step 2 (also done): the SSH agent socket is gone too

`compile` no longer mounts `${DOCKER_SSH_AUTH_SOCK}` or `~/.ssh/known_hosts`, and no longer sets
`SSH_AUTH_SOCK`. Nothing running there — `--dangerously-skip-permissions` included — can `git
push` or SSH out with your credentials, which turns [[git-push-manual]] from a preference into a
structural guarantee. `SSH_USER`/`SSH_HOST` stay: plain strings, no credential.

We considered a profile-gated twin service (`compile` + the agent) to keep the remote-fetch tasks
working, and decided against it. The affected tasks are `gradle getdb` / `getsolr` (→
`getLatestDatabaseUnload`, `getLatestSolrUnload`, `getLatestPostgresFilesTrunk`), which `scp` dumps
from `${SSH_HOST}:/research/zunloads/...`. They're unreliable enough not to be worth a credential
path: **fetch dumps on the host instead** and drop them into the already-mounted unloads dirs
(`${DOCKER_DB_UNLOADS_PATH}` → `/opt/zfin/unloads/db`, `${DOCKER_SOLR_UNLOADS_PATH}` →
`/opt/zfin/unloads/solr`). Everything downstream — `loaddb`, `loadsolr`, `getLatestSolrIndex`,
and so every `z build` phase — reads those mounts and is unaffected.

Left as-is deliberately: the `:rw` source mount (the whole point is editing the worktree) and the
shared gradle/maven/npm cache volumes. So `compile` is now credential-free, not fully isolated —
what a real sidecar would still add is restricted network egress, a minimal purpose-built
`~/.claude`, and unshared caches.


## Goal

Run Claude *inside* a per-feature container with `--dangerously-skip-permissions`
so it can work autonomously without permission prompts, while keeping the blast
radius contained to a disposable feature worktree instead of the host.

The bargain: skip-permissions is only acceptable if the environment is built so
the worst case is "a trashed, git-tracked worktree" — not "a compromised host"
or "an unwanted push."

## Critical anti-pattern: do NOT reuse the `compile` container

> **Superseded 2026-08-14** — the first two mounts below are gone (see the decision section
> above); this is the original reasoning, kept because it's why the hardening happened. What
> remains true: the `:rw` source mount and shared caches, so `compile` is credential-free but
> not isolated.

The existing `compile` service is the wrong host for this. It mounts:

- ~~`/var/run/docker.sock` → full control of the host Docker daemon = **root on
  the host**.~~ *(removed)*
- ~~the SSH agent socket (`${DOCKER_SSH_AUTH_SOCK}`) → **can push / SSH out** with
  your credentials.~~ *(removed)*
- the whole source tree `:rw`, plus shared maven/gradle cache volumes.

`--dangerously-skip-permissions` there was *not* sandboxed — it was more dangerous
than running on the host. A sidecar would still be a purpose-built, minimal container.

## What makes the container an actual boundary

- **Mount only the one feature worktree `:rw`.** Nothing else from the host
  filesystem.
- **No `docker.sock`.**
- **No SSH agent socket, no host credentials** (`~/.ssh`, `~/.aws`, gcloud,
  tokens beyond what Claude itself needs).
- **Only Claude's own auth** passed in (e.g. `ANTHROPIC_API_KEY`).
- **Restricted network egress** if we want to go further (allow the Anthropic
  API + package registries; deny the rest).
- Reaches the feature's `db`/`solr` over the Compose network for testing — that
  is the only other access it needs.

## Synergy with "human does the push"

We already prefer `git push` to be run by a human ([[git-push-manual]]). A
sidecar with **no push credentials** turns that preference into a *hard
guarantee*: Claude can't push because there's nothing to push with. The sandbox
enforces the policy structurally rather than relying on a soft rule.

Defense-in-depth option on top: a `PreToolUse` hook that blocks `git push` even
in skip-permissions mode (hooks still run under `--dangerously-skip-permissions`).

## Tradeoffs / open questions to resolve before building

- **Loses the host setup** — MCP servers, `~/.claude` config, tooling. We'd
  provision a minimal `~/.claude` into the image (which config, which MCP
  servers, if any).
- **Auth** — how Claude authenticates inside the container (API key vs other).
- **Build access** — does the sidecar need the gradle/maven caches (read-only?)
  to build/test in-container, or does it only edit files while a separate
  `compile`/`tomcat` deploy path handles builds? Keeping caches out is safer;
  mounting them read-only is a middle ground.
- **Network policy** — whether to actually enforce egress restrictions or just
  rely on the no-credentials posture.
- **Lifecycle / attach** — how you interact with it (`docker attach` / a wrapper
  that `exec`s in), and whether it's one sidecar per feature stack.

## Likely integration

An optional `claude` service in the feature stack, behind a `--claude` flag /
Compose profile in `docker/utils/new-feature.groovy`, so a feature can be brought up with
or without its own contained agent.
