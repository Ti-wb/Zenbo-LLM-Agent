package application

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/httpapi"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/toolvalidation"
)

type Profile struct {
	ID              string
	DisplayName     string
	Languages       []string
	Default         bool
	ProviderKind    string
	ProviderProfile string
}

type Config struct {
	Profiles            []Profile
	SessionTTL          time.Duration
	IdempotencyTTL      time.Duration
	TranscriptRetention time.Duration
	InputAudioLifetime  time.Duration
	TTSLifetime         time.Duration
	Clock               func() time.Time
}

type Adapter struct {
	repository Repository
	blobs      blob.Store
	providers  Providers
	config     Config
	profiles   map[string]Profile
	clock      func() time.Time
}

func NewAdapter(repository Repository, blobs blob.Store, providers Providers, config Config) (*Adapter, error) {
	if repository == nil {
		return nil, fmt.Errorf("application repository is required")
	}
	if blobs == nil {
		return nil, fmt.Errorf("application blob store is required")
	}
	if len(config.Profiles) == 0 {
		return nil, fmt.Errorf("at least one agent profile is required")
	}
	if config.SessionTTL <= 0 {
		config.SessionTTL = 24 * time.Hour
	}
	if config.IdempotencyTTL <= 0 {
		config.IdempotencyTTL = 24 * time.Hour
	}
	if config.TranscriptRetention <= 0 {
		config.TranscriptRetention = 7 * 24 * time.Hour
	}
	if config.InputAudioLifetime <= 0 {
		config.InputAudioLifetime = 30 * time.Minute
	}
	if config.TTSLifetime <= 0 {
		config.TTSLifetime = 30 * time.Minute
	}
	clock := config.Clock
	if clock == nil {
		clock = time.Now
	}
	profiles := make(map[string]Profile, len(config.Profiles))
	defaults := 0
	for _, profile := range config.Profiles {
		if profile.ID == "" || profile.ProviderKind == "" || profile.ProviderProfile == "" {
			return nil, fmt.Errorf("profile ID, provider kind and provider profile are required")
		}
		if _, exists := profiles[profile.ID]; exists {
			return nil, fmt.Errorf("duplicate profile %q", profile.ID)
		}
		if profile.Default {
			defaults++
		}
		profiles[profile.ID] = profile
	}
	if defaults != 1 {
		return nil, fmt.Errorf("exactly one default profile is required")
	}
	return &Adapter{
		repository: repository,
		blobs:      blobs,
		providers:  providers,
		config:     config,
		profiles:   profiles,
		clock:      clock,
	}, nil
}

func (service *Adapter) Capabilities(context.Context, domain.Principal) (domain.Capabilities, error) {
	profiles := make([]domain.AgentProfile, 0, len(service.config.Profiles))
	for _, profile := range service.config.Profiles {
		profiles = append(profiles, domain.AgentProfile{
			ID:          profile.ID,
			DisplayName: profile.DisplayName,
			Languages:   append([]string(nil), profile.Languages...),
			IsDefault:   profile.Default,
		})
	}
	return domain.Capabilities{
		ProtocolVersion: domain.ProtocolVersion,
		AudioInput: domain.AudioInput{
			ContentTypes:  []string{"audio/wav"},
			MaxBytes:      domain.MaxInputAudioBytes,
			MaxDurationMS: domain.MaxInputDurationMS,
		},
		AudioOutput: domain.AudioOutput{
			ContentTypes: []string{"audio/mpeg", "audio/wav"},
			MaxBytes:     domain.MaxOutputAudioBytes,
		},
		EventTypes:    append([]domain.EventType(nil), domain.AllEventTypes...),
		AgentProfiles: profiles,
		RetentionPolicy: domain.RetentionPolicy{
			RawAudio: domain.RetentionRule{Retained: false, MaxAgeSeconds: 0},
			Transcript: domain.RetentionRule{
				Retained:      true,
				MaxAgeSeconds: int(service.config.TranscriptRetention.Seconds()),
			},
		},
	}, nil
}

func (service *Adapter) CreateSession(
	ctx context.Context,
	principal domain.Principal,
	idempotencyKey string,
	request domain.CreateSessionRequest,
) (domain.Session, error) {
	profile, exists := service.profiles[request.AgentProfile]
	if !exists {
		return domain.Session{}, domain.NewError(
			domain.ErrorInvalidArgument,
			"UNKNOWN_AGENT_PROFILE",
			"Requested agent profile is unavailable",
		)
	}
	now := service.clock().UTC()
	client, _ := json.Marshal(request.Client)
	robotContext, _ := json.Marshal(request.Context)
	manifest, _ := json.Marshal(request.ToolManifest)
	requestBytes, _ := json.Marshal(request)
	session, _, err := service.repository.CreateSession(ctx, CreateSessionParams{
		ID:               newUUID(),
		Device:           principalDevice(principal),
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    digestRequest("create_session", requestBytes),
		IdempotencyUntil: now.Add(service.config.IdempotencyTTL),
		AgentProfile:     profile.ID,
		ProviderKind:     profile.ProviderKind,
		ProviderProfile:  profile.ProviderProfile,
		Client:           client,
		Context:          robotContext,
		ToolManifest:     manifest,
		Now:              now,
		ExpiresAt:        now.Add(service.config.SessionTTL),
	})
	if err != nil {
		return domain.Session{}, publicError(err)
	}
	return domainSession(session), nil
}

func (service *Adapter) GetSession(ctx context.Context, principal domain.Principal, sessionID string) (domain.Session, error) {
	session, err := service.repository.GetSession(ctx, principal.DeviceID, sessionID)
	if err != nil {
		return domain.Session{}, publicError(err)
	}
	return domainSession(session), nil
}

func (service *Adapter) CloseSession(ctx context.Context, principal domain.Principal, sessionID string) error {
	_, err := service.repository.CloseSession(ctx, CloseSessionParams{
		SessionID: sessionID,
		DeviceID:  principal.DeviceID,
		State:     SessionClosed,
		Reason:    "client_request",
		Now:       service.clock().UTC(),
	})
	return publicError(err)
}

func (service *Adapter) CreateTextTurn(
	ctx context.Context,
	principal domain.Principal,
	sessionID, idempotencyKey string,
	request domain.TextTurn,
) (domain.TurnAccepted, error) {
	now := service.clock().UTC()
	encoded, _ := json.Marshal(request)
	turn, _, err := service.repository.CreateTurn(ctx, CreateTurnParams{
		ID:               newUUID(),
		SessionID:        sessionID,
		Device:           principalDevice(principal),
		ClientTurnID:     request.ClientTurnID,
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    digestRequest("create_text_turn:"+sessionID, encoded),
		IdempotencyUntil: now.Add(service.config.IdempotencyTTL),
		InputKind:        "text",
		InputText:        request.Text,
		Language:         request.Language,
		Now:              now,
	})
	if err != nil {
		return domain.TurnAccepted{}, publicError(err)
	}
	return domainTurnAccepted(turn), nil
}

func (service *Adapter) CreateAudioTurn(
	ctx context.Context,
	principal domain.Principal,
	sessionID, idempotencyKey string,
	request domain.AudioTurn,
) (domain.TurnAccepted, error) {
	now := service.clock().UTC()
	turnID, artifactID := newUUID(), newUUID()
	key, err := blob.Key(sessionID, artifactID, "wav")
	if err != nil {
		return domain.TurnAccepted{}, publicError(err)
	}
	object, err := service.blobs.Put(
		ctx, key, bytes.NewReader(request.Audio), domain.MaxInputAudioBytes,
	)
	if err != nil {
		return domain.TurnAccepted{}, publicError(err)
	}
	// Include the bytes in the digest so the same metadata cannot alias a
	// different upload under one idempotency key.
	audioDigest := sha256.Sum256(request.Audio)
	encoded, _ := json.Marshal(struct {
		ClientTurnID string `json:"clientTurnId"`
		DurationMS   int    `json:"durationMs"`
		Language     string `json:"language,omitempty"`
		AudioSHA256  string `json:"audioSha256"`
	}{
		ClientTurnID: request.ClientTurnID,
		DurationMS:   request.DurationMS,
		Language:     request.Language,
		AudioSHA256:  hex.EncodeToString(audioDigest[:]),
	})
	artifact := &Artifact{
		ID:         artifactID,
		SessionID:  sessionID,
		TurnID:     turnID,
		Kind:       "input_audio",
		BlobKey:    object.Key,
		MIMEType:   "audio/wav",
		ByteLength: object.Size,
		SHA256:     object.SHA256[:],
		CreatedAt:  now,
		ExpiresAt:  now.Add(service.config.InputAudioLifetime),
	}
	turn, replayed, err := service.repository.CreateTurn(ctx, CreateTurnParams{
		ID:               turnID,
		SessionID:        sessionID,
		Device:           principalDevice(principal),
		ClientTurnID:     request.ClientTurnID,
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    digestRequest("create_audio_turn:"+sessionID, encoded),
		IdempotencyUntil: now.Add(service.config.IdempotencyTTL),
		InputKind:        "audio",
		InputArtifactID:  artifactID,
		InputArtifact:    artifact,
		Language:         request.Language,
		Now:              now,
	})
	if err != nil {
		_ = service.blobs.Delete(context.Background(), key)
		return domain.TurnAccepted{}, publicError(err)
	}
	if replayed {
		_ = service.blobs.Delete(context.Background(), key)
	}
	return domainTurnAccepted(turn), nil
}

func (service *Adapter) CancelTurn(
	ctx context.Context,
	principal domain.Principal,
	sessionID, turnID, idempotencyKey string,
	request domain.CancelTurnRequest,
) (domain.TurnState, error) {
	now := service.clock().UTC()
	encoded, _ := json.Marshal(request)
	turn, changed, _, err := service.repository.CancelTurn(ctx, CancelTurnParams{
		SessionID:        sessionID,
		TurnID:           turnID,
		Device:           principalDevice(principal),
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    digestRequest("cancel_turn:"+turnID, encoded),
		IdempotencyUntil: now.Add(service.config.IdempotencyTTL),
		Reason:           request.Reason,
		Now:              now,
	})
	if err != nil {
		return domain.TurnState{}, publicError(err)
	}
	if changed && turn.InputArtifactID != "" {
		service.deleteArtifactBestEffort(ctx, turn.InputArtifactID)
	}
	if changed && service.providers != nil {
		if session, getErr := service.repository.GetSession(ctx, principal.DeviceID, sessionID); getErr == nil {
			if providers, providerErr := service.providers.ForSession(ctx, session); providerErr == nil && providers.Interrupt != nil {
				state, _ := service.repository.GetProviderState(ctx, sessionID)
				var remote struct {
					ActiveRemoteTurnID string `json:"activeRemoteTurnId"`
				}
				if json.Unmarshal(state.OpaqueState, &remote) == nil &&
					remote.ActiveRemoteTurnID != "" {
					_ = providers.Interrupt.Interrupt(
						ctx, state.RemoteThreadID, remote.ActiveRemoteTurnID,
					)
				}
			}
		}
	}
	return domainTurnState(turn), nil
}

func (service *Adapter) deleteArtifactBestEffort(
	requestContext context.Context,
	artifactID string,
) {
	ctx, cancel := context.WithTimeout(
		context.WithoutCancel(requestContext),
		5*time.Second,
	)
	defer cancel()
	owner := "api-" + newUUID()
	lease, err := service.repository.LeaseArtifactForDeletion(
		ctx, artifactID, owner, service.clock().UTC(), time.Minute,
	)
	if err != nil {
		return
	}
	if err := service.blobs.Delete(ctx, lease.BlobKey); err != nil {
		_ = service.repository.ReleaseArtifactLease(ctx, artifactID, owner)
		return
	}
	// If marking loses its lease, leave the deletion state untouched. The
	// durable delete_artifact job or expired-lease sweep will retry the
	// idempotent byte deletion and finish the metadata transition.
	_ = service.repository.MarkArtifactDeleted(
		ctx, artifactID, owner, service.clock().UTC(),
	)
}

func (service *Adapter) PutToolCallUpdate(
	ctx context.Context,
	principal domain.Principal,
	sessionID, callID string,
	update domain.ToolCallUpdate,
) (domain.ToolCallUpdate, error) {
	session, err := service.repository.GetSession(ctx, principal.DeviceID, sessionID)
	if err != nil {
		return domain.ToolCallUpdate{}, publicError(err)
	}
	now := service.clock().UTC()
	if update.Status == string(ToolSucceeded) {
		call, err := service.repository.GetToolCall(ctx, sessionID, callID)
		if err != nil {
			return domain.ToolCallUpdate{}, publicError(err)
		}
		// An expired result is durably normalized to TIMEOUT by the repository.
		// Only a still-timely success is allowed to reach schema validation.
		if now.Before(call.DeadlineAt) {
			registry, err := toolvalidation.Compile(session.ToolManifest)
			if err != nil {
				return domain.ToolCallUpdate{}, domain.WrapError(
					domain.ErrorInternal,
					"INVALID_SESSION_MANIFEST",
					"Stored device tool manifest is invalid",
					err,
				)
			}
			if err := registry.ValidateResult(call.Name, update.Output); err != nil {
				return domain.ToolCallUpdate{}, domain.WrapError(
					domain.ErrorUnprocessable,
					"INVALID_TOOL_RESULT",
					"Tool result does not match its registered schema",
					err,
				)
			}
		}
	}
	var errorDetail json.RawMessage
	if update.Error != nil {
		errorDetail, _ = json.Marshal(update.Error)
	}
	call, _, err := service.repository.UpdateToolCall(
		ctx, sessionID, callID, ToolStatus(update.Status), update.Output,
		errorDetail, now,
	)
	if err != nil {
		return domain.ToolCallUpdate{}, publicError(err)
	}
	return domainToolUpdate(call), nil
}

func (service *Adapter) GetAudioArtifact(
	ctx context.Context,
	principal domain.Principal,
	sessionID, artifactID string,
) (domain.AudioArtifact, error) {
	artifact, err := service.repository.GetArtifact(
		ctx, principal.DeviceID, sessionID, artifactID, service.clock().UTC(),
	)
	if err != nil {
		return domain.AudioArtifact{}, publicError(err)
	}
	if artifact.Kind != "tts_audio" {
		return domain.AudioArtifact{}, domain.NewError(
			domain.ErrorNotFound,
			"AUDIO_NOT_FOUND",
			"Requested audio artifact was not found",
		)
	}
	reader, object, err := service.blobs.Open(ctx, artifact.BlobKey)
	if err != nil {
		return domain.AudioArtifact{}, publicError(err)
	}
	if object.Size != artifact.ByteLength {
		_ = reader.Close()
		return domain.AudioArtifact{}, domain.NewError(
			domain.ErrorInternal, "INVALID_ARTIFACT",
			"Artifact byte length does not match durable metadata",
		)
	}
	return domain.AudioArtifact{
		ArtifactID: artifact.ID,
		MIMEType:   artifact.MIMEType,
		ByteLength: artifact.ByteLength,
		SHA256Hex:  hex.EncodeToString(artifact.SHA256),
		ExpiresAt:  domain.NewTimestamp(artifact.ExpiresAt),
		Body:       reader,
	}, nil
}

func (service *Adapter) ReportPlayback(
	ctx context.Context,
	principal domain.Principal,
	sessionID, idempotencyKey string,
	update domain.PlaybackUpdate,
) error {
	encoded, _ := json.Marshal(update)
	_, _, err := service.repository.ReportPlayback(
		ctx, principalDevice(principal), sessionID, idempotencyKey,
		digestRequest("playback:"+sessionID, encoded),
		Playback{
			SessionID:  sessionID,
			TurnID:     update.TurnID,
			ArtifactID: update.ArtifactID,
			Status:     update.Status,
			ReportedAt: update.Timestamp.Time.UTC(),
			PositionMS: update.PositionMS,
			Reason:     update.Reason,
		},
		service.clock().UTC(),
	)
	return publicError(err)
}

func (service *Adapter) OpenEventStream(
	ctx context.Context,
	principal domain.Principal,
	sessionID string,
	after uint64,
) (httpapi.EventStream, error) {
	window, err := service.repository.ReplayWindow(ctx, principal.DeviceID, sessionID, after, 256)
	if err != nil {
		return nil, publicError(err)
	}
	terminal := window.SessionState.Terminal()
	if terminal && window.Stale {
		// Android clears a stale remote session only on 404. Return the same
		// public problem as an unknown session so retention state does not
		// disclose that the terminal session once existed.
		return nil, publicError(ErrNotFound)
	}
	streamCtx, cancel := context.WithCancel(context.Background())
	cursor := after
	events := window.Events
	if window.Stale {
		cursor = window.CurrentSequence
		events = nil
	}
	return &eventStream{
		repository: service.repository,
		deviceID:   principal.DeviceID,
		sessionID:  sessionID,
		bootstrap: domain.StreamBootstrap{
			AcceptedAfter:   after,
			CurrentSequence: window.CurrentSequence,
			Stale:           window.Stale,
			SessionState:    string(window.SessionState),
			ActiveTurnID:    stringPointer(window.ActiveTurnID),
		},
		cursor:   cursor,
		queue:    events,
		current:  window.CurrentSequence,
		terminal: terminal,
		ctx:      streamCtx,
		cancel:   cancel,
	}, nil
}

func domainSession(value Session) domain.Session {
	return domain.Session{
		SessionID:       value.ID,
		DeviceID:        value.DeviceID,
		ProtocolVersion: value.ProtocolVersion,
		State:           string(value.State),
		CreatedAt:       domain.NewTimestamp(value.CreatedAt),
		ExpiresAt:       domain.NewTimestamp(value.ExpiresAt),
		LastSequence:    value.LastSequence,
	}
}

func domainTurnAccepted(value Turn) domain.TurnAccepted {
	return domain.TurnAccepted{
		SessionID:    value.SessionID,
		TurnID:       value.ID,
		ClientTurnID: value.ClientTurnID,
		State:        "accepted",
		AcceptedAt:   domain.NewTimestamp(value.AcceptedAt),
	}
}

func domainTurnState(value Turn) domain.TurnState {
	return domain.TurnState{
		TurnID:    value.ID,
		State:     string(value.State),
		UpdatedAt: domain.NewTimestamp(value.UpdatedAt),
	}
}

func domainToolUpdate(value ToolCall) domain.ToolCallUpdate {
	result := domain.ToolCallUpdate{
		Status:    string(value.Status),
		UpdatedAt: domain.NewTimestamp(value.UpdatedAt),
	}
	if len(value.Output) > 0 && string(value.Output) != "null" {
		result.Output = value.Output
	}
	if len(value.Error) > 0 && string(value.Error) != "null" {
		var detail domain.ErrorDetail
		if json.Unmarshal(value.Error, &detail) == nil {
			result.Error = &detail
		}
	}
	return result
}

func principalDevice(value domain.Principal) Device {
	return Device{ID: value.TokenID, DeviceID: value.DeviceID}
}

func digestRequest(operation string, data []byte) []byte {
	hash := sha256.New()
	_, _ = io.WriteString(hash, operation)
	_, _ = hash.Write([]byte{0})
	_, _ = hash.Write(data)
	return hash.Sum(nil)
}

func stringPointer(value string) *string {
	if value == "" {
		return nil
	}
	copy := value
	return &copy
}

func newUUID() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		panic("crypto/rand unavailable: " + err.Error())
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	return fmt.Sprintf(
		"%08x-%04x-%04x-%04x-%012x",
		value[0:4], value[4:6], value[6:8], value[8:10], value[10:16],
	)
}

func publicError(err error) error {
	if err == nil {
		return nil
	}
	switch {
	case errors.Is(err, ErrUnauthorized):
		return domain.WrapError(domain.ErrorUnauthenticated, "UNAUTHORIZED", "Device credential is invalid", err)
	case errors.Is(err, ErrNotFound):
		return domain.WrapError(domain.ErrorNotFound, "NOT_FOUND", "Requested gateway resource was not found", err)
	case errors.Is(err, ErrArtifactGone):
		return domain.WrapError(domain.ErrorGone, "AUDIO_EXPIRED", "Audio artifact has expired", err)
	case errors.Is(err, ErrIdempotencyConflict):
		return domain.WrapError(domain.ErrorConflict, "IDEMPOTENCY_CONFLICT", "Idempotency key was already used for another request", err)
	case errors.Is(err, ErrSessionNotActive):
		return domain.WrapError(domain.ErrorConflict, "SESSION_NOT_ACTIVE", "Session does not accept new turns", err)
	case errors.Is(err, ErrTurnTerminal), errors.Is(err, ErrToolTerminal):
		return domain.WrapError(domain.ErrorConflict, "TERMINAL_STATE_CONFLICT", "Terminal state cannot be replaced", err)
	case errors.Is(err, ErrInvalidTransition), errors.Is(err, ErrConflict):
		return domain.WrapError(domain.ErrorConflict, "STATE_CONFLICT", "Requested state transition is not allowed", err)
	default:
		var existing *domain.Error
		if errors.As(err, &existing) {
			return err
		}
		return domain.WrapError(domain.ErrorInternal, "INTERNAL_ERROR", "The gateway could not complete the request", err)
	}
}

var _ httpapi.Service = (*Adapter)(nil)
