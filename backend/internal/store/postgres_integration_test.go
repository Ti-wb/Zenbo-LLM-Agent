package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	migrationassets "github.com/Ti-wb/Zenbo-LLM-Agent/backend/migrations"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
)

func TestPostgresIntegration(t *testing.T) {
	databaseURL := os.Getenv("GATEWAY_TEST_DATABASE_URL")
	if databaseURL == "" {
		databaseURL = os.Getenv("TEST_DATABASE_URL")
	}
	if databaseURL == "" {
		t.Skip("set GATEWAY_TEST_DATABASE_URL or TEST_DATABASE_URL to run PostgreSQL integration tests")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, schema := integrationPool(t, ctx, databaseURL)
	store := New(pool)
	t.Cleanup(store.events.Close)

	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	digest := sha256.Sum256([]byte("device-token"))
	device, err := store.IssueDevice(ctx, digest[:], "integration", now)
	if err != nil {
		t.Fatal(err)
	}
	device, err = store.AuthenticateAndBindDevice(ctx, digest[:], "integration-device", now)
	if err != nil {
		t.Fatal(err)
	}

	t.Run("concurrent cancellation idempotency replays committed response", func(t *testing.T) {
		session := createIntegrationSession(
			t, ctx, store, device,
			"01000000-0000-4000-8000-000000000001",
			"02000000-0000-4000-8000-000000000001",
			now.Add(-3*time.Second),
		)
		turn := createIntegrationTurn(
			t, ctx, store, device, session.ID,
			"03000000-0000-4000-8000-000000000001",
			"04000000-0000-4000-8000-000000000001",
			"05000000-0000-4000-8000-000000000001",
			now.Add(-2*time.Second),
		)
		requestDigest := sha256.Sum256([]byte("concurrent-cancel"))
		params := application.CancelTurnParams{
			SessionID:        session.ID,
			TurnID:           turn.ID,
			Device:           device,
			IdempotencyKey:   "06000000-0000-4000-8000-000000000001",
			RequestDigest:    requestDigest[:],
			IdempotencyUntil: now.Add(24 * time.Hour),
			Reason:           "client_request",
			Now:              now.Add(-time.Second),
		}
		type result struct {
			turnID   string
			changed  bool
			replayed bool
			err      error
		}
		const callers = 4
		start := make(chan struct{})
		results := make(chan result, callers)
		var ready sync.WaitGroup
		ready.Add(callers)
		for range callers {
			go func() {
				ready.Done()
				<-start
				got, changed, replayed, err := store.CancelTurn(ctx, params)
				results <- result{
					turnID: got.ID, changed: changed, replayed: replayed, err: err,
				}
			}()
		}
		ready.Wait()
		close(start)

		changedCount := 0
		replayedCount := 0
		for range callers {
			result := <-results
			if result.err != nil {
				t.Fatalf("concurrent cancellation failed: %v", result.err)
			}
			if result.turnID != turn.ID {
				t.Fatalf("turn ID = %q, want %q", result.turnID, turn.ID)
			}
			if result.changed {
				changedCount++
			}
			if result.replayed {
				replayedCount++
			}
		}
		if changedCount != 1 || replayedCount != callers-1 {
			t.Fatalf(
				"changed = %d, replayed = %d; want 1 and %d",
				changedCount, replayedCount, callers-1,
			)
		}
	})

	first := createIntegrationSession(
		t, ctx, store, device,
		"10000000-0000-4000-8000-000000000001",
		"20000000-0000-4000-8000-000000000001",
		now,
	)
	firstTurn := createIntegrationTurn(
		t, ctx, store, device, first.ID,
		"30000000-0000-4000-8000-000000000001",
		"40000000-0000-4000-8000-000000000001",
		"50000000-0000-4000-8000-000000000001",
		now.Add(time.Second),
	)

	t.Run("sequence allocation and terminal CAS", func(t *testing.T) {
		completed, changed, err := store.FinishTurn(
			ctx, first.ID, firstTurn.ID, application.TurnCompleted,
			"done", nil, "turn.completed", json.RawMessage(`{}`),
			now.Add(2*time.Second),
		)
		if err != nil {
			t.Fatal(err)
		}
		if !changed || completed.State != application.TurnCompleted {
			t.Fatalf("first finish = changed %v state %s", changed, completed.State)
		}
		_, changed, err = store.FinishTurn(
			ctx, first.ID, firstTurn.ID, application.TurnCompleted,
			"done", nil, "turn.completed", json.RawMessage(`{}`),
			now.Add(3*time.Second),
		)
		if err != nil || changed {
			t.Fatalf("duplicate finish = changed %v err %v", changed, err)
		}
		events, err := store.ListEvents(ctx, device.DeviceID, first.ID, 0, 20)
		if err != nil {
			t.Fatal(err)
		}
		if len(events) != 2 || events[0].Sequence != 1 ||
			events[1].Sequence != 2 || events[1].Type != "turn.completed" {
			t.Fatalf("events = %#v", events)
		}
	})

	replacedTurn := createIntegrationTurn(
		t, ctx, store, device, first.ID,
		"30000000-0000-4000-8000-000000000002",
		"40000000-0000-4000-8000-000000000002",
		"50000000-0000-4000-8000-000000000002",
		now.Add(4*time.Second),
	)
	_ = replacedTurn
	second := createIntegrationSession(
		t, ctx, store, device,
		"10000000-0000-4000-8000-000000000002",
		"20000000-0000-4000-8000-000000000002",
		now.Add(5*time.Second),
	)

	t.Run("new session replacement terminal ordering", func(t *testing.T) {
		events, err := store.ListEvents(ctx, device.DeviceID, first.ID, 2, 20)
		if err != nil {
			t.Fatal(err)
		}
		got := make([]string, len(events))
		for index, event := range events {
			got[index] = event.Type
		}
		want := []string{"turn.accepted", "turn.cancelled", "session.closed"}
		if strings.Join(got, ",") != strings.Join(want, ",") {
			t.Fatalf("replacement event order = %v, want %v", got, want)
		}
	})

	activeTurn := createIntegrationTurn(
		t, ctx, store, device, second.ID,
		"30000000-0000-4000-8000-000000000003",
		"40000000-0000-4000-8000-000000000003",
		"50000000-0000-4000-8000-000000000003",
		now.Add(6*time.Second),
	)

	t.Run("job lease recovers after worker crash", func(t *testing.T) {
		firstLease, err := store.LeaseJobs(ctx, "worker-a", now.Add(7*time.Second), time.Second, 1)
		if err != nil || len(firstLease) != 1 {
			t.Fatalf("first lease = %#v err %v", firstLease, err)
		}
		secondLease, err := store.LeaseJobs(ctx, "worker-b", now.Add(9*time.Second), time.Minute, 1)
		if err != nil || len(secondLease) != 1 {
			t.Fatalf("recovered lease = %#v err %v", secondLease, err)
		}
		if secondLease[0].ID != firstLease[0].ID ||
			secondLease[0].Attempts != firstLease[0].Attempts+1 {
			t.Fatalf("recovered job = %#v, first %#v", secondLease[0], firstLease[0])
		}
	})

	t.Run("last attempt crash is leased for terminal finalization", func(t *testing.T) {
		jobID := "90000000-0000-4000-8000-000000000001"
		job, _, err := store.EnqueueJob(ctx, application.Job{
			ID:          jobID,
			Type:        "finalization-test",
			DedupeKey:   "finalization-test",
			MaxAttempts: 1,
			AvailableAt: now.Add(-time.Hour),
			CreatedAt:   now.Add(-time.Hour),
		})
		if err != nil {
			t.Fatal(err)
		}
		firstLease, err := store.LeaseJobs(
			ctx, "last-attempt-worker", now.Add(2*time.Hour), time.Second, 1,
		)
		if err != nil || len(firstLease) != 1 || firstLease[0].ID != job.ID ||
			firstLease[0].Attempts != 1 {
			t.Fatalf("last attempt lease = %#v err %v", firstLease, err)
		}
		finalization, err := store.LeaseJobs(
			ctx, "finalizer-worker", now.Add(2*time.Hour+2*time.Second), time.Minute, 1,
		)
		if err != nil || len(finalization) != 1 ||
			finalization[0].ID != job.ID ||
			finalization[0].Attempts <= finalization[0].MaxAttempts {
			t.Fatalf("finalization lease = %#v err %v", finalization, err)
		}
		if err := store.CompleteJob(
			ctx, job.ID, "finalizer-worker", now.Add(2*time.Hour+3*time.Second),
		); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("late tool result durably becomes timeout", func(t *testing.T) {
		callID := "70000000-0000-4000-8000-000000000001"
		deadline := now.Add(12 * time.Second)
		eventData, _ := json.Marshal(map[string]any{
			"callId": callID, "toolName": "show_emotion",
			"toolVersion": "1.0.0", "arguments": map[string]any{},
			"timeoutMs":  5000,
			"deadlineAt": deadline.Format("2006-01-02T15:04:05.000Z"),
		})
		if _, _, err := store.CreateToolCall(ctx, application.CreateToolCallParams{
			ToolCall: application.ToolCall{
				ID: callID, SessionID: second.ID, TurnID: activeTurn.ID,
				Name: "show_emotion", Version: "1.0.0", Owner: "web",
				SideEffect: "ui", Arguments: json.RawMessage(`{}`),
				DeadlineAt: deadline, TimeoutMS: 5000,
				CreatedAt: now.Add(11 * time.Second),
			},
			EventID:   "71000000-0000-4000-8000-000000000001",
			EventData: eventData,
		}); err != nil {
			t.Fatal(err)
		}
		_, _, err := store.UpdateToolCall(
			ctx, second.ID, callID, application.ToolSucceeded,
			json.RawMessage(`{"applied":true}`), nil,
			deadline.Add(time.Millisecond),
		)
		if !errors.Is(err, application.ErrToolTerminal) {
			t.Fatalf("late result error = %v", err)
		}
		call, err := store.GetToolCall(ctx, second.ID, callID)
		if err != nil {
			t.Fatal(err)
		}
		if call.Status != application.ToolFailed ||
			!strings.Contains(string(call.Error), `"TIMEOUT"`) {
			t.Fatalf("late call = %#v", call)
		}
	})

	t.Run("replay live handoff and listener fanout have no gap", func(t *testing.T) {
		window, err := store.ReplayWindow(ctx, device.DeviceID, second.ID, 0, 20)
		if err != nil {
			t.Fatal(err)
		}
		cursor := window.CurrentSequence
		const waiterCount = 32
		waitResults := make(chan error, waiterCount)
		for range waiterCount {
			go func() {
				waitResults <- store.WaitForEvent(ctx, second.ID, cursor)
			}()
		}
		deadline := time.Now().Add(2 * time.Second)
		for store.events.subscriberCount(second.ID) != waiterCount &&
			time.Now().Before(deadline) {
			time.Sleep(time.Millisecond)
		}
		if count := store.events.subscriberCount(second.ID); count != waiterCount {
			t.Fatalf("registered waiters = %d, want %d", count, waiterCount)
		}
		// The shared listener is the only long-lived pool checkout regardless
		// of WebSocket fan-out.
		connectionDeadline := time.Now().Add(2 * time.Second)
		for pool.Stat().AcquiredConns() > 1 &&
			time.Now().Before(connectionDeadline) {
			time.Sleep(time.Millisecond)
		}
		if acquired := pool.Stat().AcquiredConns(); acquired > 1 {
			t.Fatalf("fan-out held %d database connections, want at most 1", acquired)
		}
		event, err := store.AppendEvent(ctx, application.AppendEventParams{
			SessionID: second.ID,
			TurnID:    activeTurn.ID,
			Type:      "agent.thinking",
			Timestamp: now.Add(10 * time.Second),
			Data:      json.RawMessage(`{}`),
		})
		if err != nil {
			t.Fatal(err)
		}
		for range waiterCount {
			if err := <-waitResults; err != nil {
				t.Fatal(err)
			}
		}
		live, err := store.ListEvents(ctx, device.DeviceID, second.ID, cursor, 20)
		if err != nil {
			t.Fatal(err)
		}
		if len(live) != 1 || live[0].Sequence != cursor+1 ||
			live[0].EventID != event.EventID {
			t.Fatalf("live events = %#v", live)
		}

		// If the durable event commits before a waiter subscribes (including
		// while LISTEN reconnects), the initial sequence check closes the gap.
		replayCursor := event.Sequence
		committed, err := store.AppendEvent(ctx, application.AppendEventParams{
			SessionID: second.ID,
			TurnID:    activeTurn.ID,
			Type:      "agent.thinking",
			Timestamp: now.Add(10*time.Second + time.Millisecond),
			Data:      json.RawMessage(`{}`),
		})
		if err != nil {
			t.Fatal(err)
		}
		catchupContext, cancelCatchup := context.WithTimeout(ctx, 250*time.Millisecond)
		defer cancelCatchup()
		if err := store.WaitForEvent(
			catchupContext, second.ID, replayCursor,
		); err != nil {
			t.Fatalf("replay/listener catch-up = %v", err)
		}
		if committed.Sequence != replayCursor+1 {
			t.Fatalf(
				"committed sequence = %d, want %d",
				committed.Sequence, replayCursor+1,
			)
		}
	})

	t.Run("prune retains active turn history", func(t *testing.T) {
		old, err := store.AppendEvent(ctx, application.AppendEventParams{
			SessionID: second.ID,
			TurnID:    activeTurn.ID,
			Type:      "agent.thinking",
			Timestamp: now.Add(-8 * 24 * time.Hour),
			Data:      json.RawMessage(`{}`),
		})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := store.Prune(
			ctx, now, 7*24*time.Hour, 24*time.Hour, 1000,
		); err != nil {
			t.Fatal(err)
		}
		var retained bool
		if err := pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM events WHERE event_id = $1)`,
			old.EventID).Scan(&retained); err != nil {
			t.Fatal(err)
		}
		if !retained {
			t.Fatal("active-turn event was pruned")
		}
	})

	t.Run("artifact deletion lease can be recovered", func(t *testing.T) {
		artifact, err := store.CreateArtifact(ctx, application.Artifact{
			ID:         "60000000-0000-4000-8000-000000000001",
			SessionID:  second.ID,
			TurnID:     activeTurn.ID,
			Kind:       "tts_audio",
			BlobKey:    "integration/artifact.wav",
			MIMEType:   "audio/wav",
			ByteLength: 4,
			SHA256:     digest[:],
			CreatedAt:  now,
			ExpiresAt:  now.Add(time.Hour),
		})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := store.LeaseArtifactForDeletion(
			ctx, artifact.ID, "cleaner-a", now, time.Second,
		); err != nil {
			t.Fatal(err)
		}
		recovered, err := store.LeaseExpiredArtifacts(
			ctx, "cleaner-b", now.Add(2*time.Second), time.Minute, 10,
		)
		if err != nil {
			t.Fatal(err)
		}
		if len(recovered) != 1 || recovered[0].ID != artifact.ID ||
			recovered[0].DeletionLeaseOwner != "cleaner-b" {
			t.Fatalf("recovered artifacts = %#v", recovered)
		}
	})

	t.Run("terminal turn rejects late nonterminal event", func(t *testing.T) {
		requestDigest := sha256.Sum256([]byte("cancel-active-turn"))
		_, changed, _, err := store.CancelTurn(ctx, application.CancelTurnParams{
			SessionID:        second.ID,
			TurnID:           activeTurn.ID,
			Device:           device,
			IdempotencyKey:   "80000000-0000-4000-8000-000000000001",
			RequestDigest:    requestDigest[:],
			IdempotencyUntil: now.Add(24 * time.Hour),
			Reason:           "client_request",
			Now:              now.Add(20 * time.Second),
		})
		if err != nil || !changed {
			t.Fatalf("cancel changed=%v err=%v", changed, err)
		}
		before, err := store.GetSession(ctx, device.DeviceID, second.ID)
		if err != nil {
			t.Fatal(err)
		}
		_, err = store.AppendEvent(ctx, application.AppendEventParams{
			SessionID: second.ID,
			TurnID:    activeTurn.ID,
			Type:      "agent.thinking",
			Timestamp: now.Add(21 * time.Second),
			Data:      json.RawMessage(`{}`),
		})
		if !errors.Is(err, application.ErrTurnTerminal) {
			t.Fatalf("late append error = %v", err)
		}
		after, err := store.GetSession(ctx, device.DeviceID, second.ID)
		if err != nil {
			t.Fatal(err)
		}
		if after.LastSequence != before.LastSequence {
			t.Fatalf("cursor changed from %d to %d", before.LastSequence, after.LastSequence)
		}
	})

	t.Run("credential revoke expires sessions with final event", func(t *testing.T) {
		before, err := store.GetSession(ctx, device.DeviceID, second.ID)
		if err != nil {
			t.Fatal(err)
		}
		revokeTurn, inputArtifactID := createIntegrationAudioTurn(
			t, ctx, store, device, second.ID,
			"30000000-0000-4000-8000-000000000004",
			"40000000-0000-4000-8000-000000000004",
			"50000000-0000-4000-8000-000000000004",
			"60000000-0000-4000-8000-000000000004",
			now.Add(22*time.Second),
		)
		rotatedDigest := sha256.Sum256([]byte("rotated-device-token"))
		rotated, err := store.IssueDevice(
			ctx, rotatedDigest[:], "integration-rotated", now.Add(22*time.Second),
		)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := store.AuthenticateAndBindDevice(
			ctx, rotatedDigest[:], device.DeviceID, now.Add(22*time.Second),
		); !errors.Is(err, application.ErrUnauthorized) {
			t.Fatalf("concurrent active binding = %v, want unauthorized", err)
		}
		revokedAt := now.Add(23 * time.Second)
		if err := store.RevokeDevice(ctx, device.ID, revokedAt); err != nil {
			t.Fatal(err)
		}
		events, err := store.ListEvents(
			ctx, device.DeviceID, second.ID, before.LastSequence, 20,
		)
		if err != nil {
			t.Fatal(err)
		}
		got := make([]string, len(events))
		for index, event := range events {
			got[index] = event.Type
		}
		want := []string{"turn.accepted", "turn.cancelled", "session.expired"}
		if strings.Join(got, ",") != strings.Join(want, ",") {
			t.Fatalf("revoke event order = %v, want %v (turn %s)", got, want, revokeTurn.ID)
		}
		var expiredData map[string]string
		if err := json.Unmarshal(events[len(events)-1].Data, &expiredData); err != nil {
			t.Fatal(err)
		}
		if expiredData["reason"] != "credential_revoked" ||
			expiredData["expiredAt"] == "" {
			t.Fatalf("expired data = %v", expiredData)
		}
		if _, err := store.AuthenticateAndBindDevice(
			ctx, digest[:], device.DeviceID, now.Add(24*time.Second),
		); !errors.Is(err, application.ErrUnauthorized) {
			t.Fatalf("revoked authentication = %v", err)
		}
		rotated, err = store.AuthenticateAndBindDevice(
			ctx, rotatedDigest[:], device.DeviceID, now.Add(24*time.Second),
		)
		if err != nil || rotated.DeviceID != device.DeviceID {
			t.Fatalf("rotated binding = %#v err %v", rotated, err)
		}
		var inputExpiresAt time.Time
		if err := pool.QueryRow(ctx, `
			SELECT expires_at FROM artifacts WHERE id = $1
		`, inputArtifactID).Scan(&inputExpiresAt); err != nil {
			t.Fatal(err)
		}
		if inputExpiresAt.After(revokedAt) {
			t.Fatalf(
				"cancelled raw audio expires at %s, after revoke %s",
				inputExpiresAt, revokedAt,
			)
		}
		var deletionQueued bool
		if err := pool.QueryRow(ctx, `
			SELECT EXISTS (
				SELECT 1 FROM jobs
				WHERE job_type = 'delete_artifact'
				  AND payload ->> 'artifactId' = $1
				  AND status = 'queued'
			)
		`, inputArtifactID).Scan(&deletionQueued); err != nil {
			t.Fatal(err)
		}
		if !deletionQueued {
			t.Fatal("cancelled raw audio has no durable deletion job")
		}
	})

	t.Run("retention redacts device tool errors", func(t *testing.T) {
		retentionNow := now.Add(8 * 24 * time.Hour)
		if _, err := store.Prune(
			ctx, retentionNow, 7*24*time.Hour, 24*time.Hour, 1000,
		); err != nil {
			t.Fatal(err)
		}
		call, err := store.GetToolCall(
			ctx, second.ID, "70000000-0000-4000-8000-000000000001",
		)
		if err != nil {
			t.Fatal(err)
		}
		if string(call.Error) != "null" {
			t.Fatalf("retained tool error = %s", call.Error)
		}
	})

	t.Run("codex thread cleanup is leased before session pruning", func(t *testing.T) {
		old := now.Add(-10 * 24 * time.Hour)
		cleanupDigest := sha256.Sum256([]byte("cleanup-device"))
		cleanupDevice, err := store.IssueDevice(
			ctx, cleanupDigest[:], "cleanup", old,
		)
		if err != nil {
			t.Fatal(err)
		}
		cleanupDevice, err = store.AuthenticateAndBindDevice(
			ctx, cleanupDigest[:], "cleanup-device", old,
		)
		if err != nil {
			t.Fatal(err)
		}
		idempotencyKey := "a0000000-0000-4000-8000-000000000001"
		_ = createIntegrationSessionWithProvider(
			t, ctx, store, cleanupDevice,
			"a1000000-0000-4000-8000-000000000001",
			idempotencyKey, "codex", "oauth", old,
		)
		// The same key is reusable after its durable 24-hour window, even if
		// the prune sweep has not yet removed the expired row.
		session := createIntegrationSessionWithProvider(
			t, ctx, store, cleanupDevice,
			"a1000000-0000-4000-8000-000000000002",
			idempotencyKey, "codex", "oauth", old.Add(25*time.Hour),
		)
		if err := store.PutProviderState(ctx, application.ProviderState{
			SessionID:       session.ID,
			ProviderKind:    "codex",
			ProviderProfile: "oauth",
			RemoteThreadID:  "codex-thread-to-delete",
			OpaqueState:     json.RawMessage(`{"conversation":"private"}`),
			UpdatedAt:       old.Add(25 * time.Hour),
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := store.CloseSession(ctx, application.CloseSessionParams{
			SessionID: session.ID,
			DeviceID:  cleanupDevice.DeviceID,
			State:     application.SessionClosed,
			Reason:    "client_request",
			Now:       old.Add(26 * time.Hour),
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := store.Prune(
			ctx, now, 7*24*time.Hour, 24*time.Hour, 1000,
		); err != nil {
			t.Fatal(err)
		}
		state, err := store.GetProviderState(ctx, session.ID)
		if err != nil {
			t.Fatalf("session was pruned before thread cleanup: %v", err)
		}
		if state.RemoteThreadID != "codex-thread-to-delete" ||
			state.CleanupState != "live" {
			t.Fatalf("provider state was prematurely redacted: %#v", state)
		}
		known, err := store.ProviderThreadKnown(ctx, "codex-thread-to-delete")
		if err != nil || !known {
			t.Fatalf("known provider thread = %v err %v", known, err)
		}
		known, err = store.ProviderThreadKnown(ctx, "orphan-thread")
		if err != nil || known {
			t.Fatalf("unknown provider thread = %v err %v", known, err)
		}
		leases, err := store.LeaseProviderThreadsForDeletion(
			ctx, "provider-cleaner-a", now.Add(-7*24*time.Hour),
			now, time.Second, 10,
		)
		if err != nil || len(leases) != 1 ||
			leases[0].RemoteThreadID != "codex-thread-to-delete" {
			t.Fatalf("provider cleanup leases = %#v err %v", leases, err)
		}
		recovered, err := store.LeaseProviderThreadsForDeletion(
			ctx, "provider-cleaner-b", now.Add(-7*24*time.Hour),
			now.Add(2*time.Second), time.Minute, 10,
		)
		if err != nil || len(recovered) != 1 ||
			recovered[0].SessionID != session.ID {
			t.Fatalf("recovered provider cleanup lease = %#v err %v", recovered, err)
		}
		if err := store.MarkProviderThreadDeleted(
			ctx, session.ID, "provider-cleaner-b", now.Add(3*time.Second),
		); err != nil {
			t.Fatal(err)
		}
		known, err = store.ProviderThreadKnown(ctx, "codex-thread-to-delete")
		if err != nil || known {
			t.Fatalf("deleted provider thread = %v err %v", known, err)
		}
		if _, err := store.Prune(
			ctx, now.Add(4*time.Second), 7*24*time.Hour, 24*time.Hour, 1000,
		); err != nil {
			t.Fatal(err)
		}
		if _, err := store.GetSession(
			ctx, cleanupDevice.DeviceID, session.ID,
		); !errors.Is(err, application.ErrNotFound) {
			t.Fatalf("cleaned session lookup = %v, want not found", err)
		}
	})

	_ = schema
}

func TestPostgresProviderRevisionMigrationIntegration(t *testing.T) {
	databaseURL := os.Getenv("GATEWAY_TEST_DATABASE_URL")
	if databaseURL == "" {
		databaseURL = os.Getenv("TEST_DATABASE_URL")
	}
	if databaseURL == "" {
		t.Skip("set GATEWAY_TEST_DATABASE_URL or TEST_DATABASE_URL to run PostgreSQL integration tests")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, _ := integrationPoolVersion(t, ctx, databaseURL, 1)

	now := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	deviceDigest := sha256.Sum256([]byte("v1-device-token"))
	const deviceRowID = "a1000000-0000-4000-8000-000000000001"
	const sessionID = "a2000000-0000-4000-8000-000000000001"
	if _, err := pool.Exec(ctx, `
		INSERT INTO devices (
			id, token_digest, device_id, label, created_at, bound_at
		) VALUES ($1, $2, 'v1-device', 'v1 migration', $3, $3)
	`, deviceRowID, deviceDigest[:], now); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `
		INSERT INTO sessions (
			id, device_row_id, device_id, protocol_version, state,
			agent_profile, provider_kind, provider_profile,
			client, context, tool_manifest, created_at, updated_at, expires_at
		) VALUES (
			$1, $2, 'v1-device', '1.0', 'active',
			'default', 'openai-compatible', 'default',
			'{}'::jsonb, '{}'::jsonb, '{}'::jsonb, $3, $3, $4
		)
	`, sessionID, deviceRowID, now, now.Add(24*time.Hour)); err != nil {
		t.Fatal(err)
	}

	applyIntegrationMigrations(t, ctx, pool.Config().ConnConfig, 0)
	session, err := scanSession(pool.QueryRow(ctx,
		`SELECT `+sessionColumns+` FROM sessions WHERE id = $1`,
		sessionID,
	))
	if err != nil {
		t.Fatal(err)
	}
	if session.ProviderRevision != "" {
		t.Fatalf("migrated v1 provider revision = %q, want empty", session.ProviderRevision)
	}
}

func TestPostgresEventRetentionIntegration(t *testing.T) {
	databaseURL := os.Getenv("GATEWAY_TEST_DATABASE_URL")
	if databaseURL == "" {
		databaseURL = os.Getenv("TEST_DATABASE_URL")
	}
	if databaseURL == "" {
		t.Skip("set GATEWAY_TEST_DATABASE_URL or TEST_DATABASE_URL to run PostgreSQL integration tests")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pool, _ := integrationPool(t, ctx, databaseURL)
	store := New(pool)
	t.Cleanup(store.events.Close)

	base := time.Date(2026, 7, 24, 0, 0, 0, 0, time.UTC)
	pruneNow := base.Add(10 * 24 * time.Hour)
	oldEventTime := pruneNow.Add(-48 * time.Hour)
	freshEventTime := pruneNow.Add(-time.Hour)
	const terminalRetention = 24 * time.Hour
	const transcriptRetention = 7 * 24 * time.Hour

	issueDevice := func(token, label, deviceID string) application.Device {
		t.Helper()
		digest := sha256.Sum256([]byte(token))
		device, err := store.IssueDevice(ctx, digest[:], label, base)
		if err != nil {
			t.Fatal(err)
		}
		device, err = store.AuthenticateAndBindDevice(
			ctx, digest[:], deviceID, base,
		)
		if err != nil {
			t.Fatal(err)
		}
		return device
	}

	device := issueDevice("retention-token", "retention", "retention-device")
	session := createIntegrationSession(
		t, ctx, store, device,
		"b1000000-0000-4000-8000-000000000001",
		"b2000000-0000-4000-8000-000000000001",
		base,
	)
	turn := createIntegrationTurn(
		t, ctx, store, device, session.ID,
		"b3000000-0000-4000-8000-000000000001",
		"b4000000-0000-4000-8000-000000000001",
		"b5000000-0000-4000-8000-000000000001",
		base.Add(time.Second),
	)
	if _, err := store.AppendEvent(ctx, application.AppendEventParams{
		SessionID: session.ID,
		TurnID:    turn.ID,
		Type:      "agent.thinking",
		Timestamp: base.Add(2 * time.Second),
		Data:      json.RawMessage(`{}`),
	}); err != nil {
		t.Fatal(err)
	}
	if _, changed, err := store.FinishTurn(
		ctx, session.ID, turn.ID, application.TurnCompleted,
		"done", nil, "turn.completed", json.RawMessage(`{}`),
		base.Add(3*time.Second),
	); err != nil || !changed {
		t.Fatalf("finish turn changed=%v err=%v", changed, err)
	}

	// Sequence 3 has a regressed timestamp older than sequence 2. A retention
	// sweep may delete sequence 1, but must stop at the fresh sequence 2.
	if _, err := pool.Exec(ctx, `
		UPDATE events
		SET occurred_at = CASE sequence
			WHEN 2 THEN $2
			ELSE $3
		END
		WHERE session_id = $1
	`, session.ID, freshEventTime, oldEventTime); err != nil {
		t.Fatal(err)
	}
	result, err := store.Prune(
		ctx, pruneNow, transcriptRetention, terminalRetention, 1000,
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.Events != 1 {
		t.Fatalf("clock-regression prune deleted %d events, want 1", result.Events)
	}
	assertRetainedSequences(t, ctx, pool, session.ID, []int64{2, 3})

	// A bounded sweep may stop inside an eligible prefix, but it must never
	// skip over the first retained event.
	if _, err := pool.Exec(ctx, `
		UPDATE events SET occurred_at = $2
		WHERE session_id = $1 AND sequence = 2
	`, session.ID, oldEventTime); err != nil {
		t.Fatal(err)
	}
	result, err = store.Prune(
		ctx, pruneNow, transcriptRetention, terminalRetention, 1,
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.Events != 1 {
		t.Fatalf("bounded prune deleted %d events, want 1", result.Events)
	}
	assertRetainedSequences(t, ctx, pool, session.ID, []int64{3})
	if _, err := store.Prune(
		ctx, pruneNow, transcriptRetention, terminalRetention, 1,
	); err != nil {
		t.Fatal(err)
	}
	assertRetainedSequences(t, ctx, pool, session.ID, nil)

	// Rank eligible prefixes across sessions so a bounded batch progresses
	// fairly instead of exhausting one large session first.
	fairDevices := []application.Device{
		issueDevice("fair-token-a", "fair-a", "fair-device-a"),
		issueDevice("fair-token-b", "fair-b", "fair-device-b"),
	}
	fairSessions := make([]application.Session, 0, len(fairDevices))
	for index, fairDevice := range fairDevices {
		suffix := index + 1
		fairSession := createIntegrationSession(
			t, ctx, store, fairDevice,
			fmt.Sprintf("c1000000-0000-4000-8000-%012d", suffix),
			fmt.Sprintf("c2000000-0000-4000-8000-%012d", suffix),
			base,
		)
		fairTurn := createIntegrationTurn(
			t, ctx, store, fairDevice, fairSession.ID,
			fmt.Sprintf("c3000000-0000-4000-8000-%012d", suffix),
			fmt.Sprintf("c4000000-0000-4000-8000-%012d", suffix),
			fmt.Sprintf("c5000000-0000-4000-8000-%012d", suffix),
			base.Add(time.Second),
		)
		if _, changed, err := store.FinishTurn(
			ctx, fairSession.ID, fairTurn.ID, application.TurnCompleted,
			"done", nil, "turn.completed", json.RawMessage(`{}`),
			base.Add(2*time.Second),
		); err != nil || !changed {
			t.Fatalf("finish fair turn %d changed=%v err=%v", suffix, changed, err)
		}
		if _, err := pool.Exec(ctx, `
			UPDATE events SET occurred_at = $2 WHERE session_id = $1
		`, fairSession.ID, oldEventTime); err != nil {
			t.Fatal(err)
		}
		fairSessions = append(fairSessions, fairSession)
	}
	result, err = store.Prune(
		ctx, pruneNow, transcriptRetention, terminalRetention, 2,
	)
	if err != nil {
		t.Fatal(err)
	}
	if result.Events != 2 {
		t.Fatalf("fair prune deleted %d events, want 2", result.Events)
	}
	for _, fairSession := range fairSessions {
		assertRetainedSequences(t, ctx, pool, fairSession.ID, []int64{2})
	}

	activeTurn := createIntegrationTurn(
		t, ctx, store, device, session.ID,
		"b3000000-0000-4000-8000-000000000002",
		"b4000000-0000-4000-8000-000000000002",
		"b5000000-0000-4000-8000-000000000002",
		base.Add(4*time.Second),
	)
	for offset := 5; offset <= 6; offset++ {
		if _, err := store.AppendEvent(ctx, application.AppendEventParams{
			SessionID: session.ID,
			TurnID:    activeTurn.ID,
			Type:      "agent.thinking",
			Timestamp: base.Add(time.Duration(offset) * time.Second),
			Data:      json.RawMessage(`{}`),
		}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := pool.Exec(ctx, `
		UPDATE events SET occurred_at = $2 WHERE session_id = $1
	`, session.ID, oldEventTime); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Prune(
		ctx, pruneNow, transcriptRetention, terminalRetention, 1000,
	); err != nil {
		t.Fatal(err)
	}
	assertRetainedSequences(t, ctx, pool, session.ID, []int64{4, 5, 6})

	// Simulate a legacy/pre-fix retention hole. ReplayWindow must declare the
	// requested range stale even though its first retained sequence is present.
	if _, err := pool.Exec(ctx, `
		DELETE FROM events WHERE session_id = $1 AND sequence = 5
	`, session.ID); err != nil {
		t.Fatal(err)
	}
	window, err := store.ReplayWindow(
		ctx, device.DeviceID, session.ID, 3, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if !window.Stale || window.CurrentSequence != 6 || len(window.Events) != 0 {
		t.Fatalf("gap replay window = %#v", window)
	}
	if _, err := store.ListEvents(
		ctx, device.DeviceID, session.ID, 3, 20,
	); !errors.Is(err, application.ErrReplayStale) {
		t.Fatalf("gap ListEvents error = %v, want replay stale", err)
	}
	if err := store.WaitForEvent(
		ctx, session.ID, 4,
	); !errors.Is(err, application.ErrReplayStale) {
		t.Fatalf("gap WaitForEvent error = %v, want replay stale", err)
	}

	if _, err := store.CloseSession(ctx, application.CloseSessionParams{
		SessionID: session.ID,
		DeviceID:  device.DeviceID,
		State:     application.SessionClosed,
		Reason:    "client_request",
		Now:       base.Add(7 * time.Second),
	}); err != nil {
		t.Fatal(err)
	}
	terminalWindow, err := store.ReplayWindow(
		ctx, device.DeviceID, session.ID, 3, 20,
	)
	if err != nil {
		t.Fatal(err)
	}
	if !terminalWindow.Stale ||
		terminalWindow.SessionState != application.SessionClosed {
		t.Fatalf("terminal gap replay window = %#v", terminalWindow)
	}
}

func assertRetainedSequences(
	t *testing.T,
	ctx context.Context,
	pool *pgxpool.Pool,
	sessionID string,
	want []int64,
) {
	t.Helper()
	rows, err := pool.Query(ctx, `
		SELECT sequence FROM events WHERE session_id = $1 ORDER BY sequence
	`, sessionID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var got []int64
	for rows.Next() {
		var sequence int64
		if err := rows.Scan(&sequence); err != nil {
			t.Fatal(err)
		}
		got = append(got, sequence)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if len(got) != len(want) {
		t.Fatalf("retained sequences = %v, want %v", got, want)
	}
	for index := range got {
		if got[index] != want[index] {
			t.Fatalf("retained sequences = %v, want %v", got, want)
		}
	}
}

func integrationPool(t *testing.T, ctx context.Context, databaseURL string) (*pgxpool.Pool, string) {
	return integrationPoolVersion(t, ctx, databaseURL, 0)
}

func integrationPoolVersion(
	t *testing.T,
	ctx context.Context,
	databaseURL string,
	version int64,
) (*pgxpool.Pool, string) {
	t.Helper()
	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil {
		t.Fatal(err)
	}
	schema := "gateway_test_" + hex.EncodeToString(random[:])
	admin, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := admin.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	admin.Close()

	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	config.ConnConfig.RuntimeParams["search_path"] = schema + ",public"
	// Keep the pool intentionally small: WebSocket fan-out must not require
	// one checked-out PostgreSQL connection per client.
	config.MaxConns = 4
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = pool.Exec(context.Background(), `DROP SCHEMA `+schema+` CASCADE`)
		pool.Close()
	})
	applyIntegrationMigrations(t, ctx, config.ConnConfig, version)
	return pool, schema
}

func applyIntegrationMigrations(
	t *testing.T,
	ctx context.Context,
	config *pgx.ConnConfig,
	version int64,
) {
	t.Helper()
	database := stdlib.OpenDB(*config)
	defer database.Close()
	database.SetMaxOpenConns(1)
	goose.SetBaseFS(migrationassets.FS)
	if err := goose.SetDialect("postgres"); err != nil {
		t.Fatal(err)
	}
	var err error
	if version > 0 {
		err = goose.UpToContext(ctx, database, ".", version)
	} else {
		err = goose.UpContext(ctx, database, ".")
	}
	if err != nil {
		t.Fatalf("apply migrations: %v", err)
	}
}

func createIntegrationSession(
	t *testing.T,
	ctx context.Context,
	store *Postgres,
	device application.Device,
	sessionID, idempotencyKey string,
	now time.Time,
) application.Session {
	return createIntegrationSessionWithProvider(
		t, ctx, store, device, sessionID, idempotencyKey,
		"openai-compatible", "default", now,
	)
}

func createIntegrationSessionWithProvider(
	t *testing.T,
	ctx context.Context,
	store *Postgres,
	device application.Device,
	sessionID, idempotencyKey, providerKind, providerProfile string,
	now time.Time,
) application.Session {
	t.Helper()
	digest := sha256.Sum256([]byte(sessionID))
	providerRevision := strings.Repeat("a", 64)
	session, replayed, err := store.CreateSession(ctx, application.CreateSessionParams{
		ID:               sessionID,
		Device:           device,
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    digest[:],
		IdempotencyUntil: now.Add(24 * time.Hour),
		AgentProfile:     "default",
		ProviderKind:     providerKind,
		ProviderProfile:  providerProfile,
		ProviderRevision: providerRevision,
		Client:           json.RawMessage(`{"appVersion":"test"}`),
		Context:          json.RawMessage(`{"robotName":"Kira","language":"zh-TW"}`),
		ToolManifest:     json.RawMessage(`{"protocolVersion":"1.0","tools":[]}`),
		Now:              now,
		ExpiresAt:        now.Add(24 * time.Hour),
	})
	if err != nil || replayed {
		t.Fatalf("create session = replayed %v err %v", replayed, err)
	}
	if session.ProviderRevision != providerRevision {
		t.Fatalf("provider revision = %q", session.ProviderRevision)
	}
	return session
}

func createIntegrationAudioTurn(
	t *testing.T,
	ctx context.Context,
	store *Postgres,
	device application.Device,
	sessionID, turnID, clientTurnID, idempotencyKey, artifactID string,
	now time.Time,
) (application.Turn, string) {
	t.Helper()
	requestDigest := sha256.Sum256([]byte(turnID))
	audioDigest := sha256.Sum256([]byte("raw-audio"))
	turn, replayed, err := store.CreateTurn(ctx, application.CreateTurnParams{
		ID:               turnID,
		SessionID:        sessionID,
		Device:           device,
		ClientTurnID:     clientTurnID,
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    requestDigest[:],
		IdempotencyUntil: now.Add(24 * time.Hour),
		InputKind:        "audio",
		InputArtifactID:  artifactID,
		InputArtifact: &application.Artifact{
			ID:         artifactID,
			SessionID:  sessionID,
			TurnID:     turnID,
			Kind:       "input_audio",
			BlobKey:    "integration/" + artifactID + ".wav",
			MIMEType:   "audio/wav",
			ByteLength: 9,
			SHA256:     audioDigest[:],
			CreatedAt:  now,
			ExpiresAt:  now.Add(30 * time.Minute),
		},
		Language: "en-US",
		Now:      now,
	})
	if err != nil || replayed {
		t.Fatalf("create audio turn = replayed %v err %v", replayed, err)
	}
	return turn, artifactID
}

func createIntegrationTurn(
	t *testing.T,
	ctx context.Context,
	store *Postgres,
	device application.Device,
	sessionID, turnID, clientTurnID, idempotencyKey string,
	now time.Time,
) application.Turn {
	t.Helper()
	digest := sha256.Sum256([]byte(turnID))
	turn, replayed, err := store.CreateTurn(ctx, application.CreateTurnParams{
		ID:               turnID,
		SessionID:        sessionID,
		Device:           device,
		ClientTurnID:     clientTurnID,
		IdempotencyKey:   idempotencyKey,
		RequestDigest:    digest[:],
		IdempotencyUntil: now.Add(24 * time.Hour),
		InputKind:        "text",
		InputText:        "hello",
		Language:         "en-US",
		Now:              now,
	})
	if err != nil || replayed {
		t.Fatalf("create turn = replayed %v err %v", replayed, err)
	}
	return turn
}
