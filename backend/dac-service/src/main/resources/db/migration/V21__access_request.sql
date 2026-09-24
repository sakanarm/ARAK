-- V21 — asking for access, and the owner's answer (FR-7 grown into the first
-- slice of the Phase 2 workflow).
--
-- A refused query used to be a dead end: the message named the policy that said
-- no, and the reader then had to find out on their own who could say yes. This
-- table is the other half of that sentence. A request names one asset and one
-- requester; the asset's owner, as OpenMetadata records it, is who decides; and
-- approving writes an ordinary grant with source = 'request', so that nothing
-- downstream -- the engine, the composition rules, expiry, the asset page --
-- has to learn that requests exist. V3 left `access_grant.source` and
-- `request_id` for exactly this.
--
-- What approval cannot do is the same thing a grant cannot do (V11): it enters
-- the engine at the TABLE layer and composes by intersection, so an approved
-- request opens a table nothing else speaks to and never passes a DENY or a
-- higher layer that refuses the requester.

CREATE TABLE access_request (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_fqn           text NOT NULL,
    -- The id is what approval grants to. The username is denormalised beside it
    -- for the reason the audit tables give: the history has to stay readable
    -- after the person it names is gone, so the id is SET NULL, not CASCADE.
    requester_id        uuid REFERENCES principal (id) ON DELETE SET NULL,
    requester_username  text NOT NULL,
    -- Which source the refused statement was aimed at, when the request came
    -- from the query page. Informational: the grant is on the asset, whatever
    -- source it was reached through.
    data_source_id      uuid,
    reason              text NOT NULL CHECK (length(btrim(reason)) > 0),
    purpose             text,
    -- How long the requester asked for. Null means "until revoked", which the
    -- owner sees as such before approving; the owner may shorten it and may not
    -- lengthen it past what was asked.
    requested_days      integer CHECK (requested_days IS NULL OR requested_days BETWEEN 1 AND 365),
    -- What the requester was trying to do and what stopped them, so the owner
    -- decides with the same facts the requester had. The SQL is theirs to see;
    -- it is shown only to the requester and to whoever can decide.
    attempted_sql       text,
    denied_by           text,
    status              text NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    decided_by          text,
    decided_at          timestamptz,
    decision_note       text,
    grant_id            uuid REFERENCES access_grant (id),
    -- A request is open until somebody closes it, and closing it says who and
    -- when; an approval additionally points at the grant it produced.
    CONSTRAINT access_request_decided
        CHECK ((status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
            OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT access_request_approved_has_grant
        CHECK ((status = 'APPROVED') = (grant_id IS NOT NULL))
);

-- One open request per person per asset. A second click, or a second refused
-- query on the same table, is the same question and must not queue twice in the
-- owner's inbox.
CREATE UNIQUE INDEX access_request_one_open_idx
    ON access_request (asset_fqn, requester_id)
    WHERE status = 'PENDING';

-- The owner's inbox reads by asset; "my requests" reads by requester.
CREATE INDEX access_request_asset_idx ON access_request (asset_fqn, created_at DESC);
CREATE INDEX access_request_requester_idx ON access_request (requester_username, created_at DESC);

-- V3 left request_id without a target because the table it points at did not
-- exist yet. It does now.
ALTER TABLE access_grant
    ADD CONSTRAINT access_grant_request_fk
    FOREIGN KEY (request_id) REFERENCES access_request (id);

-- A grant that came from a request says which one, and only such a grant does.
ALTER TABLE access_grant
    ADD CONSTRAINT access_grant_request_source
    CHECK ((source = 'request') = (request_id IS NOT NULL));


-- Append-only, the same contract as V5, V10, V11 and V20: the application role
-- is granted INSERT and SELECT here and nothing else at deploy time. The request
-- row itself changes state; this is the record that it did, and who moved it.
CREATE TABLE audit_access_request (
    id                  bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT now(),
    actor               text NOT NULL,
    action              text NOT NULL CHECK (action IN ('REQUEST', 'APPROVE', 'REJECT', 'WITHDRAW')),
    request_id          uuid NOT NULL,
    asset_fqn           text NOT NULL,
    requester_username  text NOT NULL,
    grant_id            uuid,
    note                text
);

CREATE INDEX audit_access_request_asset_idx
    ON audit_access_request (asset_fqn, occurred_at DESC);
