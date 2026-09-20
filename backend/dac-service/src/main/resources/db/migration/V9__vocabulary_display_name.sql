-- The name a person reads, beside the name a policy is written against.
--
-- OpenMetadata carries both: a classification whose fqn is `PII` displays as
-- "Personally Identifiable Information", and a domain `Finance.Risk.Credit`
-- displays as "Credit Risk". The vocabulary screens were already built to show
-- it — the API contract has carried `displayName` since the catalog UI landed —
-- but the column was never added, so every one of those queries failed on
-- `column v.display_name does not exist`.
--
-- Nullable with no backfill: the value is OpenMetadata's to supply, and the
-- next crawl brings it. A default copied from `name` would be indistinguishable
-- from a display name somebody actually set, and the UI would have no way to
-- fall back.
ALTER TABLE classification  ADD COLUMN display_name text;
ALTER TABLE tag             ADD COLUMN display_name text;
ALTER TABLE glossary        ADD COLUMN display_name text;
ALTER TABLE glossary_term   ADD COLUMN display_name text;
ALTER TABLE domain          ADD COLUMN display_name text;
ALTER TABLE data_product    ADD COLUMN display_name text;
