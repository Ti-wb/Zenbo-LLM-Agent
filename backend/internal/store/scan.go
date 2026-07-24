package store

import (
	"encoding/json"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
)

const sessionColumns = `
id::text, device_row_id::text, device_id, protocol_version, state,
agent_profile, provider_kind, provider_profile, provider_revision,
client, context, tool_manifest,
last_sequence, created_at, updated_at, expires_at, closed_at,
COALESCE(close_reason, '')`

func scanSession(row rowScanner) (application.Session, error) {
	var value application.Session
	var state string
	var lastSequence int64
	err := row.Scan(
		&value.ID, &value.DeviceRowID, &value.DeviceID, &value.ProtocolVersion, &state,
		&value.AgentProfile, &value.ProviderKind, &value.ProviderProfile,
		&value.ProviderRevision,
		&value.Client, &value.Context, &value.ToolManifest, &lastSequence,
		&value.CreatedAt, &value.UpdatedAt, &value.ExpiresAt, &value.ClosedAt,
		&value.CloseReason,
	)
	value.State = application.SessionState(state)
	value.LastSequence = uint64(lastSequence)
	return value, err
}

const turnColumns = `
id::text, session_id::text, client_turn_id::text, state, input_kind,
COALESCE(input_text, ''), COALESCE(input_artifact_id::text, ''),
COALESCE(language, ''), COALESCE(transcript, ''), COALESCE(response_text, ''),
COALESCE(cancel_reason, ''), COALESCE(error, 'null'::jsonb),
accepted_at, updated_at, terminal_at`

func scanTurn(row rowScanner) (application.Turn, error) {
	var value application.Turn
	var state string
	err := row.Scan(
		&value.ID, &value.SessionID, &value.ClientTurnID, &state, &value.InputKind,
		&value.InputText, &value.InputArtifactID, &value.Language, &value.Transcript,
		&value.ResponseText, &value.CancelReason, &value.Error, &value.AcceptedAt,
		&value.UpdatedAt, &value.TerminalAt,
	)
	value.State = application.TurnState(state)
	return value, err
}

const eventColumns = `
event_id::text, sequence, session_id::text, turn_id::text, event_type,
occurred_at, data`

func scanEvent(row rowScanner) (application.Event, error) {
	var value application.Event
	var sequence int64
	var turnID *string
	err := row.Scan(
		&value.EventID, &sequence, &value.SessionID, &turnID, &value.Type,
		&value.Timestamp, &value.Data,
	)
	value.ProtocolVersion = application.ProtocolVersion
	value.Sequence = uint64(sequence)
	value.TurnID = turnID
	return value, err
}

const toolColumns = `
id::text, session_id::text, turn_id::text, name, version, owner, side_effect,
arguments, status, COALESCE(output, 'null'::jsonb),
COALESCE(error, 'null'::jsonb), deadline_at, timeout_ms,
created_at, updated_at, terminal_at`

func scanTool(row rowScanner) (application.ToolCall, error) {
	var value application.ToolCall
	var status string
	err := row.Scan(
		&value.ID, &value.SessionID, &value.TurnID, &value.Name, &value.Version,
		&value.Owner, &value.SideEffect, &value.Arguments, &status, &value.Output,
		&value.Error, &value.DeadlineAt, &value.TimeoutMS, &value.CreatedAt,
		&value.UpdatedAt, &value.TerminalAt,
	)
	value.Status = application.ToolStatus(status)
	return value, err
}

const artifactColumns = `
id::text, session_id::text, turn_id::text, kind, blob_key, mime_type,
byte_length, sha256, created_at, expires_at, deletion_state,
COALESCE(deletion_lease_owner, ''), deletion_lease_until, deleted_at`

func scanArtifact(row rowScanner) (application.Artifact, error) {
	var value application.Artifact
	err := row.Scan(
		&value.ID, &value.SessionID, &value.TurnID, &value.Kind, &value.BlobKey,
		&value.MIMEType, &value.ByteLength, &value.SHA256, &value.CreatedAt,
		&value.ExpiresAt, &value.DeletionState, &value.DeletionLeaseOwner,
		&value.DeletionLeaseUntil, &value.DeletedAt,
	)
	return value, err
}

const jobColumns = `
id::text, job_type, COALESCE(session_id::text, ''), COALESCE(turn_id::text, ''),
COALESCE(dedupe_key, ''), payload, status, attempts, max_attempts, available_at,
COALESCE(lease_owner, ''), lease_until, COALESCE(last_error, ''),
created_at, updated_at, completed_at`

func scanJob(row rowScanner) (application.Job, error) {
	var value application.Job
	err := row.Scan(
		&value.ID, &value.Type, &value.SessionID, &value.TurnID, &value.DedupeKey,
		&value.Payload, &value.Status, &value.Attempts, &value.MaxAttempts,
		&value.AvailableAt, &value.LeaseOwner, &value.LeaseUntil, &value.LastError,
		&value.CreatedAt, &value.UpdatedAt, &value.CompletedAt,
	)
	return value, err
}

func normalizeJSON(value json.RawMessage) json.RawMessage {
	if len(value) == 0 {
		return json.RawMessage(`{}`)
	}
	return value
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func nullableTime(value time.Time) any {
	if value.IsZero() {
		return nil
	}
	return value
}
