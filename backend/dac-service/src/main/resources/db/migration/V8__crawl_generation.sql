-- V8 — how a crawl knows what OpenMetadata has stopped saying.
--
-- A full crawl has to retire assets that no longer exist, and the only way to
-- tell "gone" from "not mentioned yet" is to stamp everything the crawl touched
-- and then close whatever the stamp missed. The alternative — collecting every
-- FQN the crawl saw and diffing at the end — holds a hundred thousand strings
-- in memory for the length of the run, which is the one thing NFR-2 asks the
-- crawler not to do.
--
-- Stamped on every write, including the ones that changed nothing: an asset
-- whose description has not moved in a year must still count as seen, or the
-- first crawl after that year would retire it.

ALTER TABLE asset ADD COLUMN last_seen_at timestamptz;
ALTER TABLE asset_column ADD COLUMN last_seen_at timestamptz;

-- The retirement sweep at the end of a crawl reads exactly this.
CREATE INDEX asset_last_seen_idx ON asset (last_seen_at) WHERE is_current;
CREATE INDEX asset_column_last_seen_idx ON asset_column (last_seen_at) WHERE is_current;

-- A custom-property facet needs to say which property it is.
--
-- V1 gave asset_facet one value column, which is enough for every facet whose
-- name is part of its value: tags, terms and domains are all FQNs. A custom
-- property is not — `dataResidency = TH` and `country = TH` both reduce to the
-- single value TH, so without the name a selector for one matches the other.
-- Null for every facet type but customProperty.
ALTER TABLE asset_facet ADD COLUMN property text;

CREATE INDEX asset_facet_property_idx ON asset_facet (property, facet_fqn)
    WHERE property IS NOT NULL;
