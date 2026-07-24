package application

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"io"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

type fakeRepository struct {
	Repository
	createTurn       func(context.Context, CreateTurnParams) (Turn, bool, error)
	cancelTurn       func(context.Context, CancelTurnParams) (Turn, bool, bool, error)
	getSession       func(context.Context, string, string) (Session, error)
	getProviderState func(context.Context, string) (ProviderState, error)
	getArtifact      func(context.Context, string, string, string, time.Time) (Artifact, error)
	getToolCall      func(context.Context, string, string) (ToolCall, error)
	updateToolCall   func(context.Context, string, string, ToolStatus, json.RawMessage, json.RawMessage, time.Time) (ToolCall, bool, error)
	replayWindow     func(context.Context, string, string, uint64, int) (ReplayWindow, error)
	leaseArtifact    func(context.Context, string, string, time.Time, time.Duration) (ArtifactLease, error)
	markArtifact     func(context.Context, string, string, time.Time) error
	releaseArtifact  func(context.Context, string, string) error
}

func (repository *fakeRepository) CreateTurn(ctx context.Context, params CreateTurnParams) (Turn, bool, error) {
	return repository.createTurn(ctx, params)
}

func (repository *fakeRepository) CancelTurn(ctx context.Context, params CancelTurnParams) (Turn, bool, bool, error) {
	return repository.cancelTurn(ctx, params)
}

func (repository *fakeRepository) GetSession(ctx context.Context, deviceID, sessionID string) (Session, error) {
	return repository.getSession(ctx, deviceID, sessionID)
}

func (repository *fakeRepository) GetProviderState(ctx context.Context, sessionID string) (ProviderState, error) {
	return repository.getProviderState(ctx, sessionID)
}

func (repository *fakeRepository) GetArtifact(ctx context.Context, deviceID, sessionID, artifactID string, now time.Time) (Artifact, error) {
	return repository.getArtifact(ctx, deviceID, sessionID, artifactID, now)
}

func (repository *fakeRepository) GetToolCall(ctx context.Context, sessionID, callID string) (ToolCall, error) {
	return repository.getToolCall(ctx, sessionID, callID)
}

func (repository *fakeRepository) UpdateToolCall(
	ctx context.Context,
	sessionID, callID string,
	status ToolStatus,
	output, errorDetail json.RawMessage,
	now time.Time,
) (ToolCall, bool, error) {
	return repository.updateToolCall(
		ctx, sessionID, callID, status, output, errorDetail, now,
	)
}

func (repository *fakeRepository) ReplayWindow(
	ctx context.Context,
	deviceID, sessionID string,
	after uint64,
	limit int,
) (ReplayWindow, error) {
	return repository.replayWindow(ctx, deviceID, sessionID, after, limit)
}

func (repository *fakeRepository) LeaseArtifactForDeletion(
	ctx context.Context,
	artifactID, owner string,
	now time.Time,
	lease time.Duration,
) (ArtifactLease, error) {
	return repository.leaseArtifact(ctx, artifactID, owner, now, lease)
}

func (repository *fakeRepository) MarkArtifactDeleted(
	ctx context.Context,
	artifactID, owner string,
	now time.Time,
) error {
	return repository.markArtifact(ctx, artifactID, owner, now)
}

func (repository *fakeRepository) ReleaseArtifactLease(
	ctx context.Context,
	artifactID, owner string,
) error {
	return repository.releaseArtifact(ctx, artifactID, owner)
}

type memoryBlobStore struct {
	values  map[string][]byte
	deleted []string
	opened  int
}

func (store *memoryBlobStore) Put(_ context.Context, key string, reader io.Reader, max int64) (blob.Object, error) {
	value, err := io.ReadAll(io.LimitReader(reader, max+1))
	if err != nil {
		return blob.Object{}, err
	}
	sum := sha256.Sum256(value)
	if store.values == nil {
		store.values = map[string][]byte{}
	}
	store.values[key] = value
	return blob.Object{Key: key, Size: int64(len(value)), SHA256: sum}, nil
}

func (store *memoryBlobStore) Open(_ context.Context, key string) (io.ReadCloser, blob.Object, error) {
	store.opened++
	value := store.values[key]
	sum := sha256.Sum256(value)
	return io.NopCloser(bytes.NewReader(value)), blob.Object{
		Key: key, Size: int64(len(value)), SHA256: sum,
	}, nil
}

func (store *memoryBlobStore) Delete(_ context.Context, key string) error {
	store.deleted = append(store.deleted, key)
	delete(store.values, key)
	return nil
}

func TestCreateAudioTurnDeletesSpeculativeBlobOnIdempotentReplay(t *testing.T) {
	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	existing := Turn{
		ID:           "30000000-0000-4000-8000-000000000003",
		SessionID:    "10000000-0000-4000-8000-000000000001",
		ClientTurnID: "20000000-0000-4000-8000-000000000002",
		State:        TurnAccepted,
		AcceptedAt:   now,
		UpdatedAt:    now,
	}
	repository := &fakeRepository{
		createTurn: func(_ context.Context, params CreateTurnParams) (Turn, bool, error) {
			if params.InputArtifact == nil {
				t.Fatal("input artifact metadata was not supplied")
			}
			return existing, true, nil
		},
	}
	blobs := &memoryBlobStore{}
	service := testAdapter(t, repository, blobs, now)
	result, err := service.CreateAudioTurn(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		existing.SessionID,
		"40000000-0000-4000-8000-000000000004",
		domain.AudioTurn{
			ClientTurnID: existing.ClientTurnID,
			Audio:        []byte("RIFF-test-WAVE"),
			DurationMS:   100,
			Language:     "zh-TW",
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.TurnID != existing.ID {
		t.Fatalf("turn ID = %s", result.TurnID)
	}
	if len(blobs.deleted) != 1 || len(blobs.values) != 0 {
		t.Fatalf("deleted=%v remaining=%v", blobs.deleted, blobs.values)
	}
}

func TestCancelUsesPersistedRemoteTurnIDOnly(t *testing.T) {
	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	tests := []struct {
		name      string
		opaque    json.RawMessage
		wantCalls []string
	}{
		{"missing", json.RawMessage(`{}`), nil},
		{"persisted", json.RawMessage(`{"activeRemoteTurnId":"remote-turn"}`), []string{"remote-thread", "remote-turn"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			interrupt := &fakeInterrupt{}
			repository := &fakeRepository{
				cancelTurn: func(context.Context, CancelTurnParams) (Turn, bool, bool, error) {
					return Turn{
						ID:    "30000000-0000-4000-8000-000000000003",
						State: TurnCancelled, UpdatedAt: now,
					}, true, false, nil
				},
				getSession: func(context.Context, string, string) (Session, error) {
					return Session{ID: "session"}, nil
				},
				getProviderState: func(context.Context, string) (ProviderState, error) {
					return ProviderState{
						RemoteThreadID: "remote-thread",
						OpaqueState:    test.opaque,
					}, nil
				},
			}
			providers := fakeProviders{interrupt: interrupt}
			service := testAdapter(t, repository, &memoryBlobStore{}, now)
			service.providers = providers
			_, err := service.CancelTurn(
				context.Background(),
				domain.Principal{TokenID: "device-row", DeviceID: "device"},
				"10000000-0000-4000-8000-000000000001",
				"30000000-0000-4000-8000-000000000003",
				"40000000-0000-4000-8000-000000000004",
				domain.CancelTurnRequest{Reason: "client_request"},
			)
			if err != nil {
				t.Fatal(err)
			}
			if !equalStrings(interrupt.calls, test.wantCalls) {
				t.Fatalf("interrupt calls = %v, want %v", interrupt.calls, test.wantCalls)
			}
		})
	}
}

func TestCancelImmediatelyDeletesRawInputArtifact(t *testing.T) {
	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	const artifactID = "60000000-0000-4000-8000-000000000006"
	const blobKey = "session/raw.wav"
	marked := false
	repository := &fakeRepository{
		cancelTurn: func(context.Context, CancelTurnParams) (Turn, bool, bool, error) {
			return Turn{
				ID:              "30000000-0000-4000-8000-000000000003",
				State:           TurnCancelled,
				InputKind:       "audio",
				InputArtifactID: artifactID,
				UpdatedAt:       now,
			}, true, false, nil
		},
		leaseArtifact: func(
			_ context.Context, gotArtifactID, _ string, _ time.Time, _ time.Duration,
		) (ArtifactLease, error) {
			if gotArtifactID != artifactID {
				t.Fatalf("leased artifact = %s", gotArtifactID)
			}
			return ArtifactLease{Artifact: Artifact{
				ID: artifactID, BlobKey: blobKey,
			}}, nil
		},
		markArtifact: func(
			_ context.Context, gotArtifactID, _ string, _ time.Time,
		) error {
			marked = gotArtifactID == artifactID
			return nil
		},
		releaseArtifact: func(context.Context, string, string) error {
			t.Fatal("successful deletion unexpectedly released its lease")
			return nil
		},
	}
	blobs := &memoryBlobStore{values: map[string][]byte{blobKey: []byte("raw")}}
	service := testAdapter(t, repository, blobs, now)
	if _, err := service.CancelTurn(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		"10000000-0000-4000-8000-000000000001",
		"30000000-0000-4000-8000-000000000003",
		"40000000-0000-4000-8000-000000000004",
		domain.CancelTurnRequest{Reason: "client_request"},
	); err != nil {
		t.Fatal(err)
	}
	if !marked || len(blobs.deleted) != 1 || blobs.deleted[0] != blobKey {
		t.Fatalf("marked=%v deleted=%v", marked, blobs.deleted)
	}
}

func TestGetAudioArtifactDoesNotExposeInputAudio(t *testing.T) {
	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	repository := &fakeRepository{
		getArtifact: func(context.Context, string, string, string, time.Time) (Artifact, error) {
			return Artifact{Kind: "input_audio"}, nil
		},
	}
	blobs := &memoryBlobStore{}
	service := testAdapter(t, repository, blobs, now)
	_, err := service.GetAudioArtifact(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		"10000000-0000-4000-8000-000000000001",
		"20000000-0000-4000-8000-000000000002",
	)
	if err == nil {
		t.Fatal("expected input artifact to be rejected")
	}
	if blobs.opened != 0 {
		t.Fatalf("input blob was opened %d times", blobs.opened)
	}
}

func TestPutToolCallUsesServerClockAndValidatesSucceededResult(t *testing.T) {
	now := time.Date(2026, 7, 24, 0, 0, 10, 0, time.UTC)
	manifest := json.RawMessage(`{
		"protocolVersion":"1.0",
		"manifestVersion":"test",
		"tools":[{
			"name":"show_emotion",
			"owner":"web",
			"version":"1.0.0",
			"description":"Show an expression",
			"inputSchema":{"type":"object","properties":{},"additionalProperties":false},
			"resultSchema":{
				"type":"object",
				"properties":{"applied":{"type":"boolean"}},
				"required":["applied"],
				"additionalProperties":false
			},
			"sideEffect":"ui",
			"idempotent":true,
			"requiresConfirmation":false,
			"timeoutMs":5000
		}]
	}`)
	var receivedNow time.Time
	updates := 0
	repository := &fakeRepository{
		getSession: func(context.Context, string, string) (Session, error) {
			return Session{ToolManifest: manifest}, nil
		},
		getToolCall: func(context.Context, string, string) (ToolCall, error) {
			return ToolCall{
				ID:   "30000000-0000-4000-8000-000000000003",
				Name: "show_emotion", DeadlineAt: now.Add(time.Second),
			}, nil
		},
		updateToolCall: func(
			_ context.Context, _, _ string, status ToolStatus,
			output, _ json.RawMessage, authoritativeNow time.Time,
		) (ToolCall, bool, error) {
			updates++
			receivedNow = authoritativeNow
			return ToolCall{
				Status: status, Output: output, UpdatedAt: authoritativeNow,
			}, true, nil
		},
	}
	service := testAdapter(t, repository, &memoryBlobStore{}, now)
	_, err := service.PutToolCallUpdate(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		"10000000-0000-4000-8000-000000000001",
		"30000000-0000-4000-8000-000000000003",
		domain.ToolCallUpdate{
			Status:    "succeeded",
			UpdatedAt: domain.NewTimestamp(now.Add(-24 * time.Hour)),
			Output:    json.RawMessage(`{"applied":true}`),
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if updates != 1 || !receivedNow.Equal(now) {
		t.Fatalf("updates=%d authoritative now=%s", updates, receivedNow)
	}

	_, err = service.PutToolCallUpdate(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		"10000000-0000-4000-8000-000000000001",
		"30000000-0000-4000-8000-000000000003",
		domain.ToolCallUpdate{
			Status: "succeeded", UpdatedAt: domain.NewTimestamp(now),
			Output: json.RawMessage(`{"applied":"yes"}`),
		},
	)
	if err == nil || domain.AsError(err).Kind != domain.ErrorUnprocessable {
		t.Fatalf("invalid result error = %v", err)
	}
	if updates != 1 {
		t.Fatalf("invalid result reached repository; updates=%d", updates)
	}
}

func TestOpenEventStreamAllowsTerminalReplayButHidesStaleTerminal(t *testing.T) {
	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	stale := false
	sessionState := SessionClosed
	repository := &fakeRepository{
		replayWindow: func(
			context.Context, string, string, uint64, int,
		) (ReplayWindow, error) {
			return ReplayWindow{
				CurrentSequence: 5,
				Stale:           stale,
				SessionState:    sessionState,
			}, nil
		},
	}
	service := testAdapter(t, repository, &memoryBlobStore{}, now)
	stream, err := service.OpenEventStream(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		"10000000-0000-4000-8000-000000000001",
		5,
	)
	if err != nil {
		t.Fatal(err)
	}
	if stream.Bootstrap().SessionState != "closed" {
		t.Fatalf("bootstrap = %#v", stream.Bootstrap())
	}
	if _, err := stream.Next(context.Background()); err != io.EOF {
		t.Fatalf("terminal stream Next = %v, want EOF", err)
	}
	_ = stream.Close()

	stale = true
	missingService := testAdapter(t, &fakeRepository{
		replayWindow: func(
			context.Context, string, string, uint64, int,
		) (ReplayWindow, error) {
			return ReplayWindow{}, ErrNotFound
		},
	}, &memoryBlobStore{}, now)
	_, missingError := missingService.OpenEventStream(
		context.Background(),
		domain.Principal{TokenID: "device-row", DeviceID: "device"},
		"10000000-0000-4000-8000-000000000001",
		0,
	)
	missingProblem := domain.AsError(missingError)
	for _, terminalState := range []SessionState{SessionClosed, SessionExpired} {
		sessionState = terminalState
		_, staleError := service.OpenEventStream(
			context.Background(),
			domain.Principal{TokenID: "device-row", DeviceID: "device"},
			"10000000-0000-4000-8000-000000000001",
			0,
		)
		if staleError == nil {
			t.Fatalf("%s stale terminal stream was accepted", terminalState)
		}
		staleProblem := domain.AsError(staleError)
		if staleProblem.Kind != domain.ErrorNotFound ||
			staleProblem.Code != "NOT_FOUND" ||
			staleProblem.Detail != "Requested gateway resource was not found" {
			t.Fatalf("%s stale terminal error = %#v", terminalState, staleProblem)
		}
		if staleProblem.Kind != missingProblem.Kind ||
			staleProblem.Code != missingProblem.Code ||
			staleProblem.Detail != missingProblem.Detail {
			t.Fatalf(
				"%s stale terminal problem %#v differs from missing problem %#v",
				terminalState, staleProblem, missingProblem,
			)
		}
	}
}

func testAdapter(t *testing.T, repository Repository, blobs blob.Store, now time.Time) *Adapter {
	t.Helper()
	service, err := NewAdapter(repository, blobs, nil, Config{
		Profiles: []Profile{{
			ID: "default", DisplayName: "Default", Languages: []string{"zh-TW"},
			Default: true, ProviderKind: "openai-compatible",
			ProviderProfile: "default",
		}},
		Clock: func() time.Time { return now },
	})
	if err != nil {
		t.Fatal(err)
	}
	return service
}

type fakeProviders struct {
	interrupt Interruptible
}

func (providers fakeProviders) ForSession(context.Context, Session) (ProviderSet, error) {
	return ProviderSet{Interrupt: providers.interrupt}, nil
}

type fakeInterrupt struct {
	calls []string
}

func (interrupt *fakeInterrupt) Interrupt(_ context.Context, threadID, turnID string) error {
	interrupt.calls = []string{threadID, turnID}
	return nil
}

func equalStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}
