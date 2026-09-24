-- Attributes typed into ARAK, and the audit trail for them (FR-2.2, FR-2.4).
--
-- The attribute half of ABAC only works if the attribute exists. Directories
-- hold the ones the HR system happens to carry -- department, job title -- and
-- almost never the ones a data policy is written against: clearance, branch,
-- the country a person may see data for. Waiting for Entra to grow a field is
-- how a policy ends up matching nobody, so those values are entered here.
--
-- Nothing about the storage changes. `principal_attribute.source` already
-- distinguishes 'local' from 'entra' and 'openmetadata', and a sync writes
-- only its own rows, so a locally entered value cannot be overwritten by one
-- and cannot overwrite one. What changes is that the audit table has to be
-- able to describe the change, which it could not: its action list predates
-- attributes being writable at all, and its columns describe a role grant.

ALTER TABLE audit_identity_change
    ADD COLUMN attr_key   text,
    ADD COLUMN attr_value text;

COMMENT ON COLUMN audit_identity_change.attr_key IS
    'The attribute added or withdrawn. Null for every other action.';

-- The value is recorded in full, on purpose. "somebody changed clearance" is
-- not an answer an auditor can use; "clearance L3 was added to analyst_a by
-- admin" is. These are governance labels, not secrets -- the secret is the
-- data they unlock, which is exactly why the label needs a trail.
COMMENT ON COLUMN audit_identity_change.attr_value IS
    'The value added or withdrawn, recorded in full so a grant can be explained.';

ALTER TABLE audit_identity_change
    DROP CONSTRAINT audit_identity_change_action_check;

ALTER TABLE audit_identity_change
    ADD CONSTRAINT audit_identity_change_action_check CHECK (action IN (
        'CREATE_PRINCIPAL',
        'ENABLE_PRINCIPAL',
        'DISABLE_PRINCIPAL',
        'SET_PASSWORD',
        'GRANT_ROLE',
        'REVOKE_ROLE',
        -- Withdrawing closes the row rather than deleting it: a decision made
        -- last month was made against the attributes of last month, and an
        -- explanation that reads the current ones would be a different answer
        -- to the question that was actually asked.
        'ADD_ATTRIBUTE',
        'REMOVE_ATTRIBUTE'
    ));
