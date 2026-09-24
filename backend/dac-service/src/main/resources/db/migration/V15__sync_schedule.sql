-- When the nightly reconcile runs, decided here rather than at deploy time.
--
-- The hour was an environment variable, which meant changing it was a restart
-- and an operator. It is an operational choice -- it belongs in the quiet
-- window of whoever runs the estate, and that window moves -- so it moves into
-- the database where an administrator can set it and the running process can
-- pick it up without being stopped.
--
-- One row, enforced by the primary key: there is one catalog crawl, and a
-- second schedule row would silently be ignored by whichever half of the code
-- read the other one.
CREATE TABLE om_sync_schedule (
    id          boolean     PRIMARY KEY DEFAULT true CHECK (id),

    -- Off is a real setting, not an absent row. An estate that crawls from a
    -- pipeline instead wants the backstop disabled on purpose, and that has to
    -- be distinguishable from "nobody has configured this yet".
    enabled     boolean     NOT NULL,

    -- Local time in `zone`, never UTC. The point of the setting is "run it at
    -- half past two in the morning where the data centre is", and a UTC
    -- instant stops meaning that on the first daylight-saving change.
    run_at      time        NOT NULL,
    zone        text        NOT NULL,

    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text
);

COMMENT ON TABLE om_sync_schedule IS
    'When the nightly OpenMetadata reconcile runs (FR-1.5). One row.';
