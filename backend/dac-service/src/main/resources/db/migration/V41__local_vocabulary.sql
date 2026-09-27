-- Classifications and tags made in ARAK, for vocabulary OpenMetadata does not
-- have yet (FR-1.7).
--
-- They live in the same two tables as the crawled ones, with provenance
-- 'local', so everything that reads the vocabulary -- the Governance screens,
-- the policy builder's pickers, "Edit tags" on a table -- sees them without a
-- second code path. The crawl already leaves them alone: its upserts and its
-- deletes are confined to provenance = 'openmetadata'.

ALTER TABLE classification ADD COLUMN created_by text;
ALTER TABLE tag ADD COLUMN created_by text;

-- Append-only, like every audit table before it. What the value said before
-- and after, so a renamed description or a disabled tag can be explained.
CREATE TABLE audit_vocabulary (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor           text NOT NULL,
    action          text NOT NULL CHECK (action IN ('CREATE', 'UPDATE')),
    kind            text NOT NULL CHECK (kind IN ('CLASSIFICATION', 'TAG')),
    fqn             text NOT NULL,
    before          jsonb,
    after           jsonb
);
CREATE INDEX audit_vocabulary_fqn_idx ON audit_vocabulary (fqn, occurred_at DESC);
