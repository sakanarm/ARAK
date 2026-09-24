-- V14 — each person configures their own assistant.
--
-- V12 read the requirement as "an administrator configures the gateway, and
-- each person is switched on against it". What was actually wanted is the other
-- reading: each person points the assistant at their own endpoint with their own
-- key. This migration adds that, and keeps the central gateway as an optional
-- default rather than deleting it -- a deployment that would rather hand out one
-- shared key still can, and a person who has not configured anything falls back
-- to it.
--
-- The consequence worth writing down here, because it is a change of security
-- posture and not merely a column: a base URL now comes from an ordinary user,
-- and the service makes an outbound request to it. That is a server-side request
-- to an address somebody typed. `LlmGatewayUrl` refuses loopback and link-local
-- addresses for exactly this reason -- the cloud metadata service on
-- 169.254.169.254 is the classic target -- and the platform keeps a switch
-- (`allow_personal` below) to turn the whole capability off without unpicking
-- anybody's settings.


-- Whether people may point the assistant at their own gateway at all.
--
-- Separate from `enabled`, which governs the shared gateway. The two together
-- give the four states that are actually wanted: shared only, personal only,
-- both, and off. Defaulting to true matches what was asked for; an installation
-- that wants the assistant locked to one vetted endpoint sets it false.
ALTER TABLE llm_provider
    ADD COLUMN allow_personal boolean NOT NULL DEFAULT true;


-- The person's own gateway. Null means "use the shared one, if there is one".
ALTER TABLE llm_user_setting
    ADD COLUMN base_url text;

-- The person's own key, encrypted with the deployment's Fernet key.
--
-- This is the one column in this schema that holds a secret rather than a
-- pointer to one, and the exception is deliberate: `credential_ref` works
-- because one administrator sets one value that an operator can put in the
-- environment. Nobody is going to add an environment variable per analyst, so
-- requiring a pointer here would make the feature administrator-only again --
-- which is precisely what this migration exists to undo.
--
-- What that buys: a database copy, a backup or a screenshot of this table does
-- not yield anybody's key. What it does not buy: secrecy from this service,
-- which necessarily holds the Fernet key in order to make the call. See
-- com.mfec.dac.crypto.SecretBox.
--
-- Never selected into any response. The API answers `hasOwnKey` -- a boolean --
-- and nothing derived from the value itself, not a prefix and not a length.
ALTER TABLE llm_user_setting
    ADD COLUMN api_key_cipher text;
