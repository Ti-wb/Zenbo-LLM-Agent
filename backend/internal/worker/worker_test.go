package worker

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

const (
	testSessionID = "10000000-0000-4000-8000-000000000001"
	testTurnID    = "20000000-0000-4000-8000-000000000001"
	testDeviceID  = "zenbo-test"
)

func TestProcessAudioTurnSuccessDeletesRawAudio(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	repository.turn.InputKind = "audio"
	repository.turn.InputText = ""
	repository.turn.InputArtifactID = "30000000-0000-4000-8000-000000000001"
	audio := []byte("RIFF-test-audio")
	key := testSessionID + "/input.wav"
	blobs.values[key] = audio
	hash := sha256.Sum256(audio)
	repository.artifacts[repository.turn.InputArtifactID] = application.Artifact{
		ID:            repository.turn.InputArtifactID,
		SessionID:     testSessionID,
		TurnID:        testTurnID,
		Kind:          "input_audio",
		BlobKey:       key,
		MIMEType:      "audio/wav",
		ByteLength:    int64(len(audio)),
		SHA256:        hash[:],
		CreatedAt:     time.Now().UTC(),
		ExpiresAt:     time.Now().UTC().Add(time.Hour),
		DeletionState: "live",
	}
	providers.transcription = application.Transcription{
		Text:     "Hallo Kira",
		Language: "de-DE",
	}
	providers.responses = []application.StepResponse{{Text: "Guten Tag!"}}

	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.processTurn(context.Background(), application.Job{
		ID:        "40000000-0000-4000-8000-000000000001",
		Type:      "process_turn",
		SessionID: testSessionID,
		TurnID:    testTurnID,
		Attempts:  1,
	}); err != nil {
		t.Fatal(err)
	}

	turn := repository.turnSnapshot()
	if turn.State != application.TurnCompleted || turn.Transcript != "Hallo Kira" {
		t.Fatalf("unexpected completed turn: %+v", turn)
	}
	if _, exists := blobs.values[key]; exists {
		t.Fatal("raw input audio was retained after successful STT")
	}
	if state := repository.artifactSnapshot(repository.turn.InputArtifactID); state.DeletionState != "deleted" {
		t.Fatalf("raw artifact was not marked deleted: %+v", state)
	}
	assertEventOrder(t, repository.eventTypes(), []string{
		"stt.final",
		"agent.thinking",
		"agent.text.final",
		"tts.ready",
		"turn.completed",
	})
	durable, err := decodeState(repository.providerSnapshot().OpaqueState)
	if err != nil {
		t.Fatal(err)
	}
	if durable.Phase != phaseComplete {
		t.Fatalf("provider state did not reach complete: %+v", durable)
	}
}

func TestProcessTextTurnEmitsExactPipelineEventOrder(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	providers.responses = []application.StepResponse{{Text: "Hello back."}}

	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.processTurn(context.Background(), application.Job{
		ID:        "40000000-0000-4000-8000-000000000012",
		Type:      "process_turn",
		SessionID: testSessionID,
		TurnID:    testTurnID,
		Attempts:  1,
	}); err != nil {
		t.Fatal(err)
	}

	assertEventOrder(t, repository.eventTypes(), []string{
		"stt.final",
		"agent.thinking",
		"agent.text.final",
		"tts.ready",
		"turn.completed",
	})
	var final contract.STTFinalData
	if err := json.Unmarshal(repository.events[0].Data, &final); err != nil {
		t.Fatal(err)
	}
	if final.Text != "Hello" || final.Language != "en-US" {
		t.Fatalf("unexpected text-turn stt.final payload: %+v", final)
	}
}

func TestPhaseTTSRecoveryRestoresFinalTextBeforeSynthesis(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	repository.turn.State = application.TurnProcessing
	repository.providerState.OpaqueState = encodedTestState(t, persistedState{
		Version:      stateVersion,
		TurnID:       testTurnID,
		Phase:        phaseTTS,
		ResponseText: "Recovered final response.",
		Messages: []application.Message{
			{Role: "user", Content: "Hello"},
			{Role: "assistant", Content: "Recovered final response."},
		},
	})
	appendTestEvent(t, repository, "stt.final", json.RawMessage(
		`{"text":"Hello","language":"en-US"}`,
	))
	appendTestEvent(t, repository, "agent.thinking", json.RawMessage(`{}`))

	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.processTurn(context.Background(), application.Job{
		ID:        "40000000-0000-4000-8000-000000000014",
		Type:      "process_turn",
		SessionID: testSessionID,
		TurnID:    testTurnID,
		Attempts:  2,
	}); err != nil {
		t.Fatal(err)
	}

	assertEventOrder(t, repository.eventTypes(), []string{
		"stt.final",
		"agent.thinking",
		"agent.text.final",
		"tts.ready",
		"turn.completed",
	})
	turn := repository.turnSnapshot()
	if turn.ResponseText != "Recovered final response." {
		t.Fatalf("durable response was not recovered: %+v", turn)
	}
	if repository.setResponseSnapshots() != 1 {
		t.Fatalf("recovery wrote the turn response more than once: %d", repository.setResponseSnapshots())
	}
	if requests := providers.requestSnapshots(); len(requests) != 0 {
		t.Fatalf("phaseTTS recovery unexpectedly called the LLM: %d", len(requests))
	}
}

func TestPhaseTTSRetryDoesNotDuplicateFinalTextOrResponse(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	repository.turn.State = application.TurnProcessing
	repository.turn.ResponseText = "Already durable."
	repository.providerState.OpaqueState = encodedTestState(t, persistedState{
		Version:      stateVersion,
		TurnID:       testTurnID,
		Phase:        phaseTTS,
		ResponseText: "Already durable.",
		Messages: []application.Message{
			{Role: "user", Content: "Hello"},
			{Role: "assistant", Content: "Already durable."},
		},
	})
	appendTestEvent(t, repository, "stt.final", json.RawMessage(
		`{"text":"Hello","language":"en-US"}`,
	))
	appendTestEvent(t, repository, "agent.thinking", json.RawMessage(`{}`))
	appendTestEvent(t, repository, "agent.text.final", json.RawMessage(
		`{"text":"Already durable."}`,
	))

	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.processTurn(context.Background(), application.Job{
		ID:        "40000000-0000-4000-8000-000000000015",
		Type:      "process_turn",
		SessionID: testSessionID,
		TurnID:    testTurnID,
		Attempts:  2,
	}); err != nil {
		t.Fatal(err)
	}

	assertEventOrder(t, repository.eventTypes(), []string{
		"stt.final",
		"agent.thinking",
		"agent.text.final",
		"tts.ready",
		"turn.completed",
	})
	if repository.setResponseSnapshots() != 0 {
		t.Fatalf("retry rewrote an already durable response: %d", repository.setResponseSnapshots())
	}
}

func TestProcessTurnToolLoopUsesGatewayUUIDAndProviderCorrelation(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emotionManifest())
	repository.autoToolOutput = json.RawMessage(`{"applied":true}`)
	providers.responses = []application.StepResponse{
		{
			ToolCalls: []application.LLMStepToolCall{{
				ID:        "opaque-provider-call",
				Name:      "show_emotion",
				Arguments: json.RawMessage(`{"expression":"HAPPY"}`),
			}},
		},
		{Text: "You look happy."},
	}

	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.processTurn(context.Background(), application.Job{
		ID:        "40000000-0000-4000-8000-000000000002",
		Type:      "process_turn",
		SessionID: testSessionID,
		TurnID:    testTurnID,
		Attempts:  1,
	}); err != nil {
		t.Fatal(err)
	}

	calls := repository.toolSnapshots()
	if len(calls) != 1 {
		t.Fatalf("expected one tool call, got %d", len(calls))
	}
	if !contract.IsUUID(calls[0].ID) || calls[0].ID == "opaque-provider-call" {
		t.Fatalf("provider ID escaped onto gateway protocol: %+v", calls[0])
	}
	if calls[0].TimeoutMS != 5000 {
		t.Fatalf("tool timeout was not clamped to 5 seconds: %d", calls[0].TimeoutMS)
	}
	requests := providers.requestSnapshots()
	if len(requests) != 2 {
		t.Fatalf("expected two model steps, got %d", len(requests))
	}
	last := requests[1].Messages[len(requests[1].Messages)-1]
	if last.Role != "tool" || last.ToolCallID != "opaque-provider-call" {
		t.Fatalf("provider correlation was not preserved privately: %+v", last)
	}
	assertEventOrder(t, repository.eventTypes(), []string{
		"stt.final",
		"agent.thinking",
		"tool.call",
		"agent.text.final",
		"tts.ready",
		"turn.completed",
	})
}

func TestProcessTurnCancellationCancelsProviderContext(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	providerStarted := make(chan struct{})
	providerCancelled := make(chan struct{})
	providers.step = func(ctx context.Context, _ application.StepRequest) (application.StepResponse, error) {
		close(providerStarted)
		<-ctx.Done()
		close(providerCancelled)
		return application.StepResponse{}, ctx.Err()
	}
	worker := newTestWorker(t, repository, blobs, providers)

	result := make(chan error, 1)
	go func() {
		result <- worker.processTurn(context.Background(), application.Job{
			ID:        "40000000-0000-4000-8000-000000000003",
			Type:      "process_turn",
			SessionID: testSessionID,
			TurnID:    testTurnID,
			Attempts:  1,
		})
	}()
	select {
	case <-providerStarted:
	case <-time.After(time.Second):
		t.Fatal("provider step did not start")
	}
	repository.setTerminal(application.TurnCancelled)
	select {
	case <-providerCancelled:
	case <-time.After(time.Second):
		t.Fatal("provider context was not cancelled after durable turn cancellation")
	}
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) && !errors.Is(err, errTurnStopped) {
			t.Fatalf("unexpected cancellation result: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("worker did not stop the cancelled turn")
	}
}

func TestProcessTurnRejectsMalformedProviderText(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	providers.responses = []application.StepResponse{{Text: string([]byte{0xff})}}
	worker := newTestWorker(t, repository, blobs, providers)

	err := worker.processTurn(context.Background(), application.Job{
		ID:        "40000000-0000-4000-8000-000000000004",
		Type:      "process_turn",
		SessionID: testSessionID,
		TurnID:    testTurnID,
		Attempts:  1,
	})
	var processErr *processError
	if !errors.As(err, &processErr) || processErr.Code != "PROVIDER_MALFORMED_RESPONSE" {
		t.Fatalf("malformed output was not normalized safely: %v", err)
	}
	if repository.turnSnapshot().State.Terminal() {
		t.Fatal("processTurn directly committed a terminal state before job policy")
	}
}

func TestHandleJobRetriesAndResumesWithoutDuplicatingUserMessage(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	providers.errors = []error{&provider.Error{
		Provider:  "fake",
		Operation: "step",
		Kind:      provider.ErrorUnavailable,
		Message:   "upstream unavailable",
	}}
	providers.responses = []application.StepResponse{{Text: "Recovered."}}
	worker := newTestWorker(t, repository, blobs, providers)
	job := application.Job{
		ID:          "40000000-0000-4000-8000-000000000005",
		Type:        "process_turn",
		SessionID:   testSessionID,
		TurnID:      testTurnID,
		Attempts:    1,
		MaxAttempts: 3,
	}

	if err := worker.handleJob(context.Background(), job); err != nil {
		t.Fatal(err)
	}
	if repository.retryCount != 1 || repository.turnSnapshot().State.Terminal() {
		t.Fatalf("retry was not durably scheduled: retries=%d turn=%+v", repository.retryCount, repository.turnSnapshot())
	}
	job.Attempts = 2
	if err := worker.handleJob(context.Background(), job); err != nil {
		t.Fatal(err)
	}
	if repository.turnSnapshot().State != application.TurnCompleted || repository.completeCount != 1 {
		t.Fatalf("retry did not complete: turn=%+v completions=%d", repository.turnSnapshot(), repository.completeCount)
	}
	requests := providers.requestSnapshots()
	if len(requests) != 2 {
		t.Fatalf("unexpected provider attempts: %d", len(requests))
	}
	userMessages := 0
	for _, message := range requests[1].Messages {
		if message.Role == "user" {
			userMessages++
		}
	}
	if userMessages != 1 {
		t.Fatalf("restart duplicated user input: %+v", requests[1].Messages)
	}
}

func TestEnsureThreadIsPersistedBeforeStepAndReusedAfterRetry(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	providers.ensureThreadID = "codex-thread-persisted"
	providers.errors = []error{&provider.Error{
		Provider:  "codex",
		Operation: "turn/start",
		Kind:      provider.ErrorUnavailable,
		Message:   "app-server restarted",
	}}
	providers.responses = []application.StepResponse{{Text: "Recovered."}}
	providers.beforeStep = func(request application.StepRequest) error {
		if request.ThreadID != "codex-thread-persisted" {
			return errors.New("step did not receive the ensured thread id")
		}
		if repository.providerSnapshot().RemoteThreadID != request.ThreadID {
			return errors.New("thread id was not durable before Step")
		}
		return nil
	}
	worker := newTestWorker(t, repository, blobs, providers)
	job := application.Job{
		ID:          "40000000-0000-4000-8000-000000000013",
		Type:        "process_turn",
		SessionID:   testSessionID,
		TurnID:      testTurnID,
		Attempts:    1,
		MaxAttempts: 3,
	}

	if err := worker.handleJob(context.Background(), job); err != nil {
		t.Fatal(err)
	}
	if repository.retryCount != 1 {
		t.Fatalf("provider crash was not retried: %d", repository.retryCount)
	}
	job.Attempts = 2
	if err := worker.handleJob(context.Background(), job); err != nil {
		t.Fatal(err)
	}
	if providers.ensureThreadCalls != 1 {
		t.Fatalf("durable thread was recreated on retry: %d", providers.ensureThreadCalls)
	}
	requests := providers.requestSnapshots()
	if len(requests) != 2 {
		t.Fatalf("expected two Step attempts, got %d", len(requests))
	}
	for _, request := range requests {
		if request.ThreadID != "codex-thread-persisted" {
			t.Fatalf("Step used a non-durable thread id: %q", request.ThreadID)
		}
	}
}

func TestRetryExhaustionFailsTurnAndDeletesRawAudio(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	repository.retryFinal = true
	repository.turn.InputKind = "audio"
	repository.turn.InputText = ""
	repository.turn.InputArtifactID = "30000000-0000-4000-8000-000000000009"
	key := testSessionID + "/failed.wav"
	audio := []byte("raw-audio")
	blobs.values[key] = audio
	hash := sha256.Sum256(audio)
	repository.artifacts[repository.turn.InputArtifactID] = application.Artifact{
		ID:            repository.turn.InputArtifactID,
		SessionID:     testSessionID,
		TurnID:        testTurnID,
		Kind:          "input_audio",
		BlobKey:       key,
		MIMEType:      "audio/wav",
		ByteLength:    int64(len(audio)),
		SHA256:        hash[:],
		CreatedAt:     time.Now().UTC(),
		ExpiresAt:     time.Now().UTC().Add(time.Hour),
		DeletionState: "live",
	}
	providers.transcribeError = &provider.Error{
		Provider:  "fake",
		Operation: "transcribe",
		Kind:      provider.ErrorUnavailable,
		Message:   "upstream unavailable",
	}

	worker := newTestWorker(t, repository, blobs, providers)
	err := worker.handleJob(context.Background(), application.Job{
		ID:          "40000000-0000-4000-8000-000000000009",
		Type:        "process_turn",
		SessionID:   testSessionID,
		TurnID:      testTurnID,
		Attempts:    3,
		MaxAttempts: 3,
	})
	if err != nil {
		t.Fatal(err)
	}
	turn := repository.turnSnapshot()
	if turn.State != application.TurnFailed || repository.completeCount != 1 {
		t.Fatalf(
			"retry exhaustion left turn/job incomplete: turn=%+v completions=%d",
			turn,
			repository.completeCount,
		)
	}
	if _, exists := blobs.values[key]; exists {
		t.Fatal("retry-exhausted raw audio was not deleted")
	}
	events := repository.eventTypes()
	if len(events) != 1 || events[0] != "turn.error" {
		t.Fatalf("unexpected terminal events: %v", events)
	}
	var detail struct {
		Error struct {
			Code      string `json:"code"`
			Retryable bool   `json:"retryable"`
		} `json:"error"`
	}
	if err := json.Unmarshal(repository.events[0].Data, &detail); err != nil {
		t.Fatal(err)
	}
	if detail.Error.Code != "PROVIDER_UNAVAILABLE" || detail.Error.Retryable {
		t.Fatalf("unsafe retry exhaustion detail: %+v", detail)
	}
}

func TestFinalizationLeaseTerminalizesTurnAfterWorkerCrash(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	worker := newTestWorker(t, repository, blobs, providers)
	err := worker.handleJob(context.Background(), application.Job{
		ID:          "40000000-0000-4000-8000-000000000010",
		Type:        "process_turn",
		SessionID:   testSessionID,
		TurnID:      testTurnID,
		Attempts:    4,
		MaxAttempts: 3,
	})
	if err != nil {
		t.Fatal(err)
	}
	turn := repository.turnSnapshot()
	if turn.State != application.TurnFailed || repository.completeCount != 1 {
		t.Fatalf(
			"finalization lease did not finish turn/job: turn=%+v completions=%d",
			turn,
			repository.completeCount,
		)
	}
	if requests := providers.requestSnapshots(); len(requests) != 0 {
		t.Fatalf("finalization lease unexpectedly called provider: %d", len(requests))
	}
	var detail struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if err := json.Unmarshal(repository.events[0].Data, &detail); err != nil {
		t.Fatal(err)
	}
	if detail.Error.Code != "WORKER_RETRY_EXHAUSTED" {
		t.Fatalf("unexpected finalization error: %+v", detail)
	}
}

func TestProviderThreadSweeperDeletesBeforeMarkingDurableState(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	repository.providerLeases = []application.ProviderThreadLease{{
		SessionID:       testSessionID,
		ProviderKind:    "fake",
		ProviderProfile: "default",
		RemoteThreadID:  "thread-retained",
		LeaseOwner:      "worker-test",
		LeaseUntil:      time.Now().Add(time.Minute),
	}}
	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.SweepProviderThreadsOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := providers.deletedThreadSnapshots(); len(got) != 1 || got[0] != "thread-retained" {
		t.Fatalf("provider thread was not deleted: %v", got)
	}
	if repository.providerDeleted != testSessionID {
		t.Fatalf("provider cleanup was not marked durable: %q", repository.providerDeleted)
	}
}

func TestUntrackedProviderThreadSweeperDeletesOnlyOldUnknownThreads(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	now := time.Now().UTC()
	repository.knownProviderThreads = map[string]bool{"known-thread": true}
	providers.threadPages = map[string]application.ProviderThreadPage{
		"": {
			Threads: []application.ProviderThreadInfo{
				{ID: "known-thread", CreatedAt: now.Add(-time.Hour)},
				{ID: "orphan-thread", CreatedAt: now.Add(-time.Hour)},
				{ID: "new-thread", CreatedAt: now.Add(-time.Minute)},
			},
			NextCursor: "page-2",
		},
		"page-2": {
			Threads: []application.ProviderThreadInfo{
				{ID: "second-orphan", CreatedAt: now.Add(-time.Hour)},
			},
		},
	}
	worker := newTestWorker(t, repository, blobs, providers)

	if err := worker.SweepUntrackedProviderThreadsOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := providers.deletedThreadSnapshots(); len(got) != 1 || got[0] != "orphan-thread" {
		t.Fatalf("first bounded page deleted the wrong threads: %v", got)
	}
	if err := worker.SweepUntrackedProviderThreadsOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := providers.deletedThreadSnapshots(); len(got) != 2 ||
		got[1] != "second-orphan" {
		t.Fatalf("stable cursor did not reach the second page: %v", got)
	}
	if got := providers.listCursorSnapshots(); len(got) != 2 ||
		got[0] != "" || got[1] != "page-2" {
		t.Fatalf("unexpected provider catalog cursors: %v", got)
	}
}

func TestOrphanBlobSweeperDeletesOnlyObjectsWithoutMetadata(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	_ = providers
	blobs.values["orphan/file.mp3"] = []byte("orphan")
	blobs.values["retained/file.mp3"] = []byte("retained")
	repository.blobKeys["retained/file.mp3"] = true
	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.SweepOrphanBlobsOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, exists := blobs.values["orphan/file.mp3"]; exists {
		t.Fatal("committed orphan was not deleted")
	}
	if _, exists := blobs.values["retained/file.mp3"]; !exists {
		t.Fatal("artifact-backed blob was deleted")
	}
}

func TestDeleteArtifactJobDoesNotMutateTurn(t *testing.T) {
	repository, blobs, providers := newTestDependencies(t, emptyManifest())
	artifactID := "30000000-0000-4000-8000-000000000011"
	key := testSessionID + "/cancelled.wav"
	blobs.values[key] = []byte("cancelled-audio")
	repository.artifacts[artifactID] = application.Artifact{
		ID:            artifactID,
		SessionID:     testSessionID,
		TurnID:        testTurnID,
		Kind:          "input_audio",
		BlobKey:       key,
		MIMEType:      "audio/wav",
		ByteLength:    int64(len(blobs.values[key])),
		CreatedAt:     time.Now().Add(-time.Minute),
		ExpiresAt:     time.Now(),
		DeletionState: "live",
	}
	worker := newTestWorker(t, repository, blobs, providers)
	if err := worker.handleJob(context.Background(), application.Job{
		ID:          "40000000-0000-4000-8000-000000000011",
		Type:        "delete_artifact",
		SessionID:   testSessionID,
		TurnID:      testTurnID,
		Payload:     json.RawMessage(`{"artifactId":"` + artifactID + `"}`),
		Attempts:    1,
		MaxAttempts: 5,
	}); err != nil {
		t.Fatal(err)
	}
	if _, exists := blobs.values[key]; exists {
		t.Fatal("delete_artifact job retained bytes")
	}
	if repository.artifactSnapshot(artifactID).DeletionState != "deleted" {
		t.Fatal("delete_artifact job did not mark metadata")
	}
	if repository.turnSnapshot().State != application.TurnAccepted {
		t.Fatal("artifact cleanup job mutated the conversational turn")
	}
	if repository.completeCount != 1 {
		t.Fatalf("artifact cleanup job was not completed: %d", repository.completeCount)
	}
}

func newTestWorker(
	t *testing.T,
	repository *fakeRepository,
	blobs *fakeBlobStore,
	providers *fakeProviders,
) *Worker {
	t.Helper()
	value, err := New(repository, blobs, providers, Config{
		WorkerID:        "worker-test",
		LeaseDuration:   time.Second,
		ProviderTimeout: 200 * time.Millisecond,
		TurnPoll:        5 * time.Millisecond,
		ToolPoll:        time.Millisecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func newTestDependencies(
	t *testing.T,
	manifest json.RawMessage,
) (*fakeRepository, *fakeBlobStore, *fakeProviders) {
	t.Helper()
	now := time.Now().UTC()
	repository := &fakeRepository{
		session: application.Session{
			ID:              testSessionID,
			DeviceID:        testDeviceID,
			State:           application.SessionActive,
			ProviderKind:    "fake",
			ProviderProfile: "default",
			Context:         json.RawMessage(`{"robotName":"Kira","language":"en-US"}`),
			ToolManifest:    manifest,
			ExpiresAt:       now.Add(time.Hour),
		},
		turn: application.Turn{
			ID:         testTurnID,
			SessionID:  testSessionID,
			State:      application.TurnAccepted,
			InputKind:  "text",
			InputText:  "Hello",
			Language:   "en-US",
			AcceptedAt: now,
			UpdatedAt:  now,
		},
		providerState: application.ProviderState{
			SessionID:       testSessionID,
			ProviderKind:    "fake",
			ProviderProfile: "default",
			OpaqueState:     json.RawMessage(`{}`),
			UpdatedAt:       now,
		},
		tools:     make(map[string]application.ToolCall),
		artifacts: make(map[string]application.Artifact),
		blobKeys:  make(map[string]bool),
	}
	return repository, &fakeBlobStore{values: make(map[string][]byte)}, &fakeProviders{
		speech: application.Speech{
			Audio:    []byte("fake-mp3"),
			MIMEType: "audio/mpeg",
			Format:   "mp3",
		},
	}
}

func emptyManifest() json.RawMessage {
	return json.RawMessage(`{
		"protocolVersion":"1.0",
		"manifestVersion":"empty-1",
		"tools":[]
	}`)
}

func emotionManifest() json.RawMessage {
	return json.RawMessage(`{
		"protocolVersion":"1.0",
		"manifestVersion":"emotion-1",
		"tools":[{
			"name":"show_emotion",
			"owner":"web",
			"version":"1.0.0",
			"description":"Show an expression",
			"inputSchema":{
				"type":"object",
				"properties":{"expression":{"type":"string","enum":["HAPPY","DEFAULT"]}},
				"required":["expression"],
				"additionalProperties":false
			},
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
}

func assertEventOrder(t *testing.T, actual, expected []string) {
	t.Helper()
	if len(actual) != len(expected) {
		t.Fatalf("event count mismatch: got %v, want %v", actual, expected)
	}
	for index := range expected {
		if actual[index] != expected[index] {
			t.Fatalf("event order mismatch: got %v, want %v", actual, expected)
		}
	}
}

func encodedTestState(t *testing.T, state persistedState) json.RawMessage {
	t.Helper()
	value, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func appendTestEvent(
	t *testing.T,
	repository *fakeRepository,
	eventType string,
	data json.RawMessage,
) {
	t.Helper()
	_, err := repository.AppendEvent(context.Background(), application.AppendEventParams{
		SessionID: testSessionID,
		TurnID:    testTurnID,
		EventID:   newUUID(),
		Type:      eventType,
		Timestamp: time.Now().UTC(),
		Data:      data,
	})
	if err != nil {
		t.Fatal(err)
	}
}

type fakeProviders struct {
	mu                sync.Mutex
	responses         []application.StepResponse
	errors            []error
	requests          []application.StepRequest
	step              func(context.Context, application.StepRequest) (application.StepResponse, error)
	beforeStep        func(application.StepRequest) error
	ensureThreadID    string
	ensureThreadCalls int
	transcription     application.Transcription
	transcribeError   error
	speech            application.Speech
	deletedThreads    []string
	threadPages       map[string]application.ProviderThreadPage
	listCursors       []string
}

func (providers *fakeProviders) ForSession(context.Context, application.Session) (application.ProviderSet, error) {
	set := application.ProviderSet{
		LLM:           providers,
		Transcriber:   providers,
		Synthesizer:   providers,
		ThreadDeleter: providers,
		Model:         "fake-model",
		STTModel:      "fake-stt",
		TTSModel:      "fake-tts",
		Voice:         "fake-voice",
	}
	if providers.ensureThreadID != "" {
		set.ThreadEnsurer = providers
	}
	return set, nil
}

func (providers *fakeProviders) Step(
	ctx context.Context,
	request application.StepRequest,
) (application.StepResponse, error) {
	providers.mu.Lock()
	providers.requests = append(providers.requests, request)
	beforeStep := providers.beforeStep
	custom := providers.step
	if beforeStep != nil {
		if err := beforeStep(request); err != nil {
			providers.mu.Unlock()
			return application.StepResponse{}, err
		}
	}
	if custom == nil && len(providers.errors) > 0 {
		err := providers.errors[0]
		providers.errors = providers.errors[1:]
		providers.mu.Unlock()
		return application.StepResponse{}, err
	}
	if custom == nil {
		if len(providers.responses) == 0 {
			providers.mu.Unlock()
			return application.StepResponse{}, errors.New("no fake response")
		}
		response := providers.responses[0]
		providers.responses = providers.responses[1:]
		providers.mu.Unlock()
		return response, nil
	}
	providers.mu.Unlock()
	return custom(ctx, request)
}

func (providers *fakeProviders) EnsureThread(
	_ context.Context,
	_ application.StepRequest,
) (string, error) {
	providers.mu.Lock()
	defer providers.mu.Unlock()
	providers.ensureThreadCalls++
	return providers.ensureThreadID, nil
}

func (providers *fakeProviders) Transcribe(
	context.Context,
	application.TranscriptionRequest,
) (application.Transcription, error) {
	return providers.transcription, providers.transcribeError
}

func (providers *fakeProviders) Synthesize(
	context.Context,
	application.SpeechRequest,
) (application.Speech, error) {
	return providers.speech, nil
}

func (providers *fakeProviders) DeleteThread(_ context.Context, threadID string) error {
	providers.mu.Lock()
	providers.deletedThreads = append(providers.deletedThreads, threadID)
	providers.mu.Unlock()
	return nil
}

func (providers *fakeProviders) ThreadDeleterForKind(
	context.Context,
	string,
) (application.ThreadDeleter, error) {
	return providers, nil
}

func (providers *fakeProviders) ThreadListerForKind(
	context.Context,
	string,
) (application.ThreadLister, error) {
	return providers, nil
}

func (providers *fakeProviders) ListThreads(
	_ context.Context,
	cursor string,
	_ int,
) (application.ProviderThreadPage, error) {
	providers.mu.Lock()
	defer providers.mu.Unlock()
	providers.listCursors = append(providers.listCursors, cursor)
	return providers.threadPages[cursor], nil
}

func (providers *fakeProviders) requestSnapshots() []application.StepRequest {
	providers.mu.Lock()
	defer providers.mu.Unlock()
	return append([]application.StepRequest(nil), providers.requests...)
}

func (providers *fakeProviders) deletedThreadSnapshots() []string {
	providers.mu.Lock()
	defer providers.mu.Unlock()
	return append([]string(nil), providers.deletedThreads...)
}

func (providers *fakeProviders) listCursorSnapshots() []string {
	providers.mu.Lock()
	defer providers.mu.Unlock()
	return append([]string(nil), providers.listCursors...)
}

type fakeBlobStore struct {
	mu     sync.Mutex
	values map[string][]byte
}

func (store *fakeBlobStore) Put(
	_ context.Context,
	key string,
	reader io.Reader,
	maximum int64,
) (blob.Object, error) {
	value, err := io.ReadAll(io.LimitReader(reader, maximum+1))
	if err != nil {
		return blob.Object{}, err
	}
	if int64(len(value)) > maximum {
		return blob.Object{}, blob.ErrTooLarge
	}
	store.mu.Lock()
	store.values[key] = append([]byte(nil), value...)
	store.mu.Unlock()
	digest := sha256.Sum256(value)
	return blob.Object{Key: key, Size: int64(len(value)), SHA256: digest, CreatedAt: time.Now().UTC()}, nil
}

func (store *fakeBlobStore) Open(
	_ context.Context,
	key string,
) (io.ReadCloser, blob.Object, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	value, exists := store.values[key]
	if !exists {
		return nil, blob.Object{}, blob.ErrNotFound
	}
	copy := append([]byte(nil), value...)
	digest := sha256.Sum256(copy)
	return io.NopCloser(bytes.NewReader(copy)), blob.Object{
		Key: key, Size: int64(len(copy)), SHA256: digest, CreatedAt: time.Now().UTC(),
	}, nil
}

func (store *fakeBlobStore) Delete(_ context.Context, key string) error {
	store.mu.Lock()
	delete(store.values, key)
	store.mu.Unlock()
	return nil
}

func (store *fakeBlobStore) ListFinalObjects(
	_ context.Context,
	_ time.Time,
	limit int,
) ([]blob.Object, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	result := make([]blob.Object, 0, len(store.values))
	for key, value := range store.values {
		digest := sha256.Sum256(value)
		result = append(result, blob.Object{
			Key:       key,
			Size:      int64(len(value)),
			SHA256:    digest,
			CreatedAt: time.Now().Add(-time.Hour),
		})
		if len(result) == limit {
			break
		}
	}
	return result, nil
}

type fakeRepository struct {
	mu sync.Mutex

	session       application.Session
	turn          application.Turn
	providerState application.ProviderState
	events        []application.Event
	tools         map[string]application.ToolCall
	artifacts     map[string]application.Artifact
	blobKeys      map[string]bool

	autoToolOutput       json.RawMessage
	retryCount           int
	retryFinal           bool
	completeCount        int
	providerLeases       []application.ProviderThreadLease
	providerDeleted      string
	knownProviderThreads map[string]bool
	setResponseCount     int
}

func (repo *fakeRepository) GetSessionForWorker(context.Context, string) (application.Session, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.session, nil
}

func (repo *fakeRepository) GetTurn(context.Context, string, string) (application.Turn, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.turn, nil
}

func (repo *fakeRepository) SetTurnState(
	_ context.Context,
	_, _ string,
	state application.TurnState,
	now time.Time,
) (application.Turn, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	if repo.turn.State.Terminal() {
		return application.Turn{}, application.ErrTurnTerminal
	}
	repo.turn.State = state
	repo.turn.UpdatedAt = now
	return repo.turn, nil
}

func (repo *fakeRepository) SetTurnTranscript(
	_ context.Context,
	_, _, transcript string,
	now time.Time,
) (application.Turn, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	repo.turn.Transcript = transcript
	repo.turn.UpdatedAt = now
	return repo.turn, nil
}

func (repo *fakeRepository) SetTurnResponse(
	_ context.Context,
	_, _, response string,
	now time.Time,
) (application.Turn, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	repo.setResponseCount++
	repo.turn.ResponseText = response
	repo.turn.UpdatedAt = now
	return repo.turn, nil
}

func (repo *fakeRepository) FinishTurn(
	_ context.Context,
	_, _ string,
	state application.TurnState,
	response string,
	errorDetail json.RawMessage,
	eventType string,
	eventData json.RawMessage,
	now time.Time,
) (application.Turn, bool, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	if repo.turn.State.Terminal() {
		return repo.turn, false, nil
	}
	repo.turn.State = state
	repo.turn.ResponseText = response
	repo.turn.Error = errorDetail
	repo.turn.UpdatedAt = now
	repo.turn.TerminalAt = &now
	repo.appendEventLocked(eventType, repo.turn.ID, eventData, now)
	return repo.turn, true, nil
}

func (repo *fakeRepository) AppendEvent(
	_ context.Context,
	params application.AppendEventParams,
) (application.Event, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	if repo.turn.State.Terminal() {
		return application.Event{}, application.ErrTurnTerminal
	}
	return repo.appendEventLocked(params.Type, params.TurnID, params.Data, params.Timestamp), nil
}

func (repo *fakeRepository) appendEventLocked(
	eventType, turnID string,
	data json.RawMessage,
	now time.Time,
) application.Event {
	repo.session.LastSequence++
	id := newUUID()
	value := application.Event{
		ProtocolVersion: application.ProtocolVersion,
		EventID:         id,
		Sequence:        repo.session.LastSequence,
		SessionID:       repo.session.ID,
		Type:            eventType,
		Timestamp:       now,
		Data:            append(json.RawMessage(nil), data...),
	}
	if turnID != "" {
		copy := turnID
		value.TurnID = &copy
	}
	repo.events = append(repo.events, value)
	return value
}

func (repo *fakeRepository) ListEvents(
	_ context.Context,
	_, _ string,
	after uint64,
	limit int,
) ([]application.Event, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	var result []application.Event
	for _, event := range repo.events {
		if event.Sequence > after {
			result = append(result, event)
			if len(result) == limit {
				break
			}
		}
	}
	return result, nil
}

func (repo *fakeRepository) CreateToolCall(
	_ context.Context,
	params application.CreateToolCallParams,
) (application.ToolCall, application.Event, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	call := params.ToolCall
	call.Status = application.ToolPending
	if len(repo.autoToolOutput) > 0 {
		call.Status = application.ToolSucceeded
		call.Output = append(json.RawMessage(nil), repo.autoToolOutput...)
		now := call.CreatedAt
		call.TerminalAt = &now
	}
	repo.tools[call.ID] = call
	repo.turn.State = application.TurnWaitingForTool
	event := repo.appendEventLocked("tool.call", call.TurnID, params.EventData, call.CreatedAt)
	return call, event, nil
}

func (repo *fakeRepository) GetToolCall(
	_ context.Context,
	_, callID string,
) (application.ToolCall, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	call, exists := repo.tools[callID]
	if !exists {
		return application.ToolCall{}, application.ErrNotFound
	}
	return call, nil
}

func (repo *fakeRepository) UpdateToolCall(
	_ context.Context,
	_, callID string,
	status application.ToolStatus,
	output, errorDetail json.RawMessage,
	now time.Time,
) (application.ToolCall, bool, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	call, exists := repo.tools[callID]
	if !exists {
		return application.ToolCall{}, false, application.ErrNotFound
	}
	if call.Status.Terminal() {
		return application.ToolCall{}, false, application.ErrToolTerminal
	}
	call.Status = status
	call.Output = output
	call.Error = errorDetail
	call.UpdatedAt = now
	if status.Terminal() {
		call.TerminalAt = &now
	}
	repo.tools[callID] = call
	return call, true, nil
}

func (repo *fakeRepository) ExpireToolCalls(context.Context, time.Time, int) ([]application.ToolCall, error) {
	return nil, nil
}

func (repo *fakeRepository) CreateArtifact(
	_ context.Context,
	artifact application.Artifact,
) (application.Artifact, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	artifact.DeletionState = "live"
	repo.artifacts[artifact.ID] = artifact
	return artifact, nil
}

func (repo *fakeRepository) GetArtifact(
	_ context.Context,
	_, _, artifactID string,
	now time.Time,
) (application.Artifact, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	artifact, exists := repo.artifacts[artifactID]
	if !exists {
		return application.Artifact{}, application.ErrNotFound
	}
	if artifact.DeletionState != "live" || !artifact.ExpiresAt.After(now) {
		return application.Artifact{}, application.ErrArtifactGone
	}
	return artifact, nil
}

func (repo *fakeRepository) LeaseArtifactForDeletion(
	_ context.Context,
	artifactID, owner string,
	now time.Time,
	lease time.Duration,
) (application.ArtifactLease, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	artifact, exists := repo.artifacts[artifactID]
	if !exists || artifact.DeletionState != "live" {
		return application.ArtifactLease{}, application.ErrNotFound
	}
	until := now.Add(lease)
	artifact.DeletionState = "deleting"
	artifact.DeletionLeaseOwner = owner
	artifact.DeletionLeaseUntil = &until
	repo.artifacts[artifactID] = artifact
	return application.ArtifactLease{Artifact: artifact}, nil
}

func (repo *fakeRepository) LeaseExpiredArtifacts(
	context.Context,
	string,
	time.Time,
	time.Duration,
	int,
) ([]application.ArtifactLease, error) {
	return nil, nil
}

func (repo *fakeRepository) MarkArtifactDeleted(
	_ context.Context,
	artifactID, owner string,
	now time.Time,
) error {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	artifact := repo.artifacts[artifactID]
	if artifact.DeletionLeaseOwner != owner {
		return application.ErrLeaseLost
	}
	artifact.DeletionState = "deleted"
	artifact.DeletionLeaseOwner = ""
	artifact.DeletedAt = &now
	repo.artifacts[artifactID] = artifact
	return nil
}

func (repo *fakeRepository) ReleaseArtifactLease(
	_ context.Context,
	artifactID, owner string,
) error {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	artifact := repo.artifacts[artifactID]
	if artifact.DeletionLeaseOwner != owner {
		return application.ErrLeaseLost
	}
	artifact.DeletionState = "live"
	artifact.DeletionLeaseOwner = ""
	repo.artifacts[artifactID] = artifact
	return nil
}

func (repo *fakeRepository) BlobKeyExists(_ context.Context, key string) (bool, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	if repo.blobKeys[key] {
		return true, nil
	}
	for _, artifact := range repo.artifacts {
		if artifact.BlobKey == key {
			return true, nil
		}
	}
	return false, nil
}

func (repo *fakeRepository) GetProviderState(
	context.Context,
	string,
) (application.ProviderState, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.providerState, nil
}

func (repo *fakeRepository) PutProviderState(
	_ context.Context,
	state application.ProviderState,
) error {
	repo.mu.Lock()
	repo.providerState = state
	repo.mu.Unlock()
	return nil
}

func (repo *fakeRepository) LeaseProviderThreadsForDeletion(
	context.Context,
	string,
	time.Time,
	time.Time,
	time.Duration,
	int,
) ([]application.ProviderThreadLease, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	result := append([]application.ProviderThreadLease(nil), repo.providerLeases...)
	repo.providerLeases = nil
	return result, nil
}

func (repo *fakeRepository) MarkProviderThreadDeleted(
	_ context.Context,
	sessionID string,
	_ string,
	_ time.Time,
) error {
	repo.mu.Lock()
	repo.providerDeleted = sessionID
	repo.mu.Unlock()
	return nil
}

func (repo *fakeRepository) ReleaseProviderThreadLease(
	context.Context,
	string,
	string,
) error {
	return nil
}

func (repo *fakeRepository) ProviderThreadKnown(
	_ context.Context,
	threadID string,
) (bool, error) {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.providerState.RemoteThreadID == threadID || repo.knownProviderThreads[threadID], nil
}

func (repo *fakeRepository) LeaseJobs(
	context.Context,
	string,
	time.Time,
	time.Duration,
	int,
) ([]application.Job, error) {
	return nil, nil
}

func (repo *fakeRepository) CompleteJob(context.Context, string, string, time.Time) error {
	repo.mu.Lock()
	repo.completeCount++
	repo.mu.Unlock()
	return nil
}

func (repo *fakeRepository) RetryJob(
	context.Context,
	string,
	string,
	time.Time,
	time.Time,
	string,
) (bool, error) {
	repo.mu.Lock()
	repo.retryCount++
	final := repo.retryFinal
	repo.mu.Unlock()
	return final, nil
}

func (repo *fakeRepository) WithSessionLock(
	ctx context.Context,
	_ string,
	action func(context.Context) error,
) error {
	return action(ctx)
}

func (repo *fakeRepository) ExpireSessions(context.Context, time.Time, int) ([]application.Session, error) {
	return nil, nil
}

func (repo *fakeRepository) Prune(
	context.Context,
	time.Time,
	time.Duration,
	time.Duration,
	int,
) (application.PruneResult, error) {
	return application.PruneResult{}, nil
}

func (repo *fakeRepository) turnSnapshot() application.Turn {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.turn
}

func (repo *fakeRepository) providerSnapshot() application.ProviderState {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.providerState
}

func (repo *fakeRepository) setResponseSnapshots() int {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.setResponseCount
}

func (repo *fakeRepository) artifactSnapshot(id string) application.Artifact {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	return repo.artifacts[id]
}

func (repo *fakeRepository) toolSnapshots() []application.ToolCall {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	result := make([]application.ToolCall, 0, len(repo.tools))
	for _, call := range repo.tools {
		result = append(result, call)
	}
	return result
}

func (repo *fakeRepository) eventTypes() []string {
	repo.mu.Lock()
	defer repo.mu.Unlock()
	result := make([]string, 0, len(repo.events))
	for _, event := range repo.events {
		result = append(result, event.Type)
	}
	return result
}

func (repo *fakeRepository) setTerminal(state application.TurnState) {
	repo.mu.Lock()
	now := time.Now().UTC()
	repo.turn.State = state
	repo.turn.TerminalAt = &now
	repo.mu.Unlock()
}

var _ Repository = (*fakeRepository)(nil)
var _ blob.Store = (*fakeBlobStore)(nil)
var _ blob.FinalObjectLister = (*fakeBlobStore)(nil)
var _ application.Providers = (*fakeProviders)(nil)
var _ application.LLM = (*fakeProviders)(nil)
var _ application.Transcriber = (*fakeProviders)(nil)
var _ application.Synthesizer = (*fakeProviders)(nil)
var _ application.ThreadEnsurer = (*fakeProviders)(nil)
var _ application.ThreadDeleter = (*fakeProviders)(nil)
var _ application.ThreadLister = (*fakeProviders)(nil)
