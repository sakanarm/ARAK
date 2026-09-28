-- V42 -- a download of every row says so on the query log (FR-6.3, FR-8.3).
--
-- The console shows at most a few thousand rows and offers the rest as a
-- file. Both are reads through the same policy and both are EXECUTED rows,
-- but a download is the one that leaves with a copy of the table, and it is
-- the row an investigation into where data went will look for first. The
-- flag is set on refused and failed downloads too: somebody trying to take a
-- copy is worth finding whether or not they got one.
--
-- Every row written before this was a read for the screen, so false is the
-- truth for them.

ALTER TABLE audit_query ADD COLUMN exported boolean NOT NULL DEFAULT false;
