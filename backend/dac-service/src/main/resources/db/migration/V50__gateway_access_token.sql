-- V50 -- the ARAK Gateway (FR-19, M29a): tools that speak the PostgreSQL wire
-- protocol connect to ARAK, and every statement still goes through the proxy.
--
-- Numbered 50, not 48: the numbers in between are held for work on the data
-- policy side, and Flyway runs out of order.

-- A personal access token: what a tool such as DBeaver or Power BI sends as its
-- password. Only a hash is kept; the token itself is shown once, when it is
-- made. Every token has an end, and a token is ended, never deleted, so the
-- audit rows that name it keep meaning something.
CREATE TABLE access_token (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id    uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    name            text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    -- The first characters of the token, to tell tokens apart on screen. Too
    -- short to be of any use to somebody who finds it.
    token_prefix    text NOT NULL,
    -- SHA-256 of the whole token, hex. The token carries 256 random bits, so a
    -- plain hash is enough: there is nothing to guess.
    token_hash      text NOT NULL UNIQUE,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    last_used_at    timestamptz,
    revoked_at      timestamptz,
    revoked_by      text,
    revoke_reason   text,
    CHECK (expires_at > created_at),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE INDEX access_token_principal_idx ON access_token (principal_id, created_at DESC);

-- Sign-ins through the gateway, and every token made or ended. A failed
-- sign-in names the account the client claimed, which may not exist.
CREATE TABLE audit_auth_event (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    event           text NOT NULL
        CHECK (event IN ('GATEWAY_LOGIN', 'TOKEN_CREATE', 'TOKEN_REVOKE')),
    outcome         text NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    principal_name  text,
    token_id        uuid,
    actor           text,
    detail          text,
    client_app      text,
    client_ip       inet
);

CREATE INDEX audit_auth_event_time_idx ON audit_auth_event (occurred_at DESC);
CREATE INDEX audit_auth_event_principal_idx ON audit_auth_event (principal_name, occurred_at DESC);

-- Rows here are the record; nothing in ARAK changes or removes one.
CREATE FUNCTION audit_auth_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_auth_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_auth_event_no_change
    BEFORE UPDATE OR DELETE ON audit_auth_event
    FOR EACH ROW EXECUTE FUNCTION audit_auth_event_append_only();

-- Which way a query came in, and the tool that sent it when it came through
-- the gateway (the application_name the client gave, as the client gave it).
ALTER TABLE audit_query ADD COLUMN channel text NOT NULL DEFAULT 'WEB'
    CHECK (channel IN ('WEB', 'GATEWAY'));
ALTER TABLE audit_query ADD COLUMN client_app text;
