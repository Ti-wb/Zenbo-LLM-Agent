-- +goose Up
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_digest bytea NOT NULL UNIQUE CHECK (octet_length(token_digest) = 32),
    device_id text,
    label text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    bound_at timestamptz,
    revoked_at timestamptz,
    last_seen_at timestamptz,
    CHECK ((device_id IS NULL) = (bound_at IS NULL))
);

CREATE UNIQUE INDEX devices_one_active_binding
    ON devices(device_id)
    WHERE device_id IS NOT NULL AND revoked_at IS NULL;

CREATE TABLE sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_row_id uuid NOT NULL REFERENCES devices(id),
    device_id text NOT NULL,
    protocol_version text NOT NULL CHECK (protocol_version = '1.0'),
    state text NOT NULL DEFAULT 'active'
        CHECK (state IN ('active', 'closing', 'closed', 'expired')),
    agent_profile text NOT NULL,
    provider_kind text NOT NULL CHECK (provider_kind IN ('codex', 'openai-compatible')),
    provider_profile text NOT NULL,
    client jsonb NOT NULL,
    context jsonb NOT NULL,
    tool_manifest jsonb NOT NULL,
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    close_reason text,
    CHECK (expires_at > created_at),
    CHECK (
        (state IN ('active', 'closing') AND closed_at IS NULL)
        OR (state IN ('closed', 'expired') AND closed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX sessions_one_live_per_device
    ON sessions(device_row_id)
    WHERE state IN ('active', 'closing');
CREATE INDEX sessions_device_created_idx ON sessions(device_row_id, created_at DESC);
CREATE INDEX sessions_expiry_idx ON sessions(expires_at) WHERE state IN ('active', 'closing');

CREATE TABLE turns (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    client_turn_id uuid NOT NULL,
    state text NOT NULL DEFAULT 'accepted'
        CHECK (state IN (
            'accepted', 'processing', 'waiting_for_tool',
            'completed', 'cancelled', 'failed'
        )),
    input_kind text NOT NULL CHECK (input_kind IN ('text', 'audio')),
    input_text text,
    input_artifact_id uuid,
    language text,
    transcript text,
    response_text text,
    cancel_reason text,
    error jsonb,
    input_redacted_at timestamptz,
    accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    terminal_at timestamptz,
    CHECK (
        (
            input_kind = 'text' AND input_artifact_id IS NULL
            AND (
                (input_text IS NOT NULL AND input_redacted_at IS NULL)
                OR (input_text IS NULL AND input_redacted_at IS NOT NULL)
            )
        )
        OR (input_kind = 'audio' AND input_text IS NULL AND input_artifact_id IS NOT NULL)
    ),
    CHECK (
        (state IN ('completed', 'cancelled', 'failed') AND terminal_at IS NOT NULL)
        OR (state IN ('accepted', 'processing', 'waiting_for_tool') AND terminal_at IS NULL)
    ),
    UNIQUE (session_id, client_turn_id)
);

CREATE UNIQUE INDEX turns_one_active_per_session
    ON turns(session_id)
    WHERE state IN ('accepted', 'processing', 'waiting_for_tool');
CREATE INDEX turns_terminal_retention_idx ON turns(terminal_at)
    WHERE terminal_at IS NOT NULL;

CREATE TABLE events (
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    sequence bigint NOT NULL CHECK (sequence > 0),
    event_id uuid NOT NULL DEFAULT gen_random_uuid(),
    turn_id uuid REFERENCES turns(id) ON DELETE CASCADE,
    event_type text NOT NULL CHECK (event_type IN (
        'turn.accepted', 'stt.final', 'agent.thinking', 'tool.call',
        'agent.text.final', 'tts.ready', 'turn.completed', 'turn.error',
        'session.expired', 'turn.cancelled', 'session.closed'
    )),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    data jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data) = 'object'),
    PRIMARY KEY (session_id, sequence),
    UNIQUE (event_id)
);

CREATE INDEX events_turn_idx ON events(turn_id, sequence) WHERE turn_id IS NOT NULL;
CREATE INDEX events_retention_idx ON events(occurred_at);
CREATE UNIQUE INDEX events_one_turn_terminal
    ON events(turn_id)
    WHERE event_type IN ('turn.completed', 'turn.error', 'turn.cancelled');
CREATE UNIQUE INDEX events_one_session_terminal
    ON events(session_id)
    WHERE event_type IN ('session.closed', 'session.expired');

CREATE TABLE idempotency_records (
    device_row_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    operation text NOT NULL,
    idempotency_key uuid NOT NULL,
    request_digest bytea NOT NULL CHECK (octet_length(request_digest) = 32),
    session_id uuid REFERENCES sessions(id) ON DELETE CASCADE,
    resource_id uuid,
    response_status integer,
    response_body jsonb,
    state text NOT NULL DEFAULT 'started' CHECK (state IN ('started', 'completed')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at),
    PRIMARY KEY (device_row_id, operation, idempotency_key)
);

CREATE INDEX idempotency_expiry_idx ON idempotency_records(expires_at);

CREATE TABLE tool_calls (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_id uuid NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
    name text NOT NULL,
    version text NOT NULL,
    owner text NOT NULL CHECK (owner IN ('native', 'web')),
    side_effect text NOT NULL CHECK (side_effect IN ('none', 'ui', 'physical')),
    arguments jsonb NOT NULL CHECK (jsonb_typeof(arguments) = 'object'),
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'accepted', 'succeeded', 'failed', 'rejected')),
    output jsonb,
    error jsonb,
    deadline_at timestamptz NOT NULL,
    timeout_ms integer NOT NULL CHECK (timeout_ms > 0 AND timeout_ms <= 5000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    terminal_at timestamptz,
    CHECK (
        (status IN ('succeeded', 'failed', 'rejected') AND terminal_at IS NOT NULL)
        OR (status IN ('pending', 'accepted') AND terminal_at IS NULL)
    ),
    CHECK (
        deadline_at > created_at
        AND deadline_at <= created_at + timeout_ms * interval '1 millisecond'
    ),
    UNIQUE (session_id, id)
);

CREATE UNIQUE INDEX tool_calls_one_physical_active
    ON tool_calls(session_id)
    WHERE side_effect = 'physical' AND status IN ('pending', 'accepted');
CREATE INDEX tool_calls_deadline_idx ON tool_calls(deadline_at)
    WHERE status IN ('pending', 'accepted');
CREATE INDEX tool_calls_terminal_retention_idx ON tool_calls(terminal_at)
    WHERE terminal_at IS NOT NULL;

CREATE TABLE artifacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_id uuid NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('input_audio', 'tts_audio')),
    blob_key text NOT NULL UNIQUE,
    mime_type text NOT NULL,
    byte_length bigint NOT NULL CHECK (byte_length > 0 AND byte_length <= 10485760),
    sha256 bytea NOT NULL CHECK (octet_length(sha256) = 32),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    deletion_state text NOT NULL DEFAULT 'live'
        CHECK (deletion_state IN ('live', 'deleting', 'deleted')),
    deletion_lease_owner text,
    deletion_lease_until timestamptz,
    deleted_at timestamptz,
    CHECK (expires_at >= created_at),
    CHECK (
        (deletion_state = 'live' AND deletion_lease_owner IS NULL AND deletion_lease_until IS NULL AND deleted_at IS NULL)
        OR (deletion_state = 'deleting' AND deletion_lease_owner IS NOT NULL AND deletion_lease_until IS NOT NULL AND deleted_at IS NULL)
        OR (deletion_state = 'deleted' AND deletion_lease_owner IS NULL AND deletion_lease_until IS NULL AND deleted_at IS NOT NULL)
    )
);

ALTER TABLE turns
    ADD CONSTRAINT turns_input_artifact_fk
    FOREIGN KEY (input_artifact_id) REFERENCES artifacts(id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX artifacts_expiry_idx ON artifacts(expires_at)
    WHERE deletion_state = 'live';
CREATE INDEX artifacts_deletion_lease_idx ON artifacts(deletion_lease_until)
    WHERE deletion_state = 'deleting';

CREATE TABLE playback (
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    turn_id uuid NOT NULL REFERENCES turns(id) ON DELETE CASCADE,
    artifact_id uuid NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
    status text NOT NULL CHECK (status IN ('started', 'completed', 'interrupted')),
    reported_at timestamptz NOT NULL,
    position_ms integer CHECK (position_ms IS NULL OR position_ms >= 0),
    reason text CHECK (
        reason IS NULL OR reason IN (
            'barge_in', 'screen_off', 'playback_error', 'client_cancelled'
        )
    ),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (session_id, turn_id, artifact_id),
    CHECK (
        (status = 'interrupted' AND reason IS NOT NULL)
        OR (status IN ('started', 'completed') AND reason IS NULL)
    )
);

CREATE TABLE provider_state (
    session_id uuid PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    provider_kind text NOT NULL CHECK (provider_kind IN ('codex', 'openai-compatible')),
    provider_profile text NOT NULL,
    remote_thread_id text,
    opaque_state jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(opaque_state) = 'object'),
    cleanup_state text NOT NULL DEFAULT 'live'
        CHECK (cleanup_state IN ('live', 'deleting', 'deleted')),
    cleanup_lease_owner text,
    cleanup_lease_until timestamptz,
    cleaned_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (
        (
            cleanup_state = 'live'
            AND cleanup_lease_owner IS NULL
            AND cleanup_lease_until IS NULL
            AND cleaned_at IS NULL
        )
        OR (
            cleanup_state = 'deleting'
            AND cleanup_lease_owner IS NOT NULL
            AND cleanup_lease_until IS NOT NULL
            AND cleaned_at IS NULL
            AND provider_kind = 'codex'
            AND remote_thread_id IS NOT NULL
        )
        OR (
            cleanup_state = 'deleted'
            AND cleanup_lease_owner IS NULL
            AND cleanup_lease_until IS NULL
            AND cleaned_at IS NOT NULL
            AND remote_thread_id IS NULL
        )
    )
);

CREATE INDEX provider_state_cleanup_idx
    ON provider_state(cleanup_state, cleanup_lease_until)
    WHERE provider_kind = 'codex' AND remote_thread_id IS NOT NULL;
CREATE UNIQUE INDEX provider_state_codex_thread_idx
    ON provider_state(remote_thread_id)
    WHERE provider_kind = 'codex' AND remote_thread_id IS NOT NULL;

CREATE TABLE jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type text NOT NULL,
    session_id uuid REFERENCES sessions(id) ON DELETE CASCADE,
    turn_id uuid REFERENCES turns(id) ON DELETE CASCADE,
    dedupe_key text,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
    status text NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_owner text,
    lease_until timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    CHECK (
        (status = 'running' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'running' AND lease_owner IS NULL AND lease_until IS NULL)
    )
);

CREATE UNIQUE INDEX jobs_dedupe_idx ON jobs(job_type, dedupe_key)
    WHERE dedupe_key IS NOT NULL AND status IN ('queued', 'running');
CREATE INDEX jobs_lease_idx ON jobs(available_at, created_at)
    WHERE status = 'queued';
CREATE INDEX jobs_expired_lease_idx ON jobs(lease_until)
    WHERE status = 'running';

-- +goose Down
DROP TABLE IF EXISTS jobs;
DROP TABLE IF EXISTS provider_state;
DROP TABLE IF EXISTS playback;
ALTER TABLE turns DROP CONSTRAINT IF EXISTS turns_input_artifact_fk;
DROP TABLE IF EXISTS artifacts;
DROP TABLE IF EXISTS tool_calls;
DROP TABLE IF EXISTS idempotency_records;
DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS turns;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS devices;
