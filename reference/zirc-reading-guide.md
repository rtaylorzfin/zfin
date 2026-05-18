# ZIRC Submission — Reading Guide

A curated path through the ZIRC line-submission codebase for someone
coming up to speed. Don't read the code top-down by directory — read it
in the order below, where each step makes the next one easier.

There are three companion docs in `reference/`:

- **`zirc-architecture.md`** — the contract. What conventions hold, where
  things live. Reference material; you'll return to it.
- **`zirc-rearchitect-retrospective.html`** — the story. Why we made the
  bets we did, what we tried that didn't work, what the alternative on
  `zfin-10265-zirc-line-submission-react-re-architect-squash` looks like.
- **`zirc-openapi-approach.md`** — the API documentation decision: hand-
  curated YAML over springdoc, with the reasoning.

This file is meant to *order* those docs and the source code into a
reading sequence.

---

## 15-minute tour: the heart of it

If you only have 15 minutes, read these three files in this order. They
contain the whole architectural idea; everything else is plumbing.

1. **`reference/zirc-architecture.md` §1–3** — three short sections that
   cover the mental model, the three aggregates, and the
   `FieldDescriptor` pattern. ~10 min.

2. **`source/org/zfin/zirc/api/ZircFormSchema.java`** — the single source
   of truth for the submission form. Read it top to bottom. ~600 lines
   but mostly data; the `FIELDS` map at the bottom carries the whole
   contract. Notice:
   - `schema()` produces JSON Schema
   - `uiSchema()` produces JSON Forms uiSchema
   - `FIELDS` is the path → (read, write) dispatch table
   - Three builder helpers (`stringProp`, `nullableBoolProp`, etc.)
     repeat throughout — once you've seen them you can skim.

3. **`home/javascript/react/zirc/schemaForm/SchemaForm.tsx`** — the
   React side. The whole client form is ~240 lines. Notice:
   - Fetches the schema from the server via React Query
   - The `diffLeaves` function (lines ~95–121) — recursively walks
     two form-data trees and emits one entry per changed leaf
   - The autosave `useEffect` that debounces and fires one PATCH
     per change

That's it. Everything else in this codebase is "the same pattern but for
mutations" or "the same pattern but for assays" or "polish on top."

---

## One-hour deep tour

If you can spend an hour, walk the code in this order. Each step builds
on the prior one.

### Step 1 — Read the retrospective (10 min)

`reference/zirc-rearchitect-retrospective.html`. Focus on:
- "Where we started" (clarifies what was already there at branch creation)
- "TL;DR" (five-bullet summary of the architectural bets)
- The "Head-to-head: this branch vs ZFIN-10265" section to understand
  what's distinctive about our approach

Skip the pitfalls section for now — you'll appreciate it more after
seeing the code.

### Step 2 — Backend, in the order data flows (25 min)

Start at the entity, work outward to the controller. Read these files in
order:

1. **`source/org/zfin/zirc/entity/LineSubmission.java`** — Lombok
   `@Getter @Setter` Hibernate entity. Mostly columns. Notice
   `@DynamicUpdate` and the column-default boilerplate.

2. **`source/org/zfin/zirc/dto/LineSubmissionResponse.java`** — Java
   record. The `of(entity)` static factory is the only thing here;
   read it to see how nested children (`mutations`) map.

3. **`source/org/zfin/zirc/api/ZircFormSchema.java`** — already covered
   above; re-skim if needed. This is the file you'll come back to most
   often as you work.

4. **`source/org/zfin/zirc/api/ZircSubmissionApiController.java`** —
   thin. Just glue from HTTP to `ZircSubmissionService`. Read all of it.

5. **`source/org/zfin/zirc/service/ZircSubmissionService.java`** — the
   only mutator. Focus on:
   - `updateField` — the central PATCH method. Once you've read this,
     `updateMutationField` and `updateAssayField` are identical
     shapes for the other two aggregates.
   - `writeAudit` — the audit trail helper. Inside-the-transaction
     insert, with backwards-compatible log4j line.
   - `storeAttachment` — the multipart-upload path. The
     `sanitizeFilename` helper sits next to it.

6. **`source/org/zfin/zirc/api/ZircApiExceptionHandler.java`** — small.
   The `@Order(HIGHEST_PRECEDENCE)` is the only subtle bit. Three
   handlers map to 404 / 400 / 422 ProblemDetail responses.

### Step 3 — Frontend, in the order React mounts (15 min)

1. **`home/javascript/react/zirc/api/types.ts`** — TypeScript mirrors
   of the Java DTO records. Hand-typed, not generated. Source of truth
   on the wire.

2. **`home/javascript/react/zirc/api/client.ts`** — ~50 lines. The
   `api` object has `get` / `post` / `patch` / `delete` / `upload`
   methods. Notice the `FormData` detection in `request()` so the
   browser sets the multipart boundary itself.

3. **`home/javascript/react/zirc/api/queries.ts`** — React Query hooks
   (`useLineSubmission`, `useAddMutation`, `useUploadAttachment`,
   etc.). Each is ~10 lines.

4. **`home/javascript/react/zirc/schemaForm/SchemaForm.tsx`** — covered
   above. Re-read with the API client in mind now.

5. **`home/javascript/react/zirc/schemaForm/renderers/`** — eight
   custom JSON Forms renderers. Don't read all of them; pick:
   - `RowControlRenderer.tsx` — the workhorse table-row Control for
     string fields. Shows the `options` vocabulary
     (placeholder/helpText/infoHref/suffix).
   - `SectionRenderer.tsx` — the layout renderer for Groups. Note the
     `visible === false` early return; without it, group-level uiSchema
     rules silently don't apply.
   - `MutationsListRenderer.tsx` — the pattern for server-managed list
     widgets with `maxItems` caps.

### Step 4 — Pitfalls section of the retrospective (10 min)

Now go back to `zirc-rearchitect-retrospective.html` and read the
"Pitfalls and how we resolved them" section. You'll recognize all the
files involved and the descriptions will land.

### Step 5 — The architecture doc end-to-end (10 min)

`reference/zirc-architecture.md` §4–16. Sections 14–16 are the
practical bits:
- §14 — directory layout
- §15 — gotchas (read these; each one comes from a real bug)
- §16 — the "adding a new field" checklist

---

## Contributor path: "I need to make a change"

### "Add a new field to an existing aggregate"

Follow the checklist in `zirc-architecture.md` §16. Eight files, in
order: DB migration → entity → DTO → `FIELDS` map → schema → uiSchema →
TS type → OpenAPI YAML. The drift test will tell you if you missed the
last step.

### "Add a new aggregate (new child entity under Mutation)"

Mirror the existing M4.2 pattern. The skeleton is:

1. DB migration: new table with FK to `zirc.mutation`
2. New entity in `source/org/zfin/zirc/entity/`
3. New response record in `source/org/zfin/zirc/dto/`
4. Add to `hibernate.cfg.xml`
5. Service methods: `getRequired*ById`, `add*`, `delete*`, `update*Field`
6. New `Zirc*FormSchema` class with schema/uiSchema/FIELDS
7. New `Zirc*ApiController` with `/form-schema`, `/{id}`, PATCH, POST, DELETE
8. New React Query hooks in `api/queries.ts`
9. New page component if the aggregate gets its own route
   (`MutationEdit.tsx` shape) OR inline expansion via a renderer
   (`AssaysListRenderer` + `AssayEdit.tsx` shape)
10. Update the OpenAPI YAML with all new paths

Look at the M4.x commit chain for a worked example:

```
af40bb58c6  M4.1: list on parent
f16ef7ed89  M4.2: per-aggregate schema + edit page
0b77dcdf5f  M4.3: multipart attachments (if the aggregate has files)
```

### "Change a conditional reveal rule"

uiSchema `rule` blocks are in the `*FormSchema.java` files. Two
patterns to copy:

- Boolean: `Map.of("effect", "SHOW", "condition", Map.of("scope",
  "#/properties/X", "schema", Map.of("const", true)))`
- Enum membership: same shape but
  `"schema", Map.of("enum", List.of("foo", "bar"))`

For Group-level rules, remember `SectionRenderer` honors `props.visible`.
For Control-level rules, JSON Forms handles it automatically.

### "Add a new widget type"

1. Write the renderer under `home/javascript/react/zirc/schemaForm/renderers/`
2. Register it with `rankWith(20, and(isControl, optionIs("widget",
   "yourWidgetName")))` higher than the default rank of 10
3. Export it as `*RendererEntry` and add it to the renderers array on
   whichever page component mounts the form
4. In the relevant `*FormSchema.uiSchema()`, use
   `controlWithOptions("#/properties/foo", Map.of("widget", "yourWidgetName"))`

### "I hit a bug related to autosave"

The autosave story is centralized in three places — all behave the
same way:

- `home/javascript/react/zirc/schemaForm/SchemaForm.tsx`
- `home/javascript/react/zirc/pages/MutationEdit.tsx`
- `home/javascript/react/zirc/pages/AssayEdit.tsx`

The shared idioms are:
- `formData: T | null` starts null; we don't render `<JsonForms>` until
  the seed effect has populated it (prevents the seed-vs-autosave race)
- `lastSavedRef` tracks what's on the server; `diffLeaves` produces the
  PATCH list
- `EXTERNALLY_MANAGED_PATHS` filters out paths handled by dedicated
  POST/DELETE endpoints (e.g. `/mutations`, `/assays`, `/attachments`)
- The React Query cache is mirrored back into local state for server-
  managed lists via a `JSON.stringify(...)` keyed effect

If you see "field clears unexpectedly on reload," read
`zirc-rearchitect-retrospective.html` "Spurious field clear during slow
page reload" — that bug is fixed but the pattern that causes it is
worth recognizing.

---

## What to skip on a first read

- The entity classes beyond `LineSubmission`. They're all the same
  Lombok-decorated column-list shape.
- The renderers other than the three named above. They follow the same
  pattern; one is enough to understand the shape.
- `home/WEB-INF/openapi/zirc-api.yaml` — read its top-of-file `info:`
  block, but you don't need to read every endpoint until you're
  changing the API surface.
- The vendored Swagger UI assets under `home/WEB-INF/openapi/swagger-ui/`
  — they're upstream files, not maintained here.

---

## When you're stuck

The most-likely-first answers to common confusions:

| You see... | Read |
|---|---|
| "Method undefined" Java LSP errors that look impossible | `zirc-rearchitect-retrospective.html` Dev-practice section — Lombok-LSP false positives are pervasive; trust `gradle compileJava` |
| Spock test you wrote doesn't run | Same section — Spock specs are silently dormant; write JUnit 4 Java until that's resolved |
| `@DeleteMapping` returns 500 to the client even though the delete worked | Add `@ResponseStatus(HttpStatus.NO_CONTENT)`. The client expects 204 |
| New uiSchema `rule` is silently ignored | Check whether it's on a Group; `SectionRenderer` needs the explicit `visible === false` gate (already in place — the bug only re-appears if you make a new layout renderer) |
| JSP fails to compile with "Must use jsp:body" | Comments between `<jsp:attribute>` blocks break the parser; move the comment inside an attribute body |
| Drift test fails | Either you added a `@*Mapping` without adding to `zirc-api.yaml`, or vice versa. The test message tells you which direction |
| Liquibase says a changeset already ran but it didn't | The dev DB's tracker is out of sync; apply the SQL directly with `psql` for local work — CI runs cleanly |

---

## Recommended reading sequence at a glance

```
30 sec:  this guide
15 min:  zirc-architecture.md §1–3 + ZircFormSchema.java + SchemaForm.tsx
60 min:  + retrospective + service + DTO + queries + renderers
deep:    + read the architecture doc end-to-end + skim the OpenAPI YAML
```

If you've read all of the above and are still missing context, the
commit log on the `zirc-rearchitect` branch is well-annotated and
chronological — each commit message explains the *why* of one
self-contained change.
