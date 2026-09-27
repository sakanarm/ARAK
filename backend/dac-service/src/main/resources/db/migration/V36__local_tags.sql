-- Tags set in ARAK itself, on a table or one of its columns (FR-1.7).
--
-- OpenMetadata stays the source of the tag vocabulary: a local tag is one of
-- the tags the governance crawl already brought in, attached here because
-- nobody has attached it in OpenMetadata yet. This table is what was attached;
-- asset_facet is what a selector reads, and the local rows there are derived
-- from this one every time the asset's facets are written -- by a crawl, a
-- webhook or a change here -- so a sync can never take a local tag away.
--
-- Keyed by FQN rather than by asset_column.id: a column that changes gets a
-- new version and a new id, and the tag is on the column, not on its version.

CREATE TABLE local_tag (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The table, or one of its columns.
    target_fqn      text NOT NULL,
    -- The table the target is, or belongs to: who governs it decides who may
    -- tag it, and the bindings to re-resolve are that table's.
    asset_fqn       text NOT NULL,
    tag_fqn         text NOT NULL,
    reason          text NOT NULL CHECK (length(btrim(reason)) > 0),
    added_by        text NOT NULL,
    added_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (target_fqn, tag_fqn)
);
CREATE INDEX local_tag_asset_idx ON local_tag (asset_fqn);

-- Append-only, like every audit table before it.
CREATE TABLE audit_local_tag (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor           text NOT NULL,
    action          text NOT NULL CHECK (action IN ('ADD', 'REMOVE')),
    target_fqn      text NOT NULL,
    asset_fqn       text NOT NULL,
    tag_fqn         text NOT NULL,
    reason          text NOT NULL
);
CREATE INDEX audit_local_tag_asset_idx ON audit_local_tag (asset_fqn, occurred_at DESC);

-- Where a facet row came from. The derived local rows are told apart by this
-- column alone, so a value outside the three would be a row nothing rebuilds.
ALTER TABLE asset_facet
    ADD CONSTRAINT asset_facet_provenance_check
    CHECK (provenance IN ('openmetadata', 'local', 'discovered'));
