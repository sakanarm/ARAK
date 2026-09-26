-- Saved queries: a statement somebody wants to run again, under a name.
--
-- What is kept is the statement and nothing it returned. A query shared with
-- everyone is shared as text: whoever opens it runs it as themselves, through
-- the same proxy and the same policies, so sharing a query never shares its
-- rows. The literals in a WHERE can themselves be data, which is why sharing
-- is the author's choice per query and off by default.

CREATE TABLE saved_query (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner           text NOT NULL,
    name            text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    description     text CHECK (description IS NULL OR length(description) <= 500),
    source_id       uuid REFERENCES data_source (id) ON DELETE SET NULL,
    sql             text NOT NULL CHECK (length(btrim(sql)) BETWEEN 1 AND 100000),
    shared          boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- A name is how its owner finds it again, so one owner has one of each; two
-- people may both have a "Monthly sales".
CREATE UNIQUE INDEX saved_query_owner_name_idx ON saved_query (lower(owner), lower(name));
CREATE INDEX saved_query_shared_idx ON saved_query (shared) WHERE shared;
