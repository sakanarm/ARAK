-- V1 — the metadata cache.
--
-- OpenMetadata is the source of truth for discovery metadata; this is our copy
-- of it. We keep one because authorization sits in the hot path of every query
-- and cannot depend on another service being up (see plan 0.1). Everything here
-- is therefore rebuildable from a full crawl: no data is born in these tables
-- except what the provenance column marks as local.

CREATE TABLE data_source (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    text NOT NULL UNIQUE,
    engine                  text NOT NULL CHECK (engine IN ('POSTGRES', 'SQLSERVER')),
    engine_version          text,
    host                    text NOT NULL,
    port                    integer NOT NULL,
    default_database        text,
    -- Never the credential itself: a pointer into the vault or the Fernet store.
    credential_ref          text NOT NULL,
    -- Default for assets on this source; overridable per asset (FR-6.0a).
    default_enforcement_mode text NOT NULL DEFAULT 'SECURE_VIEW'
        CHECK (default_enforcement_mode IN ('NATIVE_CONFIG', 'SECURE_VIEW', 'PROXY', 'NONE')),
    om_service_fqn          text,
    secure_schema           text NOT NULL DEFAULT 'sec',
    secure_object_pattern   text NOT NULL DEFAULT '{table}',
    enabled                 boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);

-- Assets are versioned (SCD2) so an audit three months from now can answer what
-- the catalog said at the moment a decision was made, not what it says today.
CREATE TABLE asset (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id      uuid REFERENCES data_source (id) ON DELETE CASCADE,
    om_id               uuid,
    fqn                 text NOT NULL,
    asset_type          text NOT NULL CHECK (asset_type IN ('SERVICE', 'DATABASE', 'SCHEMA', 'TABLE', 'VIEW')),
    parent_fqn          text,
    name                text NOT NULL,
    display_name        text,
    description         text,
    tier                text,
    certification       text,
    -- Raw OpenMetadata custom property values (the extension field).
    custom_properties   jsonb NOT NULL DEFAULT '{}'::jsonb,
    provenance          text NOT NULL DEFAULT 'openmetadata'
        CHECK (provenance IN ('openmetadata', 'local', 'discovered')),
    valid_from          timestamptz NOT NULL DEFAULT now(),
    valid_to            timestamptz,
    is_current          boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX asset_fqn_current_uq ON asset (fqn) WHERE is_current;
CREATE INDEX asset_parent_idx ON asset (parent_fqn) WHERE is_current;
CREATE INDEX asset_om_id_idx ON asset (om_id) WHERE is_current;

CREATE TABLE asset_column (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            uuid NOT NULL REFERENCES asset (id) ON DELETE CASCADE,
    fqn                 text NOT NULL,
    name                text NOT NULL,
    ordinal             integer,
    data_type           text,
    data_length         integer,
    nullable            boolean,
    description         text,
    custom_properties   jsonb NOT NULL DEFAULT '{}'::jsonb,
    valid_from          timestamptz NOT NULL DEFAULT now(),
    valid_to            timestamptz,
    is_current          boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX asset_column_fqn_current_uq ON asset_column (fqn) WHERE is_current;
CREATE INDEX asset_column_asset_idx ON asset_column (asset_id) WHERE is_current;

-- The catalog can lag the database. Before any DDL is generated we re-verify
-- the column list against the source itself; this table records the mapping and
-- when it was last confirmed (FR-1.6).
CREATE TABLE asset_fqn_map (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_fqn              text NOT NULL UNIQUE,
    om_id               uuid,
    data_source_id      uuid NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    database_name       text NOT NULL,
    schema_name         text NOT NULL,
    object_name         text NOT NULL,
    object_kind         text NOT NULL DEFAULT 'TABLE',
    last_verified_at    timestamptz,
    verification_status text NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (verification_status IN ('UNVERIFIED', 'MATCHED', 'ORPHANED', 'DRIFTED')),
    UNIQUE (data_source_id, database_name, schema_name, object_name)
);

CREATE TABLE classification (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_id               uuid,
    fqn                 text NOT NULL UNIQUE,
    name                text NOT NULL,
    description         text,
    -- If true an asset may carry only one tag from this classification, so the
    -- builder must offer a radio group rather than checkboxes (FR-2A.3a).
    mutually_exclusive  boolean NOT NULL DEFAULT false,
    provider            text NOT NULL DEFAULT 'user' CHECK (provider IN ('system', 'user')),
    disabled            boolean NOT NULL DEFAULT false,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tag (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_id               uuid,
    classification_fqn  text NOT NULL,
    fqn                 text NOT NULL UNIQUE,
    parent_fqn          text,
    name                text NOT NULL,
    description         text,
    disabled            boolean NOT NULL DEFAULT false,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tag_classification_idx ON tag (classification_fqn);

CREATE TABLE glossary (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_id               uuid,
    fqn                 text NOT NULL UNIQUE,
    name                text NOT NULL,
    description         text,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE glossary_term (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_id               uuid,
    glossary_fqn        text NOT NULL,
    fqn                 text NOT NULL UNIQUE,
    parent_fqn          text,
    name                text NOT NULL,
    description         text,
    -- Synonyms and related terms are stored but never matched implicitly: a
    -- policy whose reach changes because someone added a synonym is a policy
    -- nobody can predict (FR-2A.3).
    synonyms            jsonb NOT NULL DEFAULT '[]'::jsonb,
    related_terms       jsonb NOT NULL DEFAULT '[]'::jsonb,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX glossary_term_glossary_idx ON glossary_term (glossary_fqn);

CREATE TABLE domain (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_id               uuid,
    fqn                 text NOT NULL UNIQUE,
    -- Sub-domains nest arbitrarily deep: Finance, Finance.Risk, Finance.Risk.Credit.
    parent_fqn          text,
    depth               integer NOT NULL DEFAULT 0,
    name                text NOT NULL,
    description         text,
    domain_type         text,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX domain_parent_idx ON domain (parent_fqn);

CREATE TABLE data_product (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    om_id               uuid,
    fqn                 text NOT NULL UNIQUE,
    name                text NOT NULL,
    description         text,
    domain_fqn          text,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- The definitions, not the values: the builder needs to know that
-- dataResidency is an enum of three countries before it can offer a dropdown
-- instead of a free-text box (FR-1.9).
CREATE TABLE custom_property_def (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type         text NOT NULL,
    name                text NOT NULL,
    display_name        text,
    description         text,
    data_type           text NOT NULL,
    enum_values         jsonb NOT NULL DEFAULT '[]'::jsonb,
    multi_select        boolean NOT NULL DEFAULT false,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (entity_type, name)
);

-- The table every policy selector reads.
--
-- Ancestors are expanded at write time, so an asset in Finance.Risk.Credit gets
-- three rows: Finance (depth 2), Finance.Risk (depth 1), Finance.Risk.Credit
-- (depth 0, is_direct). A selector then becomes an index lookup instead of a
-- recursive query, which is what keeps decisions inside the p95 budget of 50ms
-- (FR-2A.2, NFR-2).
CREATE TABLE asset_facet (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id            uuid REFERENCES asset (id) ON DELETE CASCADE,
    column_id           uuid REFERENCES asset_column (id) ON DELETE CASCADE,
    target_fqn          text NOT NULL,
    facet_type          text NOT NULL,
    facet_fqn           text NOT NULL,
    -- 0 when the facet is bound at this exact level, higher for each ancestor.
    depth               integer NOT NULL DEFAULT 0,
    is_direct           boolean NOT NULL DEFAULT true,
    -- Which asset the facet was inherited from, so the UI can answer "why does
    -- this column count as PII?" without guessing (FR-2A.1).
    inherited_from      text,
    provenance          text NOT NULL DEFAULT 'openmetadata',
    om_state            text CHECK (om_state IN ('Suggested', 'Confirmed')),
    om_label_type       text CHECK (om_label_type IN ('Manual', 'Automated', 'Propagated', 'Derived')),
    computed_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (asset_id IS NOT NULL OR column_id IS NOT NULL)
);
CREATE INDEX asset_facet_lookup_idx ON asset_facet (facet_type, facet_fqn);
CREATE INDEX asset_facet_target_idx ON asset_facet (target_fqn);
CREATE INDEX asset_facet_asset_idx ON asset_facet (asset_id);
CREATE INDEX asset_facet_column_idx ON asset_facet (column_id);

CREATE TABLE asset_owner (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target_fqn          text NOT NULL,
    owner_type          text NOT NULL CHECK (owner_type IN ('user', 'team')),
    owner_name          text NOT NULL,
    owner_om_id         uuid,
    is_direct           boolean NOT NULL DEFAULT true,
    inherited_from      text,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (target_fqn, owner_type, owner_name)
);
CREATE INDEX asset_owner_owner_idx ON asset_owner (owner_type, owner_name);

-- Cursor for the change-event poller that backs up the webhook (FR-1.5).
CREATE TABLE sync_state (
    source              text PRIMARY KEY,
    last_event_ts       bigint,
    last_full_crawl_at  timestamptz,
    last_reconcile_at   timestamptz,
    status              text NOT NULL DEFAULT 'IDLE',
    last_error          text,
    updated_at          timestamptz NOT NULL DEFAULT now()
);
