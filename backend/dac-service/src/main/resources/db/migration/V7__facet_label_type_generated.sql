-- OpenMetadata 2.0.1 emits a fifth tag label type that V1 did not allow for.
--
-- TagLabel.labelType is {Manual, Automated, Propagated, Derived, Generated};
-- the last one marks a label an agent wrote rather than a person. V1 constrained
-- the column to the first four, so the first crawl to meet an agent-written tag
-- would abort on a check violation — and it would abort mid-crawl, leaving the
-- facet table holding a partial picture of which columns are sensitive. That is
-- the worst possible failure for this table: a policy asking "is this PII?"
-- would get a truthful-looking "no".
--
-- Widened rather than dropped. The point of the constraint is to catch a
-- connector writing something OpenMetadata never said, and that is still worth
-- catching; it just has to know the whole vocabulary.

ALTER TABLE asset_facet DROP CONSTRAINT asset_facet_om_label_type_check;

ALTER TABLE asset_facet ADD CONSTRAINT asset_facet_om_label_type_check
    CHECK (om_label_type IN ('Manual', 'Automated', 'Propagated', 'Derived', 'Generated'));
