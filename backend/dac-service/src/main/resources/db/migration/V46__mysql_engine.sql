-- V46 — MySQL joins the engines a source may name.
--
-- Numbered 46, not 45: main took V45 (grant_access.purpose) while this was on
-- its branch, and two migrations with one number stop the service starting.
-- A development database that ran this as V45 already has the row, so the
-- insert leaves an existing one alone rather than failing on it.
--
-- V18 made this list a table so that adding an engine is a row rather than a
-- rewritten CHECK. The code is the authoritative side
-- (com.mfec.dac.common.engine.SourceEngines); SourceEngineRegistryIT holds the
-- two together.
--
-- supports_schemas is false: a MySQL database is the level the other two call
-- a schema. An FQN keeps four segments all the same, as OpenMetadata's does --
-- service.default.<database>.<table> -- so a table imported here and the same
-- table crawled from OpenMetadata are one asset.
--
-- No engine_capability rows. That table speaks for what an engine enforces by
-- itself, in the native and secure-view modes, and neither has been worked out
-- for MySQL; a row there would be a claim nobody has tested. What the query
-- proxy can do on MySQL is in the code, beside the rewriter that has to do it.

INSERT INTO source_engine (id, display_name, default_port, supports_schemas) VALUES
  ('MYSQL', 'MySQL', 3306, false)
ON CONFLICT (id) DO NOTHING;
