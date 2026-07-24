-- +goose Up
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS provider_revision text NOT NULL DEFAULT '';

ALTER TABLE sessions
    DROP CONSTRAINT IF EXISTS sessions_provider_revision_format;

ALTER TABLE sessions
    ADD CONSTRAINT sessions_provider_revision_format
    CHECK (provider_revision = '' OR provider_revision ~ '^[a-f0-9]{64}$');

ALTER TABLE sessions
    ALTER COLUMN provider_revision DROP DEFAULT;

-- Existing rows deliberately retain an empty revision. Workers reject those
-- sessions instead of silently binding them to whatever provider configuration
-- happens to be active during an upgrade.

-- +goose Down
ALTER TABLE sessions
    DROP COLUMN IF EXISTS provider_revision;
