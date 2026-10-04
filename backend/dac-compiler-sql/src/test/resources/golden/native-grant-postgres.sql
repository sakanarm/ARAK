CREATE ROLE "arak_sub_5e1f0c2a_0a7b3c9d" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
COMMENT ON ROLE "arak_sub_5e1f0c2a_0a7b3c9d" IS 'Managed by ARAK. Subscription policy 5e1f0c2a-0000-4000-8000-000000000001 (Sales analysts). Changes made here by hand are reported as drift and undone by the next apply.';
GRANT CONNECT ON DATABASE "shop" TO "arak_sub_5e1f0c2a_0a7b3c9d";
GRANT USAGE ON SCHEMA "finance" TO "arak_sub_5e1f0c2a_0a7b3c9d";
GRANT USAGE ON SCHEMA "sales" TO "arak_sub_5e1f0c2a_0a7b3c9d";
GRANT SELECT ON TABLE "finance"."ledger" TO "arak_sub_5e1f0c2a_0a7b3c9d";
GRANT SELECT ON TABLE "sales"."customer" TO "arak_sub_5e1f0c2a_0a7b3c9d";
GRANT SELECT ON TABLE "sales"."orders" TO "arak_sub_5e1f0c2a_0a7b3c9d";
GRANT "arak_sub_5e1f0c2a_0a7b3c9d" TO "alice" WITH INHERIT TRUE, SET FALSE;
GRANT "arak_sub_5e1f0c2a_0a7b3c9d" TO "bob" WITH INHERIT TRUE, SET FALSE;

-- rollback

REVOKE "arak_sub_5e1f0c2a_0a7b3c9d" FROM "alice";
REVOKE "arak_sub_5e1f0c2a_0a7b3c9d" FROM "bob";
REVOKE SELECT ON TABLE "finance"."ledger" FROM "arak_sub_5e1f0c2a_0a7b3c9d";
REVOKE SELECT ON TABLE "sales"."customer" FROM "arak_sub_5e1f0c2a_0a7b3c9d";
REVOKE SELECT ON TABLE "sales"."orders" FROM "arak_sub_5e1f0c2a_0a7b3c9d";
REVOKE USAGE ON SCHEMA "finance" FROM "arak_sub_5e1f0c2a_0a7b3c9d";
REVOKE USAGE ON SCHEMA "sales" FROM "arak_sub_5e1f0c2a_0a7b3c9d";
REVOKE CONNECT ON DATABASE "shop" FROM "arak_sub_5e1f0c2a_0a7b3c9d";
DROP ROLE "arak_sub_5e1f0c2a_0a7b3c9d";
