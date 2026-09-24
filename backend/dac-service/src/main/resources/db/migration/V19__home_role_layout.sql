-- V19 — the home page an administrator arranges on behalf of a role.
--
-- V13 gave every account its own page and made absence mean "the arrangement in
-- code". That is right for somebody who has opened the editor, and wrong for
-- the first morning of everybody who never will: an auditor and a requester
-- want different pages, and the only lever an administrator had was to change
-- the code and redeploy.
--
-- One row per platform role, holding the page somebody with that role sees
-- until they arrange their own. The resolution order is personal row, then the
-- row for the highest-precedence role they hold that has one, then the built-in
-- default. A personal arrangement is never overwritten by an administrator
-- editing a persona — the persona is a starting point, not a mandate, which is
-- why this is a separate table and not a template that gets copied into
-- home_layout.
--
-- The role list is repeated from app_role_assignment's CHECK rather than made
-- into a reference table. Engines got a table in V18 because that list grows
-- with every connector; these five are the product's own vocabulary, named in
-- @Secured annotations and in FR-2.6, and a sixth would be a change to the
-- authorisation model rather than a row.
--
-- ⚠️ This table is the first place in the product where content one person
-- typed is rendered into another person's session by design. Everything V13
-- says about HomeLayoutValidator applies here with the stakes raised: a note
-- widget saved onto the POLICY_AUTHOR persona is served to every policy author
-- who has not customised their page. It is cleaned on write and again on read,
-- and only PLATFORM_ADMIN may write it.

CREATE TABLE home_role_layout (
    app_role        text PRIMARY KEY CHECK (app_role IN
        ('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR', 'REQUESTER')),
    layout          jsonb NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text NOT NULL
);

COMMENT ON TABLE home_role_layout IS
    'Per-role starting arrangement of the home page, set by a platform administrator. Absent row = the built-in default for that role. Never overrides an account that arranged its own page.';
COMMENT ON COLUMN home_role_layout.layout IS
    'Sanitised layout document, served to other people. Never trusted raw.';
