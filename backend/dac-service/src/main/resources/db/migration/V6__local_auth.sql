-- V6 — credentials for local principals.
--
-- Entra ID is the production identity provider and never gives us a password
-- to store (FR-2.1). This table exists for the other case the requirement names
-- explicitly: local users for unit tests, integration tests, service accounts
-- and external users who have no tenant account (FR-2.2). Only principals whose
-- source is 'local' may have a row here, which is what the trigger below
-- enforces -- an Entra user with a password in our database would be a second,
-- weaker way into the same account.
--
-- The hash is stored as a self-describing string rather than a bare digest:
--   pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>
-- Recording the algorithm and the work factor next to the hash is what makes it
-- possible to raise the iteration count later and re-hash on next login without
-- a migration that cannot read the old rows.

CREATE TABLE local_credential (
    principal_id        uuid PRIMARY KEY REFERENCES principal (id) ON DELETE CASCADE,
    password_hash       text NOT NULL,
    -- Set when an administrator issues a password rather than the user choosing
    -- one; the login response carries it so the UI can force a change.
    must_change         boolean NOT NULL DEFAULT false,
    -- Throttling lives next to the credential so a lockout survives a restart
    -- and applies across every instance, unlike an in-memory counter.
    failed_attempts     integer NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION local_credential_source_check() RETURNS trigger AS $$
BEGIN
    IF (SELECT source FROM principal WHERE id = NEW.principal_id) <> 'local' THEN
        RAISE EXCEPTION 'only a principal with source = local may hold a password';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER local_credential_source_trg
    BEFORE INSERT OR UPDATE ON local_credential
    FOR EACH ROW EXECUTE FUNCTION local_credential_source_check();

-- The bootstrap administrator. The row is created with no credential on
-- purpose: a password shipped in a migration is a password that reaches every
-- deployment and every fork of this repository. The application sets one on
-- first start from IDENTITY_BOOTSTRAP_ADMIN_PASSWORD, and refuses every login
-- until someone does (see DacApplication.bootstrapLocalAdmin).
INSERT INTO principal (principal_type, username, email, display_name, source, enabled)
VALUES ('USER', 'admin', NULL, 'Platform Administrator', 'local', true)
ON CONFLICT (source, username) DO NOTHING;

INSERT INTO app_role_assignment (principal_id, app_role, granted_by)
SELECT id, 'PLATFORM_ADMIN', 'bootstrap'
FROM principal
WHERE source = 'local' AND username = 'admin'
ON CONFLICT (principal_id, app_role, scope_fqn) DO NOTHING;
