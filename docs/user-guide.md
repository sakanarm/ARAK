# ARAK user guide

This guide is for the people who use ARAK day to day: people who look for data
and ask for access, table owners who decide who gets in, policy authors,
auditors and administrators. It says what each page is for and how to do the
common jobs. The assistant, NokRak, reads this guide to answer questions about
the app, so every section stands on its own.

The rules for combining policies are explained with worked examples in
*policy-conflict-resolution.md*, and the policy model itself in
*policy-spec.md*. The expression language has its own page in the app,
**Docs → Expressions** (`/docs/expressions`).

## What ARAK is

ARAK is a data access control platform. It decides who may read which tables,
which rows and which columns, and applies that decision when the data is read.

- **Metadata comes from OpenMetadata.** Tables, columns, tags, glossary terms,
  domains, data products, owners and custom properties are synced from
  OpenMetadata into ARAK's own catalogue. ARAK can also hold its own local tags.
- **Policies are written once, against metadata.** A policy says "mask every
  column tagged PII unless the person has clearance L2" rather than naming
  tables one by one, so a newly tagged table is covered the moment it is synced.
- **Decisions are enforced when data is read.** The main way today is the
  Query page: ARAK compiles the policy into the SQL before it reaches the
  database. Secure views (a view that applies the policy inside the database)
  can be reviewed and applied from the Enforcement page.
- **Nothing is visible by default.** When no policy lets a person in, the
  answer is no.

## Signing in and your account

Sign in on the login page with the username and password an administrator gave
you. Your platform roles decide which pages you see in the left-hand menu.

- **Profile** (click your name, or `/profile`) shows your username, email,
  account type, the directory the account comes from, your groups, your
  attributes (such as department, country or clearance) and your platform roles.
  Policies are written against these attributes and groups, so this is the page
  to check when access is not what you expect.
- **Changing your password.** On **Profile**, the **Password** section at the
  bottom asks for your current password and a new one twice. The new password
  needs at least 12 characters, cannot be your username and must differ from
  the current one; a sentence you will remember works well. Other browsers
  where you are already signed in stay signed in until their session ends.
  This is only for accounts ARAK holds the password of (account type
  `local`). If you sign in through a directory such as Entra ID, change your
  password there.
- **The first time you sign in** with a password an administrator chose for
  you (a new account, or after they reset it), ARAK shows **Choose a new
  password** before anything else. Enter the password you were given and one
  only you know; the page you were going to opens once it is saved. **Sign out
  instead** leaves without changing it, and you will be asked again next time.
- A wrong current password counts as a failed sign-in. After too many in a row
  the account is locked for a while and both signing in and changing the
  password are refused until the lock ends. An administrator can reset the
  password if you have forgotten it.
- An administrator creates local accounts under **Settings → Application
  roles**. Attributes of a local account are set by an administrator under
  **People**.

## Platform roles: who can do what

Every account has one or more platform roles. A role decides what you may do in
ARAK itself; it never lets you see data a policy withholds.

- **Platform admin** runs the platform: the OpenMetadata connection, syncs,
  registered data sources, accounts and roles. May write a policy at any scope.
  May not approve a policy they wrote themselves, and is not exempt from policy
  when reading data.
- **Policy author** writes policy at any scope, including organisation-wide,
  without the keys to the platform. May not activate a policy they wrote;
  somebody else moves it out of PENDING_APPROVAL.
- **Data owner** writes policy for the assets they own and below, and nothing
  else. Ownership comes from OpenMetadata. May not write an organisation-wide
  policy.
- **Auditor** reads everything and changes nothing: policies, decisions, the
  query log and the dashboard.
- **Requester** is everyone else. Reads the catalogue and their own access,
  and asks for more. A requester cannot run queries through the Query page.

Table owners and stewards named in OpenMetadata decide access requests and give
grants on their own tables whatever their platform role.

## Finding your way around (menu)

The left-hand menu shows only what your roles can use:

- **Home**: what you own and what applies to you. You can arrange it yourself.
- **Dashboard**: the whole estate at once (admins, policy authors, auditors).
- **Catalog**: tables, views and columns synced from OpenMetadata.
- **Governance**: classifications, tags, glossaries, domains and custom
  properties, and what each one covers.
- **People**: accounts, groups and attributes (administrators).
- **Policies**: subscription and data policies.
- **Query**: run SQL with policy applied.
- **Query log**: what was run through the Query page and how it ended.
- **Requests**: ask for access, and decide requests for tables you own.
- **Simulator**: see a table as another person would.
- **Enforcement**: review, apply and roll back secure views.
- **Settings**: connections, roles, workflows, templates and the assistant.

**Customize rail**, at the foot of the menu, shows or hides sections. Some are
hidden by default (Enforcement, for one, which is also reached from Settings),
and Customize rail puts them back. The bell at the top shows requests waiting
for you and news about your own requests.

## Home page

Home is laid out per account. Choose **Edit this page** to add, move or remove
widgets and pick an arrangement (one column, two equal, wide left, wide right,
three columns). Widgets include search, recent policies, governance coverage,
sources, access that is ending soon with a countdown, requests per table,
charts, and free-form notes, formatted content, links and videos.

- Your own layout always wins. If you have not arranged one, you get the layout
  an administrator set for your strongest platform role, and failing that the
  built-in one.
- **Reset to default** removes your layout and brings back the default.
- Administrators set the starting layout per role under **Settings → Home page
  per role**. It is a starting point, never an override of what somebody
  arranged themselves.

## Catalog: finding data

**Catalog** lists every table and view ARAK knows, with its tags, glossary
terms, domains and owners.

- The search box searches by name or fully qualified name
  (service.database.schema.table).
- **Ask NokRak**, next to it, searches by meaning instead: type what you are
  after in plain words, such as "customer emails by branch". NokRak reads
  descriptions, columns and tags, only of tables you may see, and says which
  you can read and which you would have to request.
- Narrow the list by asset type, by **connection** (*Connected*: ARAK can query
  it; *Not connected*: metadata only) and by **origin** (*From OpenMetadata*,
  *Read from source*, *ARAK only*). Click a tag, term or domain to filter on it;
  with several, an asset must carry all of them. **Clear** removes every filter.
- Switch between **List** and **Hierarchy** (service → database → schema →
  table). The list can be exported.
- The figures at the top count tables, columns, tagged columns and tables
  without an owner.

## A table's page

Open a table from the catalogue to see its page. The header shows its location
(service, database, schema), type, owners, domains, tier and certification.

- **Overview**: description, governance (tags, terms, domains, data products),
  custom properties, and a short summary of who may read it and what they see.
- **Contents**: on a service, database or schema, the objects under it.
- **Columns**: every column with its type, description and tags. Tags inherited
  from a higher level say where they came from. The search box also looks in
  descriptions.
- **Policies**: every active policy bound to this table, outermost layer first,
  split into *Subscription* (who may read it) and *Data* (what is visible once
  they are in).
- **Access** and **Audit**: shown to the table's owners and stewards and to
  auditors. See the sections on the Access tab and on grants.

The badge at the top right says where you stand (*You can query*, *You
requested access*, *You can request*, *You have no access*), and the button
beside it is what you can do about it:

- **Query** when you can already read the table. It opens the Query page on
  the source the table is connected through, with `SELECT * FROM
  <schema>.<table>` in the editor. Nothing runs until you press **Run**, and
  the result is what your masks and row filters leave, as for any query.
- **Access requested** when you already have an open request; it opens
  **Requests**.
- **Request access** when you cannot read it yet.

### Column descriptions

A column's description says what it holds, so people know what they are asking
for and approvers know what they are opening. It comes from OpenMetadata, or is
written in ARAK; when both exist, the one written in ARAK is shown, with an
**ARAK** label.

To write them, the table's owner or steward (or a platform administrator)
presses **Describe columns** on the Columns tab. Every column is listed in one
form: type or change a description, clear one to remove it, and **Save**. With
the assistant switched on, **Draft with NokRak** fills in drafts from the column
names and types (it never sees the data); read and correct them before you
save, because nothing is saved until you press Save. Every change is kept in
the audit log with what it replaced. Descriptions written in ARAK are not sent
back to OpenMetadata, and a sync does not overwrite them.

### Edit tags

On the Columns tab, **Edit tags** attaches a classification tag to the table or
to a column in ARAK, with a reason, when OpenMetadata does not have it yet. The
tags on offer are OpenMetadata's and the ones made in **Governance** (see
*Governance vocabulary and local tags*). A policy that selects by that tag
covers the column straight away.

## Asking for access

There are three ways to ask:

1. **From the table's page**: the **Request access** button at the top right.
2. **From the Query page**: when a query is refused because you lack access,
   the box *The owner can let you in* opens the same form, with your SQL
   attached.
3. **Requests → New request**: ask for one or several tables with one reason.
   Search for tables, choose them, say why you need them and for how long.

The form asks for a reason, a duration and sometimes a purpose or a reference
(for example a ticket number). What it asks and which durations it offers come
from the request template for those tables. When the tables' templates differ,
the stricter one applies.

After you send it, the request has a ticket number and its own page
(`/requests/<ticket>`), which you can share. You can withdraw an open request.

If no grant could let you in, because a DENY or a policy on an outer layer
refuses you, ARAK says which policy is in the way instead of sending the request
to an owner who could not help.

## Pre-authorize: asking ahead for a group

**Requests → Pre-authorize** asks ahead of need for a class of tables, for a
group rather than one person. Say for whom (groups, teams, or whoever holds
certain attributes), where (the service, database, schema or table the tables
are under), which tables (conditions on tags, terms or domains; *contains*
takes a tag's children and a domain's sub-domains with it), why, and for how
long. *What it reaches today* shows the tables the request would cover now.

## Request status: what each state means

- **Pending**: waiting for the people who approve it.
- **Approved**: the approvers said yes. Approving is not the same as giving
  access: somebody still has to configure it.
- **Configuring**: somebody has started configuring it.
- **Completed**: configured. It says how: a grant (never longer than you
  asked), an updated policy, or a newly created policy.
- **Rejected**: either an approver said no, or it was approved but the person
  configuring it could not give the access (the ticket then says "declined to
  configure it"). Both come with a note.
- **Withdrawn**: you took it back.

Pending, Approved and Configuring count as open.

**Requests → My requests** lists yours. The bell tells you when one changes.

## Deciding requests (owners and approvers)

**Requests → Inbox** (*Waiting for you*) lists requests at a stage you answer.
Open one to see its review:

- **The requester**: attributes, groups, roles, current grants and history.
- **What a grant would open**: each column as visible, masked or hidden, with
  its description and sensitive columns marked, and any row filter that would
  still apply.
- **Risk**: LOW, MEDIUM or HIGH, with the reasons.
- **Conflicts**: BLOCKER, WARNING or INFO, such as a policy that would still
  refuse them.
- **Suggestion**: decline, grant, update a policy, or create a policy draft.
  A suggestion never activates anything by itself.

Approve or reject with a note. After approval, the request is configured: give
a grant up to the requested end date, point to a policy that was updated or
created, or decline with a reason. A grant that a policy would still refuse is
turned away (the policy has to change first). A table with no owner falls to
the platform administrators, and shows in their inbox and bell.

## Access workflows (who approves what)

**Settings → Access workflows** decides who must approve a request, per scope:
an organisation default, then per service, database, schema or table. The most
specific workflow for the table applies.

- A workflow has **steps** that run one after another. Stages inside one step
  run at the same time.
- Each stage has approvers: the table's owner, steward or custodian, a platform
  role, a team, or named people.
- Each stage decides by **ALL**, **ANY** or **AT LEAST n** of its approvers.
- A rejection rule per stage: **VETO** (one rejection ends it), **QUORUM** or
  **FIRST_RESPONSE**.
- An administrator can answer for any stage.

## Request templates

**Settings → Request templates** sets what a request asks of the requester,
per scope or per tag or term, with a fallback *When nothing else applies*:
default, suggested and longest duration, how many days ahead it may start, the
shortest reason accepted, the purposes offered, a reference label, and guidance
shown to the requester. When a request covers tables with different templates,
the stricter setting of each applies.

## Grants: giving access directly

A grant lets one person or group read one table for a period. Table owners and
stewards give grants from the table's **Access** tab with **Grant access**.

- A reason is always required, and the end date must be in the future.
- You cannot grant to yourself.
- A grant can start later. Grants end by themselves on their end date.
- Edit or revoke a grant from its **⋯** menu. Revoking needs a reason. Revoked
  grants are kept in the history (the **Audit** tab), never deleted.
- A grant only opens the door. Masks, hidden columns and row filters from data
  policies still apply, and a DENY or an outer-layer policy can still refuse the
  person. A policy decides whether a grant may pass it (*Can a grant let
  somebody past this policy?* in the policy builder).

## The Access tab of a table

The **Access** tab answers "who can read this table, and how".

- **Access at a glance**: counts you can click to filter the lists below:
  grants in force, overruled, not started, expired, people who can read now,
  and people who see less than all.
- **Direct grants**: one line per grant with its state:
  - *In force*: live and letting people in.
  - *Overruled*: live, but a policy on an outer layer refuses everyone it
    covers, so it lets nobody in.
  - *Not started*: starts later.
  - *Expired*: ended (hidden by default).
  Search by name, reason or granter, filter by state, and page through long
  lists. Click a name or a reason for the full details, including **Lets in**:
  the people this grant actually lets in, which for a group answers who in it
  gets access.
- **How it is decided**: the policies that apply.
- **Who can read this now**: every person evaluated, how they got in
  (*Everyone*, *Direct grant*, *Via policy*, *Grant and policy*), and whether
  they see less than all (masked or hidden columns, row filters). When there are
  many people, a sample is evaluated and the page says so.
- **Outside ARAK**: accounts that can read the table directly in the database,
  bypassing ARAK's policy.

## Query page: running SQL with policy applied

**Query** runs SQL against a registered source with your policy compiled into
the statement before it reaches the database.

- Pick the source, write a statement, and run it. The explorer on the left lists
  the tables you can see; as you type, the editor suggests table and column
  names (this completion is built in, not the assistant).
- Only one read-only statement (SELECT, or WITH ... SELECT) is accepted. Anything
  else is refused.
- Masked columns come back masked, hidden columns are left out, and row filters
  are applied, exactly as your policies say. A statement ARAK cannot fully parse
  or resolve is refused rather than run (fail-closed).
- Limits: a row limit (at most 5,000 rows), a time limit, a limit on how many
  queries run at once, and a cost guard that refuses a statement the database
  estimates is too expensive. The refusal says which limit it was.
- A repeated query can be answered from the result cache. The result is marked,
  the policy was still applied, and the database was not asked again.
- **Purpose** records why you are reading, and some policies depend on it.
- **Ran as** lets an administrator see a query as another person would, for
  checking a policy.

When a query is refused for lack of access, the refusal says why. If the owner
can let you in, the box *The owner can let you in* opens a request with your SQL.

## NokRak on the Query page

When the assistant is turned on for you:

- **NokRak, write it**: describe what you want in words and NokRak writes a
  statement from the tables and columns you can see. It is put in front of you as
  a suggestion; you run it yourself.
- **Fix with AI**: when a statement fails because of a syntax error or an
  unknown column, NokRak proposes a corrected one. It never gets you around a
  refusal for lack of access; that is what a request is for.
- **Explain**: explains in plain words what a statement does: what it reads,
  joins and filters.

NokRak is sent metadata (table and column names, types, descriptions, tags) and
your statement or error. It is never sent result rows.

## Query log

**Query log** (`/audit`) lists what was sent through the Query page: when, by
whom, which tables, the outcome (*Executed*, *Refused*, *Failed*), the number of
rows and how long it took.

- Each entry shows the statement **As written** and **As it ran, with policy
  compiled in**.
- Filter by outcome, person, text, table and date.
- What you see depends on your role: administrators, policy authors and
  auditors see every query; table owners see their own queries and queries on
  their tables (a statement is hidden if it also touched someone else's table);
  everyone else sees their own.
- A query answered from the result cache is marked: the policy was applied, but
  the database has no record of that read.

## Dashboard

**Dashboard** (administrators, policy authors, auditors) shows the whole estate:

- **Key figures**: protected by policy, grants in force, open requests, proxy
  queries, decision time.
- **Needs attention**: things that want a decision, most urgent first, such as
  sensitive tables with no policy, grants with no end date, access not used in
  90 days, and requests waiting.
- **Coverage** of a chosen classification (PII by default): how many sensitive
  tables a policy protects.
- Queries per day and why queries were refused, most-read tables, most active
  people, grants, access ending in 14 days with a countdown, requests, and the
  health of the platform (data sources, OpenMetadata sync, enforced objects).

The dashboard never shows client addresses, statements or error texts.

## Policies: the two kinds

- A **subscription policy** answers "may this person read this table at all?"
  Its effect is ALLOW or DENY.
- A **data policy** answers "what do they see once they are in?": row filters,
  column masks and hidden columns.

Every policy has a **level** (where it applies): organisation, domain, service,
database, schema, table or column, and a **selector** that picks the assets,
usually by metadata (for example tags contains 'PII.Sensitive', or domains
contains 'Finance'). Tables that match later are covered automatically.

A policy's **subject rule** says who it is for:

- named principals: roles, teams, groups, users, or the asset's owners
  (*assetOwner*), any one of which is enough;
- **attributes** of the person (department equals FINANCE, clearance at least
  L2);
- an **expression** comparing the person with the asset, for example
  user.country == asset.prop('dataResidency');
- **time** windows (days and hours in a time zone, valid from and to);
- **context**: the network address the request came from, or the purpose given.

Everything in a rule must hold at once, except the list of principals, where one
match is enough. An empty rule matches nobody.

## How policies combine (conflicts)

When several policies apply to one table:

- **DENY wins** over ALLOW.
- **No policy matching means no access.**
- Across layers (organisation, domain, service, database, schema, table,
  column), the person must pass **every** layer that has an ALLOW. Within one
  layer, **one** matching ALLOW is enough.
- **Row filters add up** (AND): more policies, fewer rows.
- **The strictest mask wins** on a column: NULLIFY, then CONSTANT, HASH,
  REGEX_REPLACE, PARTIAL, ROUNDING, CONDITIONAL, then plain. **Hiding** a
  column beats any mask.
- A lower layer can only tighten. It can relax a higher policy only when that
  policy allows local override and the author has the right to override, and
  that is recorded with a reason.
- There is no "latest wins" and no priority number.

The most common surprise: a table policy that allows team A does not let team A
in if an organisation policy denies people without clearance on anything tagged
PII. The builder warns about this while you write. Worked examples are in
*policy-conflict-resolution.md*.

## Masking functions

A data policy can mask a column with:

- **NULLIFY**: returns null.
- **CONSTANT**: a fixed text such as ***REDACTED***.
- **HASH**: SHA-256 with a salt per column, so values can still be joined within
  that column but not matched across columns.
- **PARTIAL**: keep the last few characters (ID numbers, phone numbers, cards).
- **REGEX_REPLACE**: replace a pattern, such as the part of an email before @.
- **ROUNDING**: dates to a year, numbers to a band.
- **CONDITIONAL**: mask unless a condition holds, which makes a cell mask.
  It ranks below every unconditional mask, so a plain mask on the same column
  replaces it.

Columns are chosen by metadata: name or pattern, classification, tag, glossary
term, data type or custom property.

## Writing a policy (policy builder)

**Policies → New policy** opens the builder:

1. Choose the **kind** (subscription or data), the **level** and its
   **anchor** (which service, database, schema or table), and the **effect**.
2. Write the **selector** and the **subject rule**. For a data policy, add row
   filters and column rules (mask or hide).
3. Decide whether a grant may let somebody past this policy.
4. Check the read-back and the **impact**: *Assets matched*, *Newly covered*
   and *No longer covered*, and who it changes things for, before you save.

**NokRak, help me** (*Tell NokRak the rule*) drafts a policy from a sentence.
On an existing policy, *Tell NokRak what to change* suggests an edit. Either way
the result is loaded into the builder for you to review; NokRak never saves or
activates anything.

Data owners can write policies only at or below what they own.

## Policy lifecycle and approval

A policy moves through **DRAFT → PENDING_APPROVAL → ACTIVE**, and can later be
**DISABLED** or **ARCHIVED**. Only ACTIVE policies are enforced.

The person who wrote a policy cannot activate it: a second person approves it
out of PENDING_APPROVAL. Every edit is saved as a new version.

## Policy history, compare and restore

A policy's page (`/policies/<id>`) shows how to read it (a flowchart of who
passes and what they see), its configuration, and a **History** tab:

- **Every version** with who changed it, when and why.
- **Compare** any version with the current one, by meaning rather than text.
- **Restore** an old version: it is written as a new version (nothing is
  deleted), you see its impact first, and a reason is required.

## Explain a policy with NokRak

On a policy's page (`/policies/<id>`), the **Ask NokRak** panel under *In plain
words* has **Explain with NokRak**. Choose *English* or *ไทย (Thai)* first.
NokRak then says in words what the policy does: who it lets in or keeps out,
what it masks or filters, what it lands on now, and how it meets the other
policies bound to the same tables and columns. For example, a DENY elsewhere
still wins, and a policy that does not allow local override cannot be relaxed
beneath it.

NokRak is sent the policy, the tables and columns it is bound to (up to 20
named), and what the **Conflicts** tab works out about the others (up to 10).
It is sent no data from any table. It is not sent the names in the policy's
exemptions or approvers either, only how many there are. Its answer is a
reading aid. What the engine actually decides is what the **Simulator** shows.

The panel appears only when the assistant is on for you and your role is
offered *Explain a policy* (**Settings → Assistant → Who gets which job**).
Anybody who can open a policy's page can ask. The answer is not saved.

## Simulator: see a table as someone else

**Simulator** shows what a person would see before a policy reaches production.
Choose the **person**, the **table**, and optionally a **purpose**, a **from
address** and the **environment**. It shows whether they would get in, *what they
would see* (each column as visible, masked or hidden), *which rows* (the filters),
and the reasons: every restriction traced to the policy and layer it came from.

## Governance vocabulary and local tags

**Governance** lists the classifications and tags, glossaries and terms, domains
and sub-domains, data products and custom properties synced from OpenMetadata,
with what each covers. Tags and domains are hierarchical: *contains* in a
policy includes children and sub-domains, *eq* means exactly that level.

By default only **confirmed** tags count for enforcement. A tag OpenMetadata
merely suggested does not lock data until someone confirms it.

### Making classifications and tags in ARAK

When OpenMetadata does not have the tag you need yet, a platform administrator
or a policy author can make it in ARAK, on the **Classifications & tags** tab:

- **New classification**: a name, an optional display name, a description
  (required, so others know when to use it), and whether it is *mutually
  exclusive* (one tag of it per column or table).
- **Add tag** on a classification's row: a tag under it, including under one
  that came from OpenMetadata (for example PII.Payroll under PII). The form
  shows the full name it will get.
- **Edit** on a row made in ARAK: change its display name or description, or
  mark it **Disabled**. A disabled tag is no longer offered in Edit tags or
  policies, but where it is already attached it stays, and so does any mask it
  brings, until the table's owner removes it.

A name cannot contain a dot or a double quote, and cannot be changed later
(policies and tagged columns refer to it by name). Nothing is deleted; disable
it instead. What is made here carries a **made in ARAK** badge, is never
overwritten or removed by a sync from OpenMetadata, and is not written back to
OpenMetadata. Rows that came from OpenMetadata are changed there. Every change
is audited.

Once made, attach the tag with **Edit tags** on the table's Columns tab, and
select it in a policy like any other tag. Glossaries, domains and data products
still come from OpenMetadata only.

## People, groups and attributes (administrators)

**People** (`/principals`) lists accounts and groups with their attributes; filter
by attribute, and open a group to see its members. **Settings → Local groups**
creates groups and manages their members. **Settings → Application roles**
creates local accounts and assigns or withdraws platform roles (a data owner role
takes a scope). Every change is audited.

## Data sources and enforcement (administrators)

- **Settings → Registered sources** (`/sources`) lists the databases ARAK queries
  and enforces policy in: PostgreSQL and SQL Server today. A source's credential
  is always a reference to a secret store, never a password typed into ARAK.
- **Enforcement** reviews, applies and rolls back **secure views**: a view that
  applies the policy inside the database. **Dry run** shows exactly what would
  run and the rollback, **Apply** runs what was reviewed (and refuses if
  anything changed since the review), and **Roll back** undoes it after
  confirmation.
- **Settings → OpenMetadata connection** and **Sync & reconcile** control where
  metadata comes from and when it is refreshed. **Service & build** shows the
  running version and the last crawl.

## The assistant (NokRak)

NokRak is ARAK's assistant, in the corner of every page and behind **Ask NokRak**
in the catalogue. It can:

- find tables you can read or may request, and describe their columns;
- write a query as a card you put in the editor yourself;
- draft a policy, or suggest a change to one, for you to review;
- explain a policy in words on its page, including how it meets the others;
- draft column descriptions for a table's owner to correct and save;
- read the query log and the dashboard as your role allows;
- open the right page for you;
- answer questions about how ARAK works, from this guide.

It cannot run a query, save or activate a policy, approve or submit a request,
or change a setting. It sees metadata only, never rows, and only what you may
see.

**Settings → Assistant** (`/settings/assistant`): each person can point the
assistant at their own gateway with their own key (it applies to them only and
is off until they turn it on). Administrators can set a shared gateway, allow or
forbid personal gateways, and decide per role which jobs the assistant may do
(*Who gets which job*). A key is never shown again once saved.

## Common questions

- **Why can't I read a table I was granted?** A policy on an outer layer, or a
  DENY, still refuses you, or the grant has not started or has ended. The
  table's page and the refusal on the Query page name the policy. Your owner
  can see the grant as *Overruled* on the Access tab.
- **Why is a column masked when I have access?** Access to the table and what
  you see inside it are separate: a data policy masks the column. The strictest
  mask on a column wins.
- **Why do I see fewer rows than a colleague?** Row filters from several
  policies add up, and they often depend on attributes such as branch or
  department. Check your attributes on your Profile.
- **Who approves my request?** The access workflow for that table: usually its
  owner or steward. A table with no owner goes to the platform administrators.
- **How long can I ask for?** The request template for the table sets the
  longest duration.
- **Can the assistant give me access?** No. It can open the request form's page
  for you; a person decides.
