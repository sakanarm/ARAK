-- Which parts of the assistant each application role may use (M28).
--
-- One row per feature an administrator has changed, and none for a feature
-- nobody has touched: no row means "everyone who has the assistant switched
-- on", which is how every feature behaved before this table existed, and keeps
-- a feature added in a later release usable without a visit to this screen.
--
-- `roles` holds application role names (PLATFORM_ADMIN, POLICY_AUTHOR,
-- DATA_OWNER, AUDITOR, REQUESTER), or the single word EVERYONE. An empty array
-- is a feature switched off for all. This is a narrowing on top of the
-- per-person switch in llm_user_setting, never a widening: a role listed here
-- does not turn the assistant on for somebody who has it off, and none of these
-- features reads anything its caller could not already read without it.
CREATE TABLE llm_feature_access (
    feature    text PRIMARY KEY,
    roles      text[] NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text
);
