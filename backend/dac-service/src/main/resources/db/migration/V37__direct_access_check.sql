-- V37 -- the direct-access check goes on the enforcement trail (FR-6.3.1).
--
-- Reading who holds a table at the source opens a connection to a customer's
-- database with the platform's credential, the same as a dry run does, so it is
-- written down in the same place. A check can be run on a source whose mode is
-- NONE, since "nothing is enforced here, and these people read it directly" is
-- the answer somebody deciding on a mode wants.

ALTER TABLE audit_enforcement DROP CONSTRAINT audit_enforcement_mode_check;
ALTER TABLE audit_enforcement ADD CONSTRAINT audit_enforcement_mode_check
    CHECK (mode IN ('NATIVE_CONFIG', 'SECURE_VIEW', 'PROXY', 'NONE'));

ALTER TABLE audit_enforcement DROP CONSTRAINT audit_enforcement_action_check;
ALTER TABLE audit_enforcement ADD CONSTRAINT audit_enforcement_action_check
    CHECK (action IN ('DRY_RUN', 'APPLY', 'ROLLBACK', 'DIRECT_ACCESS_CHECK'));

ALTER TABLE audit_enforcement DROP CONSTRAINT audit_enforcement_outcome_check;
ALTER TABLE audit_enforcement ADD CONSTRAINT audit_enforcement_outcome_check
    CHECK (outcome IN
        ('REVIEWED', 'APPLIED', 'ROLLED_BACK', 'STALE', 'FAILED', 'REFUSED', 'CHECKED'));
