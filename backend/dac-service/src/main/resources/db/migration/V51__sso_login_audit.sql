-- Sign-ins through Microsoft (FR-2.1) go in the same append-only log as the
-- gateway's, so one place answers "who signed in, and how". Each attempt is a
-- row, refusals included: somebody Microsoft knows but ARAK does not is
-- exactly who an administrator will be asked about.
ALTER TABLE audit_auth_event DROP CONSTRAINT audit_auth_event_event_check;

ALTER TABLE audit_auth_event ADD CONSTRAINT audit_auth_event_event_check
    CHECK (event IN ('GATEWAY_LOGIN', 'TOKEN_CREATE', 'TOKEN_REVOKE', 'SSO_LOGIN'));
