CREATE SCHEMA IF NOT EXISTS "acl";

CREATE TABLE IF NOT EXISTS "acl"."asset_subscription" (
  "principal" text NOT NULL,
  "asset" text NOT NULL,
  PRIMARY KEY ("principal", "asset")
);

CREATE TABLE IF NOT EXISTS "acl"."row_entitlement" (
  "principal" text NOT NULL,
  "asset" text NOT NULL,
  "entitlement_key" text NOT NULL,
  "value" text NOT NULL,
  PRIMARY KEY ("principal", "asset", "entitlement_key", "value")
);

CREATE TABLE IF NOT EXISTS "acl"."column_grant" (
  "principal" text NOT NULL,
  "asset" text NOT NULL,
  "column_name" text NOT NULL,
  "treatment" text NOT NULL,
  PRIMARY KEY ("principal", "asset", "column_name")
);

CREATE SCHEMA IF NOT EXISTS "sec";

CREATE OR REPLACE VIEW "sec"."customer" AS
SELECT
  "t"."id" AS "id",
  CASE
    WHEN EXISTS (
             SELECT 1 FROM "acl"."column_grant" g
              WHERE g."principal" = CAST(CURRENT_USER AS text)
                AND g."asset" = 'sales.customer'
                AND g."column_name" = 'email'
                AND g."treatment" = 'REGEX_REPLACE#3728d059'
           ) THEN regexp_replace(CAST("t"."email" AS text), '^[^@]+', '***', 'g')
    WHEN EXISTS (
             SELECT 1 FROM "acl"."column_grant" g
              WHERE g."principal" = CAST(CURRENT_USER AS text)
                AND g."asset" = 'sales.customer'
                AND g."column_name" = 'email'
                AND g."treatment" = 'PLAIN'
           ) THEN "t"."email"
    ELSE NULL
  END AS "email",
  CASE
    WHEN EXISTS (
             SELECT 1 FROM "acl"."column_grant" g
              WHERE g."principal" = CAST(CURRENT_USER AS text)
                AND g."asset" = 'sales.customer'
                AND g."column_name" = 'citizen_id'
                AND g."treatment" = 'PLAIN'
           ) THEN "t"."citizen_id"
    ELSE CASE WHEN "t"."citizen_id" IS NULL THEN NULL ELSE repeat('*', GREATEST(length(CAST("t"."citizen_id" AS text)) - 4, 0)) || right(CAST("t"."citizen_id" AS text), 4) END
  END AS "citizen_id",
  "t"."branch_code" AS "branch_code",
  CASE
    WHEN EXISTS (
             SELECT 1 FROM "acl"."column_grant" g
              WHERE g."principal" = CAST(CURRENT_USER AS text)
                AND g."asset" = 'sales.customer'
                AND g."column_name" = 'salary'
                AND g."treatment" = 'PLAIN'
           ) THEN "t"."salary"
    ELSE CASE WHEN ("t"."branch_code" <> 'BKK-01') THEN '***' ELSE "t"."salary" END
  END AS "salary"
FROM "sales"."customer" "t"
WHERE EXISTS (
    SELECT 1 FROM "acl"."asset_subscription" s
     WHERE s."principal" = CAST(CURRENT_USER AS text)
       AND s."asset" = 'sales.customer'
  )
  AND EXISTS (
    SELECT 1 FROM "acl"."row_entitlement" e
     WHERE e."principal" = CAST(CURRENT_USER AS text)
       AND e."asset" = 'sales.customer'
       AND e."entitlement_key" = 'branch_code'
       AND e."value" = CAST("t"."branch_code" AS text)
  );

GRANT SELECT ON "sec"."customer" TO "dac_reader";

-- rollback

REVOKE ALL ON "sec"."customer" FROM "dac_reader";

DROP VIEW IF EXISTS "sec"."customer";
