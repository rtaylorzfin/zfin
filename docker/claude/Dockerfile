# Claude sidecar image: the compile toolchain (Java 21 / Gradle / Node) plus the Claude
# Code CLI. Built locally per-machine; used by the opt-in `claude` service in
# docker/docker-compose.claude.yml (added by `z feature new --claude`).
#
# It is FROM the compile image only to REUSE the build toolchain -- NOT its dangerous
# mounts. The service that runs this image (see the overlay) deliberately drops
# docker.sock / the SSH agent / host credentials, so `--dangerously-skip-permissions`
# stays contained to a disposable feature worktree. See workbench/claude-sidecar.md.
ARG ZFIN_RELEASE=main
FROM ghcr.io/zfin/zfin-compile:${ZFIN_RELEASE}

# The Claude Code CLI (Node-based; the base image ships Node 24 + npm). The global npm
# root (/usr/lib/node_modules) is root-owned and the base runs as `gradle`, so install as
# root, then drop back to `gradle` for runtime (matches the worktree + ~/.claude ownership).
USER root
RUN npm install -g @anthropic-ai/claude-code
USER gradle
