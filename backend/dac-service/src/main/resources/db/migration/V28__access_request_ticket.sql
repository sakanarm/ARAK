-- A number people can say out loud, search for and quote in an email or a
-- change ticket: REQ-000042 rather than a UUID. The number is the reference;
-- the UUID stays the key everything else joins on.
--
-- Being sequential, a number can be guessed. That is fine because it opens
-- nothing: looking a request up by its number goes through the same check as
-- looking it up by id, and a request the reader takes no part in is "not
-- found" either way.

CREATE SEQUENCE access_request_ticket_seq;

ALTER TABLE access_request ADD COLUMN ticket_no bigint;

-- Requests made before this migration are numbered in the order they were
-- made, so the oldest is REQ-000001.
UPDATE access_request r
   SET ticket_no = numbered.n
  FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS n FROM access_request) numbered
 WHERE r.id = numbered.id;

SELECT setval('access_request_ticket_seq',
              COALESCE((SELECT max(ticket_no) FROM access_request), 0) + 1,
              false);

ALTER TABLE access_request
    ALTER COLUMN ticket_no SET DEFAULT nextval('access_request_ticket_seq'),
    ALTER COLUMN ticket_no SET NOT NULL,
    ADD CONSTRAINT access_request_ticket_no_key UNIQUE (ticket_no);

ALTER SEQUENCE access_request_ticket_seq OWNED BY access_request.ticket_no;
