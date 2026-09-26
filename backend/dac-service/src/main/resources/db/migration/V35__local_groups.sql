-- Groups typed into ARAK, and the audit trail for who joins them (FR-2.2).
--
-- A policy written for "Finance" needs a Finance to point at. Where Entra is
-- connected that is a synced group; where it is not -- a demo, a test bed, an
-- estate whose directory groups do not match how data is actually shared --
-- there was nothing, and a grant had to be repeated person by person.
--
-- Nothing about the storage changes. `principal` already takes a GROUP row
-- with source 'local', and `group_member.source` already allows 'local', so a
-- membership entered here is never overwritten by a sync and never overwrites
-- one. What changes is that the audit table has to be able to say it happened.
--
-- The member's name goes into attr_value, with attr_key 'member': a join is
-- a label on the group in the same sense an attribute is a label on a person,
-- and an auditor asking "who put analyst_a in Finance" reads the same columns.

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
        'ADD_ATTRIBUTE',
        'REMOVE_ATTRIBUTE',
        'ADD_MEMBER',
        'REMOVE_MEMBER'
    ));
