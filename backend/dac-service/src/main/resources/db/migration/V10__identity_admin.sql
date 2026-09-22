-- V10 — the record of who changed identity and who holds which platform role.
--
-- V5 records policy changes and access decisions. Neither covers the act that
-- precedes both: creating an account, or handing someone PLATFORM_ADMIN. An
-- auditor asking "who gave this person the right to write policy, and when"
-- had no table to read until this one.
--
-- Append-only by the same rule as V5: INSERT and SELECT for the application
-- role, nothing else, granted at deploy time.

CREATE TABLE audit_identity_change (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    -- The caller's username, not their id: a principal can be deleted and the
    -- trail has to survive that.
    actor               text NOT NULL,
    action              text NOT NULL CHECK (action IN
        ('CREATE_PRINCIPAL', 'ENABLE_PRINCIPAL', 'DISABLE_PRINCIPAL',
         'SET_PASSWORD', 'GRANT_ROLE', 'REVOKE_ROLE')),
    target_principal_id uuid,
    -- Denormalised for the same reason as the actor.
    target_username     text NOT NULL,
    target_source       text,
    app_role            text,
    scope_fqn           text,
    -- Free text: "why this person needs PLATFORM_ADMIN" is the part a review
    -- actually reads, and it cannot be reconstructed from the columns above.
    reason              text,
    client_ip           inet
);
CREATE INDEX audit_identity_change_target_idx
    ON audit_identity_change (target_principal_id, occurred_at DESC);
CREATE INDEX audit_identity_change_time_idx
    ON audit_identity_change (occurred_at DESC);

-- Two accounts differing only in case would both answer
-- `lower(username) = lower(:username)` at login, and the lookup takes exactly
-- one row — so the second such account does not merely shadow the first, it
-- breaks sign-in for both. The store rejects the case before inserting; this
-- index is the guarantee that holds when something bypasses the store.
-- Groups are left out because the login lookup leaves them out: a local group
-- named like a local user cannot shadow them.
CREATE UNIQUE INDEX principal_local_username_ci_idx
    ON principal (lower(username))
    WHERE source = 'local' AND principal_type <> 'GROUP';

-- The UNIQUE constraint in V2 is (principal_id, app_role, scope_fqn), and a
-- global grant has scope_fqn NULL — which Postgres treats as distinct from
-- every other NULL, so that constraint never fires for exactly the grants that
-- matter most, and ON CONFLICT DO NOTHING quietly inserts a second copy. One
-- person then appears twice on the roles screen, and revoking once leaves the
-- role in place.
DELETE FROM app_role_assignment a
WHERE a.scope_fqn IS NULL
  AND EXISTS (SELECT 1 FROM app_role_assignment b
              WHERE b.principal_id = a.principal_id
                AND b.app_role = a.app_role
                AND b.scope_fqn IS NULL
                AND (b.granted_at, b.id) < (a.granted_at, a.id));

CREATE UNIQUE INDEX app_role_assignment_global_idx
    ON app_role_assignment (principal_id, app_role)
    WHERE scope_fqn IS NULL;
