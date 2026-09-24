-- Which OpenMetadata instance this platform is pinned to, set from the screen.
--
-- The base URL was an environment variable, which made "we moved the catalog"
-- a redeploy -- and made the one visible symptom of a wrong value a missing
-- "Open in OpenMetadata" button with nothing on any screen to correct it. The
-- instance an estate points at changes: a move, a rename, a second environment
-- promoted to be the real one. That belongs in the database.
--
-- One row, enforced by the primary key. There is one catalog, and a second row
-- would be silently ignored by whichever half of the code read the other one.
CREATE TABLE om_connection (
    id                       boolean     PRIMARY KEY DEFAULT true CHECK (id),

    -- The console root, with no /api on the end: the client appends that for
    -- its own calls, and the asset link needs the root the browser opens.
    base_url                 text        NOT NULL,

    -- Sealed with the same Fernet key as every other stored credential. Null
    -- means "keep using whatever the environment supplies", which is how an
    -- estate that injects the token through its deployment keeps doing so
    -- while still moving the URL from this screen.
    jwt_token_cipher         text,
    webhook_secret_cipher    text,

    -- The spec version the generated client was built from. Changing the
    -- instance without changing this is exactly the case the version check
    -- exists for, so it is editable alongside the URL rather than fixed.
    expected_version         text,
    fail_on_version_mismatch boolean     NOT NULL DEFAULT false,

    connect_timeout_ms       integer     NOT NULL DEFAULT 5000
                                         CHECK (connect_timeout_ms BETWEEN 100 AND 120000),
    read_timeout_ms          integer     NOT NULL DEFAULT 60000
                                         CHECK (read_timeout_ms BETWEEN 100 AND 600000),

    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               text
);

COMMENT ON TABLE om_connection IS
    'The OpenMetadata instance this platform reads (FR-1.1). One row; absent means the configuration file still decides.';

-- Changing where the catalog comes from changes every policy selector that
-- reads a tag, so it is an audited act and not a preference. The existing
-- identity trail is the wrong table -- it is keyed to a principal -- so this
-- gets its own, and it records the URL only: a token never appears here.
CREATE TABLE audit_connection_change (
    id          bigserial   PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor       text        NOT NULL,

    -- What was reconfigured. Today only OPENMETADATA, but a data source and
    -- the LLM gateway are the same kind of act and belong in the same trail.
    target      text        NOT NULL,

    -- Free text, written by the code that made the change: "base URL
    -- http://a -> http://b", "bot token replaced". Never a secret value, and
    -- the column is checked by review rather than by the database, which
    -- cannot tell the difference.
    summary     text        NOT NULL,
    reason      text,
    client_ip   inet
);

COMMENT ON TABLE audit_connection_change IS
    'Who changed the platform''s upstream connections, and to what (FR-8.1). Summaries only -- never a credential.';

CREATE INDEX audit_connection_change_occurred_idx
    ON audit_connection_change (occurred_at DESC);
