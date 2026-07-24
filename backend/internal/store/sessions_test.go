package store

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

func TestTerminalEventPlanEndsWithSessionTerminalEvent(t *testing.T) {
	now := time.Date(2026, 7, 24, 12, 13, 14, 987654321, time.FixedZone("offset", 8*60*60))
	sessionID := "10000000-0000-4000-8000-000000000001"
	turnIDs := []string{
		"20000000-0000-4000-8000-000000000001",
		"20000000-0000-4000-8000-000000000002",
	}
	tests := []struct {
		name          string
		state         application.SessionState
		sessionReason string
		turnReason    string
		terminalType  string
	}{
		{"replacement", application.SessionClosed, "replaced", "superseded", "session.closed"},
		{"client close", application.SessionClosed, "client_request", "client_request", "session.closed"},
		{"expiry", application.SessionExpired, "idle_timeout", "timeout", "session.expired"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			plan := terminalEventPlan(
				sessionID, turnIDs, test.state, test.sessionReason,
				test.turnReason, now,
			)
			if len(plan) != len(turnIDs)+1 {
				t.Fatalf("plan length = %d", len(plan))
			}
			for index, event := range plan[:len(plan)-1] {
				if event.Type != "turn.cancelled" || event.TurnID != turnIDs[index] {
					t.Fatalf("event %d = %#v", index, event)
				}
				var data contract.TurnCancelledData
				if err := json.Unmarshal(event.Data, &data); err != nil {
					t.Fatal(err)
				}
				if data.Reason != test.turnReason {
					t.Fatalf("turn reason = %q", data.Reason)
				}
			}
			terminal := plan[len(plan)-1]
			if terminal.Type != test.terminalType || terminal.TurnID != "" {
				t.Fatalf("terminal event = %#v", terminal)
			}
			assertValidPlannedEvents(t, plan)
		})
	}
}

func TestExpiryPlanUsesAndroidTimestampAndCompleteData(t *testing.T) {
	now := time.Date(2026, 7, 24, 1, 2, 3, 456789000, time.FixedZone("offset", 8*60*60))
	plan := terminalEventPlan(
		"10000000-0000-4000-8000-000000000001",
		nil,
		application.SessionExpired,
		"idle_timeout",
		"timeout",
		now,
	)
	var data struct {
		Reason    string `json:"reason"`
		ExpiredAt string `json:"expiredAt"`
	}
	if err := json.Unmarshal(plan[0].Data, &data); err != nil {
		t.Fatal(err)
	}
	if data.Reason != "idle_timeout" {
		t.Fatalf("reason = %q", data.Reason)
	}
	if data.ExpiredAt != "2026-07-23T17:02:03.456Z" {
		t.Fatalf("expiredAt = %q", data.ExpiredAt)
	}
}

func assertValidPlannedEvents(t *testing.T, plan []application.AppendEventParams) {
	t.Helper()
	for index, planned := range plan {
		var turnID *string
		if planned.TurnID != "" {
			value := planned.TurnID
			turnID = &value
		}
		event := domain.Event{
			ProtocolVersion: domain.ProtocolVersion,
			EventID:         "30000000-0000-4000-8000-000000000001",
			Sequence:        uint64(index + 1),
			SessionID:       planned.SessionID,
			TurnID:          turnID,
			Type:            domain.EventType(planned.Type),
			Timestamp:       domain.NewTimestamp(planned.Timestamp),
			Data:            planned.Data,
		}
		if err := contract.ValidateEvent(event); err != nil {
			t.Fatalf("event %d failed contract validation: %v", index, err)
		}
	}
}
