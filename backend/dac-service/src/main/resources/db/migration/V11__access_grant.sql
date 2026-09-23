-- V11 — direct access grants (FR-7).
--
-- A grant is the answer to "let this person into this table, starting now,
-- until this date" without writing a policy for it. Policies say who may reach
-- a class of assets; a grant names one asset and one principal. Both end up in
-- the same PolicyDecision, and a grant composes by intersection like everything
-- else (FR-5.1): it can let someone in where no policy speaks, and it can never
-- let someone past a policy that says no. A grant that could loosen a global
-- policy would make every global policy a suggestion.
--
-- This migration alters rather than creates. V3 already declared `access_grant`
-- while sketching the policy tables, months before FR-7 had a shape; no code
-- ever read or wrote it, and the columns it guessed at are not the columns the
-- feature needs. Re-creating the table would have been the shorter diff and the
-- wrong one: a migration that drops a table is a migration that destroys data
-- on any database where the guess turned out to be used, and being sure that is
-- nowhere is not something a migration can check. Altering costs a longer file
-- and is true in every environment.

-- The asset by its FQN, not its id. `asset` is versioned (SCD2), so an id points
-- at one revision of a table, and a grant outlives revisions: adding a column
-- must not silently drop everyone's access. V3 called this `target_fqn`, which
-- read as "whatever a grant might point at" back when that was still open.
ALTER TABLE access_grant RENAME COLUMN target_fqn TO asset_fqn;

-- V3's name for it. Renamed rather than added so the default and the NOT NULL
-- come along with it.
ALTER TABLE access_grant RENAME COLUMN created_at TO granted_at;

-- V3 let a grant point at the policy that produced it. Nothing ever did, and the
-- model since settled the other way round: a grant is its own authority, and
-- what a policy grants is the policy's business. A nullable column no writer
-- fills is a column every reader has to ask about.
ALTER TABLE access_grant DROP COLUMN IF EXISTS policy_id;

-- Why. Mandatory, because in a year this is the only column that can still
-- explain the row, and a review reads it before it reads anything else. V3 had
-- it nullable; the fill below is for rows that predate that decision.
UPDATE access_grant SET reason = '(no reason recorded)' WHERE reason IS NULL;
ALTER TABLE access_grant ALTER COLUMN reason SET NOT NULL;

-- Revocation is a tombstone, not a delete: "who had access last March" has to
-- stay answerable. The expiry job writes these too, with actor 'system'.
ALTER TABLE access_grant ADD COLUMN revoke_reason text;

ALTER TABLE access_grant
    ADD CONSTRAINT access_grant_window
    CHECK (valid_until IS NULL OR valid_until > valid_from);

-- A revocation is only coherent if it names who did it.
ALTER TABLE access_grant
    ADD CONSTRAINT access_grant_revocation
    CHECK ((revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL));


-- The asset page's "who can reach this table" list, which reads by asset alone.
-- Same index V3 built, under the name the column now has.
ALTER INDEX access_grant_target_idx RENAME TO access_grant_asset_idx;

-- The expiry job's scan: the few rows whose window has closed but whose
-- tombstone has not been written yet. V3's version matched every live grant,
-- including the open-ended ones the job can never act on.
DROP INDEX access_grant_expiry_idx;
CREATE INDEX access_grant_expiry_idx ON access_grant (valid_until)
    WHERE revoked_at IS NULL AND valid_until IS NOT NULL;

-- The engine's lookup: every live grant for one principal on one asset. Partial
-- on the tombstone because revoked rows are for reports, never for decisions.
CREATE INDEX access_grant_lookup_idx
    ON access_grant (asset_fqn, principal_id)
    WHERE revoked_at IS NULL;

-- The same principal can hold two live grants on one asset -- a standing one and
-- a short extension -- so there is no unique constraint here on purpose. What
-- must not happen is two identical live rows created by a double-click.
CREATE UNIQUE INDEX access_grant_no_duplicate_idx
    ON access_grant (asset_fqn, principal_id, valid_from)
    WHERE revoked_at IS NULL;

-- V3's access_grant_principal_idx is already exactly what "what am I holding"
-- needs, so it stays as it is.


-- Append-only, same contract as the other audit tables in V5 and V10: the
-- application role gets INSERT and SELECT and nothing else.
CREATE TABLE audit_grant_change (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    -- A username, not a principal id, and 'system' for the expiry job. The
    -- trail has to survive the deletion of whoever is named in it.
    actor               text NOT NULL,
    action              text NOT NULL CHECK (action IN ('GRANT', 'REVOKE', 'EXPIRE')),
    grant_id            uuid,
    asset_fqn           text NOT NULL,
    -- Denormalised for the same reason as the actor.
    target_username     text NOT NULL,
    target_source       text,
    valid_from          timestamptz,
    valid_until         timestamptz,
    reason              text
);

CREATE INDEX audit_grant_change_asset_idx ON audit_grant_change (asset_fqn, occurred_at DESC);
CREATE INDEX audit_grant_change_target_idx ON audit_grant_change (target_username, occurred_at DESC);
