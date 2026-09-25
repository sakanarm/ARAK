-- V23 — access request workflows (M9 slice 2a): who approves a request, in
-- what order, by what rule, and who then configures the access.
--
-- V21 had one decision: an owner said yes and the yes was a grant. Real
-- approval chains have more than one voice -- the owner, then security; the
-- data steward and the custodian at once -- and the yes is not always a grant:
-- sometimes the right answer is a change to a policy. So a request now walks
-- a workflow of stages, and an approved request waits for somebody to
-- configure it (MANUAL fulfilment). Automatic fulfilment is on the roadmap,
-- not here.

-- A workflow applies to every asset under its scope; the deepest scope wins,
-- segment by segment, and a null scope is the organisation's default. With no
-- workflow at all, the built-in one applies: any one owner of the table, then
-- the owners or the data custodian configure it.
CREATE TABLE access_workflow (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    description     text,
    scope_fqn       text CHECK (scope_fqn IS NULL OR length(btrim(scope_fqn)) > 0),
    enabled         boolean NOT NULL DEFAULT true,
    -- [{step, name, rule, minApprovals, onReject, approvers: [{kind, name}]}]
    -- Stages with the same step run side by side; steps run one after another.
    -- Validated by AccessWorkflow before it is written; the shape is checked
    -- here only as far as a CHECK can without restating the validator.
    stages          jsonb NOT NULL
                    CHECK (jsonb_typeof(stages) = 'array' AND jsonb_array_length(stages) > 0),
    -- Who configures an approved request: [{kind, name}], the same seats.
    configurers     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(configurers) = 'array'),
    fulfilment      text NOT NULL DEFAULT 'MANUAL' CHECK (fulfilment IN ('MANUAL')),
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- One workflow per scope, the default included.
CREATE UNIQUE INDEX access_workflow_scope_idx ON access_workflow (coalesce(scope_fqn, ''));

-- Append-only, like every audit table before it.
CREATE TABLE audit_access_workflow (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor           text NOT NULL,
    action          text NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    workflow_id     uuid NOT NULL,
    scope_fqn       text,
    before          jsonb,
    after           jsonb
);


-- ------------------------------------------------------------ the request

-- A request copies what it needs of its workflow when it is made, so editing
-- a workflow never changes the rules of a request already walking it.
ALTER TABLE access_request
    ADD COLUMN workflow_id      uuid REFERENCES access_workflow (id) ON DELETE SET NULL,
    ADD COLUMN workflow_name    text,
    ADD COLUMN configurers      jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- The people who may configure it, resolved when it is approved.
    ADD COLUMN configurer_pool  jsonb,
    -- The step waiting for answers; null once the stages are over.
    ADD COLUMN current_step     integer CHECK (current_step IS NULL OR current_step >= 1),
    -- Somebody took the approved request to configure it.
    ADD COLUMN assignee         text,
    ADD COLUMN assigned_at      timestamptz,
    -- Configured, declined by the configurer, or withdrawn after approval.
    ADD COLUMN completed_by     text,
    ADD COLUMN completed_at     timestamptz,
    -- What configuring it was: a grant, or a policy changed or written. A
    -- policy is only ever pointed at: this never activates one.
    ADD COLUMN fulfilment       text
               CHECK (fulfilment IS NULL OR fulfilment IN ('GRANT', 'POLICY_UPDATED', 'POLICY_CREATED')),
    ADD COLUMN fulfilment_ref   text,
    ADD COLUMN fulfilment_note  text;

-- APPROVED used to mean granted. It now means "the approvers said yes, and
-- somebody has yet to configure it"; what used to be APPROVED is COMPLETED,
-- configured by a grant, by whoever approved it, when they did.
ALTER TABLE access_request DROP CONSTRAINT access_request_approved_has_grant;
ALTER TABLE access_request DROP CONSTRAINT access_request_status_check;

UPDATE access_request
   SET status = 'COMPLETED', fulfilment = 'GRANT',
       completed_by = decided_by, completed_at = decided_at
 WHERE status = 'APPROVED';

ALTER TABLE access_request
    ADD CONSTRAINT access_request_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'WITHDRAWN'));

-- A grant is what a GRANT fulfilment wrote, and nothing else writes one here.
ALTER TABLE access_request
    ADD CONSTRAINT access_request_grant_is_fulfilment
    CHECK ((grant_id IS NOT NULL) = (coalesce(fulfilment, '') = 'GRANT'));

ALTER TABLE access_request
    ADD CONSTRAINT access_request_completed
    CHECK ((status = 'COMPLETED') = (fulfilment IS NOT NULL)
       AND (status <> 'COMPLETED' OR (completed_by IS NOT NULL AND completed_at IS NOT NULL)));

ALTER TABLE access_request
    ADD CONSTRAINT access_request_assigned
    CHECK (status <> 'IN_PROGRESS' OR (assignee IS NOT NULL AND assigned_at IS NOT NULL));

-- Open now means anything short of an end: still one open request per person
-- per table, whether it waits for approvers or for a configurer.
DROP INDEX access_request_one_open_idx;
CREATE UNIQUE INDEX access_request_one_open_idx
    ON access_request (asset_fqn, requester_id)
    WHERE status IN ('PENDING', 'APPROVED', 'IN_PROGRESS');

CREATE INDEX access_request_status_idx ON access_request (status, created_at DESC);


-- ------------------------------------------------------------ the stages

-- One row per stage of one request: the workflow's stage as it was when the
-- request was made, plus who was asked. The pool is resolved when the stage's
-- step opens -- the owners, the team's members, the role's holders at that
-- moment -- so "everyone must approve" means everyone who was asked, not a
-- number that moves as teams change. Nobody is ever in the pool of their own
-- request.
CREATE TABLE access_request_stage (
    request_id      uuid NOT NULL REFERENCES access_request (id) ON DELETE CASCADE,
    idx             integer NOT NULL CHECK (idx >= 0),
    step            integer NOT NULL CHECK (step >= 1),
    name            text NOT NULL,
    rule            text NOT NULL CHECK (rule IN ('ALL', 'ANY', 'AT_LEAST')),
    min_approvals   integer CHECK (min_approvals IS NULL OR min_approvals >= 1),
    on_reject       text NOT NULL CHECK (on_reject IN ('VETO', 'QUORUM', 'FIRST_RESPONSE')),
    approvers       jsonb NOT NULL,
    -- [{username, via}]; null until the step opens.
    pool            jsonb,
    -- No seat resolved to anybody, so the pool is the platform administrators.
    fallback        boolean NOT NULL DEFAULT false,
    status          text NOT NULL DEFAULT 'WAITING'
                    CHECK (status IN ('WAITING', 'OPEN', 'APPROVED', 'REJECTED', 'CLOSED')),
    opened_at       timestamptz,
    settled_at      timestamptz,
    PRIMARY KEY (request_id, idx),
    CHECK ((rule = 'AT_LEAST') = (min_approvals IS NOT NULL))
);

-- One answer per person per stage. An administrator answering for a stage
-- they were not asked in is an override, and says so.
CREATE TABLE access_request_vote (
    id              bigserial PRIMARY KEY,
    request_id      uuid NOT NULL,
    idx             integer NOT NULL,
    voter           text NOT NULL,
    decision        text NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    override        boolean NOT NULL DEFAULT false,
    note            text,
    voted_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (request_id, idx) REFERENCES access_request_stage (request_id, idx) ON DELETE CASCADE,
    CHECK (decision = 'APPROVE' OR length(btrim(coalesce(note, ''))) > 0)
);

CREATE UNIQUE INDEX access_request_vote_once_idx
    ON access_request_vote (request_id, idx, lower(voter));

-- Requests already waiting walk the built-in workflow: one stage, any one
-- owner, the first answer decides -- which is what they were promised. Their
-- pool is resolved the first time somebody reads or answers them.
INSERT INTO access_request_stage
    (request_id, idx, step, name, rule, on_reject, approvers, status, opened_at)
SELECT id, 0, 1, 'Owner approval', 'ANY', 'VETO', '[{"kind": "ASSET_OWNERS"}]'::jsonb, 'OPEN', created_at
  FROM access_request
 WHERE status = 'PENDING';

UPDATE access_request
   SET current_step = 1,
       workflow_name = 'Built-in',
       configurers = '[{"kind": "ASSET_OWNERS"}, {"kind": "DATA_CUSTODIAN"}]'::jsonb
 WHERE status = 'PENDING';


-- ------------------------------------------------------------ the record

ALTER TABLE audit_access_request DROP CONSTRAINT audit_access_request_action_check;
ALTER TABLE audit_access_request
    ADD CONSTRAINT audit_access_request_action_check
    CHECK (action IN ('REQUEST', 'VOTE', 'ADVANCE', 'APPROVE', 'REJECT', 'WITHDRAW',
                      'START', 'COMPLETE'));

-- Which step a REQUEST or ADVANCE opened, so the bell tells the people asked
-- at that step and nobody else.
ALTER TABLE audit_access_request ADD COLUMN step integer;
UPDATE audit_access_request SET step = 1 WHERE action = 'REQUEST';
