# ZIRC OpenAPI spec: approach selection

**Goal**: expose an OpenAPI 3 specification for the ZIRC JSON API so other
developers (and eventually CI codegen) can consume the contract without
reading Java source.

**Constraint**: this codebase runs plain Spring MVC 6.1 on Tomcat — there is
no Spring Boot autoconfig in the picture. That eliminates the
`springdoc-openapi-starter-*` happy path most blog posts assume.

## Options evaluated

### Option A: `springdoc-openapi` v2 without Spring Boot

Add `org.springdoc:springdoc-openapi-starter-webmvc-api:2.7.0` (or later) as
an `implementation` dependency, then manually `@Import` the springdoc
`@Configuration` classes (`SpringDocConfiguration`,
`SpringDocWebMvcConfiguration`, `MultipleOpenApiSupportConfiguration` etc.)
into our existing `ZfinWebConfigConfiguration`. Provide explicit
`@Bean OpenAPI` and `@Bean SpringDocConfigProperties` instances since Boot's
`@ConfigurationProperties` binding isn't running.

**Pros**
- Spec is always in sync with the controllers — driven by reflection over
  `@RestController` + Jackson types.
- Free Swagger UI at `/swagger-ui/index.html` if we also pull in
  `springdoc-openapi-starter-webmvc-ui`.

**Cons**
- springdoc v2's bean wiring leans on Spring Boot's
  `@ConditionalOnMissingBean` machinery and on properties like
  `springdoc.api-docs.enabled` that come from Boot's environment binding.
  Without Boot, several of these conditionals don't evaluate the way
  the springdoc team intends. Past community reports describe ~1–3 days of
  fiddling to get a clean boot, and breakage on minor version bumps.
- The package-private `SpringDocConfiguration` makes the `@Import` path
  brittle: it's not designed as a public extension point.
- Risk of fighting Spring Security's filter chain over the
  `/v3/api-docs` and `/swagger-ui/**` paths.

### Option B: hand-curated YAML served as a static file

Write `home/WEB-INF/openapi/zirc-api.yaml` covering all 12 ZIRC endpoints,
serve it via a small `@RestController` that returns the file body with
`text/yaml`. Optionally embed a Swagger UI page using the standalone
`swagger-ui-dist` static assets (no Spring integration required).

**Pros**
- Zero risk to the existing app; no Spring config changes.
- Works regardless of Spring version or build environment.
- The spec is reviewable in code review when controllers change.

**Cons**
- Drift between the code and the spec is on the author to prevent.
- Need a discipline of updating the YAML when adding endpoints.

**Mitigation for drift**: a Groovy integration test under `test_source/`
that calls each endpoint, parses the YAML, and asserts the response shape
matches `paths.<op>.responses.<status>.content.application/json.schema`.
The same test fails CI if the spec falls behind.

### Option C: swagger-core annotations + build-time YAML generation

Annotate every controller method and DTO record with `@Operation`,
`@Parameter`, `@Schema`, etc. Add a Gradle task that runs
`io.swagger.core.v3:swagger-jaxrs2-jakarta` (or the springmvc-aware
equivalent) to render YAML at build time. Commit the YAML output.

**Pros**
- Spec in sync via the annotations, without springdoc.
- No runtime cost in the application JAR.

**Cons**
- Introduces another build step.
- Significant annotation retrofit across 12 endpoints + ~10 DTOs.
- Tooling support for "Spring MVC without Boot, with reflection on
  controllers" is patchy compared with springdoc.

## Decision: Option B — hand-curated YAML, served by a small controller

Rationale: the ZIRC API has stabilized at ~12 endpoints across 3
controllers; the cost of writing the YAML once is bounded, and the spec
gets us 80% of the value of springdoc with 5% of the risk. The drift
concern is real but tractable with a single integration test as
documented above. The Spring Boot migration is a known future direction;
when that lands, switching to Option A becomes near-trivial — revisit
then.

## Concrete next steps (filed as the implementation task)

1. Author `home/WEB-INF/openapi/zirc-api.yaml`. Endpoints to cover:
   - `GET /api/zirc/form-schema` — submission form schema (response: FormSchemaDTO)
   - `GET /api/zirc/line-submissions/{zdbID}` — fetch submission
   - `POST /api/zirc/line-submissions` — create draft (returns LineSubmissionDTO)
   - `PATCH /api/zirc/line-submissions/{zdbID}` — field-path update (body: FieldUpdate)
   - `POST /api/zirc/line-submissions/{zdbID}/mutations` — add mutation
   - `DELETE /api/zirc/line-submissions/{zdbID}/mutations/{mutationId}` — delete mutation
   - `GET /api/zirc/mutations/form-schema`
   - `GET /api/zirc/mutations/{mutationId}`
   - `PATCH /api/zirc/mutations/{mutationId}`
   - `POST /api/zirc/mutations/{mutationId}/assays`
   - `DELETE /api/zirc/assays/{assayId}`
   - `GET /api/zirc/assays/form-schema`
   - `GET /api/zirc/assays/{assayId}`
   - `PATCH /api/zirc/assays/{assayId}`
   - `POST /api/zirc/assays/{assayId}/attachments` — multipart
   - `DELETE /api/zirc/assays/attachments/{fileId}`
   - `GET /api/zirc/assays/attachments/{fileId}/content` — streaming
2. Capture DTO schemas in `components.schemas` (LineSubmissionDTO,
   MutationDTO, AssayDTO, AssaySummaryDTO, AssayFileDTO,
   FieldUpdate, FormSchemaDTO, ProblemDetail).
3. Document error responses per endpoint (400 / 404 / 422 — all
   `application/problem+json`).
4. Add `ZircOpenApiController` that streams the YAML at
   `GET /api/zirc/openapi.yaml` with `Content-Type: application/yaml`.
5. Optional follow-up: drop `swagger-ui-dist` into `home/static-assets/`
   and add a `GET /api/zirc/docs` route that renders Swagger UI against
   the YAML.
6. Add a Groovy integration test that walks the YAML and round-trips a
   minimum-viable request against each path; fail if a path is in the
   YAML but the controller returns 404, or vice versa.

## When to revisit Option A

Triggers:
- The codebase adopts Spring Boot for any reason — flip immediately.
- We accumulate enough new endpoints that the YAML maintenance load
  exceeds the springdoc integration cost (rough threshold: ~30 endpoints).
- We want client codegen pipelines and the curated YAML drifts despite
  the integration test.
