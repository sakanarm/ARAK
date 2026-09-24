-- V20 — what a secure view was called when it was applied, and who applied it.
--
-- Two additions, both for M5 slice 3 (dry-run / apply / rollback of 5.1.2).
--
-- The view's name is stored on the state row because rollback has to drop the
-- object that was created, not the object the source's naming settings would
-- produce today. Somebody who changes a source's secure schema from `sec` to
-- `secure` after an apply and then rolls back would otherwise drop a view that
-- was never created and leave the real one standing -- a rollback that reports
-- success and removes nothing.

ALTER TABLE enforcement_state
    ADD COLUMN secure_object_schema text,
    ADD COLUMN secure_object_name   text;

-- Every dry run, apply and rollback, whatever its outcome (FR-8.1). Append-only
-- for the same reason as the tables in V5: the application role is granted
-- INSERT and SELECT here and nothing else at deploy time.
--
-- A dry run is recorded too. It writes nothing, but it opens a connection to a
-- customer's database with the platform's credential and reads who holds what
-- there, and that is a thing an auditor asks about.
CREATE TABLE audit_enforcement (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    actor               text NOT NULL,
    target_fqn          text NOT NULL,
    data_source_id      uuid,
    mode                text NOT NULL CHECK (mode IN ('NATIVE_CONFIG', 'SECURE_VIEW', 'PROXY')),
    action              text NOT NULL CHECK (action IN ('DRY_RUN', 'APPLY', 'ROLLBACK')),
    outcome             text NOT NULL CHECK (outcome IN
        ('REVIEWED', 'APPLIED', 'ROLLED_BACK', 'STALE', 'FAILED', 'REFUSED')),
    -- The dry run an apply was carried out against, so that "what did the
    -- person who pressed apply actually read" has an answer.
    review_id           uuid,
    signature           text,
    statements          integer,
    rows_inserted       integer,
    rows_deleted        integer,
    detail              text,
    client_ip           inet
);
CREATE INDEX audit_enforcement_target_idx ON audit_enforcement (target_fqn, occurred_at DESC);
CREATE INDEX audit_enforcement_time_idx ON audit_enforcement (occurred_at DESC);
