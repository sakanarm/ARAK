-- Request access templates: what the form asks a requester, per table.
--
-- A template applies to every table under its scope (none = the whole
-- organisation) and, when it names facets, only to tables that carry one of
-- them on the table or on any of its columns -- so "anything tagged PII asks
-- for a purpose and a reference number" is one row. The request form renders
-- the template the table resolves to, and the server checks a request against
-- the same template before it is stored: the form is a convenience, the check
-- is the rule.
--
-- Guidance is plain text. It is shown to a requester as text and never as
-- HTML, whoever wrote it.

CREATE TABLE access_request_template (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text NOT NULL CHECK (length(btrim(name)) > 0),
    description     text,
    scope_fqn       text CHECK (scope_fqn IS NULL OR length(btrim(scope_fqn)) > 0),
    -- Tag, classification, glossary or term FQNs; the table matches when it or
    -- a column carries one of them or anything under one. Empty = any table.
    match_facets    jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(match_facets) = 'array'),
    enabled         boolean NOT NULL DEFAULT true,
    -- {purposes, purposeRequired, durations, defaultDays, maxDays,
    --  allowUntilRevoked, referenceLabel, referenceRequired, minReasonLength,
    --  guidance}. Validated by RequestTemplate before it is written.
    form            jsonb NOT NULL CHECK (jsonb_typeof(form) = 'object'),
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Names are how people tell templates apart in a list and on a request.
CREATE UNIQUE INDEX access_request_template_name_idx ON access_request_template (lower(name));

-- Append-only, like every audit table before it.
CREATE TABLE audit_access_request_template (
    id              bigserial PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor           text NOT NULL,
    action          text NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    template_id     uuid NOT NULL,
    scope_fqn       text,
    before          jsonb,
    after           jsonb
);
CREATE INDEX audit_access_request_template_idx ON audit_access_request_template (template_id);

-- A request remembers which template it was asked on and what it answered, so
-- editing or deleting a template never changes a request already made.
ALTER TABLE access_request
    ADD COLUMN template_id   uuid REFERENCES access_request_template (id) ON DELETE SET NULL,
    ADD COLUMN template_name text,
    ADD COLUMN reference     text CHECK (reference IS NULL OR length(reference) <= 200);
