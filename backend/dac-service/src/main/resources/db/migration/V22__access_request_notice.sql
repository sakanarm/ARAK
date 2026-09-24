-- The bell on the header: what happened to a request that somebody should hear
-- about -- a table they decide was asked for, or their own ask was answered.
--
-- The events themselves are the rows of audit_access_request (V21), read, never
-- copied: a second table of "notifications" is a second record of the same
-- facts that one day disagrees with the first. All that is stored here is how
-- far each person has read, so the bell can say "3 new" instead of "3".

CREATE TABLE access_request_notice_seen (
    -- lower-cased, as every comparison of usernames in V21 is
    username    text PRIMARY KEY CHECK (username = lower(username)),
    seen_at     timestamptz NOT NULL
);

-- A requester's own answers, newest first.
CREATE INDEX audit_access_request_requester_idx
    ON audit_access_request (lower(requester_username), occurred_at DESC);

-- Everything asked for or taken back, newest first, filtered to the reader's
-- tables after reading (the owner match is the engine's, not SQL's).
CREATE INDEX audit_access_request_action_idx
    ON audit_access_request (action, occurred_at DESC);
