-- Demo identities for the end-to-end enforcement scenario (plan section 6, steps 3–9).
--
-- These are LOCAL principals: FR-2.2 exists precisely so the platform can be
-- exercised without an Entra tenant. They carry no password and cannot sign in;
-- they are subjects a policy is written about and a query is run as, which is
-- all the enforcement path needs them to be.
--
-- Idempotent on purpose — re-running it must not multiply attributes, because
-- a principal with clearance twice is a principal whose policy result depends
-- on how many times somebody ran the seed.

INSERT INTO principal (principal_type, username, email, display_name, source)
VALUES
  ('USER',  'analyst_a', 'analyst_a@example.test', 'Analyst A (Bangkok)', 'local'),
  ('USER',  'analyst_b', 'analyst_b@example.test', 'Analyst B (Singapore)', 'local'),
  ('USER',  'steward_c', 'steward_c@example.test', 'Steward C (cleared)',   'local'),
  ('GROUP', 'Finance',   NULL,                     'Finance',               'openmetadata')
ON CONFLICT (source, username) DO NOTHING;

-- Everyone in the scenario is in the Finance team; what separates them is their
-- attributes, which is the point being demonstrated.
INSERT INTO group_member (group_id, member_id, source)
SELECT g.id, m.id, 'local'
FROM principal g, principal m
WHERE g.username = 'Finance' AND g.source = 'openmetadata'
  AND m.username IN ('analyst_a', 'analyst_b', 'steward_c') AND m.source = 'local'
ON CONFLICT DO NOTHING;

DELETE FROM principal_attribute
WHERE principal_id IN (SELECT id FROM principal
                        WHERE source = 'local'
                          AND username IN ('analyst_a', 'analyst_b', 'steward_c'));

INSERT INTO principal_attribute (principal_id, attr_key, attr_value, source)
SELECT p.id, v.attr_key, v.attr_value, 'local'
FROM (VALUES
  -- Bangkok analyst: one branch, lowest clearance. Sees least.
  ('analyst_a', 'department', 'FINANCE'),
  ('analyst_a', 'clearance',  'L1'),
  ('analyst_a', 'country',    'TH'),
  ('analyst_a', 'branch',     'BKK-01'),
  -- Singapore analyst: cleared, but in the wrong country for TH-resident data.
  ('analyst_b', 'department', 'FINANCE'),
  ('analyst_b', 'clearance',  'L2'),
  ('analyst_b', 'country',    'SG'),
  ('analyst_b', 'branch',     'SIN-01'),
  -- Thai steward: cleared AND resident, and covers two branches — the
  -- multi-value case, which is what turns the row filter into an IN list.
  ('steward_c', 'department', 'FINANCE'),
  ('steward_c', 'clearance',  'L2'),
  ('steward_c', 'country',    'TH'),
  ('steward_c', 'branch',     'BKK-01'),
  ('steward_c', 'branch',     'CNX-01')
) AS v(username, attr_key, attr_value)
JOIN principal p ON p.username = v.username AND p.source = 'local';
