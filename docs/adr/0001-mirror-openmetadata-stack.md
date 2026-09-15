# ADR 0001 — Mirror the OpenMetadata 2.0.1 stack

Status: accepted · 2026-09-15

## Context

An earlier draft of the plan specified Spring Boot 3 and Ant Design 5, chosen on
general merit rather than on anything about OpenMetadata. Since this platform
reads its governance metadata from OpenMetadata, is maintained by people who
also maintain OpenMetadata, and is meant to look like part of the same suite,
the stack was re-derived from what OpenMetadata actually runs.

Verified against the team instance (`/api/v1/system/version` → `2.0.1`) and the
matching `2.0.1-release` source tag, not from recall.

What that turned up:

- Backend: Java 21, **Dropwizard 5.0.0** with Jersey 3 on the Jakarta namespace,
  JDBI3 with HikariCP, Flyway 9, jsonschema2pojo, ANTLR 4.13, Fernet.
- Frontend: **Vite 7** — 2.0 dropped Webpack — React 18, **Tailwind 4.1.18**
  with `react-aria-components` and Untitled UI icons.
- **antd 4.24.16** is still present but is being removed; the repository carries
  `tw-audit` and `tw-deprecation-guard` scripts policing the migration.

## Decision

Match it: Java 21 + Dropwizard 5 + Jersey 3 + JDBI3 + Flyway on the backend,
Vite 7 + React 18 + Tailwind 4 + react-aria-components on the frontend.

Two qualifications:

1. **Skip antd entirely.** Adopting a dependency its own maintainers are
   deleting would mean writing code with a known rewrite already scheduled. We
   start at their destination.
2. **Vendor `@openmetadata/ui-core-components`.** It is not published to npm
   (the registry returns 404) but it is Apache-2.0, so the source is copied into
   `frontend/ui-core-components` at a pinned tag with a resync script. This is
   the only way to get identical tokens and components rather than an
   approximation of them.

Departures, where our problem has no counterpart in theirs: JSqlParser for
proxy-mode rewriting, the SQL Server JDBC driver, Testcontainers.

## Consequences

- Patterns transfer directly — resource layout, auth filters, health checks,
  and above all the JSON Schema → POJO pipeline, which is why our Policy IR is
  defined once and generated into both Java and TypeScript.
- We inherit their upgrade cadence. Dependency bumps should be made against a
  named OpenMetadata tag, recorded in the root `pom.xml`, rather than
  individually.
- Dropwizard is a smaller ecosystem than Spring. Less autoconfiguration, more
  explicit wiring — acceptable, and arguably clearer for a service whose
  security-relevant wiring should be visible rather than inferred.
- The published `openmetadata-java-client` stops at 1.8.0, behind the 2.0.1 we
  target, so the client is generated from the instance's own `/swagger.json`,
  pinned into `spec/` for reproducible builds.
