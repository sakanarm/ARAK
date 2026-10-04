-- V47 -- subscription policies pushed down to PostgreSQL as roles (FR-6.2, mode 5.1.1).
--
-- A subscription policy becomes one NOLOGIN role per source, named
-- arak_sub_<policy>_<source>, holding CONNECT, USAGE and (at Read) SELECT on the
-- policy's tables, with the logins of the people the policy lets in as its
-- members. Nothing here runs by itself: a person plans, reads the plan and
-- applies it. The sweep only ever takes away.

-- The account ARAK pushes grants with. Kept apart from data_source.credential_ref
-- on purpose: the proxy and the crawler read with that one and must stay
-- read-only, while this one needs CREATEROLE and grant options. A source with
-- no row here cannot have anything pushed to it.
CREATE TABLE native_credential (
    data_source_id  uuid PRIMARY KEY REFERENCES data_source (id) ON DELETE CASCADE,
    credential_ref  text NOT NULL,
    updated_by      text NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- One row per policy and source that ARAK has put a role on, or tried to.
-- policy_id has no foreign key: a policy that is archived or deleted still has
-- a role on the source until somebody rolls it back, and the sweep must still
-- find the row to take its members away.
CREATE TABLE native_role (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id            uuid NOT NULL,
    data_source_id       uuid NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    role_name            text NOT NULL UNIQUE,
    database_name        text NOT NULL,
    access_level         text NOT NULL CHECK (access_level IN ('BROWSE', 'READ')),
    status               text NOT NULL
        CHECK (status IN ('APPLIED', 'PENDING', 'DRIFTED', 'FAILED', 'ROLLED_BACK')),
    applied_script       text,
    applied_fingerprint  text,
    members              jsonb NOT NULL DEFAULT '[]'::jsonb,
    tables               jsonb NOT NULL DEFAULT '[]'::jsonb,
    last_applied_at      timestamptz,
    last_applied_by      text,
    last_checked_at      timestamptz,
    last_error           text,
    detail               text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (policy_id, data_source_id)
);

CREATE INDEX native_role_source_idx ON native_role (data_source_id);

-- The sweep's two acts go on the same trail as a person's, and so does every
-- change to the push account or to who connects as which login (CONFIGURE).
ALTER TABLE audit_enforcement DROP CONSTRAINT audit_enforcement_action_check;
ALTER TABLE audit_enforcement ADD CONSTRAINT audit_enforcement_action_check
    CHECK (action IN
        ('DRY_RUN', 'APPLY', 'ROLLBACK', 'DIRECT_ACCESS_CHECK', 'DRIFT_CHECK', 'EXPIRE',
         'CONFIGURE'));

ALTER TABLE audit_enforcement DROP CONSTRAINT audit_enforcement_outcome_check;
ALTER TABLE audit_enforcement ADD CONSTRAINT audit_enforcement_outcome_check
    CHECK (outcome IN
        ('REVIEWED', 'APPLIED', 'ROLLED_BACK', 'STALE', 'FAILED', 'REFUSED', 'CHECKED',
         'IN_SYNC', 'DRIFTED', 'PENDING', 'CHANGED'));
