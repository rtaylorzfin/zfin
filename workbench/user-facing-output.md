# Agenda: make the user-facing output friendly

Raised during the compose-file review, to be worked before the review is called done. This is
a list of what is actually wrong, gathered by running the commands rather than reading them,
so it can be worked through rather than argued about.

## The standard to hold

1. **An error names the thing that is wrong, then what to do about it.** Most already do. The
   gaps are where a message answers a different question than the one the user asked.
2. **Validate the target before inviting a destructive flag.** See finding 2 -- this is the
   one with teeth.
3. **When the tool knows the valid values, print them.** We have the service list, the feature
   list, and the seed list in hand at the moment we reject something.
4. **Say what happened, not what was invoked.** Raw passthrough from `docker compose` is the
   usual offender.

## Findings

### 1. `z feature rm <typo>` invites `--force` instead of saying the feature does not exist

```
$ ./z feature rm nope
!! z feature rm: no TTY -- pass --force to confirm
```

`nope` is not a feature. `FeatureRemove` builds the paths, probes tmux, and reaches the TTY
check without ever asking whether the target exists -- so a mistyped ticket gets a message
whose remedy is the DESTRUCTIVE flag. Someone following that advice literally runs a
force-remove against a name they typed wrong.

`z feature refresh` already does the right thing and is the model to copy:

```
!! no such feature: nosuchthing
   known: zfin-mactest2
```

Fix: check existence first, in `rm`, `freeze`, `thaw` and `session` alike. Only after the
target is known to exist should `--force` be mentioned.

### 2. `z up <typo>` passes docker's error through and drops the service list

```
$ ./z up nosuchservice
>> targeting 'zfin_org' (base)
no such service: nosuchservice
```

No prefix, so it does not read as our error; no list, though `z-completion.bash` proves we
know the services; and the `>>` line above it makes it look like something succeeded first.

### 3. Stale value lists in error messages

`z feature: unknown ...` was missing `refresh` (fixed in this commit). The list is typed out
by hand in `z` and again in `z-completion.bash` and again in the `z feature` help text --
three copies, and the error message was the one that drifted. Worth deriving from one list,
the way `StackConfig.featureEnv` now backs both writers of a feature `.env`.

### 4. `z status` outside a checkout says where you are not, not where to go

```
$ cd /tmp && z status
stack: none here -- cd into a checkout or feature worktree
```

True, and it stops there. It could name `$ZFIN_DEV_ROOT`, or point at `z feature ls`, which
answers the question the user actually has.

### 5. `z feature ls` calls a stack "up" when only the sidecar is running

```
PROJECT     BRANCH      DATA  STATE  URL
zf-10418    zf-10418    own   up     https://zf-10418.review.zfin.test
```

The only container in that project was `zf-10418-claude-run-...`. No httpd, no tomcat, no db.
The URL in that row returns 503, because nothing is serving the name -- so the table says up
and the link says otherwise. `state` is "any container in this project is running"
(ZfinUtil:376), which is true of a `z run claude` session or a stray `z run compile`.

Fix: decide state from the service that defines being served -- httpd -- rather than from any
container carrying the project label. A stack with only a sidecar up is a real and reasonable
state; it just is not "up".

## Surfaces still to audit

- `z feature new` end to end -- the longest output anyone sees, and a new developer's first
  impression.
- The `>>` / `!!` convention itself: never explained anywhere a user reads.
- Failure paths that print a stack trace instead of a message.
- `z review up` on a host where :443 is taken, and the other preflight deaths.
- Whether timings and byte counts are formatted for people (`6442450944` appeared in a
  rendered config during review; check nothing prints raw bytes at a user).
