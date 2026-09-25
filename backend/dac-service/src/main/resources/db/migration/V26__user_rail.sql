-- The left-hand rail, as arranged by the person using it.
--
-- One row per person who has changed it, and none for anybody who has not:
-- no row means "the rail this product ships with", which keeps a section added
-- in a later release from being invisible to everybody who once reordered
-- theirs.
--
-- `sections` is an ordered JSON array of {"href": "/catalog", "shown": true}.
-- It is a preference about a menu and nothing else. The server does not know
-- which sections exist or who may open them, and does not need to: the console
-- draws only the sections this account is offered, in this order, and every
-- one of those pages is still refused at the API to anybody without the role.
-- A stored "/principals" in a requester's row grants nothing.
CREATE TABLE user_rail (
    principal_id uuid PRIMARY KEY REFERENCES principal (id) ON DELETE CASCADE,
    sections     jsonb NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
