-- Which tables of a source the catalogue import reads.
--
-- {mode: ALL | ONLY, include: [{match, value}], exclude: [{match, value}]},
-- where match is STARTS_WITH, ENDS_WITH, CONTAINS or EQUALS. Validated by
-- TableScope before it is written. Null means every table the connector can
-- read, which is what every source did before this column existed.
--
-- The scope narrows what is imported and nothing else. It is not a permission,
-- and live verification before enforcement still reads the source directly.

ALTER TABLE data_source
    ADD COLUMN table_scope jsonb CHECK (table_scope IS NULL OR jsonb_typeof(table_scope) = 'object');
