# Config inventory: what the tomcat container actually reads

An orientation map of every configuration file that reaches the running webapp — Tomcat,
Spring, Hibernate, properties, logging — as a starting point for converging on modern
framework conventions.

> Static read of the repo (branch `preloaded-dev-stacks`), not a runtime trace. See
> [Caveats](#caveats) for the two things worth confirming before acting.

Versions in play: **Tomcat 10 / JDK 21**, **jakarta.servlet 6.0**, **Spring Framework 6.1.1**,
**Spring Security 6.1.8**, **Hibernate ORM 6.4.4**, **log4j2 2.24.3** — and **no Spring Boot**.

## Three pipelines put config in the container

```
1. IMAGE       docker/tomcat/Dockerfile (FROM tomcat:10-jdk21) → bin/setenv.sh (CATALINA_OPTS, JMX)
2. CATALINA    lib/Java/tomcat/{conf,lib}/**  --ant copy + @TOKEN@ filterset-->  $CATALINA_BASE
   BASE        (ant deploy-catalina-base → create-mutant-instance, buildfiles/tomcat.xml)
3. WEBAPP      home/WEB-INF/**  +  conf/*  --ant/gradle-->  www_data → ROOT webapp
```

`$CATALINA_BASE` is `/opt/zfin/catalina_bases/zfin.org` inside the container (the
`catalina_base` volume); the webapp lives in `www_data`. Both are warm-restored per feature
stack from `docker/preloaded-app/<tag>/*.tgz`.

## Layer 1 — Tomcat server (`lib/Java/tomcat/`, token-filtered into `$CATALINA_BASE`)

| File | Role |
|---|---|
| `conf/server.xml` | connectors/ports — `@SECUREPORT@`, `@SERVER-SHUTDOWN-PORT@` substituted by Ant |
| `conf/Catalina/mutant/ROOT.xml` | per-instance context: **JNDI `jdbc/zfin` = c3p0 `ComboPooledDataSource`** (`@PGHOST@`, `@DBNAME@`, pool 20–120), `AccessLogValve` |
| `conf/context.xml`, `conf/web.xml`, `conf/catalina.properties`, `conf/catalina.policy`, `conf/tomcat-users.xml`, `conf/jaspic-providers.xml` | stock Tomcat defaults, vendored into the repo |
| `conf/server.xml.tomcat9` | dead leftover from the Tomcat 9 era |
| `lib/log4j.properties` | log4j **1.x** properties for Tomcat's own classloader |
| `docker/tomcat/Dockerfile` | writes `bin/setenv.sh`: JMX on 9012 + `-Djava.rmi.server.hostname=$(hostname -i)` (the line that breaks if tomcat is multi-homed — see the `--shared-db` notes in `docker-compose.overlay-shared-db.yml`) |

Ant tokens substituted at deploy time: `TARGETROOT`, `DBNAME`, `PGHOST`, `SECUREPORT`,
`Non-SECUREPORT`, `SERVER-SHUTDOWN-PORT`, `DOMAIN-NAME`, `JBROWSE_*`, `GBROWSE_*`.

## Layer 2 — Webapp descriptor: `home/WEB-INF/web.xml` (376 lines)

- **context-params**: `log4jConfigLocation`, `contextConfigLocation`
  (`/WEB-INF/spring/applicationContext.xml` + `/WEB-INF/spring/security.xml`)
- **filters**: character encoding, `springSecurityFilterChain`, a log4j request-info filter
- **listeners**: `ZfinPropertiesLoadListener`, `ContextLoaderListener`,
  `HttpSessionEventPublisher`, `RequestContextListener`
- **servlets**: two DispatcherServlets (`zfin`, `zfinapp`), the ZFIN Ontology Manager, and
  **6 GWT-RPC servlets** (`AnatomyLookupService`, `SessionSaveService`, `CurationService`,
  `CurationDiseaseService`, `CurationFilterService`, …)

The GWT-RPC block is much of why this file is still 376 lines — which makes GWT retirement a
prerequisite for replacing `web.xml` with a Java initializer.

## Layer 3 — Spring (XML-first; Java config is a beachhead only)

| File | Size | Role |
|---|---|---|
| `home/WEB-INF/spring/security.xml` | 237 lines, 13 beans, ~114 namespace elements | **the big one** — XML namespace security config |
| `home/WEB-INF/spring/applicationContext.xml` | 39 / 6 beans | root context: `LocalValidatorFactoryBean`, `ResourceBundleMessageSource`, JAXB marshallers |
| `home/WEB-INF/spring/properties.xml` | 12 / 1 | `ZfinPropertiesPlaceholderConfigurer` |
| `home/WEB-INF/spring/mvc.xml` | 26 / 3 | exception resolver, mail sender. Still declares `spring-beans-3.0.xsd`; the mail host value has a stray `&quot;` baked into it |
| `home/WEB-INF/spring/mvc-webapp.xml` | 40 / 6 | one of only two files doing `component-scan` |
| `home/WEB-INF/spring/views.xml` / `framework.xml` | 15 / 18 | view resolver; `SimpleUrlHandlerMapping` |
| `home/WEB-INF/zfin-servlet.xml` | 10 | DispatcherServlet context (imports applicationContext, mvc, views) |
| `home/WEB-INF/zfinapp-servlet.xml` | 19 | second DispatcherServlet context |
| `home/WEB-INF/spring-ws-servlet.xml` | 51 / 5 | separate Spring-WS stack (component-scan) |
| `source/org/zfin/framework/ZfinConfiguration.java`, `ZfinWebConfigConfiguration.java`, `ToolBootstrap.java` | — | the only `@Configuration` classes today |

## Layer 4 — Hibernate (native bootstrap, not Spring-managed)

`conf/hibernate.cfg.xml` — 361 lines, copied to `WEB-INF/classes/` by `build.xml`'s `prepare`:

- PostgreSQL dialect; `connection.datasource=java:comp/env/jdbc/zfin` → the pool is **Tomcat's
  c3p0**, not Spring's
- batch size 50, ordered inserts/updates, Envers audit suffix `_audit`
- second-level cache **off**; references an `ehcache.xml` that doesn't exist in the repo
- **290 explicit `<mapping class=…>` entries** against **301 `@Entity` classes**; one legacy
  `.hbm.xml` remains

`SessionFactory` is built by hand — `HibernateUtil` (`new Configuration().addPackage(...)
.configure().buildSessionFactory()`) and `HibernateSessionCreator`. No `persistence.xml`, no
`LocalSessionFactoryBean`, so Spring's transaction manager is not in the picture.

## Layer 5 — Application properties

```
commons/env/all-properties.yml
   └─ PropertiesProcessor.groovy   (ant rebuildPropertiesFromYaml)
        └─ home/WEB-INF/zfin.properties   → ZfinPropertiesLoadListener / ZfinPropertiesEnum
```

Instance selection and per-env overrides: `commons/env/instances.properties`,
`env-exports.properties`, `docker-prod-defaults.properties`,
`linux-prod-vm-defaults.properties`, `test-unittest.properties`.

## Layer 6 — Logging

The live path:

```
commons/env/${LOG4J_FILE}        e.g. default.log4j.xml | production-site.log4j.xml | test-sites.log4j.xml
   └─ build.xml:116 copy (+@DEFAULT_EMAIL@ filter)
        └─ WEB-INF/classes/log4j2.xml     ← auto-discovered by log4j-jakarta-web
```

Despite the `.log4j.xml` names, those `commons/env` files are the log4j2 configs. Also present:

| File | Status |
|---|---|
| `home/WEB-INF/log4j2.xml`, `home/WEB-INF/conf/log4j2.xml` | both exist **and differ**; `log4jConfigLocation` points at the first, but nothing in Java reads that param and Spring's `Log4jConfigListener` is not registered — looks vestigial (log4j2's own param is `log4jConfiguration`) |
| `conf/gradle-log4j2.xml` | logging for Gradle-run tasks |
| `conf/mchange-log.properties` | c3p0 logging |
| `lib/Java/tomcat/lib/log4j.properties` | log4j 1.x, Tomcat's own classloader |
| `log4j-1.2-api` on the classpath | bridges legacy 1.x calls in app code |

## Layer 7 — Other app config

`home/WEB-INF/conf/site-search-categories.xml`, `home/WEB-INF/conf/download-registry.xml`,
`home/WEB-INF/validation/species-validation.xml`, `conf/unload-indexer.xml`,
`conf/zfin-messages.properties` (message bundle for `messageSource`).

## Convergence order (payoff vs. risk)

1. **`security.xml` → `SecurityFilterChain` `@Bean`s.** Biggest single XML file and the most
   deprecated style here; Spring Security 6 documents Java config as the path.
2. **Hibernate registration → package scanning** (or a JPA `persistence.xml`). Deleting 290
   hand-maintained `<mapping class>` lines removes a whole class of "added an entity, forgot
   the mapping" bugs.
3. **`web.xml` → `AbstractAnnotationConfigDispatcherServletInitializer`**, once the GWT-RPC
   servlets retire. `ZfinConfiguration` / `ZfinWebConfigConfiguration` are the beachhead.
   Follows the React migration rather than leading it.
4. **c3p0-over-JNDI → Spring-managed HikariCP.** c3p0 is effectively unmaintained and
   Hibernate 6 ships `hibernate-hikaricp`. This also deletes the `@PGHOST@`/`@DBNAME@` Ant
   filterset step and most of `ROOT.xml`'s reason to exist — which simplifies the per-feature
   stack tooling too.
5. **Collapse the logging configs** onto one log4j2 file per environment; drop the log4j 1.x
   files and the `log4jConfigLocation` param once confirmed dead.
6. **Spring Boot** is the end state the rest points at, but it's last, not first: JSP views,
   the vendored Tomcat tree, JNDI, and hand-built Hibernate sessions all have to go first.
   Steps 1–5 each stand on their own even if Boot never happens.

## Caveats

- Which of the duplicate webapp `log4j2.xml` files (if either) wins at runtime is unconfirmed —
  the classpath copy at `WEB-INF/classes/log4j2.xml` is the one log4j2 should find.
- The ~11 `@Entity` classes not listed in `hibernate.cfg.xml` may be reached via
  `addPackage("org.zfin.expression.*")` or may be genuinely unmapped. Worth checking before
  step 2.
