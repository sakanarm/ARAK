-- V3 — policies.
--
-- The policy document itself stays JSONB and is validated against the JSON
-- Schema in dac-spec; the columns lifted out beside it are exactly the ones the
-- engine filters on when collecting candidates for an asset. Duplicating them
-- is deliberate: it keeps candidate selection an index scan while leaving the
-- schema free to evolve in one place.

CREATE TABLE policy (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                text NOT NULL,
    display_name        text,
    description         text,
    policy_type         text NOT NULL CHECK (policy_type IN ('SUBSCRIPTION', 'DATA')),
    scope_level         text NOT NULL CHECK (scope_level IN
        ('ORG', 'DOMAIN', 'SERVICE', 'DATABASE', 'SCHEMA', 'TABLE', 'COLUMN')),
    scope_fqn           text,
    -- Sub-domain depth. Two DOMAIN policies are not peers: one on
    -- Finance.Risk.Credit sits below one on Finance and may only tighten it.
    scope_depth         integer NOT NULL DEFAULT 0,
    effect              text NOT NULL DEFAULT 'ALLOW' CHECK (effect IN ('ALLOW', 'DENY')),
    allow_local_override boolean NOT NULL DEFAULT false,
    lifecycle_state     text NOT NULL DEFAULT 'DRAFT' CHECK (lifecycle_state IN
        ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'DISABLED', 'ARCHIVED')),
    environment         text NOT NULL DEFAULT 'dev' CHECK (environment IN ('dev', 'uat', 'prod')),
    -- The whole Policy document, conforming to entity/policy/policy.json.
    document            jsonb NOT NULL,
    version             integer NOT NULL DEFAULT 1,
    valid_from          timestamptz,
    valid_until         timestamptz,
    created_by          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          text,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (name, environment)
);
CREATE INDEX policy_candidate_idx ON policy (environment, lifecycle_state, policy_type, scope_level, scope_depth);
CREATE INDEX policy_scope_fqn_idx ON policy (scope_fqn);
CREATE INDEX policy_document_gin ON policy USING gin (document);

-- Append-only history. Rollback writes a new version rather than restoring one,
-- so the record of what was live at any moment stays intact (FR-9.2).
CREATE TABLE policy_version (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id           uuid NOT NULL REFERENCES policy (id) ON DELETE CASCADE,
    version             integer NOT NULL,
    document            jsonb NOT NULL,
    lifecycle_state     text NOT NULL,
    changed_by          text,
    change_reason       text,
    changed_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (policy_id, version)
);

-- Materialised selector results (FR-3.1.6). Re-resolved when a policy changes,
-- when an asset appears, and when any facet on an asset changes -- which is what
-- makes a table tagged PII this afternoon covered this afternoon, with nobody
-- remembering to press anything.
CREATE TABLE policy_binding (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id           uuid NOT NULL REFERENCES policy (id) ON DELETE CASCADE,
    target_fqn          text NOT NULL,
    target_kind         text NOT NULL CHECK (target_kind IN ('TABLE', 'COLUMN')),
    asset_id            uuid REFERENCES asset (id) ON DELETE CASCADE,
    column_id           uuid REFERENCES asset_column (id) ON DELETE CASCADE,
    -- Why the selector matched, kept for the "policies affecting this asset"
    -- screen so an owner is never told only that it matched (FR-3.1.5).
    match_reason        jsonb NOT NULL DEFAULT '{}'::jsonb,
    resolved_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (policy_id, target_fqn)
);
CREATE INDEX policy_binding_target_idx ON policy_binding (target_fqn);

-- Grants made directly by an owner. Phase 2 adds the request workflow on top;
-- the source and request_id columns exist now so that arrival needs no
-- migration (FR-7).
CREATE TABLE access_grant (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id        uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    target_fqn          text NOT NULL,
    policy_id           uuid REFERENCES policy (id) ON DELETE SET NULL,
    source              text NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'request')),
    request_id          uuid,
    reason              text,
    granted_by          text NOT NULL,
    valid_from          timestamptz NOT NULL DEFAULT now(),
    valid_until         timestamptz,
    revoked_at          timestamptz,
    revoked_by          text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX access_grant_principal_idx ON access_grant (principal_id) WHERE revoked_at IS NULL;
CREATE INDEX access_grant_target_idx ON access_grant (target_fqn) WHERE revoked_at IS NULL;
CREATE INDEX access_grant_expiry_idx ON access_grant (valid_until) WHERE revoked_at IS NULL;

-- Row entitlements the secure views join against (FR-6.1). Maintained by the
-- engine from PolicyDecision plus identity sync; never edited by hand, because
-- a row here is a live grant on production data.
CREATE TABLE row_entitlement (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_name      text NOT NULL,
    target_fqn          text NOT NULL,
    entitlement_key     text NOT NULL,
    entitlement_value   text NOT NULL,
    policy_id           uuid REFERENCES policy (id) ON DELETE CASCADE,
    valid_until         timestamptz,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (principal_name, target_fqn, entitlement_key, entitlement_value)
);
CREATE INDEX row_entitlement_lookup_idx ON row_entitlement (target_fqn, principal_name);
