# ADR 0002 — How the database learns who is asking

Status: **accepted** · 2026-09-15

Decided by the project owner: modes 5.1.1 and 5.1.2 are required to work.
That requirement selects mechanism A; it is not compatible with B or C alone.

## Context

Row-level security and column masking enforced *inside* PostgreSQL or SQL Server
only work if the database can identify the caller. An application connecting
through one service account looks like one user to the database, and every
per-user rule collapses.

Three ways to solve it:

| | Mechanism | Trust |
|---|---|---|
| **A. Per-user database principal** | The platform provisions a login or role per user, mapped from Entra groups; views read `current_user` / `SESSION_USER` | Trustworthy — the database itself authenticates |
| **B. Session context** | `SET LOCAL app.principal` (PG) or `sp_set_session_context` (MSSQL) | Only if every connection passes through our proxy; a client that can set it can impersonate anyone |
| **C. Proxy only** | Identity stays in the application, which connects with a service account | Only if direct database access is firewalled off |

Which modes need which:

- **5.1.1 native config** — requires A.
- **5.1.2 secure view** — A, or B when traffic is guaranteed to arrive via our proxy.
- **5.2 proxy** — C, and it is bypassed entirely by anyone who can open a
  direct connection.

## Decision

Phase 1 implements **A** as the primary mechanism: a `DbPrincipalProvisioner`
maps Entra groups to database roles and provisions logins per source. **B** is
accepted only for connections we can attribute to our own proxy (checked on
`application_name` plus an IP allowlist), never as a general mechanism.

## What this now requires from the data platform owners

The decision is made, but the *permission* is not ours to grant. Requiring
5.1.1 and 5.1.2 to work converts the open question into a hard external
dependency on M5 and M6: without these grants in place, both milestones stop at
dry-run and neither can be demonstrated.

Ask for exactly this, per source, and get it in writing before M5 starts:

| | PostgreSQL | SQL Server |
|---|---|---|
| Create and drop principals | `CREATEROLE` on the target database | `ALTER ANY USER` + `ALTER ANY ROLE`; for Entra, `CREATE USER ... FROM EXTERNAL PROVIDER` |
| Apply row-level security | `ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY`, `CREATE POLICY` — needs table ownership or membership of the owning role | `ALTER ANY SECURITY POLICY` + `CREATE FUNCTION` in the predicate schema |
| Apply column masking | Install and use the `anon` extension, or accept that columns can only be hidden, not masked | `ALTER ANY MASK` + `GRANT UNMASK` per column (**SQL Server 2022 or newer**) |
| Create secure views | `CREATE` on the target schema, `REVOKE` on base tables | same |

Two of these are worth checking before anyone promises a date, because they are
capability limits rather than permission limits, and no amount of access solves
them:

- **PostgreSQL has no column masking in core.** If the extension `anon` cannot
  be installed — most managed Postgres services do not allow it — then in mode
  5.1.1 a column can be hidden but not masked. Masking on PostgreSQL then has to
  come from 5.1.2, which is a reason to apply both modes to the same asset
  rather than treating them as alternatives.
- **Dynamic Data Masking on SQL Server is per column, not per user.** It cannot
  express a conditional or cell-level mask at all, and `GRANT UNMASK` is
  database-wide before SQL Server 2022. Confirm the production version now.

Both limits belong in the capability matrix (FR-6.0b) so the UI warns at the
moment a mode is chosen, rather than applying successfully while silently
dropping a policy.

## Consequences

- `db_principal_map` exists from the first migration; provisioning state and
  failures are recorded per source.
- The provisioner must be idempotent and reversible: it will be re-run, and a
  half-provisioned source cannot be allowed to fail open.
- `DbPrincipalProvisioner` moves onto the M2 critical path. Identity sync is no
  longer only a read of Entra; it produces database principals, so a user
  removed from a group must lose a database login, and that revocation has to be
  as reliable as the grant.
- A per-user principal model makes the count of database logins grow with the
  workforce. Map Entra *groups* to database roles and make the per-user login a
  member of those roles, rather than granting to individuals: it keeps the
  number of grants proportional to policies rather than to people.
- Non-production first. The provisioner runs against dev, then UAT, then
  production, and every run is dry-run reviewable — apply to production is a
  reviewed step, never an automatic consequence of a policy edit.
