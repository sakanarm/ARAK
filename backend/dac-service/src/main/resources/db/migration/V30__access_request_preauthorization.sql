-- A second kind of access request: pre-authorization.
--
-- An ordinary request asks for one table for the person asking. A
-- pre-authorization asks, ahead of need, for every table under a scope that
-- carries some tags, terms or domains -- for a group, a team, a role, or
-- everybody holding some attributes. It is not a grant and never becomes one:
-- what fulfils it is a subscription policy, drafted from the request, saved as
-- a DRAFT and activated through the policy lifecycle like any other. That is
-- why its tables are named by facets rather than listed: a table tagged the
-- same way tomorrow is covered by the same policy without a second request.
--
-- asset_fqn stays the anchor: for a pre-authorization it is the scope (a
-- service, database, schema or table), and the workflow and owners of that
-- scope decide it, as they decide a request for a table inside it.

ALTER TABLE access_request
    ADD COLUMN kind text NOT NULL DEFAULT 'ASSET'
        CHECK (kind IN ('ASSET', 'PREAUTHORIZATION')),
    -- Which tables, and for whom: {"conditions": [{facet, operator, value}],
    -- "subject": {kind, principals, attributes}}. Validated by the service
    -- before it is written; the check here only keeps the two kinds apart.
    ADD COLUMN target jsonb;

ALTER TABLE access_request
    ADD CONSTRAINT access_request_kind_target
    CHECK ((kind = 'PREAUTHORIZATION') = (target IS NOT NULL));

-- A pre-authorization is fulfilled by a policy, never by a grant to the person
-- who asked: the people it is for may not include them at all.
ALTER TABLE access_request
    ADD CONSTRAINT access_request_preauthorization_by_policy
    CHECK (kind = 'ASSET' OR fulfilment IS NULL OR fulfilment IN ('POLICY_UPDATED', 'POLICY_CREATED'));

-- One open request per person per table still holds for the ordinary kind. A
-- pre-authorization is about other people and a set of tables, so the same
-- person may well have two open on one scope, for two groups.
DROP INDEX access_request_one_open_idx;
CREATE UNIQUE INDEX access_request_one_open_idx
    ON access_request (asset_fqn, requester_id)
    WHERE status IN ('PENDING', 'APPROVED', 'IN_PROGRESS') AND kind = 'ASSET';
