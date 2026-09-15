# ARAK — Data Access Control Platform

Subscription and data policies over the governance metadata already curated in
OpenMetadata, enforced on SQL Server and PostgreSQL.

OpenMetadata answers *what data exists and how it is classified*. This platform
answers *who may read it, which rows, and which columns* — and then enforces
that answer in the database itself. The two are deliberately separate: an
OpenMetadata policy governs who can edit a description in the catalog; nothing
in it reaches the data.

The approved requirement and milestone plan lives at
`~/.claude/plans/requirement-access-optimized-dahl.md`.

## The shape of it

One policy model, one engine, one decision, three ways to enforce it:

```
Policy (JSON, validated against backend/dac-spec)
   │
   ▼
PolicyEngine.evaluate(principal, asset, context)
   │
   ▼
PolicyDecision { allowed, rowPredicates, columnMasks, hiddenColumns, reasons }
   │
   ├─► ViewCompiler    → CREATE VIEW sec.customer            (mode 5.1.2)
   ├─► NativeCompiler  → CREATE POLICY / SECURITY POLICY / DDM (mode 5.1.1)
   └─► RewriteCompiler → SQL rewritten at runtime            (mode 5.2)
```

All three modes are Phase 1 deliverables and all three are first-class; none is
a fallback. They consume the same `PolicyDecision` and are expected to agree
byte for byte, which is what the cross-mode consistency suite checks.

## Stack

Mirrored on OpenMetadata 2.0.1, verified against the running instance and the
`2.0.1-release` tag rather than from memory. Engineers move between the two
codebases, and patterns are copied across directly.

| | |
|---|---|
| Backend | Java 21, Dropwizard 5, Jersey 3 (Jakarta), JDBI3 + HikariCP, Flyway |
| Schema | JSON Schema → jsonschema2pojo (Java) and `parse-schema.mjs` (TypeScript) |
| Expressions | ANTLR 4.13 |
| Frontend | Vite 7, React 18, TypeScript 5, Tailwind 4, react-aria-components, Untitled UI icons |
| State | Zustand, TanStack Query, axios |
| Tests | JUnit 5 + Testcontainers, Jest, Playwright |

Two deliberate departures from what OpenMetadata ships today:

- **No Ant Design.** OpenMetadata 2.0 is removing antd 4.24 in favour of
  Tailwind + React Aria. Starting on the thing they are deleting would be debt
  on day one, so we start where they are heading.
- **JSqlParser** for SQL rewriting has no counterpart there; it is specific to
  the proxy mode.

## Prerequisites

- **JDK 21** — required. Java 11 will not compile this.
- Node ≥ 22.17 and Yarn 1.x
- Docker (dev stack, and Testcontainers for integration tests)

Maven is not needed: `./mvnw` downloads it.

No JDK 21 on the machine? Nothing has to be installed system-wide:

```powershell
. .\scripts\use-jdk21.ps1        # bash: source scripts/use-jdk21.sh
```

It unpacks Temurin 21 into the gitignored `.tools/` and sets `JAVA_HOME` **for
that shell only** — no installer, no system PATH, no registry keys. Deleting
`.tools/` undoes it completely. If `JAVA_HOME` already points at a JDK 21, skip
this.

## Getting started

```bash
cp .env.example .env     # then fill it in; .env is gitignored and stays that way
docker compose -f deploy/docker-compose.yml up -d

./mvnw verify            # unit tests, plus both code generators
java -jar backend/dac-service/target/dac-service.jar server conf/dac.yml

cd frontend/app && yarn install && yarn parse-schema && yarn dev
```

The app is on http://localhost:3000, the API on 8080, the Dropwizard admin port
(health, metrics) on 8081.

Point `OM_BASE_URL` at the team's OpenMetadata instance and use a **bot token**
(Settings → Bots), not a personal account: a connector authenticating as a human
inherits that human's permissions and their departure breaks the sync.

## Layout

```
backend/
  dac-spec                   JSON Schema — the single source of truth
  dac-common                 shared types and utilities
  dac-engine                 PolicyEngine, conflict resolution, simulator
  dac-compiler-sql           the three compilers + PG/MSSQL dialects
  dac-connector-openmetadata generated OM client, crawler, webhook receiver
  dac-connector-identity     Entra OIDC + Graph, local users, OM teams
  dac-connector-source       JDBC introspection, DDL apply/rollback, drift
  dac-proxy                  Query API: parse, rewrite, execute
  dac-service                Dropwizard application, Flyway, the fat jar
frontend/
  app                        Vite + React + Tailwind
  ui-core-components         vendored from OpenMetadata (Apache-2.0) — see VENDORED.md
spec/                        pinned OpenMetadata OpenAPI spec
conf/                        Dropwizard configuration
deploy/                      docker-compose, Dockerfile, seed data
scripts/                     resync helpers for the spec and the design system
```

## Two rules worth knowing before you edit anything

**Generated code is not edited.** The Policy IR is defined once, in
`backend/dac-spec/src/main/resources/json/schema`. Java POJOs and TypeScript
types are both generated from those files, which is the only reason the engine
and the Policy Builder cannot drift apart. Change the schema, then:

```bash
./mvnw -pl backend/dac-spec generate-sources   # Java
cd frontend/app && yarn parse-schema           # TypeScript
```

Both output directories are gitignored, and CI rejects a commit that adds them.

**`frontend/ui-core-components` is not edited.** It is a copy of OpenMetadata's
design system at a pinned tag, resynced by `scripts/sync-om-design-system.sh`.
Edits there are lost on the next resync; local overrides belong in
`frontend/app/src/theme/`.

## Tests

```bash
./mvnw test                        # unit + golden-file SQL codegen
./mvnw verify -Pintegration        # real PostgreSQL and SQL Server via Testcontainers
cd frontend/app && yarn lint       # ESLint; the vendored design system is excluded
cd frontend/app && yarn test       # unit
cd frontend/app && yarn test:e2e   # Playwright
```

Integration tests run against real engines rather than mocks on purpose:
generated DDL that a mock accepts tells you nothing about whether PostgreSQL
will.

## The dependency that gates M5 and M6

Native enforcement only works if the database knows who is asking. Modes 5.1.1
and 5.1.2 are both required, which settles the mechanism: per-user database
principals, provisioned by this platform (ADR 0002). Session context is accepted
only for connections attributable to our own proxy, never as a general
mechanism.

That decision is made. The permission it needs is not ours to grant, and it is
now a hard external dependency: this platform must be allowed to create and
manage database logins and to run `ALTER` against the target objects. ADR 0002
lists the exact grants to request per engine. Get them in writing before M5
starts — without them both milestones stop at dry-run.

Two limits in that ADR are capability limits, not permission limits, and no
amount of access solves them: PostgreSQL has no column masking in core (it needs
the `anon` extension, which most managed services forbid), and SQL Server's
Dynamic Data Masking is per column rather than per user, so it cannot express a
cell-level mask at all. Both belong in the capability matrix so the UI warns
when a mode is chosen rather than dropping a policy silently.
