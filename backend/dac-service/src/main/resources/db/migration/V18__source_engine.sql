-- V18 — the engines this platform can govern, as a table rather than a CHECK.
--
-- Two columns spelled out the supported engines in a CHECK constraint:
-- data_source.engine (V1) and engine_capability.engine (V4). A CHECK is the
-- wrong shape for this fact. It cannot be read — nothing can ask the database
-- which engines are allowed, so the console hard-coded its own copy of the
-- list — and widening it needs a table rewrite on a production table, which
-- puts "we now support MySQL" in the same risk bracket as a schema change.
--
-- A reference table with foreign keys gives the same guarantee (a row naming
-- an engine nobody supports is still refused) and takes the list from
-- unreadable to queryable.

CREATE TABLE source_engine (
    id            text PRIMARY KEY,
    display_name  text NOT NULL,
    default_port  integer NOT NULL CHECK (default_port BETWEEN 1 AND 65535),
    -- MySQL calls a database what Postgres calls a schema, which changes how
    -- deep an FQN goes. Recorded here so the introspector and the FQN mapper
    -- read it rather than each deciding for itself.
    supports_schemas boolean NOT NULL DEFAULT true,
    -- The upper-case id is what every row already holds, so this is the one
    -- spelling the constraint can enforce without rewriting existing data.
    CONSTRAINT source_engine_id_is_upper CHECK (id = upper(id) AND id <> '')
);

COMMENT ON TABLE source_engine IS
  'Engines this build can connect to and rewrite for. Kept in step with com.mfec.dac.common.engine.SourceEngines by SourceEngineRegistryIT; the code is authoritative, this table exists so SQL and the API can read the list.';

INSERT INTO source_engine (id, display_name, default_port, supports_schemas) VALUES
  ('POSTGRES',  'PostgreSQL', 5432, true),
  ('SQLSERVER', 'SQL Server', 1433, true);

-- The proxy's capabilities are deliberately not in this table, nor left in
-- engine_capability below. In proxy mode nothing is enforced by the engine —
-- ARAK rewrites the statement — so the question is what our rewriter can say
-- in that dialect, which is a fact about this build and belongs next to the
-- code that has to keep it true. The two answers really do diverge: Postgres
-- has no native column masking at all and the proxy masks its columns
-- perfectly well.
DELETE FROM engine_capability WHERE mode = 'PROXY';

-- Now the constraints. Dropping the CHECK before adding the FK, so that a row
-- is never subject to both and there is no window where an engine is
-- half-allowed.
ALTER TABLE data_source DROP CONSTRAINT data_source_engine_check;
ALTER TABLE data_source
    ADD CONSTRAINT data_source_engine_fkey
    FOREIGN KEY (engine) REFERENCES source_engine (id)
    -- Restrict, not cascade: removing an engine while sources still point at
    -- it should fail loudly. A cascade here would delete the registry rows,
    -- and with them the credential reference and the enforcement mode of every
    -- table that engine was protecting.
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE engine_capability DROP CONSTRAINT engine_capability_engine_check;
ALTER TABLE engine_capability
    ADD CONSTRAINT engine_capability_engine_fkey
    FOREIGN KEY (engine) REFERENCES source_engine (id)
    ON UPDATE CASCADE ON DELETE CASCADE;

CREATE INDEX data_source_engine_idx ON data_source (engine);
