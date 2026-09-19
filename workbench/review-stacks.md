# Review stacks: hostname routing, freeze/thaw, and the Claude sidecar

Status: **scoping** — not built. Successor to the `preloaded-dev-stacks` work
(`reference/preloaded-dev-stacks.md`); closes several `TODO.txt` items outright.

The one-sentence version: a feature stack stops being "a Compose project on
`127.0.0.5:443` that you added to `/etc/hosts`" and becomes **a review
environment at `https://zfin-12345.review.<zone>`** that any teammate can open,
that can be *parked* to external storage when disk runs short, and that carries
its own contained Claude session from `new` through `freeze` to `rm`.

---

## 1. What changes for the user

```bash
z review up                              # once per host: the shared proxy + landing page

z feature new ZFIN-12345 -y --up         # as today, but no IP allocation for the web,
                                         #   no per-feature hosts entry, no lo0 alias
                                         # -> https://zfin-12345.review.zfin.test  (valid cert)

cd <worktree> && ./z run claude          # a shell in this stack's contained agent,
                                         #   exactly as `./z run compile` gives you one

z feature freeze ZFIN-12345              # park it: tar the data volumes + session to
                                         #   external storage, down -v, keep the worktree
                                         # -> frees ~28G; seconds if the DB was never written

z feature thaw ZFIN-12345                # bring it back exactly as it was

z feature ls                             # ... STATE column now: up | down | frozen
```

And someone who opens `https://zfin-99999.review.zfin.test` with no such stack
gets a page telling them how to make one — rather than a connection refused.

---

## 2. What already exists (do not redesign)

This feature is mostly **wiring**, because the previous branch left the right seams:

| Seam | Where | What it gives us |
|---|---|---|
| `VIRTUAL_HOST` / `LETSENCRYPT_HOST` on `httpd` | `docker/docker-compose.yml:157` | already the jwilder/nginx-proxy contract — the VMs' `ngproxy` (`nginx-proxy-config.md`) speaks exactly this |
| `StackConfig.host(slug)` | `lib/StackConfig.groovy` | the *single* line defining `<slug>.zfin.test`. Changing the zone is a one-line policy edit |
| `DOCKER_VIRTUAL_HOST` in the per-feature `.env` | `NewFeature.groovy:465` | already written per feature, already read by `FeatureList`/`FreshInstall` |
| tar capture into a tarball | `BuildPreloaded.groovy:212` | `docker run --rm -u 0 --entrypoint tar … czf` — the freeze half, already written |
| parallel volume pre-create + extract | `NewFeature.groovy:544` | the thaw half, already written |
| `!reset null` overlay idiom | `docker-compose.preloaded.yml` | how a review overlay un-publishes httpd's ports |
| external-network attach | `ZfinUtil.connectSharedData` | the pattern for joining a stack to a shared network |
| credential-free `compile` | `workbench/claude-sidecar.md` | no `docker.sock`, no SSH agent — the sidecar's hardest work is done |

`TODO.txt` items this closes: *hibernate/archive idle feature stacks* (§ freeze),
*reconcile new-feature with INSTANCES + zfin.properties* (§ 7), and the sidecar
design in `workbench/claude-sidecar.md`.

**Explicitly out of scope:** CoW clones (ZFS/btrfs), minimal-table-set DBs. Freeze/thaw
is this branch's answer to disk pressure.

---

## 3. Routing — one proxy, both environments

The convergence requirement ("same solution on the Mac and on a Linux VM") is what
picks the design. A shared proxy container satisfies it exactly: it is a container,
so it behaves identically on Docker Desktop and on a VM, and the VMs already run this
exact image.

### 3.1 The `zfin_review` stack

A new always-on Compose project, sibling of `zfin_shared` — `docker/docker-compose.review.yml`,
driven by `z review up|down|status` (new `lib/ReviewStack.groovy`, modelled on `SharedStack.groovy`):

```
  proxy     nginxproxy/nginx-proxy   :80 + :443 on the host
            + certs volume (§4), + /var/run/docker.sock:ro
  landing   nginx, static            VIRTUAL_HOST=*.review.<zone>   (§6)
  network   zfin_review_net (external)
```

**We always run our own proxy — never IT's.** On a VM this is a *second* proxy
alongside whatever `ngproxy` IT operates, not a set of entries added to theirs. See
§3.4 for why, and for what happens when IT's proxy changes or disappears. On the Mac
it is the identical compose project run locally. Same config both places; that is the
point.

> **The one docker.sock exception.** nginx-proxy watches Docker events to
> regenerate its config, so it needs the socket read-only. This is a deliberate,
> narrow exception to the no-socket stance from `claude-sidecar.md`: the socket is
> on a container that runs no project code and that the sidecar cannot reach. It is
> also how the VMs already run. If we want it gone later, the alternative is a
> separate `docker-gen` on a socket-proxy — more moving parts for the same exposure.

### 3.2 What a feature stack changes

A new overlay `docker/docker-compose.overlay-review-member.yml` (composed *after* the
preloaded/shared-db overlay, so `COMPOSE_FILE` grows one entry):

```yaml
services:
  httpd:
    ports: !reset null              # no more 127.0.0.X:443 — the proxy owns :443
    networks:
      default: {}
      review: {}
networks:
  review: { external: true, name: zfin_review_net }
```

Note what is *not* here: `VIRTUAL_PROTO`/`VIRTUAL_PORT` are left at the base file's
`https`/`443`. An earlier draft overrode them to terminate TLS once at the proxy; §3.4
explains why that turned out to be wrong.

`VIRTUAL_HOST` on `httpd` already comes from `DOCKER_VIRTUAL_HOST`, so it needs no change.

Consequences, all good:

- **No per-feature `:443` conflict**, which is the change that lets N stacks serve at
  once and makes the URL uniform and shareable.
  *Superseded:* an earlier draft kept one `hostctl` entry per stack, because
  `/etc/hosts` has no wildcard support. Per-stack entries are now **gone entirely** —
  wildcard DNS for the zone is a documented prerequisite (§3.2a), which resolves every
  stack that will ever exist and leaves nothing to clean up at teardown. The **`lo0`
  alias is still needed on macOS**, though, because `db`/`solr`/`tomcatdebug` still
  publish on `127.0.0.X`; only the *web* ports moved behind the proxy.
- **Jenkins needs no vhost of its own** — `httpd` already proxies `/jobs` to
  `jenkins:9499` (`conf-zfin.org:164`), so it is reachable at
  `zfin-12345.review.<zone>/jobs` on the app's own hostname. Its published
  `127.0.0.8:9499` port can stay for direct access. See §3.5: this is a minimalism
  decision, *not* a security one.
- `LOOPBACK_IP` survives, but only for the genuinely non-HTTP ports — `db` :5432,
  `solr` :8983, `tomcatdebug` :5000 — which a reverse proxy cannot serve and which
  host-side tooling (psql, the IDE debugger) still wants. Keep the existing allocator
  for those; it just stops being load-bearing for URLs.

> **Multi-homing note.** We attach `httpd` (not `tomcat`) to the review network.
> That is deliberate: `claude-sidecar.md`/`preloaded-dev-stacks.md` record that
> multi-homing `tomcat` is fatal — catalina sets
> `-Djava.rmi.server.hostname=$(container ip)` and a second IP leaks in as a bare
> java arg. Apache has no such behavior. Verify once with `z up` + a `docker inspect`
> of the two IPs, then move on.

### 3.2a Name resolution: why there is still one hosts entry per stack

`/etc/hosts` has **no wildcard support** — it is an exact-match table in both glibc's
`nss_files` and the macOS resolver, so `127.0.0.1 *.review.zfin.test` does nothing.
That used to mean one `hostctl` entry per stack. Those are now **removed**: wildcard DNS
is a prerequisite rather than an alternative, so there is one record per zone instead of
one entry per feature, and teardown has nothing to undo. `z feature new --review` warns
when the name would not resolve rather than provisioning something unreachable, and an
UNROUTED stack has no resolvable name at all — `z feature ls` reports its loopback URL.

Three ways out, in increasing order of how permanent they are:

**dnsmasq + `/etc/resolver` (macOS).** macOS routes DNS for a named domain and all its
subdomains to a nameserver of your choice, so a local dnsmasq answering the zone
removes the per-stack step entirely (`--review --no-hosts`):

```bash
brew install dnsmasq
echo 'address=/review.zfin.test/127.0.0.1' >> /opt/homebrew/etc/dnsmasq.conf
sudo brew services start dnsmasq                      # binds :53
sudo mkdir -p /etc/resolver
echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/review.zfin.test
```

*Verify with `ping` or `dscacheutil -q host -a name <host>`, NOT with `dig`/`nslookup`* —
those query DNS directly and bypass `/etc/resolver`, so they report failure while
every browser on the machine works. This costs an afternoon if you do not know it.

Not throwaway work either: when `*.review.cell.zfin.org` resolves publicly to the
review VM, a laptop needs those same names pointed at `127.0.0.1` instead, and a
per-domain resolver is exactly that override. Same mechanism, one line changed.

**sslip.io, for a zero-install test.** `<anything>.127.0.0.1.sslip.io` resolves to
loopback via public DNS, and since the zone is configurable this already works:
`ZFIN_REVIEW_ZONE=127.0.0.1.sslip.io z review up`. The cert naming still lines up
(nginx-proxy strips the leftmost label → `127.0.0.1.sslip.io.crt`, which is what
`z review cert` writes). Ugly URLs and a third-party dependency, so: a test, not a
destination.

**A public wildcard A record — the real answer.** Impossible for `.test` (reserved,
never delegated), but on a real zone `*.review.cell.zfin.org → <VM IP>` gives every
developer wildcard resolution with **zero per-machine setup**, and the
`*.local.review.cell.zfin.org → 127.0.0.1` companion (§4) does the same for laptops.
This is the version with no dnsmasq and no hosts file at all, and it is another
argument for prioritising the DNS ask in open question 2.

### 3.2b Which ports can be routed by hostname, and which cannot

Hostname routing works for HTTP. The three non-HTTP ports a feature stack publishes are
not equal, and the differences decide what still needs a loopback IP.

| | routable by name? | outcome |
|---|---|---|
| **solr** :8983 | **yes, already** | `httpd` proxies `/solr` → `solr:8983`, so `https://<stack>/solr` rides the review proxy. Host port **removed** |
| **db** :5432 | not as configured | keeps its `<loopback-ip>:5432` |
| **tomcatdebug** :5000 | no, and unfixably | keeps its port |

**Solr needed no work** — the `/solr` proxy already existed in `conf-zfin.org`, so the
published port was simply a second way to reach the same service. Dropped in
`docker-compose.preloaded.yml` (feature stacks only; the base `zfin_org` keeps its own).

**Postgres could be routed, but not by changing the auth method.** `POSTGRES_HOST_AUTH_METHOD`
governs authentication, not transport, so `trust` → `scram-sha-256` changes nothing a proxy
can see. The lever is TLS: a libpq connection only produces a ClientHello — and therefore
SNI — on the `SSLRequest` path, and these stacks run `ssl = off`. With `ssl=on` plus client
`sslmode=require`, libpq sends SNI by default (PG 14+; the stacks are on 18.4), and an
SNI-aware stream proxy could route without terminating TLS. A TLS-free variant also exists:
the `StartupMessage` carries the database name in cleartext, so a PG-aware pooler could route
on that.

**Decided: not worth it.** It needs a certificate and key per stack (right after the sidecar
work removed one for being unnecessary), a second proxy path — nginx-proxy is HTTP-only, so
a `stream` listener is a different mechanism — and a hostname→upstream map regenerated on
every stack lifecycle event, which is the `stacks.json` problem again in a form nginx-proxy
does not solve for you. Meanwhile `docker exec <stack>-db-1 psql` and `psql -h db` from
compile/claude already work, and the loopback IP costs one sudo prompt at provisioning.

**Revisit if a shared review VM happens.** The loopback trick is laptop-local, so several
developers wanting GUI database access over the network would make SNI routing the only way
to offer named per-stack access. Ties to open question 4.

**tomcatdebug is the one that truly pins the loopback IP.** JDWP opens with a fixed
handshake string and carries no routing information whatsoever, so no proxy can infer which
stack was meant. It is a `profiles: [debug]` service, so it only runs when asked for.

### 3.3 Independence from IT's proxy, and from nginx-proxy itself

Two separate questions, and it is worth keeping them apart: *do we depend on the
proxy IT runs on the VMs*, and *do we depend on nginx-proxy as a piece of software*.

**Do we depend on IT's proxy? No — by construction.** `z review up` brings up a proxy
*we* own. Note that IT's `ngproxy` attaches to `coral_default` and `cell_default`
(`nginx-proxy-config.md`) and is on no feature network, so it does not see feature
stacks today and will not start to. Three cases:

| If IT… | What happens |
|---|---|
| keeps `ngproxy` | Our proxy binds a non-privileged port; one static vhost in theirs forwards `*.review.<zone>` to it. **One entry, once** — not one per ticket. |
| swaps it for something else | Same: they forward one wildcard to us. We do not care what does the forwarding. |
| removes it entirely | We bind `:80`/`:443` ourselves. This is already the Mac configuration, so it is the *better*-tested path, not a fallback. |

The only thing we ever ask of an upstream proxy is one wildcard forward, and we can
run with no upstream at all.

**Do we depend on nginx-proxy?** Today, in four lines — `docker-compose.yml:157-160`
(`LETSENCRYPT_HOST`, `VIRTUAL_HOST`, `VIRTUAL_PROTO`, `VIRTUAL_PORT`). Every one of
the other thirteen references in the repo goes through `DOCKER_VIRTUAL_HOST`, which
is ours and means "the hostname this stack is served at" — vendor-neutral. That seam
already exists and is already right; this work should *preserve* it, not erode it.

So the rule for the implementation: **the vendor contract lives in
`review-member.yml` and the proxy service definition, and nowhere else.** Nothing in
`z`, in `StackConfig`, in the per-feature `.env`, or in the app may learn what
implements the routing. Swapping engines is then a contained edit:

| Engine | What the overlay says instead | Trade |
|---|---|---|
| nginx-proxy *(default)* | the 4 env vars | What the VMs already run; zero template maintenance; needs `docker.sock` |
| Traefik | `traefik.http.routers.*` labels | **Built-in DNS-01 wildcard ACME** — solves §4/Phase 7 outright; still `docker.sock` |
| Caddy (docker-proxy) | labels | Automatic HTTPS, smallest config; least familiar here |
| plain nginx, templated | *nothing* — config generated from `stacks.json` | No `docker.sock`, no vendor contract at all; we own ~30 lines of template and the reload |

The last row deserves attention, because §6 already makes it nearly free: we are
*already* writing `stacks.json` as the source of truth for the landing page. Teaching
`z` to also render an nginx `server{}` block per stack from that same file and reload
is a small addition, and it eliminates the `docker.sock` exception flagged in §3.1 —
the proxy would need no Docker access whatsoever.

**Recommendation:** default to nginx-proxy — it is what the team already runs and
understands, and it costs no template maintenance. But write `stacks.json` in Phase 2
*regardless of whether the landing page needs it yet*, because that is what makes the
independence real rather than asserted. It is cheap insurance, and Traefik's DNS-01
wildcard support is a live reason we might choose to switch on our own initiative,
not just under duress.

### 3.4 TLS: https all the way through — REVERSED from the first draft

The first draft of this section recommended terminating TLS once at the proxy and
speaking plain `http` to `httpd`, as "simpler". **Building Phase 1 showed that breaks
four paths, so we do not do it.** `VIRTUAL_PROTO`/`VIRTUAL_PORT` stay at the base
file's `https`/`443` and the member overlay does not touch them.

Why it breaks: `conf-zfin.org`'s port-80 vhost force-redirects the admin paths to
https whenever the request did not arrive over TLS —

```apache
    RewriteCond %{HTTPS} =off
    RewriteCond %{REQUEST_URI} ^/jobs        # and ^/build, ^/logs, ^/mailpit
    RewriteRule ^(.*)$ https://%{SERVER_NAME}/$1 [R=301,L]
```

With the proxy forwarding plain http, `%{HTTPS}` is always `off`, so those four paths
301 to https → the client comes back to the proxy on :443 → the proxy forwards plain
http again → **infinite redirect loop**. The same Locations also carry
`SSLRequireSSL`, which would reject the forwarded request outright.

Worth noting precisely, because the first instinct is that this is worse than it is:
`UseCanonicalName` is unset anywhere in the httpd config, so Apache's default `Off`
applies and `%{SERVER_NAME}` is taken from the client's `Host` header. The redirect
therefore points back at the review host, not at `zfin.org`. It is a loop, not a leak
to production.

Keeping the backend hop on https avoids all of it and requires **no Apache change**:
`httpd` already terminates TLS with its own dev cert, and nginx-proxy does not verify
backend certificates. The cost is one unverified hop across the compose bridge
between two containers on the same host, which is an acceptable trade for not
modifying a config file shared with staging and production.

This also disposes of the `X-Forwarded-Proto` question entirely — Apache sees a real
TLS connection, so the app builds `https://` URLs on its own, and mod_auth_openidc's
path-relative `OIDCRedirectURI` resolves correctly with no extra trust configuration.

#### 3.4a The same rule on the *upstream* leg — and the captcha loop that proved it

`docker-compose.overlay-review-upstream.yml` sets `VIRTUAL_PROTO: https` / `VIRTUAL_PORT: 443`
on our own proxy for the same reason, one hop earlier: those tell IT's `ngproxy` how to
reach *us*.

For a while they said `http`/`80` plus `HTTPS_METHOD: noredirect`. That was a workaround
for something else — a second container was also claiming `*.<zone>`, and nginx-proxy
takes **one** protocol per vhost (`groupByKeys $containers "Env.VIRTUAL_PROTO" | first`),
so the vote was being lost to the other claimant. Forcing both sides to http made the
mismatch go away.

It also broke logins, in a way that took a while to connect back. `HTTPS_METHOD` governs
what the upstream does for *clients* of the vhost, and `noredirect` means it will serve
plain http rather than redirecting to https. ZFIN's `JSESSIONID` is `Secure`, so a
request that arrives over http carries no session cookie at all: Tomcat mints a fresh
session, and anything session-bound silently restarts. The visible symptom was a captcha
that never accepted an answer — the captcha's expected value is stored against the
session id, so every solve was checked against a session that had just been created.

Only one container claims the wildcard now, so the vote is uncontested and the setting
went back to the default. The rule that falls out: **https end to end, at every hop.**
Each place it was relaxed, something session- or TLS-dependent broke, and never at the
point of the change.

### 3.5 Access control — DECIDED: IP allowlist + OIDC, no Jenkins vhost

This should have been in the first draft. It is a direct consequence of the feature
working, and it is the item most capable of making the whole thing a mistake.

`reference/preloaded-dev-stacks.md` carries an explicit **data-sensitivity
guardrail**: the preloaded images hold a *real loaded ZFIN database*, so they are
local-only — bare image names, no registry, no push path, "a structural choice, not
an oversight." That holds today only because a stack is reachable at `127.0.0.X` on
one laptop. **This feature's purpose is to undo exactly that.**

**Decision: an IP allowlist at the proxy, `DISABLE_OIDC=false` on the review host,
and no separate Jenkins vhost.**

#### Two controls covering two different things

**Correction to an earlier draft of this document:** it claimed `DISABLE_OIDC=true`
was the dev default and that the admin paths were therefore open. That was wrong — it
read *this workstation's* local config as if it were a project default. In fact
`docker/.env` is git-ignored (`.gitignore:54`), so it is per-host and untracked; the
shipped fallback is `DISABLE_OIDC: ${DISABLE_OIDC:-false}` (`docker-compose.yml:164`),
i.e. **absent means OIDC enabled, the more restrictive behavior**, and
`environment_linux:43` ships it commented out.

So `DISABLE_OIDC` is a per-host decision, and the review host gets to make it
deliberately. What follows from the `<Location>` blocks in `conf-zfin.org`:

| | protects | left open |
|---|---|---|
| **OIDC** (`DISABLE_OIDC=false`) | `/users/`, `/jobs`, `/solr`, `/logs`, `/mailpit` — the admin surfaces | the site itself, i.e. **the database** |
| **IP allowlist** at the proxy | everything, including the site | nothing, while you are inside the ranges |

They are complementary, not alternatives: the allowlist is what protects the *loaded
ZFIN database*, and OIDC is what protects the *admin endpoints* from anyone already
inside the allowed ranges. **Do both.**

#### `DISABLE_OIDC=false` on the review host is cheaper than expected

The obvious objection is that OIDC needs a redirect URI per hostname, and review
hostnames are per-ticket. It turns out Apache needs no per-hostname config at all:

```apache
    OIDCRedirectURI /users/callback        # conf-zfin.org:144 -- PATH-relative
```

mod_auth_openidc resolves a path-relative redirect URI against the incoming request's
host, so every review stack works from the same config. The only registration needed
is on the IdP side — Keycloak, at `bouncer.zfin.org/realms/ZFIN` — which supports
wildcard redirect URIs, so a single `https://*.review.<zone>/users/callback` entry
covers every stack that will ever exist.

**Register that on a dedicated review Keycloak client, not the production ZFIN one.**
A wildcard redirect URI widens the open-redirect surface of whatever client carries
it; scoped to a review-only subdomain on a review-only client that is acceptable, and
the review host's `.env` simply points `OPENIDC_CLIENTID`/`OPENIDC_CLIENTSECRET` at
it. Widening the production client to get the same effect is not worth it.

Note the propagation mechanism, because it is what makes this a one-time setting:
`NewFeature` copies the base `.env` for every key not in its `owned` set
(`NewFeature.groovy:447`), and `DISABLE_OIDC` is not owned — so whatever the review
host's base `.env` chooses is inherited by every feature stack provisioned there.

#### Implementation consequences

- **The allowlist is a Phase 1 deliverable, not a Phase 7 one.** It ships with the
  proxy. On `.zfin.test` the risk is dormant, but the control costs a single
  directive and adding it later means a window where it is missing.
- **It belongs at the proxy, not in `conf-zfin.org`.** Apache's config is shared with
  staging and production; the review posture is a property of the review host.
  Keeping it at the proxy also means it survives an engine swap (§3.3) as a generic
  reverse-proxy feature, and cannot drift per stack.
- **Set `DISABLE_OIDC=false` explicitly in the review host's `.env`** rather than
  relying on absence. The safe value and the intended value should be visibly the
  same thing.

### 3.6 The shared deployment space

The review host is **single-user for stack lifecycle** — one person runs
`z feature new` / `rm` — but the deployment lives under `/opt/<shared-space>` so
several people can work with the checkouts. Those are different questions and the
split is the useful part: nothing here needs multi-user *stack orchestration*, which
is where the hard problems would be.

What the shared filesystem does require:

**The main checkout must live in the shared space too, not just the worktrees.** A
git worktree's `.git` is a *file* pointing back at `<main-checkout>/.git/worktrees/<name>`.
If the main checkout sits in one user's home, every worktree in the shared space
breaks for everyone else. The whole tree — main checkout, `wt-*` worktrees, and the
freeze archive dir — goes under `/opt/<shared-space>`.

**Use `fishadmin` (gid 1076); it is already the established shared group.** The
compose file already adds it to `solr` so a non-root container can write the
group-owned unloads dir (`docker-compose.yml:121-123`). Reuse it rather than minting
a new group: `chgrp -R fishadmin`, setgid on directories (`chmod g+ws`) so new files
inherit the group, and `umask 002` for anyone working there.

**Containers write into the mounted worktree as their own UID.** `compile` runs as
`gradle`, so build output lands owned by that user and may not be group-writable by a
human. On macOS this is papered over by virtiofs; on a Linux review host it is real.
The fix is the idiom already in the file — `group_add: ["1076"]` on `compile` and
`claude`, plus `umask 002` in the login profile — so container-created files stay
group-writable.

**Git needs two settings** in the shared checkout: `core.sharedRepository=group` so
git itself creates group-writable objects, and per-user
`git config --global --add safe.directory <path>`, since git refuses to operate on a
repo owned by another user.

**`z feature rm` becomes other people's problem.** It is already destructive
(`down -v` plus `git worktree remove --force`), and in a shared space the worktree it
force-removes may hold someone else's uncommitted work. Record the provisioning user
in the per-feature `.env` — the file that already carries
`ZFIN_FEATURE_BRANCH_PREEXISTING` for exactly this kind of provenance — and have
`rm` say who created it and require confirmation when that is not you.

**One sidecar note:** `claude_home` is per-stack, not per-user, so on a shared stack
one person's session history is visible to the next. That is probably desirable — it
is how a handoff works — but it should be stated rather than discovered.

---

## 4. Names and certificates

Two questions, and they are separable: *what is the name* and *who signs it*. Keep
them separable in the implementation — that is what makes "start on `.test`, move to
`zfin.org` later" a config change instead of a migration.

**Make the zone a variable, not a constant.** `StackConfig.host(slug)` becomes:

```groovy
static String reviewZone() { System.getenv('ZFIN_REVIEW_ZONE') ?: 'review.zfin.test' }
static String host(String slug) { "${slug}.${reviewZone()}" }
```

The zone is then recorded in each feature's `.env` at provisioning time (as
`DOCKER_VIRTUAL_HOST` already is), so existing stacks keep their names when the
default moves.

### The options

**A. `*.review.zfin.test` + a local CA (mkcert).** Generate one wildcard cert
(`mkcert "*.review.zfin.test"`), drop it in the proxy's certs volume — nginx-proxy
falls back from `zfin-12345.review.zfin.test.crt` to the wildcard
`review.zfin.test.crt`, so one file covers every stack forever. `mkcert -install`
once per machine and the padlock is green.

- **+** No DNS ask, no public exposure, no key distribution problem, works today.
- **+** Resolution is one wildcard `hostctl`/dnsmasq entry per machine, not one per stack.
- **−** Every reviewer's machine needs the CA installed; a `.test` name is not
  routable, so a colleague opening your link needs their own hosts entry *and*
  network reachability to your box. Fine for a laptop, insufficient for "send the
  PR reviewer a link" on a shared VM.
- **− And "install the CA" is a bigger ask than it sounds on a SHARED host.** On one
  laptop the cert-generating machine and the browsing machine are the same, so
  `mkcert -install` is self-contained. On a review host they differ: the cert is
  issued there, so each teammate must import *that host's* `rootCA.pem`. Doing so
  means whoever holds the matching `rootCA-key.pem` can mint a trusted certificate
  for **any** domain, not just `*.review.<zone>` — i.e. can MITM that colleague's
  TLS anywhere. That is a trust relationship, not a setup step.

  So mkcert is honestly a **single-laptop** solution. On the shared host the
  defensible choices are to live with the browser warning until real certs land, or
  to prioritise option B. This sharpens open question 3 rather than settling it.

**B. `*.review.cell.zfin.org` + one real wildcard cert.** One DNS ask
(`*.review.cell.zfin.org A <cell IP>`) and one wildcard cert from the existing
ACME account (`ACME_CA_URI=https://acme-us.certinext.io/…` with the EAB creds in
`nginx-proxy/example.env`). A wildcard requires **DNS-01**, not the HTTP-01 that
`acme-companion` does per-host today — either via an acme.sh DNS API plugin if we
have API credentials for the zone, or a manual issuance mounted as a file.

- **+** Real padlock, zero per-machine setup, links are shareable.
- **+** *Converges the two environments perfectly*: the cert and the proxy config are
  identical everywhere; the only difference is what DNS answers — public DNS returns
  the cell VM, and a laptop's single `hostctl` wildcard entry returns `127.0.0.1`
  for the same names.
- **−** Needs the DNS record (your recommendation; you don't hold admin).
- **−** Puts a real private key on dev laptops. Mitigated by it being a *review-only*
  subordinate name — `*.review.cell.zfin.org`, never `*.zfin.org` — so the blast
  radius is names that only ever serve dev stacks.

**C. Per-host HTTP-01 via the existing `acme-companion`.** Reuses what the VMs do
verbatim (`LETSENCRYPT_HOST` is already wired). But it requires each review host to
be publicly reachable on :80, which a laptop is not — so it does not converge, and
it burns an issuance per ticket. **Reject.**

### Recommendation

**Build A, plan for B.** The proxy plumbing, the overlay, the landing page and
freeze/thaw are identical under both; the difference is a `ZFIN_REVIEW_ZONE` value
and which cert file sits in the certs volume. So:

1. Ship with `review.zfin.test` + mkcert. Nothing is blocked on anyone else.
2. In parallel, file the ask for `*.review.cell.zfin.org → <cell IP>` and find out
   whether we can get DNS-01 automation or only manual issuance on that zone.
3. When it lands: set `ZFIN_REVIEW_ZONE=review.cell.zfin.org` on the VM, drop in the
   wildcard cert, and new stacks get real names. Old stacks keep theirs.

A third name is worth asking for at the same time and costs nothing extra:
`*.local.review.cell.zfin.org → 127.0.0.1` in public DNS removes even the `hostctl`
step on laptops (this is the `localtest.me` trick). It needs its own SAN on the cert,
since a wildcard does not span a label.

---

## 5. Freeze and thaw — **BUILT**

The disk lever. `TODO.txt` already has the design and, more usefully, the key insight:

> **A feature that only changed CODE needs no DB archival at all** — its DB is
> byte-identical to the preloaded image, so "restore" is a fresh `up` that re-seeds
> from the image, and the only state to keep is the git branch.

That makes the common case *free*: a UI/JSP/React ticket freezes in seconds and
gives back ~28G, storing nothing but a manifest and a session transcript.

### Commands

```
z feature freeze <ticket> [--data|--reseed] [--to DIR] [--keep-up]
z feature thaw   <ticket>
z feature ls                       # STATE: up | down | frozen
```

### 5.1 Ordered shutdown — and the missing `depends_on`

**`docker compose stop` is not sufficient, and this is a real gap.** There is no
`depends_on` between the app tier and `db`/`solr` anywhere in
`docker-compose.yml` — the only one in the file is `filebeat → elasticsearch`. So
Compose gives no ordering guarantee, and a plain `stop` can tar a PGDATA that Tomcat
was still writing to.

Freeze must therefore sequence the shutdown itself:

1. stop the app + build tier — `httpd`, `tomcat`, `tomcatdebug`, `jenkins`,
   `compile`, `claude`
2. wait for those containers to actually exit
3. stop `db`, `solr`
4. **verify** — poll the db container's log for `database system is shut down` and
   confirm no `postmaster.pid` remains. Only then tar.

Step 4 is the one that must not be skipped for speed. Everything else in freeze is
recoverable by re-running it; a silently torn archive is discovered months later, at
thaw, when the branch it belonged to is gone.

The ordering is ZFIN service-role policy, so it belongs in `StackConfig` next to the
`DATA_SERVICES` / `APP_SERVICES` / `BUILD_SERVICE` lists that already live there —
as a `shutdownOrder()`, with `claude` added to the role lists.

Explicit sequencing in `z` is still needed for `compile` and `claude` (see below —
they stay out of the dependency graph on purpose), but the served tier should be
ordered by Compose itself.

### 5.1a Adding real `depends_on` — **BUILT**

**Decision: do it.** Compose here is **v5.3.1**, which makes this safe in a way it
would not have been on an older version.

The edges to declare — on the *served* tier only:

```yaml
  httpd:       { depends_on: { tomcat: { condition: service_started, required: false } } }
  tomcat:      # and tomcatdebug
    depends_on:
      db:      { condition: service_healthy, required: false }
      solr:    { condition: service_started, required: false }
  jenkins:     { depends_on: { db: { condition: service_healthy, required: false } } }
```

Four things make this work:

**`required: false` is what makes shared-db stacks survive it.** `docker-compose.overlay-shared-db.yml`
suppresses `db`/`solr` with a never-enabled profile. A hard `depends_on` would then fail
the whole stack — a dependency on a service no active profile enables is an error.
`required: false` is designed for exactly this: absent dependency, skip it silently;
present dependency, apply the condition. So **the shared-db overlay needs no change at
all**, which is a much better outcome than `!reset`-ing the edges back out in the overlay.

**Stop ordering only needs the edge, not the condition.** Compose stops in reverse
dependency order, and that is driven by the graph, not by whether the condition was
`service_healthy` or `service_started`. So we can pick the cheapest start conditions
that are defensible and still get the shutdown ordering freeze requires. `httpd` waits
only on tomcat *starting* (a 502 during warm-up is self-correcting and needs no
tomcat healthcheck); `solr` likewise, since the app degrades rather than fails
without it. Only `db` gets `service_healthy`, because Hibernate init genuinely cannot
proceed without it.

**`service_healthy` requires a healthcheck that does not exist yet.** There are **no
`healthcheck:` blocks anywhere in `docker-compose.yml`** today. `db` needs one:

```yaml
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d zfindb"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 60s
```

`StackConfig.dbHealthCheck()` already holds this probe as host-side argv. The compose
block and that method must not drift — make `StackConfig` the single source and have
it render both, in the same spirit as `APP_VOLS`. Generous `start_period`: a preloaded
db is up in seconds, but a base stack doing a cold init is not.

This is a real side benefit — `z up` can stop hand-polling for readiness, and cold
starts stop being flaky.

**`compile` and `claude` stay out of the graph — this is the sharp edge.**
`docker compose run <svc>` **starts its dependencies by default**. Declaring
`depends_on: db` on `compile` would silently change `./z run compile` — the most-used
command in the toolchain — from "starts nothing" to "boots a 19G Postgres." That is
actively harmful once freeze makes "this stack is down" a normal state: `./z run
compile -c "npm ci"` would thaw the world to install JavaScript. So the build tier is
excluded, and freeze sequences `compile`/`claude` explicitly. (If we ever want the
edge for correctness, the safety net is `--no-deps` in `StackOps.runExec` — but not
declaring it is simpler.)

**As built, and what verification showed.** The edges and the `db` healthcheck are in
`docker/docker-compose.yml`; `StackConfig.DB_PROBE` now holds the probe once in argv form
for the one case compose cannot cover (build-preloaded's throwaway postgres, started
outside compose, has no healthcheck to read). Confirmed: a preloaded stack resolves
`httpd→tomcat`, `tomcat→db(healthy)+solr(started)`, `jenkins→db(healthy)`, `compile→none`;
a shared-db stack still validates, so `required: false` does carry the profile-suppressed
case with no overlay change; and the db reports `healthy` ~10s after start.

**A live corroboration of §5.3.** Recreating the shared db under a running sharer — which
is what picking up the new healthcheck required — killed that stack's c3p0 pool outright:

```
FATAL: terminating connection due to administrator command
```

and it served 500s until its tomcat was restarted. That is precisely the hazard §5.3's
ordering rule exists to prevent, seen for real rather than reasoned about, and it raises
the stakes on `z shared freeze` stopping every app tier *before* touching the data tier.
`z shared up` now detects attached sharers and warns with the recovery command.

**Blast radius, stated honestly.** This file also drives the base and staging stacks,
so their startup ordering changes too. That is a change worth making on its own
merits — waiting for a healthy DB is what those stacks should already have been doing
— but it is a change, and the failure mode shifts: a `db` that never becomes healthy
now yields "dependency failed to start" instead of a Tomcat that boots and throws
Hibernate errors. The new message is better, but it is different, so it belongs in the
commit message and in `deploying-changes.md`.

### 5.2 Shared-DB stacks: freeze must be composition-aware

A `--shared-db` feature **has no `pg_data`/`solr_var` at all** —
`docker-compose.overlay-shared-db.yml` profile-suppresses them, and the real data lives in the
separate `zfin_shared` project. So freezing such a stack must not go looking for them,
and above all must not touch the shared copy: it is not that feature's data, and other
stacks are live on it.

The rule: **derive the volume set from the stack's data mode, never from a hardcoded
list.** `FeatureList.groovy:38` already makes exactly this determination
(`spec?.compose?.contains('shared-db') ? 'shared' : 'own'`); freeze reuses it rather
than re-deriving it. Then:

- capture `claude_home` only;
- `docker compose down -v` removes only *this project's* volumes — the shared ones
  are in project `zfin_shared` and are untouched;
- record `data: shared` in `freeze.json`, so thaw knows to bring the shared stack up
  and reconnect rather than to restore anything.

Two consequences worth stating plainly:

- **Thaw gains a dependency check.** Thawing a sharer while `zfin_shared` is down or
  frozen must fail with "run `z shared thaw` first", not with a stack that boots and
  cannot find `db`.
- **`down` on a sharer disconnects the shared containers from its network.** That is
  fine — they keep running on their own net — and `z feature rm` already exercises
  this path today, so freeze inherits tested behavior. Confirm Compose does not
  complain about removing a network with active endpoints.

### 5.3 `z shared freeze` / `z shared thaw`

Worth having, because the shared copy is the single biggest object on the host — one
~28G data tier rather than N of them. But it is a **host-level** operation, not a
per-feature one: every attached sharer stops working the moment it is frozen. The tool
should model that honestly rather than hide it.

```
z shared freeze [--to DIR] [--stop-sharers]
z shared thaw
```

**The guard, and the good news: the detection already exists.**
`SharedStack.groovy:63-69` finds the shared `db` container and lists the
`*_default` networks it is joined to — which *is* the set of features that would
break. Cross-referencing that against running containers per project (the same
`docker ps --format '{{.Label "com.docker.compose.project"}}'` query
`FeatureList.groovy:22` already uses) separates two cases that deserve different
treatment:

| Sharer state | Meaning | Behavior |
|---|---|---|
| network-attached **and running** | actively in use; freezing breaks it mid-flight | **error**, list them, exit non-zero |
| network-attached, **containers stopped** | `z stop`ped, not `down` — nothing to corrupt | warn, list them, proceed |
| not attached | already `down` | ignore |

**`--stop-sharers`, not `--force`.** The distinction matters: the flag should
*authorize the stopping*, not *bypass the check*. `--force` reads as "ignore the
safety", and a flag that skips a data-integrity check is a footgun someone will
eventually reach for out of impatience. `--stop-sharers` says what will happen, and
what it does is stop each sharer's app tier in §5.1 order before touching the data —
the correct action, performed on request, rather than a suppressed warning. Accept
`--force` as an alias if the muscle memory is worth it, but name the real thing.

Sequence, which is §5.1 applied one level up — *all* app tiers before *any* data tier:

1. enumerate attached features (`z shared status` already knows this) and apply the
   guard above;
2. for each, stop its app + build tier, in order, and wait;
3. stop the shared `db`/`solr` and verify the clean-shutdown marker;
4. capture `zfin_shared_pg_data` + `zfin_shared_solr_var`; `down -v` the shared project.

Thaw reverses it: restore the volumes, `z shared up`, then each sharer's `z up`
re-runs `connectSharedData` — which `StackOps` already makes idempotent for exactly
this restart case.

### 5.4 Which lever actually helps

These three cases reclaim very different amounts, and conflating them would lead
someone to freeze the wrong thing:

| Freezing… | Reclaims | Cost |
|---|---|---|
| an **own-data** feature | **~28G** | that one stack stops |
| a **shared-db** feature | ~1–2G (app volumes + caches) | that one stack stops |
| the **shared stack** | **~28G, once** | *every* sharer stops |

The middle row is the point of the whole design, not a disappointment: a shared-db
feature reclaims little **because `--shared-db` already avoided the cost.** Freeze is
not the disk lever for those stacks — it is just "release the app tier and park the
session."

This also partly answers open question 4. If most review stacks are read-mostly and
run shared, `z shared up` is the primary disk strategy and freeze is a secondary one,
mattering for own-data stacks and for parking an entire host.

### 5.5 `freeze`, step by step

1. **Stop in dependency order, and prove the DB is down** (§5.1) — a torn PGDATA in
   a tarball is the one unrecoverable failure here.
2. **Classify the data.** Default is to capture (safe). `--reseed` asserts "this
   stack never wrote to its DB" and stores nothing.
   *Auto-hint:* record `pg_current_wal_lsn()` at first boot in the `.env`, compare at
   freeze. It over-reports (autovacuum advances the LSN too) but only in the safe
   direction, so use it to *suggest* `--reseed`, never to choose it. Solr is only
   mutated by an explicit reindex, so treat it the same way with the same flag.
3. **Capture** `pg_data`, `solr_var` (unless reseeding) and `claude_home` (always —
   it is tiny) into `$ZFIN_ARCHIVE_DIR/<slug>/`, reusing `BuildPreloaded`'s tar
   invocation and `NewFeature`'s parallel-extract closure. **Lift both into
   `ZfinUtil`** so producer and consumer share one implementation, the way
   `StackConfig.APP_VOLS` already forces build-preloaded and new-feature to agree.
4. **Write `freeze.json`** next to the tarballs: slug, project, branch, HEAD sha,
   preloaded tag *and image ID*, which volumes were captured vs. marked reseed,
   sizes, checksums, `ZFIN_RELEASE`, zone, timestamp.
5. **`docker compose down -v`**, then stop.

What survives on local disk: the worktree, `docker/.env`, `.zenv`, and the branch.
That is a few hundred MB against ~28G reclaimed, and it is what makes freeze
*reversible in place* — the distinction from `z feature rm`, which is not.

### 5.6 `thaw`

Verify the manifest first, and **refuse a `reseed` thaw whose preloaded image ID no
longer matches** — if the image was rebaked, "re-seed from the image" silently means
different data. Then pre-create the volumes, extract in parallel, `up`, and the
review overlay reattaches it to the proxy network automatically.

### 5.6a As built, and what is verified

`lib/FeatureFreeze.groovy` + `lib/FeatureThaw.groovy`; the tar capture and the parallel
restore moved into `ZfinUtil.captureVolume`/`restoreVolumes`, and `BuildPreloaded` and
`NewFeature` now call those — so a preloaded snapshot and a freeze archive are produced
and restored by one implementation, as §5.5 asked. `frozen` is derived (an archive exists
AND the project has no containers), not recorded, so thawing flips it back with no marker
to go stale.

**Also archived: the app volumes.** §5.5 said only `pg_data`/`solr_var` need archiving
because `www_data`/`catalina_base` are "regenerable via a redeploy". True, but a redeploy
is 10–20 minutes, and for a `--shared-db` stack those volumes are the *entire* content —
freezing one would otherwise reclaim ~1G and cost a full rebuild to undo. At ~0.5G they
are cheap to keep, and keeping them is what makes thaw "back as it was" rather than "back
to a build". Caches stay opt-in (`--caches`): large, and genuinely regenerable.

Verified end to end on a `--shared-db` stack: freeze archived 0.5 GB in 27s and reclaimed
every volume; `z feature ls` and `stacks.json` both reported `frozen`; the landing page's
frozen branch rendered for the first time (it had been unreachable) and offered
`z feature thaw zfin-demo`; thaw restored four volumes in ~11s, reconnected the shared
data tier, and served the real application again 5s later. Guards check out too: thaw onto
a stack with live volumes refuses, and a missing tarball is caught before anything is
restored.

**Own-data path, measured.** A throwaway stack with its own db+solr (pg_data 15.95 GB,
solr_var 5.71 GB):

| | time | archive |
|---|---|---|
| freeze (full, pigz) | **1m 21s** | 6.9 GB |
| thaw (full, pigz) | 1m 58s | — |
| freeze (full, plain tar) | 2m 12s | 21.2 GB |
| thaw (full, plain tar) | **55s** | — |
| freeze `--reseed` | 27s | 0.5 GB |
| thaw `--reseed` | 1m 1s | — |

(Compressed, before the default changed: freeze 9m 08s → 6.4 GB, thaw 1m 49s.
`z shared freeze` 11m 42s → 7.6 GB, `z shared thaw` 2m 31s.)

### Compression: why the default adapts

Compression was the entire cost of a slow freeze, and the fix turned out to be *which*
gzip, not *whether*. Full 21.7 GB stack, round trip:

| | freeze | thaw | round trip | archive |
|---|---|---|---|---|
| none | 132s | **55s** | **187s** | 21.2 GB |
| **pigz -1** | **81s** | 118s | 199s | **6.9 GB** |
| gzip -1 | 270s | 114s | 384s | 6.9 GB |
| gzip -6 (tar's `czf` default) | 548s | 109s | 657s | 6.4 GB |

tar's own gzip is single-threaded, so a 10-core machine ran the whole capture on one core
at ~29 MB/s. This was never a two-pass "write then compress" — tar runs the compressor as
a *filter*, so only compressed bytes reach disk. One pass, rate-limited by the compressor.

**pigz inverts the conclusion.** With parallel gzip, compressing is *faster to freeze than
not compressing* (81s vs 132s): writing 14 GB fewer bytes more than pays for the CPU. The
round trip is within 6% of plain tar for a third of the size, and the time it saves lands
on freeze — when you want the disk back now — while the cost lands on thaw, when you are
sitting down to work anyway.

So the default **adapts**: compress when the tar image has `pigz`, plain tar when it does
not, because that is exactly what decides which is cheaper. `--compress` / `--no-compress`
force either. `pigz` is now in `docker/base/Dockerfile`; `ZfinUtil.hasPigz()` probes the
image at runtime and falls back, so an image built before that line still works. When
compressing, level `-1` — `-6` costs another 87s for 0.5 GB.

`build-preloaded` keeps compressing unconditionally: its output is baked into image layers
where size is the point, and it runs once per machine rather than per park.

Restored data verified, not assumed: 205,086 markers and 67,744 publications in the
thawed database, 1,345,826 documents in the Solr index, and the application serving
again five seconds after thaw. The `--reseed` round trip re-seeded 21.7 GB from the
preloaded image and produced the same marker count — the code-only fast path works.

**Two real bugs, both found only by running it.**

*The clean-shutdown check could never pass.* `ZfinUtil.captureOutput` discards stderr,
`docker logs` emits a container's stderr on its own stderr, and postgres logs everything
there — so the check read an empty string. It failed open in `z feature freeze` (a
"could not see the marker" note) and, once made fatal, failed closed in `z shared freeze`,
refusing to archive a database that had in fact shut down perfectly. Hence
`captureOutputMerged`. A safety check that cannot pass is worse than none, because it
teaches you to ignore it.

*Compose's 10s stop timeout is too short for this database.* Past it docker sends
SIGKILL, which leaves a PGDATA needing crash recovery — recoverable, but not something to
bake into an archive. The data tier now stops with `-t 120`.

**Deferred: the WAL-LSN hint.** §5.5 proposed comparing `pg_current_wal_lsn()` against a
baseline recorded at first boot to *suggest* `--reseed`. Recording that baseline means
touching the boot path and dealing with a not-yet-healthy database, so it is not built:
freeze defaults to archiving (the safe direction) and `--reseed` is an explicit, warned
assertion. A half-working heuristic here would be worse than none.

### 5.7 Storage

`ZFIN_ARCHIVE_DIR`, defaulting under the already-mounted `${DOCKER_RESEARCH_PATH}`.
Archive **at rest** on NFS/USB is fine; never point a running PGDATA at it —
fsync and locking semantics will corrupt it (already noted in `TODO.txt`).

Rough numbers, from the measured image sizes: ~19G PGDATA + ~9G index, gzip to
roughly 5–8G, minutes each way. Worth benchmarking `zstd` against `gzip` in the
compile image — if it is there, it is several times faster at a better ratio, and
freeze/thaw wall-clock is the whole user experience of this feature.

---

## 6. The "no such stack" landing page — **BUILT**

A `landing` service on the review stack holding `VIRTUAL_HOST=*.review.<zone>`.
nginx-proxy prefers an exact vhost over a wildcard, so a live stack always wins and
the landing page catches everything else — including frozen stacks, which have no
container at all.

The page reads `location.hostname`, pulls the ticket out of it, and renders:

- **No review stack for ZFIN-12345** — with `z feature new ZFIN-12345 -y --up --deploy`
  ready to copy.
- …or **Frozen** — with `z feature thaw ZFIN-12345`, and when it was frozen.
- A list of the stacks that *are* up, as links.

**As built.** `docker/review-landing/{index.html,default.conf}`, mounted read-only into a
`landing` service on the review stack, holding `VIRTUAL_HOST=*.<zone>,<zone>` so it also
answers the apex as a directory of stacks. `try_files … /index.html` means a deep link
into a stack that is gone still lands on the explanation rather than a 404.

Verified in a browser once wildcard DNS was in place: the unprovisioned case, a deep
link, the apex, and a live stack still winning over the wildcard — plus full TLS
verification with no `-k`, so the mkcert chain is genuinely trusted.

Two things the build corrected:

- **Wildcard DNS is a hard prerequisite, not a nicety.** Without it an unprovisioned
  name is NXDOMAIN and the browser never reaches the page at all — so §3.2a's dnsmasq
  setup is part of this phase's value, not an optional extra. (On this host the plain
  `nameserver 127.0.0.1` form works: Homebrew's caveat about loopback resolvers applies
  only when dnsmasq runs on a non-53 port, and the default config sets none.)
- **The zone cannot be derived by splitting the hostname.** `review.zfin.test` split on
  its first dot yields slug `review` in zone `zfin.test`, so the apex rendered as a
  missing stack called "review". The zone is only knowable from the inventory, which
  ships it — so that decision waits for the fetch, with the first-label split as the
  no-inventory fallback.

To know any of that it reads a small `stacks.json` from a **host bind-mount**
(`$ZFIN_REVIEW_STATE_DIR`) that `z` writes on `new`/`freeze`/`thaw`/`rm`. A bind
mount, not the Docker socket — the landing page is the most exposed thing in the
system and should know nothing it was not handed. `z feature ls` should read the
same file, so there is one source of truth for stack state.

---

## 7. The Claude sidecar — **BUILT**

`claude-sidecar.md` did the hard part already: `compile` has no `docker.sock` and no
SSH agent, so **"Claude cannot push" is now structural, not a preference.** What a
sidecar still adds is a private `~/.claude`, a distinct identity, restricted egress,
and — the new reason — *a session that freeze/thaw can carry*.

### Shape

A `claude` service in `docker/docker-compose.yml` behind `profiles: [claude]`,
built from `docker/claude/Dockerfile` **on the compile image**:

```
FROM ghcr.io/zfin/zfin-compile:${ZFIN_RELEASE}   + npm i -g @anthropic-ai/claude-code
  mounts   ${DOCKER_SOURCE_ROOTS_PATH}:…:rw      the ONE feature worktree
           claude_home:/home/gradle/.claude      per-feature session state
           www_data, catalina_base, caches        so `gradle dirtydeploy` works in here
  network  the feature's default network          reaches db/solr/tomcat
  secret   an API key / token from a host file, ro
  NOT      docker.sock, SSH agent, ~/.ssh, any other worktree
```

Building on `compile` rather than a bare node image is the call worth being explicit
about. A minimal image is more isolated but cannot compile, test, or deploy — which
makes the agent useless for the work we actually want it doing, and pushes every
build back to a human at the host shell. Since `compile` is already credential-free,
extending it costs little isolation and buys a fully capable agent. Tomcat restarts
stay a host operation (`z restart tomcat`), which `buildfiles/tomcat.xml` already
enforces with a `<fail>`.

### Entry point — no new command

The agent is **a service, so it is reached with the verb that already reaches
services**:

```bash
cd <worktree>
./z run claude          # cf. ./z run compile
```

This needs no change to `z`. `StackOps.parseSvc` (`StackOps.groovy:28`) already takes
the first bare word as the service name and falls back to `StackConfig.BUILD_SERVICE`,
so `run`/`exec`, the `-u root` handling, and the `-c "…"` form all work on `claude`
the day the service exists. The complete cost is the service definition plus adding
`claude` to the service list in `z-completion.bash:22`.

An earlier draft of this document proposed a bespoke `z feature claude <ticket>`.
That was wrong: it invents a second way to enter a container, and it puts a service
name in the `feature` namespace, which is for *lifecycle* operations on a stack.
Dropped.

Two consequences of using plain `run` worth being deliberate about:

- **`run --rm` is the right semantics, not a compromise.** It gives a throwaway
  container, but the session *state* lives in the `claude_home` volume, so history
  survives and `claude --continue` resumes it. Nothing lingers between sessions, and
  the thing freeze captures is the volume either way. (`./z exec claude` still works
  if someone has brought the service up.)
- **A login shell, not an auto-launched agent.** `./z run compile` gives you a shell;
  `./z run claude` should give you a shell too, and you type `claude`. Zero magic and
  zero special-casing in `z`. If typing it proves tiresome, the place to fix that is
  the *image's* profile — auto-exec guarded on an interactive shell
  (`[[ $- == *i* ]]`) so `./z run claude -c "…"` keeps working — not in the tooling.

**To verify at implementation time:** the service sits behind `profiles: [claude]`, and
`docker compose run <svc>` is documented to enable a service's own profile implicitly.
Confirm that holds for the Compose version in use before relying on it; the fallback
is dropping the profile and relying on `claude` simply never being in the default
`up` set.

### As built — auth, and why it shapes everything else

`docker/claude/{Dockerfile,claude_profile,settings.json,block-push.sh}` plus a `claude`
service behind `profiles: [claude]`. Entered with `./z run claude`, no new command.

**Auth: `claude setup-token` on the host, mounted read-only.** Three options were on the
table — log in inside the container, a host token, or an API key — and the deciding
argument was archiving, not convenience. A container-side `claude auth login` writes its
credentials into `~/.claude`, which IS the `claude_home` volume, which `z feature freeze`
archives: every freeze would then carry a working login to whatever external storage it
was parked on. Keeping the token on the host means `claude_home` holds only session
history, and archives stay credential-free.

The token is `$ZFIN_CLAUDE_TOKEN_FILE`, default `~/.zfin/claude-token`, bind-mounted at
`/run/secrets/claude-token:ro` and exported as `CLAUDE_CODE_OAUTH_TOKEN` by the login
profile. Note it defaults under `$HOME`, **not** `/opt/zfin` like the review dir — and the
contrast is deliberate: review certs are shared host infrastructure every developer must
see identically, while a Claude token is a personal credential that must not be
group-readable on a shared host (§3.6).

`z run claude` creates the file empty (0600) when missing, because Docker silently creates
a *directory* for an absent bind source; the container then explains how to fill it rather
than failing cryptically.

Verified in the stack: `claude 2.1.273`, settings seeded, `psql -h db` returns 205,086
markers, gradle and node present — so the agent can genuinely build and test rather than
handing every build back to a human. And confirmed absent: `docker.sock`, `SSH_AUTH_SOCK`,
any `~/.ssh` content.

Three things the build got wrong first, all worth keeping in the record:

- **The native binary was missing.** A global root `npm install -g` does not reliably run
  the postinstall that fetches it, leaving a CLI that errors on every invocation. The
  Dockerfile now runs `install.cjs` explicitly.
- **`~/.claude` must exist in the image.** Docker seeds a new named volume from the image's
  content at the mount path *including ownership*; when the path is absent it creates the
  mount point as root, and the unprivileged user cannot write its own session directory.
- **The push hook's first two patterns were both wrong** — one allowed `git -C /path push`
  (only flags were permitted between the words, and `-C` takes a value), the next then
  rejected plain `git push`. Now twelve cases pass, and the pattern errs toward
  over-matching on purpose: a denied `git log --grep push` is an annoyance, a push slipping
  through is not.

### Layers on top

- **A `PreToolUse` hook** baked into the image's `~/.claude/settings.json` blocking
  `git push` — hooks still run under `--dangerously-skip-permissions`. Defense in
  depth over a guarantee we already have structurally.
- **Egress restriction (phase 2).** The residual risk of
  `--dangerously-skip-permissions` is not the filesystem any more, it is the network.
  Mechanism that works identically on both platforms: put `claude` on an
  `internal: true` network plus an allowlist proxy (tinyproxy/squid) reachable at
  `HTTPS_PROXY`, permitting `api.anthropic.com`, npm, Maven Central and github.com.
  Worth doing, not worth blocking the rest of this on.

### Session archival

This is why the sidecar and freeze belong in one scope: with `~/.claude` as a named
volume, **archiving a session is just another volume capture** — no special case.

- `z feature freeze` captures `claude_home` unconditionally (it is megabytes).
- `z feature rm` captures it *before* `down -v`, on by default, since that is the
  only moment the history still exists.
- `z feature session export <ticket>` writes the raw `.jsonl` transcripts plus a
  rendered markdown summary into the archive dir, so the history outlives the volume.

One thing not to miss: a dev using **host** Claude Code against the worktree (which
is how this very document is being written) has their session in
`~/.claude/projects/<path-with-slashes-turned-to-dashes>/`, not in the volume. The
archive step should capture both, keyed on the worktree path.

---

## 8. The cross-cutting problem: `DOMAIN_NAME` — **BUILT**

Worth flagging because this feature is what makes it visible. `common_properties`
sets `DOMAIN_NAME: "zfin.org"` (`all-properties.yml:128`) with no instance override
anywhere, and `NewFeature` copies the base `.env` **without** changing
`DOCKER_INSTANCE` — so every feature stack generates `coral`'s properties and emits
absolute links to `https://zfin.org/...` from `ZfinProperties`, `EntityPresentation`,
password-reset mail, and the ontology loaders. Today nobody notices. The moment we
hand people a shareable review URL, they will.

`TODO.txt` already asks the question ("does a feature need its own instance?"). This
is the answer, and it is small:

- Add a `review` instance to `instances.properties` with
  `instance_overrides.review: { DOMAIN_NAME: "${env.DOCKER_VIRTUAL_HOST}" }` —
  `PropertiesProcessor` already resolves `${env.X}` (`PropertiesProcessor.groovy:365`).
- Have `NewFeature` write `DOCKER_INSTANCE=review` into the per-feature `.env`.
- Note the sharp edge: `${env.X}` **throws** when the variable is unset, so the
  `review` instance is only usable where `DOCKER_VIRTUAL_HOST` is exported — which is
  true in every compose service, and is exactly the containment we want.
- Pick a distinct `PRIMARY_COLOR` while we are there; every instance has one, and a
  visually distinct banner is worth having.

### As built

Named **`feature`**, not `review`: it applies to every per-feature stack, routed or not,
and the routed ones are a subset. `commons/env/all-properties.yml` gains
`instance_overrides.feature` (DOMAIN_NAME from `${env.DOCKER_VIRTUAL_HOST}`,
PRIMARY_COLOR `#6a1b9a`), `NewFeature` writes `DOCKER_INSTANCE=feature`, and
`StackConfig.FEATURE_INSTANCE` holds the name.

Diffing generated properties for `coral` vs `feature` showed the change is exactly
DOMAIN_NAME, PRIMARY_COLOR, INSTANCE and the email set — plus `FTP_ROOT`/`TARGETFTPROOT`,
which are `${base_path}/ftp/test/${env.INSTANCE}` and so move with any instance rename.
Benign, and all feature stacks share that path exactly as they previously shared coral's.

**The email override — and how far it actually matters.** An instance *absent* from
`email_overrides` uses the REAL addresses, that section's own documented behaviour, so a
`feature` instance without an entry resolves `CURATORS_AT_ZFIN` to `curators@zfin.org`.
Reproduced accidentally while testing, and fixed with a `feature: informix@zfin.org` entry.

An earlier draft called this a near-miss on mailing a live distribution list. **That was
overstated**, and the correction is worth recording because it changes what the override
is for. Mail cannot leave a dev stack:

- `SMTP_HOST` is `mailpit` for every development-environment instance — it is the base
  value, overridden to `smtp.uoregon.edu` only in `prod_defaults`;
- the mailpit service declares no `MP_SMTP_RELAY_*`, so it is a pure sink: it accepts and
  stores, and forwards nowhere;
- the one sender that bypasses `SMTP_HOST` (`MailXMailSender`, which shells out to
  `mailx`) is selected nowhere — `EMAIL_SENDER_CLASS` is `IntegratedJavaMailSender` — and
  `mailx` is not installed in the containers.

So the real consequence of the missing entry was messages *addressed* to curators sitting
in mailpit, not delivered to them. The entry stays regardless: it is one line, every one
of the other dev instances has one, and a wrong To: address in the mailpit UI is its own
small confusion. Hygiene and consistency, not a security barrier.

**Cross-branch guard.** A branch cut before this change has no `feature` instance, so
writing `DOCKER_INSTANCE=feature` into its stack would produce exactly that undefined-
instance fallthrough. `NewFeature` therefore checks the WORKTREE's own
`all-properties.yml` for the `${env.DOCKER_VIRTUAL_HOST}` marker and only claims the
instance when the branch defines it, otherwise leaving DOCKER_INSTANCE inherited and
saying so.

**Migration.** `home/WEB-INF/zfin.properties` is git-ignored and generated by the first
login shell in `compile`, so NEW stacks pick this up automatically. An EXISTING stack has
a file generated under its old instance — and `INSTANCE` is re-exported from that file by
`load-properties.bash` (`env-exports.properties:41`), so it is self-perpetuating. Delete
it and re-enter the container, then redeploy for the running webapp to serve the new
values.

---

## 9. Phasing

Each phase is independently useful and independently shippable.

| # | Phase | Contents | Size |
|---|---|---|---|
| 1 | **Proxy + routing** — **BUILT** | `docker-compose.review.yml`, `ReviewStack.groovy`, `z review up/down/status`, `review-member.yml` overlay, `ZFIN_REVIEW_ZONE`, mkcert wildcard, one wildcard hosts entry, **IP allowlist + `DISABLE_OIDC=false` (§3.5)**. Vendor contract confined to the overlay (§3.3) | M |
| 2 | **Landing page + `stacks.json`** — **BUILT** | `landing` service, wildcard vhost, `stacks.json` written by `z` on every lifecycle event, `FeatureList` reads it. Ship the JSON even if the page slips — it is what makes §3.3 real | S |
| 3 | **`DOMAIN_NAME` / `feature` instance** — **BUILT** | §8; independent of everything else and fixes a latent bug | S |
| 3b | **`depends_on` + db healthcheck** — **BUILT** | §5.1a; independent of freeze, improves every stack's cold start. Land before 4 | S |
| 4 | **Freeze / thaw** — **BUILT** | lift tar+restore into `ZfinUtil`, `StackConfig.shutdownOrder()` + clean-shutdown verify, `FeatureFreeze`/`FeatureThaw` (composition-aware), manifest, WAL-LSN hint, `frozen` state | L |
| 4b | **`z shared freeze/thaw`** — **BUILT** | §5.3; same machinery one level up, plus the running-sharer guard + `--stop-sharers`. Do after 4 | M |
| 5 | **Claude sidecar** — **BUILT** | `docker/claude/`, `claude` profile service (reached by the existing `./z run claude`), completion entry, push-blocking hook | M |
| 5b | **Shared-space setup** | §3.6: `fishadmin` setgid layout, `group_add`/umask on compile+claude, git `sharedRepository`, creator recorded in `.env` for `rm` | S |
| 6 | **Session archival** | capture `claude_home` in freeze + rm, `z feature session export`, host-side session dir | S |
| 7 | **Real certs** | `ZFIN_REVIEW_ZONE=review.cell.zfin.org`, wildcard cert, DNS | S (blocked on DNS) |
| 8 | **Egress allowlist** | internal network + allowlist proxy for `claude` | M |

Phase 1 is the keystone — 2, 4 and 5 all assume it. 3 can land first, on its own.

### Phase 1 as built

Files: `docker/docker-compose.review.yml`, `docker/docker-compose.overlay-review-member.yml`,
`docker/utils/lib/ReviewStack.groovy`; `StackConfig` gained the review zone/net/dir
policy; `z`, `NewFeature` (`--review`/`--no-review`) and `z-completion.bash` wired up.

**Proxy state lives in `$ZFIN_DEV_ROOT/review/{certs,vhost.d}`**, overridable with
`$ZFIN_REVIEW_DIR`. It was briefly `~/.zfin-review`, which was wrong on the one axis
that matters here: `$HOME` is per-*user*, and a review host is shared (§3.6). With one
proxy running off a bind mount from the first developer's home, a second developer's
`z review cert --force` would write a cert the proxy never sees — state split in
silence. `/opt/zfin` is where ZFIN already keeps host-level state (`catalina_bases`,
`source_roots`, `unloads`, `www_homes`), so this is the existing neighbourhood. There
is deliberately **no fallback** when the directory is missing: `z review` prints
`sudo install -d -g fishadmin -m 2775 <that dir>` and stops, because a fallback
is exactly how the per-user split would return.

Verified end to end on this host, with a throwaway backend rather than a 28G stack:

- the member overlay resolves as intended — httpd's `ports` dropped, `default` +
  `review` networks attached, `VIRTUAL_PROTO: https` preserved;
- a container on `zfin_review_net` carrying `VIRTUAL_HOST` is routed, and the wildcard
  cert is served for it (`CN=*.review.zfin.test`);
- the **allowlist is actually enforced** — narrowing `ZFIN_REVIEW_ALLOW` to a range
  excluding loopback turns a working request into a `403`;
- the **port preflight fires**: with four non-review stacks holding `127.0.0.X:443`,
  `z review up` refuses and names them rather than failing inside compose;
- the `DISABLE_OIDC=true` warning fires on this host (§3.5).

**Phase 2's mechanism is also confirmed**, which de-risks it: a container holding
`VIRTUAL_HOST=*.review.zfin.test` catches unknown stacks, *and* an exact vhost still
beats the wildcard. Until that landing container exists, an unknown name fails the TLS
handshake (no default server) rather than returning a 503 — so the landing page is
what makes the "no such stack" case presentable at all.

**Not retrofittable:** `--review` is decided at provisioning. An existing stack's
compose file list is frozen in its `.zenv` bundle, and its `.env` holds the loopback
hostname. Moving one across means `z feature refresh` plus an `.env` edit — or just
re-provisioning it, which is cheap for a code-only feature.

---

## 10. Open questions

Ordered by what blocks what. Resolved items have moved into the sections above:
access control (§3.5), the Jenkins vhost (§3.5), `depends_on` (§5.1a), the shared
space (§3.6), and the sidecar entry point (§7).

Settled since the first draft: the review host **builds as well as serves**, so
§3.6's shared-space work (container UIDs, group-write) is in scope and Phase 5b
stands.

**Blocking — answer before Phase 7 puts a public name on this**

1. **What are the allowlist ranges?** §3.5 settles the mechanism, not the contents.
   Campus CIDRs, the VPN pool, both? And is there anyone who needs access from
   outside them — which is really the question of whether basic auth is needed as a
   second layer rather than a follow-up.
2. **DNS.** Is `*.review.cell.zfin.org → <cell IP>` obtainable, and does anyone hold
   API credentials for that zone (DNS-01 automation), or is manual issuance the
   ceiling? If the allowlist makes this effectively internal anyway, a split-horizon
   or internal-only zone may be both safer and easier to obtain than a public record.
3. **Wildcard key on laptops.** Acceptable for a review-only subordinate name? If
   not, laptops stay on `.test` + mkcert permanently and only the review host gets
   real certs — which still converges the tooling, just not the cert story.
4. **One wildcard forward from IT**, if they keep a proxy in front (§3.3). The only
   thing we ever need from them; decides whether our proxy binds `:443` directly or
   sits behind theirs.

**Shapes the priority order**

5. **Is `--shared-db` the primary disk strategy, with freeze secondary?** §5.4 argues
   it may be. If most review stacks turn out read-mostly, making `--shared-db` the
   *default* for new features could matter more than Phase 4, and would reorder the
   plan.
6. **Can a dedicated review client be created on Keycloak** (`bouncer.zfin.org`)
   with a `https://*.review.<zone>/users/callback` wildcard redirect URI? §3.5 makes
   this the difference between OIDC-protected admin endpoints and an allowlist that
   has to carry the whole load alone. Internal ask, not an IT-DNS-level one.

**Operational, answerable during implementation**

7. **Does `z review up` survive a reboot?** The proxy is now infrastructure for every
   stack on the host; it wants `restart: unless-stopped`, which currently only
   `mailpit` has. Same question for the `zfin_shared` stack.
8. **Archive retention.** Frozen stacks accumulate on external storage indefinitely.
   Needs a listing (`z feature ls` showing frozen entries with age and size) and a
   prune — plausibly tied to `feature-lifecycle.md`'s definition of done, i.e. "this
   branch is merged, why is it still frozen?"
9. **Concurrency.** Two freezes at once, or a freeze racing `z feature new`, want a
   lockfile — more likely on a shared host than a laptop. Cheap to add, unpleasant
   to debug without.
10. **Agent auth in the sidecar.** API key vs. OAuth token, and where the host keeps
    it so it is mounted read-only rather than baked. On a shared host, also *whose*
    credential it is and whether usage is attributable.
11. **`zstd` in the compile image?** Decides whether freeze/thaw is "a couple of
    minutes" or "go get coffee", which is most of how this feature feels to use.

---

## 11. Follow-ups this scope deliberately leaves alone

- **Auditing which hosts actually set `DISABLE_OIDC=true`.** The shipped fallback is
  the safe one (§3.5), and `.env` is per-host and untracked — which also means nobody
  can see at a glance which dev boxes have opted out. Not this scope's problem, but a
  one-off sweep would be worth doing.
- **`depends_on` for `compile`/`claude`** (§5.1a), which would need `--no-deps` in
  `StackOps.runExec` to preserve today's `z run` behavior.
- **`z feature isolate-db`** — promoting a `--shared-db` feature to its own copy when
  it turns out to need writes. Already in `TODO.txt`; §5.2 makes the need more visible.
- **CoW clones and minimal-table-set DBs** — the other two answers to disk pressure,
  explicitly out of scope here.
- **Full checkouts instead of worktrees.** The sidecar needs four bind mounts to make git
  work, and they are the most intricate thing in `docker-compose.yml`. The cause is that a
  worktree's `.git` is a FILE holding an absolute host path into the main repo, so the
  container has to reproduce that path exactly; and once the main `.git` is mounted, `git gc`
  becomes destructive to every OTHER feature stack, because `git worktree prune` deletes the
  admin directory of any worktree whose path it cannot see and skips the usual expiry when
  doing so. Mounting `.git/worktrees` read-only contains that, but it is a guard around a
  problem we chose rather than one we were given.

  A per-feature checkout with its own `.git` directory would need **one** mount, the tree
  itself, and no guard: nothing outside it to point at, no shared prune target, and the
  object store no longer shared with every other stack.

  The objection is disk, and it is weaker than it looks. Measured here: `.git` is 3.1G against
  roughly 30G of db and solr volumes per stack, so sharing it saves about 10% of a stack. And
  `git clone --local` does not even cost that -- it hardlinks the packfiles (verified:
  `links=2`), so a clone is near-free on disk and took 1.5s for this repo. Hardlinks also
  survive a `gc` in the source, because the file lives until its last link goes.

  What it would change: `z feature new` clones instead of `git worktree add`; `z feature rm`
  becomes `rm -rf` plus whatever branch cleanup is wanted; branches would no longer be
  exclusive across stacks, which is arguably a feature; and `git worktree list` stops being
  the inventory. Not attempted here -- noted because the mount block is the evidence that the
  current arrangement costs more than it saves.

- **Collapsing the proxy chain.** Today a request crosses `ngproxy` → `review-proxy` →
  the stack's Apache. The honest version of "replace it with one nginx" is **two hops to
  one, not three to one**, and the two halves are unrelated problems:

  *`ngproxy` → `review-proxy` is genuinely redundant.* Both are the same nginx-proxy
  image running the same discovery mechanism. They are separate only because IT owns the
  one holding the host's `:443` — which is why `docker-compose.overlay-review-upstream.yml`
  exists at all, and why §3.3 keeps the ask down to one wildcard forward. If we owned
  that proxy, or IT added one vhost per zone, one container would do the whole job. The
  blocker is ownership, not design. What the extra hop actually costs is not latency but
  a class of bug: the upstream connects to us over TLS *without SNI*
  (`proxy_ssl_server_name` defaults off), which is the only reason `default.crt` has to
  be synced at all, and an earlier attempt to sidestep a protocol vote on that hop cost a
  day to an unsolvable captcha (§3.4a).

  *`review-proxy` → Apache is not a proxy hop and cannot be deleted.* `conf-zfin.org` is
  238 lines doing work no reverse proxy is standing in for: mod_auth_openidc against
  bouncer.zfin.org, mapping `preferred_username` to `REMOTE_USER` that the app reads;
  63 proxy and rewrite directives across `/action`, `/ajax`, `/blast`, `/webapp`,
  `/webservice`, `/zfinlabs`, `/jobs`, `/solr`, `/logs`, `/mailpit`, `/build`; the
  `ZfinHosts` allowlists; static file serving; and a LogFormat ops reads. nginx has no
  mod_auth_openidc — you would be adopting OpenResty/lua-resty-openidc or an
  oauth2-proxy sidecar and reproducing `REMOTE_USER` as a header the app is willing to
  trust.

  And the decisive objection is not effort. That file is baked into the httpd image and
  is the same one staging and production run. A review stack exists to show what a branch
  does *as ZFIN runs it*; swapping Apache for nginx in dev means reviewing something that
  behaves differently from what ships, in exactly the areas — auth, rewrites, redirects,
  headers — where the differences are most expensive to discover late.
