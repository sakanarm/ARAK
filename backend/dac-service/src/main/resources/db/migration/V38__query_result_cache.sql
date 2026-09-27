-- V38 -- a query answered from the result cache says so on the query log (FR-6.3).
--
-- It is still an EXECUTED row: the statement was parsed, every table governed
-- and every decision recorded exactly as on a read, and the rows went back to
-- the caller. What differs is that the source was not asked, and an auditor
-- reconciling this log with the source's own has to be able to tell which rows
-- the source will have no record of.
--
-- Every row written before this is a read, so false is the truth for them too.

ALTER TABLE audit_query ADD COLUMN served_from_cache boolean NOT NULL DEFAULT false;
