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
  Until the new password is saved the account can do nothing else in ARAK:
  every other request is refused, whether it comes from the browser or from
  a script signing in with the same account.
- A wrong current password counts as a failed sign-in. After too many in a row
  the account is locked for a while and both signing in and changing the
  password are refused until the lock ends. An administrator can reset the
  password if you have forgotten it.
- An administrator creates local accounts under **People** or **Settings →
  Application roles**. Attributes of a local account are set by an
  administrator under **People**.

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
- **Settings**: connections, roles, workflows, templates, purposes and the assistant.

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
save, because nothing is saved until you press Save. To draft only some
columns, tick the box in front of each one (the box in the header ticks every
column shown, so a search or **Only columns nobody has described** narrows it
first); the button then reads **Draft N picked with NokRak**. A ticked column
that already has a description gets a new draft in the form, which is handy to
rewrite it in Thai, and its saved description stays until you press Save. With
nothing ticked, NokRak drafts the empty columns. Anything you type in a field
while NokRak is working is kept. Every change is kept in
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

The purpose is chosen from the register (see *Purposes*). When the template
lists purposes, only those are offered; otherwise any purpose in use is, and
*No particular purpose* is allowed unless the template requires one. Retired
purposes are not offered. A purpose can limit how long access for it lasts: when
it does, the longer durations and *Until revoked* disappear and the days are
brought within the limit.

When a table holds sensitive data and the purpose chosen may not be used for it,
the form says so under the purpose (see *What counts as sensitive data*). If
the rule only warns, you can still send, and whoever decides is told. If it is
enforced, the form will not send until you choose a purpose that allows
sensitive data.

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
The purpose is chosen from the register, and a purpose with a longest access
takes the longer durations off the list.

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
- **Risk**: LOW, MEDIUM or HIGH, with the reasons. A purpose that may not be
  used for the sensitive data in the table is one of them (see *What counts as
  sensitive data*).
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

The purposes offered are picked from the register. A purpose a template already
listed before it was retired stays on that template until you remove it, but
you cannot add a retired or unlisted one.

## Purposes

**Settings → Purposes** is the register of what data may be used for. A policy
can allow a table only for some purposes, a request template offers them, and a
request or a query names one. Everybody signed in can read the register.

Each purpose has:

- a **key**, which policies, templates, requests and queries store. It is lower
  case (letters, digits, dots, dashes and underscores, like `fraud-analysis`)
  and cannot be changed once the purpose exists;
- a **name**, which people pick from. No two purposes share a name or a key;
- what it is for;
- the **legal basis** under the PDPA: consent, contract, legal obligation,
  vital interest, public task, legitimate interest, or research or statistics.
  It can be left unrecorded until somebody who knows records it;
- whether **sensitive data** (PDPA section 26) may be used for it. ARAK checks
  this on every table that holds sensitive data (see *What counts as sensitive
  data* below);
- who **answers for** it (for example the DPO or a steward);
- the **longest access** a request for it may ask for, from 1 to 365 days.
  A request for the purpose cannot ask for longer, or until revoked, and a
  grant given for it cannot last longer or go without an end.

The page has three parts:

- **In use**: the purposes people may choose. Administrators, policy authors,
  data owners and auditors also see how many policies, templates, open requests
  and decisions of the last 90 days name each one.
- **Named but not listed**: words a policy, a template, an open request or a
  recent query uses that are not in the register. What names them keeps
  working, but nothing new may name them. **List it** opens a new purpose with
  that name and a key made from it.
- **Retired**: purposes nobody may choose any more. A purpose is retired rather
  than deleted, so a request from last year still says what it was for.
  Retiring and reinstating ask for a reason.

**History** on a purpose shows every change: who made it, when, what changed,
and the reason given for retiring or reinstating.

Platform administrators and policy authors can add, edit, retire and reinstate
purposes. Everybody else can read them.

### What counts as sensitive data

The bottom of **Settings → Purposes** says which tables hold sensitive data, and
what happens when a purpose that may not be used for sensitive data is named on
one of them. The same answer marks sensitive columns in the review of a request.

- **Count the built-in names** (on as installed): `PII` and `PersonalData`, and
  any classification or tag whose name says sensitive, confidential, restricted
  or secret, but not one saying non-sensitive or public.
- **Also counts**: classifications, tags, glossaries or glossary terms that
  count as well. A classification or a glossary covers everything in it; a tag
  or a term covers itself and everything beneath it.
- **Never counts**: labels that never count, even when the built-in names would
  (for example `PII.Public`). A label cannot be in both lists.

Only confirmed labels count; a label OpenMetadata merely suggested does not. A
table counts when it, or one of its columns, carries a label that counts.

The **mode** says what happens when a query or a request names a purpose that
may not be used for sensitive data (or names none) on a table that holds some:

- **Off**: nothing is checked; the purpose is recorded as it always was.
- **Warn** (as installed): it goes ahead. The Query page shows the warning above
  the results, the query log entry is marked, the request form says so before
  sending, and whoever decides the request is told.
- **Enforce**: the query is refused and the refusal names the purpose; in the
  Query log it reads *Purpose not for sensitive data*. The request forms say so
  before anything is sent, and do not send until a purpose that allows
  sensitive data is chosen.

A purpose missing from the register allows nothing sensitive. A table a policy
already refuses you is refused for that, not for the purpose. Pre-authorize is
not checked. The Simulator and the Decision API do not apply this check yet.

**Measure coverage** shows how many tables and views the rule covers now, how
many sensitive columns are in them, some examples, and how far each label
reaches. While editing, **Measure what it covers** does the same for the draft
before you save it: measure before you choose Enforce. Every change asks for a
reason and is kept in **History**.

Platform administrators and policy authors can change the rule. Data owners and
auditors can also measure it and read its history. Everybody signed in can read
it.

## Grants: giving access directly

A grant lets one person or group read one table for a period. Table owners and
stewards give grants from the table's **Access** tab with **Grant access**.

- A reason is always required, and the end date must be in the future.
- You cannot grant to yourself.
- A grant can start later. Grants end by themselves on their end date.
- **What for** names a purpose from the register (see *Purposes*); it is
  optional. Only purposes in use are offered. A purpose with a longest access
  bounds the grant: it must have an end, no more than that many days after its
  start, so the longer durations and *No expiry* disappear, the days are brought
  within the limit, and the form says why when the dates you typed go past it.
- A grant made from a request keeps the request's purpose. If the purpose was
  retired, or its longest access was shortened, after the request was sent,
  completing the request is refused until the grant fits.
- Editing a grant keeps its purpose, and its longest access still counts from
  the grant's original start.
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
  A grant given for a purpose shows the purpose's name beside it. Search by
  name, reason, purpose or granter, filter by state, and page through long
  lists. Click a name or a reason for the full details, including the
  **Purpose** and its legal basis (or *None named*) and **Lets in**:
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
- In the explorer, rest the pointer on a column (or move to it with the keyboard)
  to see its type and description. A column with no description says so, and
  where to add one: the **Columns** tab of the table's catalog page.
- Right-click a table or a column for **Open in the Data Catalog** (a new tab;
  for a column it opens at the table's columns), **Insert into the editor** and
  **Copy the full name**. The arrow keys move through the menu and Esc closes it.
- The panels can be resized: drag the edge between the explorer and the editor,
  the edge between the editor and the results (up to about one line of SQL, or
  down until the results keep a couple of rows), and the edge between the editor
  and an explanation. Each edge also moves with the arrow keys when it has the
  focus (hold Shift for bigger steps). The sizes are remembered in your browser.
- Only one read-only statement (SELECT, or WITH ... SELECT) is accepted. Anything
  else is refused.
- Masked columns come back masked, hidden columns are left out, and row filters
  are applied, exactly as your policies say. A statement ARAK cannot fully parse
  or resolve is refused rather than run (fail-closed).
- A statement is also refused when the database could read it differently from
  the way ARAK read it. That covers an optimizer hint (`/*+ ... */`), a string
  written with a prefix such as `E'...'`, a JDBC escape (`{...}`), a session
  variable (`@name`) on MySQL and SQL Server, `$` outside a string on
  PostgreSQL, and `#` outside a string on MySQL. The refusal says which it was.
  Ordinary comments are fine: they are taken out before the statement is sent.
- On a MySQL source, name a table as `database.table`. A backslash inside a
  string is an ordinary character there, so write a quote inside a string by
  doubling it (`'it''s'`), not as `\'`. Double-quoted strings work as usual.
  The session runs in UTC, so `NOW()` and `CURDATE()` are UTC there whatever
  the server's own clock is set to, and a `TIMESTAMP` comes back in UTC.
- Limits: a row limit (at most 5,000 rows), a time limit, a limit on how many
  queries run at once, and a cost guard that refuses a statement the database
  estimates is too expensive. The refusal says which limit it was. On a MySQL
  source the cost guard applies to **All rows** downloads only, because
  MySQL's estimate takes no account of the row limit.
- The row limit is how many rows the screen shows. When a result has more, it is
  marked as cut short and an **All rows** button appears beside it: it runs the
  same statement again, with the same policy, and downloads every row as a CSV
  file (it opens in Excel). The download is always as yourself, is never served
  from the cache, and has a longer time limit (10 minutes by default). Because it
  reads every row, the cost guard prices it without the row limit; if it is
  refused as too costly, a WHERE that narrows it usually brings it under. If the
  read fails part-way, the download fails rather than saving part of the table.
- A repeated query can be answered from the result cache. The result is marked,
  the policy was still applied, and the database was not asked again.
- **Purpose** records why you are reading, and some policies depend on it.
  It is chosen from the register; a retired or unlisted purpose is refused.
  On a table holding sensitive data, a purpose that may not be used for it is
  warned about above the results, or refused, depending on the rule (see *What
  counts as sensitive data*).
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
- **Find data**: say what you are looking for in your own words, in English or
  Thai (for example "customer emails and the branch they belong to"), and NokRak
  names the tables that hold it, why each fits, and the columns that matter.
  You do not need to pick a source first. Only tables you can query or may
  request are ever looked at; each card says which:
  - *You can query*: **Put in the editor** writes `SELECT <columns> FROM
    schema.table` into the editor and switches to that table's source. Nothing
    runs until you press Run, and your masks and row filters still apply.
  - *You can request*: **Request access** opens the same form as on the table's
    page.
  If nothing fits, NokRak says so; try other words or browse the catalogue.
  When a table matched but you can neither query nor request it (a rule that an
  approval alone would not lift keeps you out, or no source is connected), NokRak
  does not name it, but says how many there were, so "nothing found" means
  nothing matched at all. Their pages in the catalogue say why. The same
  goes for asking NokRak in the chat ("is there a table about purchase
  orders?"): it tells you such tables exist rather than that there are none.

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
- A download of every row (**All rows** on the Query page) is marked
  **downloaded**, whether it succeeded, was refused or failed, so a copy of a
  table leaving ARAK is easy to find.

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

## Explain the dashboard with NokRak

Under the key figures on the **Dashboard**, the **Ask NokRak** panel has
**Explain with NokRak**. First choose what to read in **About**: *Whole
dashboard*, *Coverage*, *Queries & refusals*, *Access & grants*, *Requests* or
*Platform health*. Then choose *English* or *ไทย (Thai)*. NokRak says in a few
sentences what stands out for the window and label you have on screen, what
looks unusual and why, and which table, person or page to look at next.

NokRak is sent the dashboard's counts: the key figures, the *Needs attention*
list, up to 15 labelled tables with their counts (owners only as a number),
queries by day, refusal reasons, grants and requests. People are numbered
instead of named, as [P1], [P2] and so on, before anything leaves ARAK, and
their names are put back into the answer for you. It is sent no data from any
table, no statement and no client address. Its answer is a reading aid; the
numbers on the dashboard are what count.

The panel appears only when the assistant is on for you and your role is
offered *Explain the dashboard* (**Settings → Assistant → Who gets which job**).
Anybody who can open the dashboard can ask. The answer is not saved.

## Policies: the two kinds

- A **subscription policy** answers "may this person read this table at all?"
  Its effect is ALLOW or DENY.
- A **data policy** answers "what do they see once they are in?": row filters,
  column masks and hidden columns.

Every policy has a **level** (where it applies): organisation, domain, service,
database, schema, table or column, and a **selector** that picks the assets,
usually by metadata (for example tags contains 'PII.Sensitive', or domains
contains 'Finance'). Tables that match later are covered automatically.

Both kinds can sit at any level. The level and its **anchor** (the fully
qualified name it is measured from, such as a service, a database or a schema)
only narrow where the policy looks: step 3, *Which assets it covers*, then
picks among the assets under the anchor. An organisation-level policy looks on
every source. Whatever the level, a policy with nothing selected in step 3
covers nothing.

Under step 3, **What this covers right now** lists the tables the draft would
cover if it were saved now, grouped by schema, with how many of the tables in
scope that is. For a data policy it also shows, on each table, the columns the
column rules pick. A table's name opens its catalog page in a new tab. The
panel follows your edits and saves nothing. A long list can be filtered by
name; past 200 tables it shows the first 200 and says how many there are in
all. It shows names only, never rows.

A policy's **subject rule** says who it is for:

- named principals: roles, teams, groups, users, or the asset's owners
  (*assetOwner*), any one of which is enough;
- **attributes** of the person (department equals FINANCE, clearance at least
  L2);
- an **expression** comparing the person with the asset, for example
  user.country == asset.prop('dataResidency');
- **time** windows (days and hours in a time zone, valid from and to);
- **context**: the network address the request came from, or the purpose given.
  Purposes are ticked from the register. Saving a rule that names a purpose
  the register does not list, or has retired, is refused, unless the policy
  already named it.

Everything in a rule must hold at once, except the list of principals, where one
match is enough. An empty rule matches nobody.

**Several values.** *is one of* and *is none of* take a list, in the selector
and in the attributes alike. Type a value and press **Enter** (or type a comma);
each value becomes its own item, and × removes it. So:

- *either of two values of one attribute*: department **is one of** FINANCE,
  RISK;
- *either of two attributes*: write it in the expression, for example
  user.department == 'FINANCE' || user.clearance >= 'L2' (attribute rows are
  always joined with *and*).

*is none of* with no values, or *is not* with nothing after it, is refused when
you save: either would match everybody. A policy saved before this change with
its list typed into one box is shown as separate items and saved in the new
form. Until it is saved again it is read the same way, so "FINANCE, RISK" means
either department, not one department with a comma in its name.

**Which rows they see.** A row filter that compares a column with the person
(*Column matches their own attribute*, *Column is one of their values*, *Column
is one of the values a mapping table gives them*) picks that column in one of
two ways:

- **Column named**: type its name, for example branch_code. It fits only the
  tables that call the column that.
- **Column tagged**: pick it by its metadata, as a column rule does, for
  example tags contains Org.Branch. ARAK finds the column in each table when
  it decides, so one policy covers a branch column called branch_code in one
  table and sale_branch in another. Only tags put on the column itself count;
  a tag on the table does not pass down to its columns here.

A table with **no** column that matches shows **no rows**, rather than all of
them. A table with **several** is filtered on each, so a row must pass every
one (a transfer with from_branch and to_branch shows only transfers inside the
person's own branch). A filter uses one way or the other, not both.

**Rows given by a mapping table.** Sometimes what a person may see is not an
attribute of theirs but a row in another table. A table holds sales by
division; the person is in department AA; a mapping table says department AA
belongs to division A. The kind *Column is one of the values a mapping table
gives them* writes exactly that:

- **Column**: the column of the filtered table, for example division. Pick it
  by name (*Column named*) or by its tag (*Column tagged*, for example tags
  contains Org.Division), as for the other filters above. By tag, every column
  that carries the tag is filtered through the mapping, and a table with no
  such column shows no rows.
- **Value column** and **Mapping table**: which column of which table holds
  the values a person may see, for example division in
  warehouse.sales.ref.department_division. Write the table's full name
  (service.database.schema.table). It must be in the catalog: saving a filter
  whose mapping table, or one of its columns, is not there is refused.
- **Keys**: how a person is matched to rows of the mapping. Each key compares a
  column of the mapping with one of the person's attributes (department with
  their department). With several keys, a mapping row counts only when every
  key matches. A person without a value for one of the attributes sees no rows.

It is read in one of two ways, chosen under *The mapping is*:

- **Join it into the query**: the source reads the mapping as part of each
  query, so a change to the mapping counts from the next query, and a person
  may map to any number of values. The mapping table has to be on the same data
  source, and in the same database, as the table it filters; otherwise the
  query is refused with a message that says so.
- **Read the values first**: ARAK reads the person's values from the mapping
  when the query runs, then filters on that list. The mapping may be on another
  data source. The query is refused, not cut short, when a person maps to more
  than 1,000 values, and when the value column is not text, a whole number, a
  decimal, a UUID or a date. The values read go into that one query only: they
  are not kept, and no assistant is given them. The rewritten SQL the person
  sees carries them, since they are the values that person may see.

Things to know:

- **The mapping table decides access.** Its own policies do not apply when ARAK
  reads it for a filter, and whoever can change it decides who sees what. Keep
  it where only the people who could write this policy can change it.
- **The database compares the values**, so write them in the mapping exactly as
  the tables hold them. Letter case follows each column's own rules: on most
  columns it counts, on a case-insensitive one (citext, or a case-insensitive
  SQL Server collation) it does not.
- **A mapping that is missing, moved or unreadable refuses the query** rather
  than showing no rows, so the fault reaches someone who can report it. A
  person the mapping gives nothing to sees no rows.
- **One step only.** A mapping from department to division to region is two
  steps; put them in a view that gives department and region side by side, and
  map on the view.
- **Query API only.** A secure view over the table shows no rows to anybody,
  and native source config does not carry the filter; the builder says so when
  you choose a mode. The simulator names the mapping table and the person's own
  key values, never the values the mapping gives them.

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
  Not available on SQL Server; on MySQL it needs version 8.0 or later.
- **ROUNDING**: dates to a year, numbers to a band.
- **CONDITIONAL**: mask unless a condition holds, which makes a cell mask.
  It ranks below every unconditional mask, so a plain mask on the same column
  replaces it.

Columns are chosen by metadata: name or pattern, classification, tag, glossary
term, data type or custom property.

## The policy list

**Policies** lists every policy, a page at a time. Each row shows:

- its name, its kind (*Subscription* or *Data*) and, for a subscription, a
  *Deny* mark when it denies, with a one-line readback of what it says;
- **Connection**: where it runs. This is either the connection it is confined
  to, with the mode that connection enforces it by today (*Query proxy*,
  *Secure view*, *Native source config* or *Not enforced yet*), or **Every
  connection**, where each connection enforces it in its own mode. A service
  that no registered connection carries is marked *Not a registered
  connection*. *No connection* means the policy is anchored on one service but
  selects assets on another, so it covers nothing;
- its level and anchor, its state and environment, and when and by whom it was
  last changed.

A policy does not store a connection. The list reads it from the policy: a
level of service or below anchors it on one service, and a selector requiring
*service equals x*, which is what choosing a connection writes, confines it to
*x*. Anything that could reach a second service, such as an *or* with one
branch open, a *not*, or a bare schema name that every service may have, reads
as **Every connection**.

Narrow the list with the tabs (*All policies*, *Subscription*, *Data*), a
search, and the **state**, **level**, **connection** and **mode** filters.
*Every connection* in the connection filter shows only the policies written
for every connection. A mode shows the policies confined to a connection set
to that mode today; policies for every connection are not included, since
their mode depends on the connection. **Clear** resets everything but the
tab.

When nothing matches, the page says whether the filters or the platform are
the reason. With filters set, the policies they hide still apply; clear the
filters to see every policy. **No policies yet** appears only when there are
none at all, and then every request is denied by default.

## Writing a policy (policy builder)

**Policies → New policy** opens a menu with two choices: **Subscription
policy** (who gets in) and **Data policy** (what they see). The **Create** menu
in the header offers the same two. The page that opens is titled with the kind
you chose, and neither it nor the form offers the other kind; go back to
**Policies** to start the other one. Each kind has its own address, which you
can bookmark: `/policies/new/subscription` and `/policies/new/data`. It then
asks **where the policy runs**, on a page of its own, before the form opens:

- **What kind of policy**: asked here only when you arrive without choosing
  one, for example from **New policy** on the home page.
- **Which database**: first the database product, then the connection.
  - Each product has a card with its logo: PostgreSQL, SQL Server, and any
    other engine ARAK governs. The card shows how many connections run that
    product, how many tables they hold, and the modes they are enforced by.
    A product with no registered connection is shown greyed out.
  - Choosing a product opens **Which <product> connection** below it, with a
    card for each of its connections (version, tables, mode) and a search box
    when there are more than six. A product with a single connection chooses
    it for you.
  - **Every connection** is the organisation-wide choice.
  - **Databricks** opens a page of its own, because Databricks policies are
    configured in a separate builder. That builder is not available yet, and
    nothing can be saved there.

  Choosing a connection starts step 3 (*Which assets it covers*) on that
  connection's assets, which you can narrow further. A selector you write
  yourself is kept if you go back and choose another connection. Choosing a
  product does not by itself make a policy cover every connection of that
  product; for that, choose **Every connection** and narrow it in step 3.
- **How it will be enforced**: *Query API*, *Secure view* or *Native source
  config*. On one connection only the mode that connection is set to can be
  chosen, and choosing the connection chooses it; the other modes are greyed
  out and marked *Not set on this connection*. **Every connection** keeps all
  three, since each connection is enforced by its own mode. The builder checks
  the policy against this mode while you write it and marks it *Chosen* in the
  rail. *Query API* is not offered on an engine it has nothing for. For a
  subscription policy on PostgreSQL (or on **Every connection**), *Native
  source config* is marked *Pushed as PostgreSQL roles*: once the policy is
  saved, plan and apply its role under the policy's **PostgreSQL roles** tab.
  Everywhere else, and for every data policy, it is marked *Checked, not
  applied yet*: ARAK does not push that native config yet.

The mode is **not saved in the policy**. A policy is always enforced by the mode
its connection is set to under **Sources**, so a policy cannot quietly stop
applying because somebody changed a connection's mode. To write a policy for
another mode on one connection, an administrator first changes that
connection's mode under **Sources**. The chosen connection and mode show as chips at the
top of the form, and **Change** goes back to the page. *Configure the policy*
stays disabled until a connection and a mode are chosen.

A policy that arrives already written (suggested by an access request, or
loaded from NokRak) skips this page and opens the form directly.

Then, in the form:

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
ARCHIVED is final: an archived policy is shown as **Archived (Not active)**,
lets nobody in and keeps nobody out, and stays only as history.

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
Choose the **person**, the **table**, and optionally a **purpose** (from the
register), a **from address** and the **environment**. It shows whether they would get in, *what they
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

Administrators can also add a local account from **People** itself: **Add local
account** opens the same form as Settings → Application roles (username,
display name, kind, email, a first password and a role to start with). The new
account appears in the list at once, with a link to its page. No sync touches
a local account. A person chooses their own password the first time they sign
in, and can use nothing else until they do.

## Data sources and enforcement (administrators)

- **Settings → Registered sources** (`/sources`) lists the databases ARAK queries
  and enforces policy in: PostgreSQL, SQL Server and MySQL. A source's credential
  is always a reference to a secret store, never a password typed into ARAK.
- **Registering a MySQL source**: choose MySQL on the first step (port 3306 is
  offered), then give the host and a login that can read the tables. In MySQL a
  database is what the other engines call a schema, so **Database** works
  differently: name one and the import reads that database; leave it blank and
  the import reads every database the login can see, except MySQL's own
  (`mysql`, `information_schema`, `performance_schema`, `sys`). A table is
  named `source.default.database.table`, which is also how OpenMetadata names
  a MySQL table, so linking the source to an OpenMetadata service gives one
  asset per table rather than two.
- **What MySQL sources can and cannot do**: policy on a MySQL source is
  enforced on the Query page (the query proxy), with row filters, column
  masks, cell masks and hidden columns. Secure views are not available on
  MySQL, so that mode is not offered when registering one and **Enforcement**
  refuses it. The check of who can read a table directly at the source is not
  available on MySQL either, so restrict direct logins to the database
  yourself. Tested with MySQL 8.4.
- **Enforcement** reviews, applies and rolls back **secure views**: a view that
  applies the policy inside the database. **Dry run** shows exactly what would
  run and the rollback, **Apply** runs what was reviewed (and refuses if
  anything changed since the review), and **Roll back** undoes it after
  confirmation.
- **PostgreSQL roles**, a tab on a subscription policy's page, pushes that
  policy as a database role to a PostgreSQL source set to *Native source
  config*, so people read with their own login. See *PostgreSQL roles* below.
- **Settings → OpenMetadata connection** and **Sync & reconcile** control where
  metadata comes from and when it is refreshed. **Service & build** shows the
  running version and the last crawl.

## PostgreSQL roles: a subscription policy on the database itself

A subscription policy is normally enforced on the Query page. On a PostgreSQL
source set to **Native source config** it is also pushed to the database as a
**role**, so that people read with their own database login, from any tool,
and the database itself lets them in or keeps them out.

Open a subscription policy and choose the **PostgreSQL roles** tab (data
policies do not have it). ARAK keeps one role for the policy on each
PostgreSQL source set to Native source config, named
`arak_sub_<policy>_<source>` after the first eight characters of each id. The
role cannot log in. It holds the tables the policy applies to, and its members
are the logins of the people the policy lets in.

**The connection's mode.** A connection has one enforcement mode, set under
**Sources**, and it holds for its subscription and data policies alike. Roles
are planned and applied only on a source set to *Native source config*. On a
source set to any other mode the tab marks it *Not set to native*, says which
mode it is enforced by, and Plan is off. On a native source the Query page
keeps working: a query sent there is still checked by ARAK against every
policy.

**The Query page on a native source.** It reads a table only when the
database would let the same person in too: the role of a policy that lets
them in must be applied (or waiting on a newer plan), at Read, and hold that
table. Before the first apply, after a roll back, at Browse, or after
somebody changed the role by hand, the query is refused with the policy's
name and the reason, and the refusal is on the audit like any other. A
direct grant has no role of its own and is let through. Plan and apply the
role to read the table again.

A source with a role on it keeps its native mode. To change its mode under
Sources, roll back each policy's role on it first; until then saving another
mode is refused and says so. If a role is ever found on a source that is no
longer native, it lets nobody in: **Check** says *Nobody should hold the
role: roll it back*, and the sweep takes its members out. Check and Roll back
still work there.

**Levels.** Choose one before you plan:

- **Browse**: connect to the database and use the schemas, with no rows.
- **Read**: Browse, and `SELECT` on every table the policy applies to.

Under Browse, `information_schema` lists only the tables a person holds a
privilege on. PostgreSQL's own catalogue (`pg_tables`, `pg_attribute`) still
shows every table and column name to anybody who can connect, and the plan
warns about it. If every login may connect to the database (PostgreSQL's
default `CONNECT` for `PUBLIC`), the plan warns about that too. ARAK reports
it and does not change it.

**Source setup (administrators).** Below the role, **Source setup** holds:

- **Push account**: the login ARAK uses to make the role. It needs
  `CREATEROLE`, and it must own, or hold `GRANT OPTION` on, the tables. It
  must not be a superuser. It must not be the read-only login the query proxy
  uses: ARAK refuses that one. Give a login and password, which ARAK seals at
  once, or a reference (`vault://`, `azurekeyvault://`, `env:`). The account
  is never shown again. Without it nothing can be planned or applied, and the
  sweep cannot take anybody out.
- **Database logins**: which existing login belongs to which person. ARAK never
  creates a login. Nobody can map their own. One login used by several
  people joins the role only when every one of them qualifies.
- **Setup history**: every change to the account and the logins, with who and
  when.

**Plan.** **Plan** works out the role and changes nothing on the source.
Policy authors and administrators can plan. The plan shows:

- how many people were decided, the logins in the role, the tables and the
  statements;
- **Members**, with the people each login stands for;
- **Kept out**, with the reason for each person, for example *denied by* a
  DENY policy, *not let in by* another policy that every reader of the table
  also has to pass, or an exemption;
- **Let in, but no login here** and **Shared logins left out**;
- **Others who can already read these tables**: grants ARAK did not make,
  which it leaves alone;
- **Will run** (the exact SQL) and **Rollback, if needed**.

A plan expires after a while. At Read, people whose view of a table is
narrowed by a data policy (a row filter, a mask or hidden columns) are kept
out, because a plain `SELECT` would show them what the data policy hides. They
still read that table on the Query page.

**All or nothing.** A role gives the same access on every table in it. A
person refused on any one of its tables stays out of the whole role, and the
plan lists the tables they lose with it. If that is not what you want, split
the policy so each part binds the tables that belong together.

**Apply (administrators).** **Apply this plan** runs exactly the plan you
read. ARAK refuses a plan that was already applied, that belongs to another
policy, or that no longer matches what the policy says or what the source
holds. Plan again to see the difference.

**What a role cannot carry.** A role cannot check the time, the client's
address, a purpose or an expression over the request. The tab says *A database
role cannot carry this policy*, and Plan is refused, for:

- a policy with time windows, a network range, a purpose or a `context.`
  expression;
- a DENY policy. A DENY has no role of its own: it keeps the people it names
  out of the roles of the ALLOW policies it overlaps, and their plans say who.

Keep such a policy on the query proxy, or split the parts a role can hold into
their own policy. Plan is also refused for a policy that binds no table in the
source's database. Tables in another database on the same server are left out
with a warning, because the role connects to one database. A draft or
disabled policy lets nobody in, so its plan gives the role no members.

**Check.** **Check** reads the source and compares it with the policy. The
role shows **Applied**, **Drifted** (somebody changed what ARAK granted, by
hand), **Behind the policy** (the policy has moved on since the last apply),
**Failed**, **Rolled back** or **No role yet**. ARAK owns only the grants its
push account made. A grant somebody else made to the role is reported and
never revoked. ARAK does not repair drift on its own: plan and apply to put it
back.

**The sweep.** Every 10 minutes ARAK takes people out of the role who no
longer qualify: they left the group, their login was unmapped, the policy was
disabled or ended, or the source is no longer set to native source config. It
never adds anybody: new people join at the next apply.
Each removal is on the history as *EXPIRE* by `system:native-sweep`. A source
that is switched off, or has no push account, is skipped.

**Roll back (administrators).** **Roll back** shows the statements first.
**Drop the role** revokes what ARAK granted and drops the role, and its
members lose that access at once. If somebody else granted something to the
role, that grant stays, and so does the role.

**History** lists every plan, apply, check, sweep and rollback for the policy
on that source, with who did it and why. No password and no client address is
ever shown.

## The assistant (NokRak)

NokRak is ARAK's assistant, in the corner of every page and behind **Ask NokRak**
in the catalogue. It can:

- find tables you can read or may request, and describe their columns (in the
  chat, and from **Find data** on the Query page);
- write a query as a card you put in the editor yourself;
- draft a policy, or suggest a change to one, for you to review;
- explain a policy in words on its page, including how it meets the others;
- explain what stands out on the dashboard, with people numbered rather than named;
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
- **Why was my query refused for a hint, a prefix or a variable?** ARAK checks
  the statement and the database then reads the text itself. Where the two
  could read it differently, a table could slip past the policy, so the
  statement is refused. Take out the hint, write the string as a plain
  `'...'`, or put the value in the statement instead of a variable.
- **Why is somebody not in a policy's PostgreSQL role?** Open the policy's
  **PostgreSQL roles** tab and press **Plan**. *Kept out* gives each person's
  reason, and *Let in, but no login here* lists those who need a login mapped.
  Somebody who qualifies only after the last apply joins at the next one: the
  sweep takes people out, and never puts anybody in.
- **Can the assistant give me access?** No. It can open the request form's page
  for you; a person decides.
