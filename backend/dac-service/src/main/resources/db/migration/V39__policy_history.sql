-- V39 -- the policy change log is written (FR-8.1), and says enough to be read
-- back as a history (FR-9.2).
--
-- policy_version already holds every version of the document. What it cannot
-- say is what happened between two of them: that version 7 is version 4 put
-- back, or that version 9 is the same document published. These columns are
-- that, written in the same transaction as the version they describe.
--
-- from_state / to_state: a lifecycle change moves no field of the document, so
--     without them the log of a publish is two identical documents.
-- restored_from: the version a rollback copied. A rollback is a new version,
--     never an edit of an old one, so this is the only place the link is kept.

ALTER TABLE audit_policy_change
    ADD COLUMN from_state    text,
    ADD COLUMN to_state      text,
    ADD COLUMN restored_from integer;

-- SUBMIT is a draft sent for approval; RETURN is one sent back. Neither is a
-- publish or an edit, and the log should not have to call them one.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'audit_policy_change'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%action%'
    LOOP
        EXECUTE format('ALTER TABLE audit_policy_change DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE audit_policy_change
    ADD CONSTRAINT audit_policy_change_action_check CHECK (action IN
        ('CREATE', 'UPDATE', 'DELETE', 'SUBMIT', 'RETURN', 'PUBLISH', 'DISABLE', 'ARCHIVE',
         'ROLLBACK', 'OVERRIDE'));

-- The history reads the log row of each version.
CREATE INDEX audit_policy_change_version_idx ON audit_policy_change (policy_id, to_version);
