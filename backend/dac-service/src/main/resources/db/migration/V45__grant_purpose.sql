-- The purpose a grant was given for (FR-21, M31b).
--
-- A request has carried its purpose since M31a, and the register says how long
-- access for each purpose may last. The grant a request became did not keep
-- it, and a grant an owner gave directly had no purpose at all -- so the limit
-- held on a request and not on the grant, and "what is this access for" had to
-- be read out of free text.
--
-- The register's key, as everything else stores it. Null is a grant given
-- without one, which stays allowed: a purpose is asked for, not required.
ALTER TABLE access_grant ADD COLUMN purpose text;

-- The trail says what each grant was for at the time, as it already says the
-- window: the register can retire a purpose later, and the row it wrote then
-- must still read the same.
ALTER TABLE audit_grant_change ADD COLUMN purpose text;

-- A grant a request produced was given for the request's purpose. Only the
-- grant rows are filled in; the trail is append-only and keeps what it wrote.
UPDATE access_grant g
   SET purpose = r.purpose
  FROM access_request r
 WHERE g.request_id = r.id
   AND g.purpose IS NULL
   AND r.purpose IS NOT NULL;

CREATE INDEX access_grant_purpose_idx ON access_grant (lower(purpose))
    WHERE purpose IS NOT NULL AND revoked_at IS NULL;
