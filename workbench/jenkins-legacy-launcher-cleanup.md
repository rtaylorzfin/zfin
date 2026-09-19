# Remove the pre-Docker Jenkins launcher and its Ant lifecycle targets

## Background

While diagnosing the "+ Add build step → Execute shell" failure (config page silently
did nothing; `POST /jobs/$stapler/bound/<uuid>/render` returned 500), the root cause
turned out to be `-Dinstance=docker` on the Jenkins JVM.

Jelly — the XML templating engine Jenkins renders all its config forms with — resolves
any unbound view variable against JVM **system properties**, via this fallback in
`org.apache.commons.jelly.JellyContext.getVariable`:

```java
// ### this is a hack - remove this when we have support for pluggable Scopes
if ( value == null ) {
    value = getSystemProperty(name);
}
```

`instance` is the most-used variable name in Jenkins form views, so `-Dinstance=`
rebound it to the String `"docker"` everywhere it should have been null. The
add-new-item prototypes in `lib/form/hetero-list.jelly` then captured that String and
replayed it into the deferred render, where `hudson/tasks/Shell/config.groovy:28`
(`value: instance?.command`) threw `MissingPropertyException`.

**The proximate fix has already landed**: `-Dinstance=docker` removed from
`docker/jenkins/Dockerfile`, with a comment warning against single-word `-D` properties.

## Why this follow-up

`server_apps/jenkins/jenkins.sh:19` passes the very same `-Dinstance=$INSTANCE`. It is
harmless today only because nothing ever runs it — Jenkins' lifecycle belongs to Docker
now (the container's `CMD` runs the war directly). Leaving the script in the tree leaves
a loaded gun: anyone reviving that path re-introduces the outage.

The script cannot simply be deleted on its own, because four Ant targets still `exec` it.
The whole pre-Docker lifecycle cluster needs to go together.

## Scope

Remove, as one change:

**Scripts**

| File | Notes |
|------|-------|
| `server_apps/jenkins/jenkins.sh` | `nohup java -jar jenkins.war` launcher; carries `-Dinstance=$INSTANCE` |
| `server_apps/jenkins/start.sh` | same `nohup` pattern, referenced from nowhere at all |

**Ant targets in `buildfiles/jenkins.xml`** (line numbers as of this writing)

| Target | Line | Why dead |
|--------|------|----------|
| `create-jenkins-symlink` | 451 | symlinks `${jenkins}/jenkins-2.528.3.war`, a file that does not exist — no `.war` is checked in, and the image downloads 2.541.3 to `/opt/jenkins/jenkins.war` |
| `start-jenkins` / `jenkins-start` | 460 / 466 | exec `jenkins.sh start` |
| `stop-jenkins` / `jenkins-stop` | 468 / 473 | exec `jenkins.sh stop` |
| `kill-jenkins` | 475 | exec `jenkins.sh kill` |
| `restart-jenkins` / `jenkins-restart` | 481 / 486 | exec `jenkins.sh restart` |
| `pid-jenkins` | 488 | reads `$JENKINS_HOME/jenkins.pid`, which only `jenkins.sh` ever wrote |

Also update the usage `<echo>` block (the `JENKINS SERVER` section, ~lines 803–809) that
advertises these targets.

**Needs a decision, not obviously in scope**

- `tail-log` (line 526) — its only remaining purpose is `tail -f $JENKINS_HOME/logs/jenkins.log`,
  but under Docker the war logs to container stdout and that file is never written. It also
  `depends="create-jenkins-symlink"`, so it breaks the moment that target is removed. Either
  delete it or drop the `depends` and repoint it at `z log jenkins`.
- `status-jenkins` (line 495) and `jenkins-process-info` (line 511) — both just `ps -ef | grep
  jenkins.war`. They still technically work *inside* the container but duplicate `z status`.

## Evidence the targets are unreferenced

`grep` for every target name across the repo returns hits only inside
`buildfiles/jenkins.xml` itself (definitions, `depends` attributes, and the usage text).
No GoCD stage, `docker/utils/` command, job config, or reference doc calls them.

## Replacement

Already the documented path in `CLAUDE.md`:

```bash
z up jenkins        # start
z stop jenkins      # stop
z restart jenkins   # restart
z log jenkins       # tail logs (container stdout)
z status            # what's running
```

## Acceptance criteria

- [ ] `server_apps/jenkins/jenkins.sh` and `server_apps/jenkins/start.sh` are gone.
- [ ] No target in `buildfiles/jenkins.xml` references `jenkins.sh`, `jenkins.pid`, or
      `create-jenkins-symlink`.
- [ ] `ant -f buildfiles/jenkins.xml` parses and the usage/help target runs clean.
- [ ] `ant deploy-jobs` and `ant deploy-plugins` (the targets actually used by
      `z build deploy-jenkins`) still work — verify on a feature stack.
- [ ] `z up/stop/restart/log jenkins` still drive the container.
- [ ] No remaining single-word `-D` property on the Jenkins JVM command line.

## Risk

Low. Everything listed is unreachable in the Docker world, and the one genuinely live
consumer of this build file — job/plugin deployment — is untouched. The main care point
is not accidentally removing `deploy-jobs` / `deploy-plugins` / `deploy-email-templates`
while pruning the neighbouring lifecycle targets.
