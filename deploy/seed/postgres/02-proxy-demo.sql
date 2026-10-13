-- ARAK: a demo schema for the proxy mode, deliberately kept apart from the
-- native one.
--
-- The native demo proves that a subscription policy can become a role on the
-- source. This one proves the other half: a connection in PROXY mode, where
-- the source grants ARAK one read-only login and every rule -- row filter,
-- column mask, cell mask, hidden column -- is applied by ARAK as the query
-- goes through it. Keeping the two in separate schemas means either can be
-- re-run, broken or thrown away without disturbing the other.
--
-- It creates nothing outside the proxy_demo schema, alters no existing object,
-- and changes no existing role or privilege. Re-running it is safe.
--
-- Run it as a role that may create a schema in this database. Creating the
-- login role needs CREATEROLE as well; where the running role does not have
-- it -- a deployment whose application account is deliberately not allowed to
-- make roles -- the script says so and skips that step, and a database
-- administrator runs dba/proxy-demo-role.sql once instead. Everything else
-- still happens, so the two halves can be run by two different people.
--
-- The login gets no password here. Set it afterwards with psql's \password,
-- which prompts without echoing and sends only the hashed verifier -- see the
-- note at the end of this file.

\set ON_ERROR_STOP on
SET client_min_messages = notice;

BEGIN;

CREATE SCHEMA IF NOT EXISTS proxy_demo;

COMMENT ON SCHEMA proxy_demo IS
    'ARAK proxy-mode demo. Fictional data. Safe to drop.';

-- One table carrying a national id, an email, a phone, a salary and a branch
-- is enough to exercise row filtering, column masking, cell masking and
-- column hiding all at once.
CREATE TABLE IF NOT EXISTS proxy_demo.customer (
    id          integer PRIMARY KEY,
    full_name   text NOT NULL,
    email       text NOT NULL,
    citizen_id  text NOT NULL,
    phone       text,
    salary      numeric(12,2),
    branch_code text NOT NULL,
    country     text NOT NULL DEFAULT 'TH',
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE  proxy_demo.customer            IS 'Fictional customers for the proxy demo.';
COMMENT ON COLUMN proxy_demo.customer.citizen_id IS 'National id. Hide it, or show the last 4.';
COMMENT ON COLUMN proxy_demo.customer.salary     IS 'Monthly salary. The cell-mask example.';

-- The branch a row belongs to, so a row filter has something to read.
CREATE TABLE IF NOT EXISTS proxy_demo.branch (
    branch_code text PRIMARY KEY,
    region      text NOT NULL,
    country     text NOT NULL
);

-- Who may see which branch. A row filter can read this instead of carrying
-- the mapping in the policy itself (the LOOKUP form), which is the point of
-- having it here.
CREATE TABLE IF NOT EXISTS proxy_demo.analyst_branch (
    analyst_email text NOT NULL,
    branch_code   text NOT NULL,
    PRIMARY KEY (analyst_email, branch_code)
);

COMMENT ON TABLE proxy_demo.analyst_branch IS
    'Row-filter lookup: which branch each analyst may read.';

INSERT INTO proxy_demo.branch (branch_code, region, country) VALUES
    ('BKK-01', 'Central',  'TH'),
    ('CNX-01', 'Northern', 'TH'),
    ('SIN-01', 'Southeast Asia', 'SG')
ON CONFLICT (branch_code) DO NOTHING;

INSERT INTO proxy_demo.customer
    (id, full_name, email, citizen_id, phone, salary, branch_code, country) VALUES
    ( 1, 'Somchai Wong',   'somchai@example.test',   '1103700123456', '0812345678',  45000.00, 'BKK-01', 'TH'),
    ( 2, 'Nattaporn Sri',  'nattaporn@example.test', '1209800234567', '0823456789',  62000.00, 'BKK-01', 'TH'),
    ( 3, 'Kannika Rat',    'kannika@example.test',   '1301900345671', '0845678901',  51000.00, 'BKK-01', 'TH'),
    ( 4, 'Thanakorn Lee',  'thanakorn@example.test', '1402700456782', '0856789012',  73000.00, 'BKK-01', 'TH'),
    ( 5, 'Chaiwat Phan',   'chaiwat@example.test',   '3101900345678', '0834567890',  38000.00, 'CNX-01', 'TH'),
    ( 6, 'Pimchanok Noi',  'pimchanok@example.test', '3202800456789', '0867890123',  41000.00, 'CNX-01', 'TH'),
    ( 7, 'Anucha Kam',     'anucha@example.test',    '3303900567890', '0878901234',  36000.00, 'CNX-01', 'TH'),
    ( 8, 'Lim Wei Ling',   'weiling@example.test',   'S1234567D',     '+6591234567', 88000.00, 'SIN-01', 'SG'),
    ( 9, 'Tan Jun Hao',    'junhao@example.test',    'S2345678E',     '+6592345678', 95000.00, 'SIN-01', 'SG'),
    (10, 'Ong Mei Chen',   'meichen@example.test',   'S3456789F',     '+6593456789', 79000.00, 'SIN-01', 'SG')
ON CONFLICT (id) DO NOTHING;

-- One login for ARAK to hold as the connection's credential. Read-only, this
-- schema only, and it may not create anything anywhere.
DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'arak_proxy_ro') THEN
        RAISE NOTICE 'role arak_proxy_ro already exists; left exactly as it is';
    ELSIF EXISTS (SELECT 1 FROM pg_roles
                  WHERE rolname = current_user AND (rolcreaterole OR rolsuper)) THEN
        CREATE ROLE arak_proxy_ro LOGIN
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
            NOINHERIT CONNECTION LIMIT 10;
        COMMENT ON ROLE arak_proxy_ro IS
            'ARAK proxy demo: read-only, proxy_demo schema only. No password set by the script.';
        RAISE NOTICE 'created role arak_proxy_ro with no password';
    ELSE
        RAISE NOTICE '% may not create roles, so arak_proxy_ro was NOT created.', current_user;
        RAISE NOTICE 'The schema and its data are in place. Ask a database administrator to run';
        RAISE NOTICE 'dba/proxy-demo-role.sql once, then run this script again to add the grants.';
    END IF;
END
$do$;

-- The grants belong to the schema owner, so they are made here rather than in
-- the administrator's script -- and skipped cleanly while the role is absent.
DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'arak_proxy_ro') THEN
        GRANT USAGE ON SCHEMA proxy_demo TO arak_proxy_ro;
        GRANT SELECT ON ALL TABLES IN SCHEMA proxy_demo TO arak_proxy_ro;
        ALTER DEFAULT PRIVILEGES IN SCHEMA proxy_demo
            GRANT SELECT ON TABLES TO arak_proxy_ro;
        RAISE NOTICE 'granted USAGE + SELECT on proxy_demo to arak_proxy_ro';
    ELSE
        RAISE NOTICE 'arak_proxy_ro does not exist yet, so no grants were made';
    END IF;
END
$do$;

COMMIT;

-- What the login ended up with. Read these three before handing the
-- credential to ARAK. They return no rows at all while the role is still
-- missing, which is itself the answer.
SELECT rolname, rolcanlogin, rolsuper, rolcreaterole, rolcreatedb,
       rolbypassrls, rolconnlimit
FROM   pg_roles
WHERE  rolname = 'arak_proxy_ro';

SELECT has_schema_privilege('arak_proxy_ro', 'proxy_demo', 'USAGE')  AS reads_the_demo_schema,
       has_schema_privilege('arak_proxy_ro', 'proxy_demo', 'CREATE') AS can_create_here,
       has_schema_privilege('arak_proxy_ro', 'public',     'CREATE') AS can_create_in_public
WHERE  EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'arak_proxy_ro');
-- expected: t, f, f

-- Nothing outside this schema may be readable by that login. Expect 0; a
-- number above 0 means something in this database grants SELECT to PUBLIC,
-- which is worth knowing before a proxy connection is pointed at it.
SELECT count(*) AS readable_tables_outside_proxy_demo
FROM   pg_class c
JOIN   pg_namespace n ON n.oid = c.relnamespace
WHERE  c.relkind IN ('r', 'p', 'v', 'm')
  AND  n.nspname NOT IN ('pg_catalog', 'information_schema', 'proxy_demo')
  AND  has_table_privilege('arak_proxy_ro', c.oid, 'SELECT')
  AND  EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'arak_proxy_ro');

-- Two steps are left, and both are deliberately not in this file.
--
-- 1. The password. In psql, as a role that may alter it:
--
--        \password arak_proxy_ro
--
--    psql prompts twice without echoing and sends the server a hashed
--    verifier, so the plain password never reaches a log or this repository.
--    Type the same password into ARAK when the connection is created; ARAK
--    keeps it as a reference, not as text.
--
-- 2. The lookup rows, because they carry real sign-in addresses and those do
--    not belong in a public repository. Once the demo accounts exist, add one
--    row per analyst:
--
--        INSERT INTO proxy_demo.analyst_branch (analyst_email, branch_code)
--        VALUES ('<analyst sign-in address>', 'BKK-01');
--
-- To remove the whole demo later, in this order:
--
--        DROP SCHEMA proxy_demo CASCADE;
--        REASSIGN OWNED BY arak_proxy_ro TO CURRENT_USER;  -- owns nothing, but cheap
--        DROP OWNED BY arak_proxy_ro;                      -- clears the default privileges
--        DROP ROLE arak_proxy_ro;
