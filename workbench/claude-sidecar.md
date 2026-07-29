# Claude sidecar container (future strategy)

Status: **not built** — a design we may adopt later. Captured so the reasoning
(and the safety gotchas) aren't lost.

## Goal

Run Claude *inside* a per-feature container with `--dangerously-skip-permissions`
so it can work autonomously without permission prompts, while keeping the blast
radius contained to a disposable feature worktree instead of the host.

The bargain: skip-permissions is only acceptable if the environment is built so
the worst case is "a trashed, git-tracked worktree" — not "a compromised host"
or "an unwanted push."

## Critical anti-pattern: do NOT reuse the `compile` container

The existing `compile` service is the wrong host for this. It mounts:

- `/var/run/docker.sock` → full control of the host Docker daemon = **root on
  the host**.
- the SSH agent socket (`${DOCKER_SSH_AUTH_SOCK}`) → **can push / SSH out** with
  your credentials.
- the whole source tree `:rw`, plus shared maven/gradle cache volumes.

`--dangerously-skip-permissions` there is *not* sandboxed — it's more dangerous
than running on the host. The sidecar must be a purpose-built, minimal container.

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
