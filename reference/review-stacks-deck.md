---
marp: true
theme: default
paginate: true
header: "Review stacks"
footer: "branch: preloaded-dev-stacks"
---
<!-- _class: lead -->
<!-- _paginate: false -->

# Review stacks

### Parallel feature branches, each at its own URL — reviewable before merge

What was built on top of the per-feature dev stacks, why each piece exists,
and where it is more complicated than it needs to be.

---

## Two things this buys you

**1. Work several tickets at once, properly isolated.**
Each branch gets its own database, Solr index, Tomcat and hostname. No branch switching,
no reloading data, no two tickets fighting over `:443` or one schema.

**2. Stage a branch so it can be looked at during code review — before it merges.**
A reviewer opens a URL and sees the actual change running against real data, instead of
reading a diff and imagining it. Screenshots in the PR stop being the best available
evidence.

The first was mostly delivered by the dev stacks already. **The second is what this branch adds**
— and it is the one that changes how review works.

---

## Where we started

Dev stacks solved *provisioning*: a loaded ZFIN in minutes, several at once.

They left four things unsolved:

- **Reaching them.** `127.0.0.5:443`, one `/etc/hosts` entry per stack, a `sudo`
  prompt per machine. Nobody else could open your work.
- **Disk.** ~28 GB per stack. Three stacks ≈ 100 GB. `stop` frees nothing.
- **Certificates.** Click-through warnings everywhere.
- **Agents.** Running Claude with `--dangerously-skip-permissions` against your
  primary checkout is not a sandbox.

---

## The shape of the answer

```
            *.review.zfin.test  ──DNS──▶  one proxy  ──Host header──▶  the right stack
                                             │
   zfin-1234 ─┐                              │   no match? ──▶ landing page
   zfin-5678 ─┼── each an httpd on ──────────┘                  "here's how to make one"
   zfin-9999 ─┘   zfin_review_net

   freeze ──▶ tar the volumes to cheap storage, down -v, keep the worktree
   thaw   ──▶ put it all back

   ./z run claude ──▶ a sidecar with this worktree, no keys, no docker socket
```

Four pieces: **routing**, **freeze/thaw**, **the sidecar**, and the **`feature` instance**
that stops a dev stack claiming to be production.

---

## 1. Routing: one proxy, not N ports

Before: every stack published `127.0.0.X:443`, needed a loopback alias (`sudo`), and an
`/etc/hosts` line.

Now: one `nginx-proxy` owns `:443`. A stack joins `zfin_review_net` and advertises
`ZFIN_VIRTUAL_HOST`, set from `DOCKER_VIRTUAL_HOST`.

```yaml
# docker-compose.review-member.yml — the per-feature routing change
services:
  httpd:
    ports: !reset null                        # the proxy owns :443 now
    environment:
      ZFIN_VIRTUAL_HOST: ${DOCKER_VIRTUAL_HOST}
      VIRTUAL_HOST: !reset null               # keep it invisible to any OTHER proxy
    networks: [default, review]
```

Why not the stock `VIRTUAL_HOST`? Because on a shared VM ours is the *second* nginx-proxy,
and two of them watching one Docker socket share a namespace. Ours reads a variable the
other's template never looks at — one token, changed in a template generated from the
pinned image. More on that later.

Name resolution is **one wildcard DNS record** for the zone, not one entry per stack.
Nothing is added per feature; nothing needs cleaning up at teardown.

---

## Two decisions worth defending

**We run our own proxy, never IT's.** If they keep one in front, it forwards a single
wildcard to us — *one entry, once*. If they remove it, we bind `:443` directly, which is
already the laptop path. We never need per-ticket cooperation.

**The proxy speaks HTTPS to httpd, not plain HTTP.** Terminating TLS once looks simpler,
but `conf-zfin.org`'s port-80 vhost redirects `/jobs`, `/solr`, `/logs`, `/mailpit` to
https whenever `%{HTTPS}` is off — with a plain-http backend that is an **infinite
redirect loop** on exactly those paths.

---

## 2. Freeze / thaw: the disk answer

```bash
z feature freeze ZFIN-1234     # tar volumes to cheap storage, down -v, keep the worktree
z feature thaw   ZFIN-1234     # put it back
```

Measured on a real 21.7 GB stack:

| | freeze | thaw | archive |
|---|---|---|---|
| default (pigz) | **1m 21s** | 1m 58s | 6.9 GB |
| `--no-compress` | 2m 12s | **55s** | 21.2 GB |
| `--reseed` (code-only branch) | **27s** | 1m 01s | 0.5 GB |

Reversible, unlike `z feature rm`: the worktree and branch stay put.

---

## What freeze had to get right

**Ordering.** App tier fully stopped before the data tier, and the database *verifiably*
shut down before it is tarred. A torn PGDATA in an archive is the one unrecoverable
failure, discovered at thaw, months later.

**Composition.** A `--shared-db` stack has no `pg_data` of its own — that data belongs to
`zfin_shared` and other stacks are live on it. The volume list is derived from the stack's
own compose, never a fixed list.

**Refusing.** A seed manifest records a SHA-256 per tarball and the postgres major, so a
truncated archive, or a restore into an engine that cannot open the data, is refused up front
rather than discovered when postgres will not start.

*The shutdown check is verified rather than assumed — an early version compared against an
empty string and could never pass. A safety check that cannot pass is worse than none.*

---

## Seeds: the same machinery, one level up

Capture and restore turned out to answer *provisioning* too, not just disk.

A stack used to boot from **preloaded images** — a loaded PGDATA baked into
`zfin-db-preloaded:<tag>`. That looks efficient and isn't: postgres declares `VOLUME`, so
Docker **copies** the baked data into every stack anyway. The layer sharing an image would
normally buy simply does not apply.

So the image was a 30 GB delivery vehicle for a tarball that gets expanded regardless — and it
cost three extra full copies of the data to build (into the build context, into a layer, out
again on export).

`z seed create` stops at the tarball. Same `captureVolume` that freeze uses; `z feature new
--seed` restores it with the same parallel `restoreVolumes`.

| | preloaded images | seeds |
|---|---|---|
| one-time cost | ~30 GB, local Docker store only | **~7 GB**, ordinary files |
| can live on NFS | no — overlayfs needs a local upper dir | **yes** |
| restoring ~21 GB | ~170s (daemon copies file by file) | **79.9s** (tar streams) |
| integrity | layer digest | SHA-256 per tarball **+ the postgres major** |

Measured on the shared VM, same disk, same data.

---

## What that deleted

`docker-compose.preloaded.yml`, two Dockerfiles, `ZFIN_DB_IMAGE`/`ZFIN_SOLR_IMAGE`, and the
image-building half of the tooling. Net **−30 lines** of code and one fewer compose overlay.

It also removed a failure that had nothing to do with images: because the overlay existed, a
stack could *inherit* it. `z build all` on the main checkout picked it up and pointed `db` at an
image that does not exist until a seed has been captured — which is the very thing the base
stack is loaded to produce.

*The honest caveat: a seed captured from a data-only project has no app tier, and the stack then
fails at httpd startup with an Apache error naming nothing useful. `z seed create` warns at
capture time, which is the only place the cause is still visible.*

---

## 3. The sidecar

```bash
cd <worktree> && ./z run claude      # same verb as ./z run compile
```

Built **on the compile image**, not a minimal one. A minimal image is more isolated but
cannot compile, test or deploy — which makes the agent useless and pushes every build back
to a human.

That trade is only acceptable because `compile` was already hardened: **no docker socket,
no SSH agent, no keys**. So "the agent cannot push" is structural, not a rule.

---

## What the sidecar cannot do — by construction

| | why |
|---|---|
| `git push` | no agent, no keys. A `PreToolUse` hook makes the failure legible |
| drive Docker | no socket mounted |
| reach another worktree | only this one is mounted |
| reach `/mnt/research` | not mounted — it spans other instances and prod unloads |
| restart Tomcat | a host operation; `buildfiles/tomcat.xml` fails deliberately |

It **can** build, test, `dirtydeploy`, reach `db`/`solr`, and `loaddb` (dumps mounted
read-only). Note `loaddb` destroys that stack's database — blast radius is one feature's
own copy.

---

## Auth: the decision that shaped the rest

Three options: log in inside the container, a host token, or an API key.

**The deciding argument was archiving, not convenience.** A container-side `claude auth
login` writes credentials into `~/.claude` — which *is* the `claude_home` volume — which
`z feature freeze` archives. Every archive would carry a working login to whatever
external storage it was parked on.

So the token stays on the **host**, mounted read-only. `claude_home` holds only session
history, and archives stay credential-free — **verified**: 4 transcripts, 0 credential files.

---

## 4. The `feature` instance

Every feature stack generated **`coral`'s** properties, including `DOMAIN_NAME=zfin.org`.

So a stack served at `zfin-1234.review.zfin.test` still emitted absolute links — and
addressed mail — to **production**. Latent for years; invisible until review URLs made
stacks shareable.

Now there is a `feature` instance whose `DOMAIN_NAME` follows the stack's own host, with a
distinct banner colour and its own `email_overrides` entry.

*`TODO.txt` had been asking whether a feature needs its own instance. This was the answer.*

---

## One tree, one declared root

Everything the tooling owns lives under one directory you name — no absolute defaults
guessing at someone else's machine:

```
$ZFIN_DEV_ROOT/
├── base/                the checkout
├── worktrees/zfin-1234/ one per feature; the directory name IS the ticket
├── review/              proxy template, certs, allowlist, stacks.json
├── archive/             seeds/, freeze archives, sidecar sessions
└── mounts/              the DOCKER_*_PATH bind mounts
```

```bash
./z scaffold --root ~/zfin-dev     # creates what's missing, never clobbers
```

`ZFIN_DEV_ROOT` is **required**. Unset, you get an error naming the fix — not a directory
appearing somewhere you did not expect.

*Not in the tree: the sidecar token. It stays in `$HOME`, because this tree is
group-writable on a shared host.*

---

## The lifecycle, end to end

```bash
z seed create --from coral           # once per host, from a DEPLOYED stack
z review up                          # once per host
z feature new ZFIN-1234 --up         # → https://zfin-1234.review.zfin.test
cd $ZFIN_DEV_ROOT/worktrees/zfin-1234
./z run claude                       # work in the sidecar
./z feature freeze                   # park it (ticket inferred from the cwd)
./z feature thaw                     # bring it back
./z feature rm                       # archives the session, then removes everything
```

`z feature ls` shows `up | down | frozen`. The landing page shows the same inventory, and
tells a visitor how to create or thaw a stack that isn't there.

---

## The code-review workflow

Reviewing someone else's PR — no branch switching, no rebuild of your own work:

```bash
z feature new review-1234 --existing-branch --branch ZFIN-1234 \
                          --shared-db --up
#   → https://review-1234.review.zfin.test
# ... click through the actual change, against real data ...
z feature rm review-1234
```

`--existing-branch` checks out a branch that already exists — including one known only to
`origin`, so a colleague's PR works. `--shared-db` skips the per-stack data copy, which is
right for a read-mostly review.

The stack is disposable; the archive of what you found is the PR comment.

---

## Honest caveat: who can open the link today

On a **laptop**, the proxy binds `127.0.0.1` and the zone resolves via your own dnsmasq.
The URL works for *you*. It is not yet a link you can paste into a PR.

On a **shared review host** with a real wildcard DNS record, it is — that is exactly what
`*.review.cell.zfin.org` would buy, and it is the one thing still blocked on someone
outside this room.

**So today:** proposition 1 is delivered, and proposition 2 works for *your own* review of
someone's branch. The shareable link needs the DNS record.

*This is the main reason to prioritise that ask.*

---
<!-- _class: lead -->

# Where this is more complicated than it needs to be

An honest list. Several of these are worth cutting.

---

## Simplification candidate: three data modes

A feature can have **its own** db+solr, **share** `zfin_shared`, or be **frozen**. Each
is a different compose file list, a different volume set, a different teardown path.

`--shared-db` may in fact be the *primary* disk strategy — §5.4 argues a sharer reclaims
little from freezing precisely because sharing already avoided the cost.

**Question for the team:** if most review stacks are read-mostly, should `--shared-db`
become the **default**, making own-data the exception? That would shrink freeze/thaw from
load-bearing to occasional.

---

## Simplification candidate: profile initialisation, done properly

The sidecar inherits `compile`'s login profile, which assumes mounts the sidecar
deliberately does not have. Three separate papercuts:

- `/mnt/research` → six `mkdir: Permission denied` on every entry
- `~/.claude.json` → onboarding re-ran every time, asking for login
- TLS cert → regenerated on every entry

Each fixed separately. **The pattern says the profile is doing too much** — it runs
`generate_base.sh` on *every login shell*, for every service, regardless of need.

---

## What that script is actually doing

`more_bash_profile` → `generate_base.sh`, on every login shell, in every service built on
the compile image:

| work | really is | belongs |
|---|---|---|
| self-signed TLS cert + keystore | **stack setup**, once | an init step, or image build |
| random `pg_pass` | **stack setup**, once — and it writes into the *source tree* | provisioning, host-side |
| `mkdir /mnt/research/vol/*` | **image** concern | the Dockerfile |
| generate `zfin.properties` | **stack setup**, once per instance | an explicit task |
| export env vars | genuinely per-shell | the profile |

Only the last row belongs in a login profile. The rest is one-time initialisation that
happens to have been put where it would definitely run.

---

## How to do it properly

**Separate the two concerns.** A login profile should set up *your shell*. Initialising a
*stack* is a different job with a different lifetime.

**Make init explicit and once-per-stack.** Compose already has the mechanism:

```yaml
services:
  init:                       # certs, keystore, properties — runs once, exits
    profiles: []              # part of every up
  tomcat:
    depends_on:
      init: { condition: service_completed_successfully }
```

**Let services opt in.** The sidecar needs none of it — and proved the point by failing
three different ways when it inherited all of it anyway.

**Move image concerns to the image.** Directories that must merely *exist* are `RUN mkdir`,
not runtime work repeated on every shell.

*Payoff: entering any container gets quiet and fast, and "which mounts does this service
actually need?" becomes answerable.*

---

## Smaller rough edges

- **Archive retention** — nothing prunes `/opt/zfin/stack-archive`. `rm` clears its own; frozen stacks accumulate.
- **Freeze concurrency** — two at once, or one racing `z feature new`, want a lockfile.
- **`z shared` reboot survival** — the proxy has `restart: unless-stopped`; the shared data stack doesn't.
- **`DISABLE_OIDC=true`** turns `/jobs`, `/solr`, `/logs`, `/mailpit` into `Require all granted`. Per-host and untracked, so nobody can see which boxes opted out.

---
<!-- _class: lead -->

# Questions you're going to ask

---

## "Is a real database on a real URL safe?"

The honest answer: **the allowlist is doing the work.**

Seeds carry a real loaded ZFIN database. Reference docs call that data local-only,
"a structural choice, not an oversight" — and this feature's whole purpose is to put it on
a URL.

Two controls, covering different things:

| | protects | leaves open |
|---|---|---|
| **IP allowlist** (proxy) | everything, incl. the database | nothing, inside the ranges |
| **OIDC** (`DISABLE_OIDC=false`) | `/jobs`, `/solr`, `/logs`, `/mailpit` | the site itself |

Dropping the Jenkins vhost was **minimalism, not security** — `/jobs` is on the same vhost.

---

## "What about the agent exfiltrating data?"

**Not currently mitigated, and worth being clear about.**

Everything built shrinks the *filesystem and credential* blast radius. None of it stops
data leaving over the network. The sidecar can read every row of the database and make
arbitrary outbound HTTP. No credential needed.

The realistic attack is **prompt injection** — ticket text, dependency READMEs, web docs —
with `--dangerously-skip-permissions` meaning nobody approves each action.

An egress allowlist is planned. But it must permit the Anthropic API, npm, Maven and
GitHub — each a channel data can be tunnelled through. It **raises the bar; it does not
close the door.**

---

## "Are we locked into nginx-proxy?"

No. The vendor contract is **a few env vars in one overlay file**. Everything else goes
through `DOCKER_VIRTUAL_HOST`, which is ours and vendor-neutral — and the discovery variable
is already ours (`ZFIN_VIRTUAL_HOST`), so a different proxy reads it the same way.

Swapping to Traefik or Caddy is a contained edit. And `stacks.json` — written for the
landing page — means a templated plain-nginx config is available too, which would remove
the Docker socket entirely.

*Traefik has built-in DNS-01 wildcard ACME, which would solve the certificate problem
outright. That's a reason we might switch on our own initiative.*

---

## "Why can't I reach Postgres by hostname?"

Because the wire protocol carries no hostname, and these stacks speak plain TCP — no TLS,
so no SNI either.

It *could* be done: enable `ssl=on`, have clients use `sslmode=require`, and route on SNI
with a stream proxy. But that means a certificate per stack, a second proxy mechanism, and
a hostname→upstream map regenerated on every lifecycle event.

**We decided against it.** `docker exec <stack>-db-1 psql` already works.

Solr needed no work at all — `httpd` already proxies `/solr`, so its published port was
simply deleted. **JDWP** is the one that genuinely pins the loopback IP: a fixed handshake,
zero routing information.

---

## "What's it going to cost me to adopt?"

Once per machine:

```bash
./z scaffold --root ~/zfin-dev   # the tree; then set ZFIN_DEV_ROOT in docker/.env
z seed create --from <a loaded, DEPLOYED stack>   # or copy someone else's seed directory
brew install dnsmasq             # wildcard DNS for the zone — now a prerequisite
brew install mkcert && mkcert -install
claude setup-token               # only if you want the sidecar
```

Then per feature, one command. Freeze when you need the disk back; thaw when you return.

**What you give up:** `getdb`/`getsolr` don't work from a container (no credentials, by
design) — fetch dumps on the host. And unrouted stacks have no hostname.

---
<!-- _class: lead -->

## What I'd like from this review

1. **The DNS record for `*.review.cell.zfin.org`.** This is what turns a review stack into
   a link you can paste into a PR — the headline benefit, and the only piece blocked on
   someone outside the team.
2. **Allowlist ranges** — campus, VPN, both? And should the review host run with OIDC on,
   which would protect `/jobs`, `/solr`, `/logs` and `/mailpit` as well?
3. **Should `--shared-db` be the default?** If most review stacks are read-mostly, sharing
   one data tier may be the primary disk strategy — which would demote freeze/thaw from
   load-bearing to occasional.
4. **Is a wildcard private key on laptops acceptable?** If not, only the shared host gets
   real certificates and laptops keep a local CA.
5. **Appetite for the profile-initialisation rework?** It is hygiene, not a blocker — but it
   is the largest remaining piece of cleanup, and it touches every service's startup.

Everything else is working and in use. These five shape what it looks like in six months.
