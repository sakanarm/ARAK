-- The purposes data may be used for, as a register (FR-21, M31).
--
-- Until now a purpose was a word typed in four places -- a policy's
-- subject.context.purpose, a request template's list, the request form and the
-- query page -- and nothing said two of them were the same purpose, or what
-- the law allowed it for. Here each has a key everything refers to, a name
-- people read, and what the PDPA asks of it: the legal basis under section 24
-- (or consent), whether special categories under section 26 may be used for
-- it, who answers for it, and how long access for it may last.
--
-- Nothing is deleted. A purpose nobody should use any more is retired: it is
-- no longer offered, and what already names it keeps the name, so a request
-- from last year still says what it was for.

CREATE TABLE purpose (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- What policies, templates, requests and queries store. Lower case, so the
    -- policy engine's case-insensitive comparison and this register agree;
    -- never changed once made, because stored policies name it.
    key               text NOT NULL CHECK (key ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    name              text NOT NULL CHECK (length(btrim(name)) > 0),
    description       text,
    -- Null until somebody records it; the register says so rather than guess.
    legal_basis       text CHECK (legal_basis IN
        ('CONSENT', 'CONTRACT', 'LEGAL_OBLIGATION', 'VITAL_INTEREST', 'PUBLIC_TASK',
         'LEGITIMATE_INTEREST', 'RESEARCH_OR_STATISTICS')),
    sensitive_allowed boolean NOT NULL DEFAULT false,
    owner             text,
    -- The longest a request for this purpose may ask for; null = no limit of its own.
    max_days          integer CHECK (max_days BETWEEN 1 AND 365),
    status            text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        text NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX purpose_key_idx ON purpose (lower(key));
-- Two purposes a person cannot tell apart in a picker are one purpose too many.
CREATE UNIQUE INDEX purpose_name_idx ON purpose (lower(name));

-- Append-only, like every audit table before it.
CREATE TABLE audit_purpose (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor           text NOT NULL,
    action          text NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'RETIRE', 'REINSTATE')),
    purpose_key     text NOT NULL,
    reason          text,
    before          jsonb,
    after           jsonb
);
CREATE INDEX audit_purpose_key_idx ON audit_purpose (purpose_key, occurred_at DESC);

-- The three the query page has offered since it had a purpose box, so a query
-- that declared one yesterday can declare it today. Their legal basis is left
-- for somebody who knows it to record.
INSERT INTO purpose (key, name, description, created_by, updated_by) VALUES
    ('fraud-analysis', 'Fraud analysis', 'Offered on the query page before the register existed.', 'system', 'system'),
    ('reporting', 'Reporting', 'Offered on the query page before the register existed.', 'system', 'system'),
    ('support', 'Support', 'Offered on the query page before the register existed.', 'system', 'system');

INSERT INTO audit_purpose (actor, action, purpose_key, reason, after)
SELECT 'system', 'CREATE', key, 'Listed when the register was made',
       jsonb_build_object('name', name, 'description', description, 'legalBasis', NULL,
                          'sensitiveAllowed', false, 'owner', NULL, 'maxDays', NULL,
                          'status', 'ACTIVE')
  FROM purpose;
