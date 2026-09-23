IF SCHEMA_ID(N'acl') IS NULL EXEC(N'CREATE SCHEMA [acl]');

IF OBJECT_ID(N'[acl].[asset_subscription]', N'U') IS NULL
CREATE TABLE [acl].[asset_subscription] (
  [principal] nvarchar(256) NOT NULL,
  [asset] nvarchar(256) NOT NULL,
  PRIMARY KEY ([principal], [asset])
);

IF OBJECT_ID(N'[acl].[row_entitlement]', N'U') IS NULL
CREATE TABLE [acl].[row_entitlement] (
  [principal] nvarchar(256) NOT NULL,
  [asset] nvarchar(256) NOT NULL,
  [entitlement_key] nvarchar(256) NOT NULL,
  [value] nvarchar(256) NOT NULL,
  PRIMARY KEY ([principal], [asset], [entitlement_key], [value])
);

IF OBJECT_ID(N'[acl].[column_grant]', N'U') IS NULL
CREATE TABLE [acl].[column_grant] (
  [principal] nvarchar(256) NOT NULL,
  [asset] nvarchar(256) NOT NULL,
  [column_name] nvarchar(256) NOT NULL,
  [treatment] nvarchar(256) NOT NULL,
  PRIMARY KEY ([principal], [asset], [column_name])
);

IF SCHEMA_ID(N'sec') IS NULL EXEC(N'CREATE SCHEMA [sec]');

CREATE OR ALTER VIEW [sec].[customer] AS
SELECT
  [t].[id] AS [id],
  CASE
    WHEN EXISTS (
             SELECT 1 FROM [acl].[column_grant] g
              WHERE g.[principal] = CAST(USER_NAME() AS nvarchar(256))
                AND g.[asset] = N'sales.customer'
                AND g.[column_name] = N'email'
                AND g.[treatment] = N'REGEX_REPLACE#3728d059'
           ) THEN NULL
    WHEN EXISTS (
             SELECT 1 FROM [acl].[column_grant] g
              WHERE g.[principal] = CAST(USER_NAME() AS nvarchar(256))
                AND g.[asset] = N'sales.customer'
                AND g.[column_name] = N'email'
                AND g.[treatment] = N'PLAIN'
           ) THEN [t].[email]
    ELSE NULL
  END AS [email],
  CASE
    WHEN EXISTS (
             SELECT 1 FROM [acl].[column_grant] g
              WHERE g.[principal] = CAST(USER_NAME() AS nvarchar(256))
                AND g.[asset] = N'sales.customer'
                AND g.[column_name] = N'citizen_id'
                AND g.[treatment] = N'PLAIN'
           ) THEN [t].[citizen_id]
    ELSE CASE WHEN [t].[citizen_id] IS NULL THEN NULL ELSE REPLICATE(N'*', CASE WHEN LEN(CAST([t].[citizen_id] AS nvarchar(max))) - 4 > 0 THEN LEN(CAST([t].[citizen_id] AS nvarchar(max))) - 4 ELSE 0 END) + RIGHT(CAST([t].[citizen_id] AS nvarchar(max)), 4) END
  END AS [citizen_id],
  [t].[branch_code] AS [branch_code],
  CASE
    WHEN EXISTS (
             SELECT 1 FROM [acl].[column_grant] g
              WHERE g.[principal] = CAST(USER_NAME() AS nvarchar(256))
                AND g.[asset] = N'sales.customer'
                AND g.[column_name] = N'salary'
                AND g.[treatment] = N'PLAIN'
           ) THEN [t].[salary]
    ELSE CASE WHEN ("t"."branch_code" <> 'BKK-01') THEN N'***' ELSE [t].[salary] END
  END AS [salary]
FROM [sales].[customer] [t]
WHERE EXISTS (
    SELECT 1 FROM [acl].[asset_subscription] s
     WHERE s.[principal] = CAST(USER_NAME() AS nvarchar(256))
       AND s.[asset] = N'sales.customer'
  )
  AND EXISTS (
    SELECT 1 FROM [acl].[row_entitlement] e
     WHERE e.[principal] = CAST(USER_NAME() AS nvarchar(256))
       AND e.[asset] = N'sales.customer'
       AND e.[entitlement_key] = N'branch_code'
       AND e.[value] = CAST([t].[branch_code] AS nvarchar(256))
  );

GRANT SELECT ON OBJECT::[sec].[customer] TO [dac_reader];

-- rollback

REVOKE ALL ON OBJECT::[sec].[customer] FROM [dac_reader];

DROP VIEW IF EXISTS [sec].[customer];
