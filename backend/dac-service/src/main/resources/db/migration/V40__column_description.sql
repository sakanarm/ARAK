-- Column descriptions written in ARAK, for a column nobody has described in
-- OpenMetadata yet -- or described in words the people asking cannot use.
--
-- asset_column.description is OpenMetadata's and every crawl rewrites it; this
-- table is what somebody who governs the table wrote here, and no sync touches
-- it. Where both exist, this one is shown: it is the later, deliberate act.
--
-- Keyed by the column's FQN rather than by asset_column.id, like local_tag: a
-- column that changes gets a new version and a new id, and the description is
-- of the column, not of its version.

CREATE TABLE column_description (
    target_fqn      text PRIMARY KEY,
    -- The table the column belongs to: who governs it decides who may write,
    -- and a table's page reads its columns' descriptions in one query.
    asset_fqn       text NOT NULL,
    description     text NOT NULL CHECK (length(btrim(description)) > 0),
    -- The text started as a draft from the assistant. A person still saved it
    -- under their own name; this says where the words came from.
    assisted        boolean NOT NULL DEFAULT false,
    written_by      text NOT NULL,
    written_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX column_description_asset_idx ON column_description (asset_fqn);

-- Append-only, like every audit table before it. A description decides no
-- access, so no reason is asked for; what it said before is kept instead.
CREATE TABLE audit_column_description (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor           text NOT NULL,
    action          text NOT NULL CHECK (action IN ('SET', 'CLEAR')),
    target_fqn      text NOT NULL,
    asset_fqn       text NOT NULL,
    before          text,
    after           text,
    assisted        boolean NOT NULL DEFAULT false
);
CREATE INDEX audit_column_description_asset_idx
    ON audit_column_description (asset_fqn, occurred_at DESC);
