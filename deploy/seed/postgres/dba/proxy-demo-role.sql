-- ARAK proxy demo: the one step that needs a database administrator.
--
-- Creating a login role needs CREATEROLE, which an application account should
-- not have. This file holds nothing else, so it can be read in full in a few
-- seconds before anyone runs it with elevated rights. It is kept out of the
-- seed directory proper so no entrypoint ever runs it by itself.
--
-- It grants nothing: the role leaves here able to log in and do absolutely
-- nothing. The schema owner grants it read access on proxy_demo afterwards by
-- running ../02-proxy-demo.sql, which is safe to re-run.
--
--     psql -h <host> -U <administrator> -d <arak database> \
--          -f deploy/seed/postgres/dba/proxy-demo-role.sql
--
-- No password is set here either. Set it in psql straight afterwards:
--
--     \password arak_proxy_ro
--
-- psql prompts twice without echoing and sends the server only the hashed
-- verifier, so the password reaches neither a shell history nor a server log.

\set ON_ERROR_STOP on

DO $do$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'arak_proxy_ro') THEN
        RAISE NOTICE 'role arak_proxy_ro already exists; left exactly as it is';
    ELSE
        CREATE ROLE arak_proxy_ro LOGIN
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
            NOINHERIT CONNECTION LIMIT 10;
        COMMENT ON ROLE arak_proxy_ro IS
            'ARAK proxy demo: read-only, proxy_demo schema only. No password set by the script.';
        RAISE NOTICE 'created role arak_proxy_ro with no password and no privileges';
    END IF;
END
$do$;

SELECT rolname, rolcanlogin, rolsuper, rolcreaterole, rolcreatedb,
       rolbypassrls, rolconnlimit
FROM   pg_roles
WHERE  rolname = 'arak_proxy_ro';
-- expected: arak_proxy_ro | t | f | f | f | f | 10
