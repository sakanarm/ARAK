-- V52 — Databricks joins the engines a source may name.
--
-- V18 made the list of engines a table so that adding one is a row rather than
-- a rewritten CHECK. The code is the authoritative side
-- (com.mfec.dac.common.engine.SourceEngines); SourceEngineRegistryIT holds the
-- two together.
--
-- http_path is the one new column. A Databricks workspace is a single host, and
-- the SQL warehouse a source reads through is picked by a path on it, so two
-- sources on one host differ only there. Null on every other engine.
--
-- supports_schemas is true: a table sits under a catalog and a schema. The
-- catalog is kept in default_database, the field the other engines use for a
-- database, so an FQN is service.<catalog>.<schema>.<table>, the way
-- OpenMetadata names a Databricks table.
--
-- No engine_capability rows, for the reason V46 gives: that table speaks for
-- what an engine enforces by itself in the native and secure-view modes, and
-- neither is offered here. What the query proxy can do is in the code.

ALTER TABLE data_source ADD COLUMN IF NOT EXISTS http_path text;

INSERT INTO source_engine (id, display_name, default_port, supports_schemas) VALUES
  ('DATABRICKS', 'Databricks', 443, true)
ON CONFLICT (id) DO NOTHING;
