-- V12 — LLM assist: one gateway for the platform, one setting per person.
--
-- Two tables rather than one, because the two halves are owned by different
-- people and carry very different risk. The gateway -- its URL and the pointer
-- to its key -- is the administrator's, and getting it wrong sends the
-- organisation's schema names to a host nobody vetted. Which model to use, and
-- whether to use one at all, is each person's own, and getting it wrong costs
-- them a bad SQL suggestion.
--
-- Folding both into one table would have meant either letting every user write
-- a row that carries a base URL (so anyone could point the assistant at their
-- own endpoint and collect what it is shown), or letting nobody set a model
-- without administrator rights. Neither is the behaviour that was asked for.
--
-- What the assistant is allowed to see is not enforced here, but it is the
-- reason this feature is safe to have at all, so it is written down where the
-- schema lives: the model is sent **metadata the requester may already read** --
-- table names, column names, types, tags -- and never rows. A draft it writes
-- is a draft. It cannot activate a policy, and `policy.state` already refuses
-- to let it (FR-2.6, separation of duty).


-- The gateway. At most one row, ever: `singleton` is a constant column with a
-- unique constraint on it, which is the cheapest way to say "there is one of
-- these" in a form the database itself enforces, rather than in a comment that
-- a second writer will not read.
CREATE TABLE llm_provider (
    singleton       boolean PRIMARY KEY DEFAULT true CHECK (singleton),

    -- An OpenAI-compatible base, e.g. https://gateway.example.com/litellm.
    -- Stored without a trailing slash; the client appends /v1/....
    base_url        text NOT NULL,

    -- A POINTER to the key, never the key. Same contract as `data_source`'s
    -- credential_ref and enforced by the same rule in code: it must carry a
    -- scheme this deployment can resolve (`env:`, `vault://`, ...). A column
    -- that is allowed to hold either a pointer or a secret ends up holding a
    -- secret, and then the secret is in every backup and every pg_dump.
    credential_ref  text NOT NULL,

    -- What somebody gets when they have expressed no preference. Nullable
    -- because an administrator may legitimately want every user to choose
    -- deliberately rather than inherit a default they never saw.
    default_model   text,

    -- The master switch. Off means the feature is not offered to anyone,
    -- whatever their own row says -- so that turning the assistant off during an
    -- incident is one write, not one per user.
    enabled         boolean NOT NULL DEFAULT false,

    updated_at      timestamptz NOT NULL DEFAULT now(),
    -- A username rather than a principal id: this has to stay readable after
    -- the account is gone, like every other actor column in this schema.
    updated_by      text NOT NULL
);


-- One row per person who has expressed a preference. Absent means "has not
-- chosen", which is different from "chose off" -- hence `enabled` being NOT NULL
-- here while the row itself is optional.
CREATE TABLE llm_user_setting (
    principal_id    uuid PRIMARY KEY REFERENCES principal (id) ON DELETE CASCADE,

    -- Whether this person wants the assistant at all. Default false, and the
    -- row is only written when they say so: an assistant that is on by default
    -- is an assistant that sends somebody's schema somewhere before they have
    -- been told it exists.
    enabled         boolean NOT NULL DEFAULT false,

    -- Their model. Nullable = fall back to llm_provider.default_model. Not a
    -- foreign key to anything, because the list of models lives at the gateway
    -- and changes without telling us; a stale row here simply fails at call
    -- time with the gateway's own message, which is more honest than a
    -- constraint that was true last month.
    model           text,

    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text NOT NULL
);

-- The administrator's view of this is "show me everyone's setting", which is a
-- full scan of a table with one row per user. No index earns its keep yet.
