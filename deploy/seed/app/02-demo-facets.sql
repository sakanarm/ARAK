-- Governance facets on the discovered demo table (plan section 6, step 1).
--
-- Normally these arrive from OpenMetadata. This source was discovered by JDBC
-- introspection instead, so the facets are authored locally — which is FR-1.7,
-- and the reason every row here is provenance = 'local': the next OpenMetadata
-- sync must leave them alone rather than "correct" them out of existence.
--
-- The ancestor rows (`PII` beside `PII.Sensitive`, and the `classifications`
-- entry) are written out in full rather than derived at query time, exactly as
-- the crawler does it (FR-2A.2). A policy written as
-- `classifications contains 'PII'` then costs an index lookup, and a tag added
-- under PII later is covered without touching the policy.

DELETE FROM asset_facet
 WHERE provenance = 'local'
   AND target_fqn LIKE 'demo-pg.salesdb.sales.customer%';

-- Column-level: the two columns that carry identity.
INSERT INTO asset_facet (column_id, target_fqn, facet_type, facet_fqn, depth, is_direct,
                         provenance, om_state)
SELECT c.id, c.fqn, v.facet_type, v.facet_fqn, v.depth, v.is_direct, 'local', 'Confirmed'
FROM asset_column c
JOIN asset a ON a.id = c.asset_id AND a.is_current
JOIN (VALUES
  ('email',      'tags',            'PII.Sensitive', 0, true),
  ('email',      'tags',            'PII',           1, false),
  ('email',      'classifications', 'PII',           0, false),
  ('citizen_id', 'tags',            'PII.Sensitive', 0, true),
  ('citizen_id', 'tags',            'PII',           1, false),
  ('citizen_id', 'classifications', 'PII',           0, false)
) AS v(col, facet_type, facet_fqn, depth, is_direct) ON v.col = c.name
WHERE a.fqn = 'demo-pg.salesdb.sales.customer' AND c.is_current;

-- Table-level: which domain owns it, and where the data is allowed to live.
-- `dataResidency` is the asset side of FR-2A.4: a policy can compare it against
-- the caller's own country without naming either value.
INSERT INTO asset_facet (asset_id, target_fqn, facet_type, facet_fqn, property, depth, is_direct,
                         provenance, om_state)
SELECT a.id, a.fqn, v.facet_type, v.facet_fqn, v.property, v.depth, v.is_direct, 'local', 'Confirmed'
FROM asset a
JOIN (VALUES
  ('domains',          'Finance',                NULL,            0, true),
  ('domains',          'Finance.Risk',           NULL,            0, true),
  ('domains',          'Finance.Risk.Credit',    NULL,            0, true),
  ('customProperty',   'TH',                     'dataResidency', 0, true)
) AS v(facet_type, facet_fqn, property, depth, is_direct) ON true
WHERE a.fqn = 'demo-pg.salesdb.sales.customer' AND a.is_current;
