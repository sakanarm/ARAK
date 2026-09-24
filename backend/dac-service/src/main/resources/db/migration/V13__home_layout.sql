-- V13 — the home page, arranged by the person who reads it.
--
-- One row per account, holding the whole arrangement as a document rather than
-- a table of widgets. The arrangement is only ever read and written whole, by
-- exactly one person, and it is never queried across accounts; splitting it into
-- rows would buy nothing and cost an ordering column, a cascade and a join on
-- the first paint of every session.
--
-- No row means "the default arrangement", which is why nothing is inserted here
-- and why deleting a row is how somebody resets their page. That keeps the
-- default in code, where it can change with the widgets it refers to, instead of
-- frozen into every account that ever signed in.
--
-- ⚠️ What this column holds is content a person typed, including HTML and URLs,
-- and it is rendered back into a browser session that may belong to a platform
-- administrator. It is sanitised against a strict allowlist before it is stored
-- and again before it is served (HomeLayoutValidator). Nothing may write here
-- that has not been through that: a script smuggled into a dashboard would run
-- with the reader's rights against the policy API, which is the one place in
-- this product where stored markup turns into privilege.

CREATE TABLE home_layout (
    principal_id    uuid PRIMARY KEY REFERENCES principal (id) ON DELETE CASCADE,
    layout          jsonb NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text NOT NULL
);

COMMENT ON TABLE home_layout IS
    'Per-account arrangement of the home page. Absent row = the default layout.';
COMMENT ON COLUMN home_layout.layout IS
    'Sanitised layout document: preset + ordered widgets. Never trusted raw.';
