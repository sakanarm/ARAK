-- V5 — audit.
--
-- Append-only by intent and by permission: the application role gets INSERT and
-- SELECT here and nothing else (granted at deploy time, since the role name
-- varies by environment). An audit trail the application can rewrite is not an
-- audit trail.

CREATE TABLE audit_policy_change (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    actor               text NOT NULL,
    policy_id           uuid,
    policy_name         text,
    action              text NOT NULL CHECK (action IN
        ('CREATE', 'UPDATE', 'DELETE', 'PUBLISH', 'DISABLE', 'ARCHIVE', 'ROLLBACK', 'OVERRIDE')),
    from_version        integer,
    to_version          integer,
    before_document     jsonb,
    after_document      jsonb,
    -- Mandatory when a local policy relaxes a higher layer (FR-3.1.4).
    reason              text,
    client_ip           inet
);
CREATE INDEX audit_policy_change_policy_idx ON audit_policy_change (policy_id, occurred_at DESC);
CREATE INDEX audit_policy_change_time_idx ON audit_policy_change (occurred_at DESC);

CREATE TABLE audit_decision (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    principal_name      text NOT NULL,
    target_fqn          text NOT NULL,
    allowed             boolean NOT NULL,
    mode                text CHECK (mode IN ('NATIVE_CONFIG', 'SECURE_VIEW', 'PROXY')),
    -- The full PolicyDecision, reasons included, so the question "why did this
    -- person see this" is answerable months later without replaying the engine
    -- against policies that have since changed (FR-5.4, FR-8.2).
    decision            jsonb NOT NULL,
    matched_policy_ids  uuid[],
    evaluation_ms       integer,
    from_cache          boolean NOT NULL DEFAULT false,
    purpose             text,
    client_ip           inet
);
CREATE INDEX audit_decision_principal_idx ON audit_decision (principal_name, occurred_at DESC);
CREATE INDEX audit_decision_target_idx ON audit_decision (target_fqn, occurred_at DESC);
CREATE INDEX audit_decision_time_idx ON audit_decision (occurred_at DESC);

CREATE TABLE audit_query (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    principal_name      text NOT NULL,
    data_source_id      uuid,
    original_sql        text NOT NULL,
    -- Null when the query was rejected: a proxy that cannot resolve every table
    -- reference rejects rather than forwards, and the rejection is the record
    -- (FR-6.3).
    rewritten_sql       text,
    outcome             text NOT NULL CHECK (outcome IN ('EXECUTED', 'REJECTED', 'FAILED')),
    reject_reason       text,
    row_count           bigint,
    duration_ms         integer,
    client_ip           inet
);
CREATE INDEX audit_query_principal_idx ON audit_query (principal_name, occurred_at DESC);
CREATE INDEX audit_query_time_idx ON audit_query (occurred_at DESC);
