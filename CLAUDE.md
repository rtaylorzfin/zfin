# Project Rules

## Git Commits
- Do not add `Co-Authored-By` or any AI attribution lines to commit messages.

## Comments and Docs

Write for someone reading the code a year from now, who does not know a branch ever existed.

- **No "what used to be".** Do not narrate how the code or a ticket looked before the change —
  no "this used to…", "the original write-up had…", "previously this compared…", no summaries
  of what an earlier revision got wrong. Git history and the ticket hold that. Describe what
  the code does and why it has to be that way.
- **No development-cycle detail.** Dates, build numbers, "verified on", "observed in build #6",
  "found by running it", which iteration introduced what — all out.
- **Be wary of measured numbers.** Row counts, error totals and percentages drift, and a stale
  number is read as current. Put them in the ticket or a report. In a comment, only when the
  number is the reason the code is shaped that way, and then say what it is a count of and
  when it was taken.
- **Prefer no comment to one that will rot.** A comment that goes out of date is worse than
  none. Keep the ones that stop someone reverting a deliberate choice — why this table and not
  the obvious other one, why this field and not the one that looks right — and cut the rest.
- Reference docs in `reference/` and `server_apps/**/README-*.md` are living documents, not
  changelogs: update them in place to describe the current state. Decisions worth keeping
  (what was chosen, and why) belong there; the path taken to reach them does not.

## Tech Stack

- **Backend**: Java 21 (`source`/`targetCompatibility = 21` in `build.gradle`), Hibernate 6.x / Jakarta Persistence, Lombok, Apache Commons (CSV, IO, Lang), Jackson
- **Database**: PostgreSQL, accessed via Hibernate/JPA
- **Frontend**: React 18 (see [reference/react-18-upgrade.md](reference/react-18-upgrade.md)), legacy GWT, JSP
- **Search**: Apache Solr (`site_index` core)
- **Build**: Gradle 8 + Ant, npm for JS; tests written in Groovy/Spock
- **Runtime**: everything runs inside Docker — the `compile`, `tomcat`, `db` (PostgreSQL), `solr`, `httpd`, and `jenkins` services are orchestrated by `~/zfin/docker/docker-compose.yml`

## Running Commands in Docker (`z`)

Almost nothing runs on the host — Java, Gradle, Ant, npm, and DB access all happen inside
Docker containers. **`z`** is the single front door that wraps the verbose `docker compose`
invocations. Run it as **`./z <cmd> …`** from anywhere inside a checkout or feature worktree:
nothing to source, nothing to put on `PATH`.

| Command | What it does |
|---------|--------------|
| `z run [service] [args]` | `docker compose run --rm <service> bash -l <args>` — one-off command in a fresh container (default: `compile`) |
| `z exec [service] [args]` | exec into the **running** container for that service (default: `compile`) |
| `z up`/`stop`/`pull`/`log`/`restart [service…]` | start / stop / pull / tail-logs / restart services (default: all) |
| `z down [service…]` | remove containers + network; `-v` also discards this stack's DB/Solr/app copy |
| `z status` | the current stack: name, dir, branch, url, containers |
| `z feature new [<ticket>]` | provision a feature stack; prompts for the plan (base, tag, boot, npm, deploy, liquibase, tmux) — `-y` takes the defaults |
| `z feature ls` / `z feature rm <ticket>` | list feature stacks / tear one down |
| `z feature freeze`/`thaw <ticket>` | park a stack's volumes to an archive, and bring it back |
| `z feature session export\|ls` | archive the Claude sidecar's history (outlives `rm`) |
| `z seed create\|ls\|rm` | capture a loaded stack's volumes as a reusable seed; `z feature new --seed <tag>` restores one |
| `z build <phase…>` | hands-free build/deploy — `configure\|load-db\|load-solr\|deploy-jenkins\|deploy\|all` (the CI engine) |
| `z shared up\|down\|status\|freeze\|thaw` | the single db+solr copy that `--shared-db` features attach to |
| `z review up\|down\|status\|cert` | the proxy routing `https://<slug>.<zone>` to each stack |
| `z scaffold` / `z fresh-install` | create the dev-tree layout / guided day-zero setup |

`./z help` prints the authoritative list; `./z feature help <sub>` and `./z <cmd> --help` give
each command's own flags.

Key points:
- The default service is `compile`. `./z run -c "…"` runs a command there; `./z run <service> -c "…"` targets another.
- `z run` uses a **login shell** (`bash -l`), which sources `.profile` and sets up `SOURCEROOT`/`TARGETROOT`/`NODE_ENV`. A non-login shell (raw `bash -c`) skips this and builds fail with errors like `NODE_ENV environment variable is undefined` — so prefer `./z run` over hand-rolled `docker compose`/`docker exec`.
- `./z run` with no args drops into an interactive login shell in `compile` at `SOURCEROOT`.
- Pass `-u root` (and similar docker flags) for `run`/`exec`; it may come before or after the service name, e.g. `./z run -u root -c "…"` or `./z exec -u root jenkins`.

```bash
./z run -c "gradle compileJava"   # compile Java
./z run -c "gradle dirtydeploy"   # compile + hot-deploy to Tomcat
./z run                           # interactive login shell in compile
```

### How `z` finds the stack

There is nothing to activate. Stack ops (`run`/`exec`/`up`/`stop`/`down`/`pull`/`log`/`restart`/
`status`) ask git for the root of the checkout you are standing in, read its **`docker/.env`**,
and target the stack that file describes — announced on stderr. So
`./z run -c "gradle dirtydeploy"` works from any subdirectory of a checkout or worktree.

A directory **is** a stack when its `docker/.env` names a `COMPOSE_PROJECT_NAME`. That file is
the whole record: it also carries the compose overlays (`ZFIN_COMPOSE_OVERLAYS`), image tags and
hostname, so a stack's composition is knowable from the stack itself rather than from a copied
bundle. `ZFIN_COMPOSE_OVERLAYS=` (present but empty) means **base compose only** — what the main
checkout wants; omitting the key entirely falls back to the feature-stack overlay pair, so
stacks provisioned before it was recorded still tear down.

`build`, `shared`, `review`, `scaffold` and `fresh-install` manage their own projects and need no
target. An explicit `COMPOSE_PROJECT_NAME`/`COMPOSE_FILE` in the environment (as GoCD sets) always
wins over auto-detection.

**Layout.** `docker/utils/z` is the real front door (Groovy); `./z` at the repo root is a thin
`exec` into it, so the common case reads well from a fresh checkout. Every command is a class in
[docker/utils/lib/](docker/utils/lib): `NewFeature`, `Seed`, `Zbuild`, `FreshInstall`,
`StackOps` (the run/exec/up/down/… family), `FeatureFreeze`/`FeatureThaw`/`FeatureSession`,
`SharedStack`, `ReviewStack`, `Scaffold`, plus **`ZfinUtil`** (process/logging helpers, canonical
roots, stack resolution) and **`StackConfig`** (ZFIN policy: image names, volume contracts,
service roles). `z` self-locates, loads `ZfinUtil` and every command class through ONE
`GroovyClassLoader`, and calls `cmd.run(args, zfinUtil)` **in-process** — no forked JVM, no env
plumbing. GoCD stages should call `./z build <phase>` rather than inlining `docker compose` tasks.

Tab completion exists but nothing wires it up for you — add it to `~/.bashrc`:

```bash
source /path/to/checkout/docker/utils/lib/z-completion.bash
```

## Reference Docs

Detailed, task-specific guides live in [`reference/`](reference/). Read the relevant one
before starting work in that area:

| Doc | Covers |
|-----|--------|
| [dev-stacks.md](reference/dev-stacks.md) | Per-feature dev stacks: seeds, the review proxy, freeze/thaw, and how a stack is targeted |
| [dev-tree-layout.md](reference/dev-tree-layout.md) | The `ZFIN_DEV_ROOT` tree — where worktrees, seeds, archives and proxy state live |
| [build-and-docker.md](reference/build-and-docker.md) | Docker services, image layering, full deployment pipeline, Ant task trees |
| [deploying-changes.md](reference/deploying-changes.md) | Incremental "what to run after editing X" cheat sheet |
| [gradle-deployment.md](reference/gradle-deployment.md) | Gradle task → file-flow schematics (SOURCEROOT → TARGETROOT) |
| [table-regeneration.md](reference/table-regeneration.md) | Rename-and-recreate pattern for regenerating denormalized/generated tables without deadlocks |
| [solr-reindex.md](reference/solr-reindex.md) | Solr `site_index` reindex pipeline (DIH + Java indexer steps) |
| [compare-database-loads.md](reference/compare-database-loads.md) | Whole-DB table snapshot/compare tooling: capture every table's row count + content hash, diff two captures to see what changed |
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
./z run -c 'cd $TARGETROOT; ant -f $SOURCEROOT/server_apps/DB_maintenance/build.xml <target> -DJobName=<JobName>'
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
./z run -c "gradle compileJava"

# Compile and deploy to Tomcat (hot reload)
./z run -c "gradle dirtydeploy"
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

