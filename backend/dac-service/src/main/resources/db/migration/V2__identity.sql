-- V2 — identity cache.
--
-- Entra ID is the production source; local principals exist so tests, service
-- accounts and external users do not require a tenant (FR-2.2). Token claims
-- are usable immediately, without waiting for a sync round (FR-2.5).

CREATE TABLE principal (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type      text NOT NULL CHECK (principal_type IN ('USER', 'GROUP', 'SERVICE')),
    username            text NOT NULL,
    email               text,
    display_name        text,
    external_id         text,
    source              text NOT NULL CHECK (source IN ('entra', 'openmetadata', 'local')),
    enabled             boolean NOT NULL DEFAULT true,
    last_synced_at      timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, username)
);
CREATE INDEX principal_email_idx ON principal (lower(email));

CREATE TABLE principal_attribute (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id        uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    attr_key            text NOT NULL,
    -- One row per value: attributes are multi-valued by nature (a clearance of
    -- L1 and L2, membership of three regions) and collapsing them into a string
    -- makes every comparison a parsing problem (FR-2.4).
    attr_value          text NOT NULL,
    source              text NOT NULL CHECK (source IN ('entra', 'openmetadata', 'local')),
    valid_from          timestamptz NOT NULL DEFAULT now(),
    valid_to            timestamptz,
    UNIQUE (principal_id, attr_key, attr_value, source)
);
CREATE INDEX principal_attribute_key_idx ON principal_attribute (attr_key, attr_value);

CREATE TABLE group_member (
    group_id            uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    member_id           uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    source              text NOT NULL CHECK (source IN ('entra', 'openmetadata', 'local')),
    synced_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, member_id, source)
);
CREATE INDEX group_member_member_idx ON group_member (member_id);

-- The platform's own roles, distinct from anything in a data source. The point
-- of separating them is separation of duty: whoever writes a policy must be
-- able to be someone other than whoever approves it (FR-2.6).
CREATE TABLE app_role_assignment (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id        uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    app_role            text NOT NULL CHECK (app_role IN
        ('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR', 'REQUESTER')),
    -- Scopes a DATA_OWNER to their own corner of the tree; null means global.
    scope_fqn           text,
    granted_by          text,
    granted_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (principal_id, app_role, scope_fqn)
);

-- Which database login or role each principal maps to on each source. Native
-- enforcement only works if the database itself knows who is asking, so this
-- mapping is the backbone of modes 5.1.1 and 5.1.2 rather than an optimisation
-- (see plan section 2).
CREATE TABLE db_principal_map (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id      uuid NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    principal_id        uuid NOT NULL REFERENCES principal (id) ON DELETE CASCADE,
    db_principal_name   text NOT NULL,
    db_principal_kind   text NOT NULL CHECK (db_principal_kind IN ('LOGIN', 'USER', 'ROLE')),
    provisioned         boolean NOT NULL DEFAULT false,
    provisioned_at      timestamptz,
    last_error          text,
    UNIQUE (data_source_id, principal_id)
);
