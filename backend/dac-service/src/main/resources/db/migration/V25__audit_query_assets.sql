-- M10: the tables a proxied query touched.
--
-- The query log is read by more people than the platform's operators: a data
-- owner is entitled to see who has been reading the tables they own, and
-- nobody else's. Deciding that from the SQL text at read time would mean
-- parsing every row again and trusting that parse to agree with the one that
-- governed the statement. The proxy already knows which governed tables it
-- resolved, so it writes them down as it goes.
--
-- Empty for rows written before this column existed and for statements refused
-- before any table was resolved (no source, source disabled, unparseable): the
-- owner filter cannot place those, and they stay visible to the person who ran
-- them and to the roles that oversee everything, as they were before.
ALTER TABLE audit_query ADD COLUMN asset_fqns text[] NOT NULL DEFAULT '{}';

CREATE INDEX audit_query_assets_idx ON audit_query USING gin (asset_fqns);

-- Who sent the statement, when that is not the principal it ran as. An
-- administrator may run a query as somebody else to see what they see; the row
-- is theirs to answer for, not the other person's, and a log that recorded
-- only the principal would show that person a query they never ran. Null when
-- the two are the same, and for every row written before this column existed.
ALTER TABLE audit_query ADD COLUMN run_by text;

-- Rows a policy refused before this column existed still name the table they
-- were refused on, in the proxy's own words. That one table is recoverable, so
-- its owner can see who was turned away from it; nothing else in those rows is
-- placed, because the rest of the statement was never resolved. This fills in
-- metadata derived from the row itself and changes nothing the row recorded.
UPDATE audit_query
   SET asset_fqns = ARRAY[substring(reject_reason from '^Access to (.+?) is denied')]
 WHERE outcome = 'REJECTED'
   AND reject_reason ~ '^Access to .+ is denied';
