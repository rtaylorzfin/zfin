# Project Rules

## Git Commits
- Do not add `Co-Authored-By` or any AI attribution lines to commit messages.

## Tech Stack

- **Backend**: Java 21 (`source`/`targetCompatibility = 21` in `build.gradle`), Hibernate 6.x / Jakarta Persistence, Lombok, Apache Commons (CSV, IO, Lang), Jackson
- **Database**: PostgreSQL, accessed via Hibernate/JPA
- **Frontend**: React 18 (see [reference/react-18-upgrade.md](reference/react-18-upgrade.md)), legacy GWT, JSP
- **Search**: Apache Solr (`site_index` core)
- **Build**: Gradle 8 + Ant, npm for JS; tests written in Groovy/Spock
- **Runtime**: everything runs inside Docker — the `compile`, `tomcat`, `db` (PostgreSQL), `solr`, `httpd`, and `jenkins` services are orchestrated by `~/zfin/docker/docker-compose.yml`

## Running Commands in Docker (`z`)

Almost nothing runs on the host — Java, Gradle, Ant, npm, and DB access all happen
inside Docker containers. **`z`** ([docker/utils/z](docker/utils/z)) is the single front
door that wraps the verbose `docker compose` invocations. After activating a stack (below)
you invoke it as `z <cmd> …`, or via the short-name shell functions (`zrun` == `z run`):

| Command | What it does |
|---------|--------------|
| `z run [service] [args]` (`zrun`) | `docker compose run --rm <service> bash -l <args>` — one-off command in a fresh container (default: `compile`) |
| `z exec [service] [args]` (`zexec`) | exec into the **running** container for that service (default: `compile`) |
| `z up`/`down`/`pull`/`log`/`restart [service…]` (`zup`/…) | start / stop / pull / tail-logs / restart services (default: all) |
| `z status` (`zstatus`) | active stack: name, dir, url, containers |
| `z feature new [<ticket>]` (`zfeature new`) | provision a feature stack (prompts if no ticket) |
| `z feature build-preloaded` | bake preloaded db/solr images from a loaded stack |
| `z build <phase>` (`zbuild`) | hands-free build/deploy — `configure\|load-db\|load-solr\|deploy-jenkins\|deploy\|all` (the CI engine) |
| `z create-zenv` / `z fresh-install` | bootstrap: make a `.zenv` / guided day-zero setup |

Key points:
- The default service is `compile`. `zrun -c "…"` runs a command there; `zrun <service> -c "…"` targets another.
- `z run` uses a **login shell** (`bash -l`), which sources `.profile` and sets up `SOURCEROOT`/`TARGETROOT`/`NODE_ENV`. A non-login shell (raw `bash -c`) skips this and builds fail with errors like `NODE_ENV environment variable is undefined` — so prefer `zrun` over hand-rolled `docker compose`/`docker exec`.
- `zrun` with no args drops into an interactive login shell in `compile` at `SOURCEROOT`.
- Pass `-u root` (and similar docker flags) for `run`/`exec`; it may come before or after the service name, e.g. `zrun -u root -c "…"` or `zexec -u root jenkins`.
- Stack ops (run/exec/up/down/pull/log/restart) act on the **active `.zenv` stack**: activation exports `COMPOSE_PROJECT_NAME`/`COMPOSE_FILE`/`COMPOSE_ENV_FILES`, which `docker compose` reads natively. With no `.zenv` active they error and tell you to activate one. `build`/`create-zenv`/`fresh-install` don't require activation (CI/bootstrap).

```bash
zrun -c "gradle compileJava"      # compile Java
zrun -c "gradle dirtydeploy"      # compile + hot-deploy to Tomcat
zrun                              # interactive login shell in compile
```

### Activating a stack (`.zenv`) — how `z` gets on `PATH`

`z` is the only executable, and it's Groovy like everything in `docker/utils/lib/`. You get
it on `PATH` — plus the short-name functions (`zrun`/`zexec`/`zup`/`zdown`/`zpull`/`zlog`/
`zrestart`/`zstatus`/`zhelp`/`zfeature`/`zbuild`) — by activating a stack's **`.zenv`**,
venv-style, once per session:

```bash
source .zenv/activate     # z on PATH + short-name functions + this stack targeted; `deactivate` to exit
```

A `.zenv/` is a generated, git-ignored directory (`activate` + a `bin/` with a single
`z` symlink → `docker/utils/z`) created by **`z create-zenv …`**. The short names are
shell **functions** defined by `activate` (`zrun() { z run "$@"; }`), not separate
executables — a Groovy `z` can't tell which name invoked it. The main checkout has a
`.zenv` for the base `zfin_org` stack; feature worktrees get theirs from `z feature new`.

**Layout.** `docker/utils/z` is the front door; every command is a class in
[docker/utils/lib/](docker/utils/lib): `NewFeature`, `BuildPreloaded`, `CreateZenv`,
`Zbuild`, `FreshInstall`, `StackOps` (the run/exec/up/down/pull/log/restart/status family),
plus **`ZfinUtil`** — a shared class holding the process/logging helpers, the canonical
roots, and the preloaded-volume contract. `z` self-locates once, then loads `ZfinUtil` and
every command class through ONE `GroovyClassLoader` (lib/ on its classpath, so `ZfinUtil`
is a single `Class`), builds one `zfinUtil`, and calls `cmd.run(args, zfinUtil)`
**in-process** — no forked JVM, no env plumbing (each command gets helpers + roots as a
typed param). `z build`/`z fresh-install` also work with **no** `.zenv` (GoCD/day-zero);
GoCD stages should call `z build <phase>` instead of inlining `docker compose` tasks.

Day-zero on a bare box: `./docker/utils/z fresh-install`. To (re)create a `.zenv` directly
(base stack, or repair): `./docker/utils/z create-zenv --dir <repo-or-worktree> --project
<name> --compose <base.yml[:overlay.yml]> --env-file <env> [--tag <preloaded-tag>] [--host <host>]`.

## Reference Docs

Detailed, task-specific guides live in [`reference/`](reference/). Read the relevant one
before starting work in that area:

| Doc | Covers |
|-----|--------|
| [build-and-docker.md](reference/build-and-docker.md) | Docker services, image layering, full deployment pipeline, Ant task trees, `zrun` |
| [deploying-changes.md](reference/deploying-changes.md) | Incremental "what to run after editing X" cheat sheet |
| [gradle-deployment.md](reference/gradle-deployment.md) | Gradle task → file-flow schematics (SOURCEROOT → TARGETROOT) |
| [table-regeneration.md](reference/table-regeneration.md) | Rename-and-recreate pattern for regenerating denormalized/generated tables without deadlocks |
| [solr-reindex.md](reference/solr-reindex.md) | Solr `site_index` reindex pipeline (DIH + Java indexer steps) |
| [load-gaf-goa.md](reference/load-gaf-goa.md) | Monthly Load-GAF-GOA job that syncs GO annotations from EBI GOA |
| [react-18-upgrade.md](reference/react-18-upgrade.md) | React 16 → 18 upgrade notes |
| [zirc-reading-guide.md](reference/zirc-reading-guide.md) | Curated entry point into the ZIRC line-submission codebase; links to the other `zirc-*` architecture/design docs |

---

## ZFIN Application Architecture

### Directory Structure (SOURCEROOT vs TARGETROOT)

ZFIN uses a split directory structure separating source code from runtime files:

- **SOURCEROOT** (`/opt/zfin/source_roots/zfin.org/`): Contains source code, SQL files, build scripts
- **TARGETROOT** (`/opt/zfin/www_homes/zfin.org/`): Contains runtime files, generated reports, deployed artifacts

**Important**: When Ant build scripts or Java code write output files (reports, logs, etc.), they should write to `TARGETROOT`, not `SOURCEROOT`. Input files (SQL queries, templates, properties) are read from `SOURCEROOT`.

### DB_maintenance Build System

The file `server_apps/DB_maintenance/build.xml` defines Ant targets for database maintenance jobs, data validation reports, and data loading tasks.

#### Key Properties
```xml
${basedir}        - Resolves to SOURCEROOT (where build.xml lives)
${validateData}   - ${basedir}/server_apps/DB_maintenance (SOURCEROOT - for reading)
${env.TARGETROOT} - Environment variable for output directory
${env.SOURCEROOT} - Environment variable for source directory
```

#### Common Ant Targets

| Target | Purpose | Output Location |
|--------|---------|-----------------|
| `validate-data-report-simple` | Run SQL validation query, generate report | `$TARGETROOT/server_apps/DB_maintenance/validatedata/<JobName>/` |
| `validate-data-report-dynamic` | Run dynamic SQL (.sqlj) validation | `$TARGETROOT/server_apps/DB_maintenance/validatedata/<JobName>/` |
| `run-data-report` | Run Java-based data report task | `$TARGETROOT/server_apps/DB_maintenance/report_data/<JobName>/` |
| `run-data-report-param` | Run parameterized data report | `$TARGETROOT/server_apps/DB_maintenance/report_data/<JobName>/` |

#### Java Task Classes

- `org.zfin.infrastructure.ant.CreateValidateDataReportTask` - Executes SQL files, generates HTML/TXT reports
- `org.zfin.infrastructure.ant.DataReportTask` - General data reporting with custom task classes
- `org.zfin.infrastructure.ant.AbstractValidateDataReportTask` - Base class with report generation utilities

### Jenkins Integration

Jenkins jobs are defined in `server_apps/jenkins/jobs/<JobName>/config.xml`. Each job typically:
1. Sets workspace to `$TARGETROOT`
2. Invokes Ant with `-file build.xml <target> -DJobName=<JobName>`
3. Archives artifacts from `server_apps/DB_maintenance/report_data/<JobName>/` or `validatedata/<JobName>/`
4. Publishes HTML reports

#### Testing Jenkins Jobs Locally

```bash
# Run a Jenkins job via CLI (exec into the running jenkins container)
zexec jenkins -c 'java -jar /tmp/jenkins-cli.jar -auth admin:<token> -s http://localhost:9499/jobs build <JobName> -s -v'

# Run Ant target directly in the compile container
zrun -c 'cd $TARGETROOT; ant -f $SOURCEROOT/server_apps/DB_maintenance/build.xml <target> -DJobName=<JobName>'
```

### Report Generation Flow

1. **SQL/Java execution**: Task reads `.sql` or `.sqlj` file from `$SOURCEROOT/server_apps/DB_maintenance/validatedata/`
2. **Query execution**: Runs against PostgreSQL database via Hibernate
3. **Report generation**: `ReportGenerator` writes HTML and TXT files to `$TARGETROOT/.../validatedata/<JobName>/` or `report_data/<JobName>/`
4. **Jenkins archival**: HTML publisher archives reports for web viewing

### Adding New Validation Jobs

1. Create SQL file: `server_apps/DB_maintenance/validatedata/<JobName>.sql`
2. Add entry to `server_apps/DB_maintenance/validatedata/report.properties`:
   ```properties
   <JobName>.errorMessage=Description of what the query finds
   <JobName>.headerColumns=Column1|Column2|Column3
   ```
3. Create Jenkins job config: `server_apps/jenkins/jobs/<JobName>/config.xml`
4. The job will use `validate-data-report-simple` target with `-DJobName=<JobName>`

### Compiling and Deploying Java Changes

See [reference/build-and-docker.md](reference/build-and-docker.md) for Docker architecture and full deployment pipeline, [reference/gradle-deployment.md](reference/gradle-deployment.md) for Gradle task file flow schematics, and [reference/deploying-changes.md](reference/deploying-changes.md) for an incremental "what to run after editing X" cheat sheet.

```bash
# Compile Java code
zrun -c "gradle compileJava"

# Compile and deploy to Tomcat (hot reload)
zrun -c "gradle dirtydeploy"
```

### Genomic Reference Files

Genomic reference files (FASTA, GFF3) are stored in `/opt/zfin/gff3/` (mapped from external folder in Docker):
- `Danio_rerio.fa` - GRCz11 genome assembly
- `Danio_rerio.fa.fai` - FASTA index file
- `GCF_049306965.1_GRCz12tu_genomic.fna` - GRCz12 genome assembly

Key classes for genomic sequence access:
- `org.zfin.mapping.GenomicLocationService` - Defines `FASTA_URL_BASE_DIR = "/opt/zfin/gff3/"` and provides methods for accessing reference sequences
- `org.zfin.datatransfer.flankingsequence.FlankSeqProcessor` - Updates flanking sequences for features using the reference genome

**Important**: When accessing genomic files, always use `FASTA_URL_BASE_DIR` prefix for the full absolute path.

