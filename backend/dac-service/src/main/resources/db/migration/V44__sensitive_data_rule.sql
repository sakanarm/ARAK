-- What counts as sensitive data, as a setting (FR-21, M31b).
--
-- Until now "sensitive" was a rule written into the code: anything under PII
-- or PersonalData, or a tag calling itself sensitive, confidential, restricted
-- or secret. That rule stays, as the starting point, and around it the people
-- who author policy can say more: these classifications, tags, glossaries or
-- terms count too, those never do. A label names everything beneath it.
--
-- The same answer drives three things, so they cannot disagree: whether a
-- purpose that does not allow sensitive data may be used on a table (the mode
-- below), which columns an access review calls sensitive, and what the
-- dashboard counts.
--
-- One row, always. Every change is kept with its reason, before and after.

CREATE TABLE sensitive_data_rule (
    id          smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    -- The rule that was in the code: on, so nothing changes on the day this ships.
    built_in    boolean NOT NULL DEFAULT true,
    -- [{"kind": "CLASSIFICATION" | "TAG" | "GLOSSARY" | "TERM", "fqn": "PII"}]
    include     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(include) = 'array'),
    exclude     jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(exclude) = 'array'),
    -- What happens when a purpose that does not allow sensitive data meets a
    -- table that holds it: nothing, a warning on the record, or a refusal.
    mode        text NOT NULL DEFAULT 'WARN' CHECK (mode IN ('OFF', 'WARN', 'ENFORCE')),
    updated_by  text NOT NULL DEFAULT 'system',
    updated_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO sensitive_data_rule (id) VALUES (1);

-- Append-only, like every audit table before it.
CREATE TABLE audit_sensitive_data_rule (
    id          bigserial PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    reason      text NOT NULL,
    before      jsonb,
    after       jsonb NOT NULL
);
CREATE INDEX audit_sensitive_data_rule_at_idx ON audit_sensitive_data_rule (occurred_at DESC);

INSERT INTO audit_sensitive_data_rule (actor, reason, after)
VALUES ('system', 'The rule that was in the code, kept as the starting point',
        jsonb_build_object('builtIn', true, 'include', '[]'::jsonb, 'exclude', '[]'::jsonb,
                           'mode', 'WARN'));

-- What the proxy saw when it decided, so a report of what sensitive data was
-- used for reads what was true then rather than what the rule says today.
-- sensitive: the table or one of its columns counted as sensitive.
-- purpose_check: WARNED when the purpose did not allow it and the query ran
-- anyway; REFUSED when that is why it did not.
ALTER TABLE audit_decision ADD COLUMN sensitive boolean;
ALTER TABLE audit_decision ADD COLUMN purpose_check text
    CHECK (purpose_check IN ('WARNED', 'REFUSED'));
CREATE INDEX audit_decision_sensitive_idx ON audit_decision (occurred_at DESC) WHERE sensitive;
