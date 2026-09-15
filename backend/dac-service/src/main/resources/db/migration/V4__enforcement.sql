-- V4 — enforcement state.
--
-- One row per asset per mode, holding the DDL we applied and the DDL that undoes
-- it. Rollback scripts are generated at apply time and stored, not reconstructed
-- later: reconstructing them means guessing what production looked like before,
-- and that guess is wrong exactly when it matters (FR-6.4).

CREATE TABLE enforcement_state (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            uuid NOT NULL REFERENCES asset (id) ON DELETE CASCADE,
    target_fqn          text NOT NULL,
    data_source_id      uuid NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    mode                text NOT NULL CHECK (mode IN ('NATIVE_CONFIG', 'SECURE_VIEW', 'PROXY')),
    status              text NOT NULL DEFAULT 'NOT_ENFORCED' CHECK (status IN
        ('NOT_ENFORCED', 'PENDING', 'APPLIED', 'DRIFTED', 'FAILED')),
    applied_ddl         text,
    rollback_ddl        text,
    -- Fingerprint of the objects as we left them, compared by the drift
    -- detector against what the source reports now (FR-6.4).
    applied_fingerprint text,
    last_applied_at     timestamptz,
    last_applied_by     text,
    last_checked_at     timestamptz,
    drift_detail        text,
    last_error          text,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (asset_id, mode)
);
CREATE INDEX enforcement_state_status_idx ON enforcement_state (status);
CREATE INDEX enforcement_state_source_idx ON enforcement_state (data_source_id);

-- What each engine version can actually enforce. Consulted before apply so the
-- UI can say "this cell mask cannot be expressed here" up front, rather than
-- applying successfully and dropping the restriction on the floor (FR-6.0b).
CREATE TABLE engine_capability (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    engine              text NOT NULL CHECK (engine IN ('POSTGRES', 'SQLSERVER')),
    min_version         text,
    mode                text NOT NULL CHECK (mode IN ('NATIVE_CONFIG', 'SECURE_VIEW', 'PROXY')),
    capability          text NOT NULL,
    supported           boolean NOT NULL,
    requires_extension  text,
    note                text,
    UNIQUE (engine, min_version, mode, capability)
);

-- Seeded from the limits documented in the plan (FR-6.2). Everything here is a
-- statement about the database engine, not about our implementation, which is
-- why it lives in data rather than in code.
INSERT INTO engine_capability (engine, min_version, mode, capability, supported, requires_extension, note) VALUES
  ('POSTGRES',  '9.5',  'NATIVE_CONFIG', 'ROW_FILTER',        true,  NULL,   'CREATE POLICY; needs FORCE ROW LEVEL SECURITY for the table owner'),
  ('POSTGRES',  '9.5',  'NATIVE_CONFIG', 'COLUMN_HIDE',       true,  NULL,   'GRANT SELECT (col, ...)'),
  ('POSTGRES',  '12',   'NATIVE_CONFIG', 'COLUMN_MASK',       false, 'anon', 'No column masking in core; the anon extension is unavailable on most managed services'),
  ('POSTGRES',  '9.5',  'NATIVE_CONFIG', 'CELL_MASK',         false, NULL,   'Conditional per-user masking cannot be expressed natively'),
  ('POSTGRES',  '9.5',  'SECURE_VIEW',   'ROW_FILTER',        true,  NULL,   NULL),
  ('POSTGRES',  '9.5',  'SECURE_VIEW',   'COLUMN_MASK',       true,  NULL,   NULL),
  ('POSTGRES',  '9.5',  'SECURE_VIEW',   'CELL_MASK',         true,  NULL,   'CASE expression over the row predicate'),
  ('POSTGRES',  '9.5',  'SECURE_VIEW',   'COLUMN_HIDE',       true,  NULL,   NULL),
  ('SQLSERVER', '2016', 'NATIVE_CONFIG', 'ROW_FILTER',        true,  NULL,   'CREATE SECURITY POLICY with an inline table-valued function'),
  ('SQLSERVER', '2016', 'NATIVE_CONFIG', 'COLUMN_MASK',       true,  NULL,   'Dynamic data masking; on or off per column, not per user'),
  ('SQLSERVER', '2022', 'NATIVE_CONFIG', 'COLUMN_UNMASK',     true,  NULL,   'GRANT UNMASK at column level; before 2022 UNMASK is database-wide and unusable'),
  ('SQLSERVER', '2016', 'NATIVE_CONFIG', 'CELL_MASK',         false, NULL,   'DDM cannot vary by row or by user'),
  ('SQLSERVER', '2016', 'NATIVE_CONFIG', 'COLUMN_HIDE',       true,  NULL,   'GRANT or DENY SELECT ON tbl(col)'),
  ('SQLSERVER', '2016', 'SECURE_VIEW',   'ROW_FILTER',        true,  NULL,   NULL),
  ('SQLSERVER', '2016', 'SECURE_VIEW',   'COLUMN_MASK',       true,  NULL,   NULL),
  ('SQLSERVER', '2016', 'SECURE_VIEW',   'CELL_MASK',         true,  NULL,   NULL),
  ('SQLSERVER', '2016', 'SECURE_VIEW',   'COLUMN_HIDE',       true,  NULL,   NULL),
  ('POSTGRES',  NULL,   'PROXY',         'ROW_FILTER',        true,  NULL,   'Rewritten before execution'),
  ('POSTGRES',  NULL,   'PROXY',         'COLUMN_MASK',       true,  NULL,   NULL),
  ('POSTGRES',  NULL,   'PROXY',         'CELL_MASK',         true,  NULL,   NULL),
  ('POSTGRES',  NULL,   'PROXY',         'COLUMN_HIDE',       true,  NULL,   NULL),
  ('SQLSERVER', NULL,   'PROXY',         'ROW_FILTER',        true,  NULL,   NULL),
  ('SQLSERVER', NULL,   'PROXY',         'COLUMN_MASK',       true,  NULL,   NULL),
  ('SQLSERVER', NULL,   'PROXY',         'CELL_MASK',         true,  NULL,   NULL),
  ('SQLSERVER', NULL,   'PROXY',         'COLUMN_HIDE',       true,  NULL,   NULL);
