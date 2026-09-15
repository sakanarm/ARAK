# Policy specification

The normative definition is the JSON Schema in
`backend/dac-spec/src/main/resources/json/schema`. This document explains what
those files mean and, more importantly, fixes the rules for combining policies —
which schemas cannot express and which every reader of the engine needs to agree
on.

## Two policy types

| | Question it answers | Produces |
|---|---|---|
| **Subscription** | May this principal reach this table at all? | `allowed` |
| **Data** | What do they see inside it? | `rowPredicates`, `columnMasks`, `hiddenColumns` |

Split as Immuta splits them, because they are authored by different people at
different times: a data owner grants access to a table; a governance team masks
every PII column everywhere, once.

## One predicate, not four engines

RBAC, ABAC, rule-based and time-based access are four *views* of a single
`SubjectRule`, and its fields are ANDed:

```jsonc
{
  "principals": [ { "role": "analyst" }, { "team": "Finance" }, { "assetOwner": true } ],
  "attributes": [ { "key": "clearance", "operator": "gte", "value": "L2" } ],
  "expression": "user.country == asset.prop('dataResidency') && user.department in asset.domains",
  "time":       { "windows": [ { "days": ["MON-FRI"], "from": "08:00", "to": "18:00", "timezone": "Asia/Bangkok" } ] },
  "context":    { "ipCidr": ["10.0.0.0/8"] }
}
```

`principals` is an OR list; everything else is an AND. An empty `SubjectRule`
matches nobody. There is no configuration under which absence of a rule means
permission.

`assetOwner: true` is a dynamic subject: whoever OpenMetadata currently lists as
an owner. Written once, it applies to every table and follows ownership changes
without a policy edit.

## Selectors: hierarchy is the point

Every facet selector matches on a fully qualified name, and most facets are
hierarchical:

| Selector | Matches |
|---|---|
| `classifications contains 'PII'` | every tag under PII, including ones created next month |
| `tags contains 'PII.Sensitive'` | that tag and its children |
| `domains contains 'Finance'` | Finance and every sub-domain below it |
| `domains eq 'Finance'` | Finance only — not `Finance.Risk` |
| `terms contains 'Finance.CustomerIdentity'` | that term and its children |

`contains` is what makes a policy survive a steward adding a tag; `eq` is what
makes a policy mean exactly one level. Both are legitimate and the builder must
make the difference visible, because the failure mode is silent: a policy that
was supposed to cover a new sub-domain and quietly does not.

Two implementation rules follow:

1. **Match by segment, never by string prefix.** `LIKE 'Finance.%'` also matches
   a domain called `Finance Ops` if anyone quotes an FQN carelessly.
2. **Expand ancestors at write time.** An asset in `Finance.Risk.Credit` gets
   three rows in `asset_facet` — depth 2, 1, 0 — so evaluation is an index
   lookup rather than a recursive query. That is what keeps decisions inside the
   50ms p95 budget.

### Which tags count

OpenMetadata tag labels carry `state` (`Suggested` | `Confirmed`) and
`labelType` (`Manual` | `Automated` | `Propagated` | `Derived`).

Default: **only `Confirmed` is enforced**, and propagated labels are ignored
because we compute inheritance ourselves. Both are opt-in per condition. The
reason is blunt: enforcing on `Suggested` lets a model's guess lock production
data with no human having approved it.

## Combining policies

An asset is usually covered by several policies at several levels:

```
ORG → DOMAIN (shallow → deep) → SERVICE → DATABASE → SCHEMA → TABLE → COLUMN
```

Each is evaluated independently, producing its own decision. Those decisions are
then composed by **intersection**:

| Situation | Rule |
|---|---|
| Subscription ALLOW and DENY | **DENY wins** |
| Subscription across layers | must pass **every** layer |
| Row filters from several policies | **ANDed** |
| Two masks on one column | **strictest wins**: `NULLIFY > CONSTANT > HASH > REGEX_REPLACE > PARTIAL > ROUNDING > plaintext` |
| Mask and hide on one column | **hide wins** |
| No policy matches | **deny** |

A lower layer may only tighten. It may relax a higher one only if that higher
policy sets `allowLocalOverride: true` **and** the author holds the right to
override — and the override is written to `audit_policy_change` with a mandatory
reason.

### The mistake this rule exists to prevent

A table owner writes a local policy: *allow team A to read `customer`.* Team A
still cannot read it, because an org-level policy denies anyone with
`clearance < L2` on anything tagged PII. The local policy is not wrong; it is
simply not sufficient, and intersection means the stricter layer holds.

The Policy Builder must say so **while the local policy is being written**. A
user who discovers this in production has been failed by the tool, not by the
model.

## Data policies

Row filters come in five kinds: `ATTRIBUTE_COMPARE`, `IN_LIST`,
`ENTITLEMENT_JOIN`, `ALWAYS_FALSE`, `RAW_PREDICATE`. The first four are
structural and get validated; `RAW_PREDICATE` is an escape hatch that requires
elevated rights precisely because it is not.

`ALWAYS_FALSE` means the schema stays visible and no rows are — different from a
subscription denial, which hides the table entirely.

Column rules select columns **by facet**, not by name list. `HIDE` removes the
column from the projection; `MASK` keeps it and transforms the values; a
`condition` on a mask makes it a cell mask.

Masking functions and their intent:

| Function | Notes |
|---|---|
| `NULLIFY` | any type |
| `CONSTANT` | fixed replacement |
| `HASH` | SHA-256 with a **per-column** salt — joinable within the column, not correlatable across columns |
| `PARTIAL` | keep last *n*, for national IDs, phone numbers, card numbers |
| `REGEX_REPLACE` | e.g. email local part |
| `ROUNDING` | date of birth to year, salary to band |
| `CONDITIONAL` | wrapper that makes any of the above a cell mask |

## The decision

`PolicyDecision` is the entire contract between the engine and enforcement.
Nothing in it mentions SQL: the engine does not know which mode will run, which
is why the three compilers can be built in parallel and still agree.

It carries `reasons` — every restriction traced to a policy, a scope level and a
condition. This is not a debugging aid. A decision nobody can explain is a
decision no data team will let near production, and the simulator that renders
these reasons is what makes the system adoptable at all.

It also carries `unenforceable`: restrictions the selected mode cannot express,
such as a conditional cell mask under SQL Server dynamic data masking. Apply
must warn on these. A policy that silently does not apply is worse than one that
visibly fails, because everyone continues to believe the data is protected.
