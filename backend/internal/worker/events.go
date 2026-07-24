package worker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

func (worker *Worker) ensureTurnEvent(
	ctx context.Context,
	session application.Session,
	turnID, eventType string,
	data json.RawMessage,
) error {
	exists, err := worker.hasTurnEvent(ctx, session, turnID, eventType)
	if err != nil {
		return err
	}
	if exists {
		return nil
	}
	if err := worker.requireActive(ctx, session.ID, turnID); err != nil {
		return err
	}
	eventID := newUUID()
	timestamp := worker.now()
	if err := validateOutgoingEvent(
		session.ID, turnID, eventID, eventType, timestamp, data,
	); err != nil {
		return permanent(
			"INVALID_GATEWAY_EVENT",
			"The worker generated an invalid protocol event",
			err,
		)
	}
	_, err = worker.repository.AppendEvent(ctx, application.AppendEventParams{
		SessionID: session.ID,
		TurnID:    turnID,
		EventID:   eventID,
		Type:      eventType,
		Timestamp: timestamp,
		Data:      data,
	})
	if errors.Is(err, application.ErrTurnTerminal) ||
		errors.Is(err, application.ErrSessionNotActive) {
		return errTurnStopped
	}
	return err
}

func validateOutgoingEvent(
	sessionID, turnID, eventID, eventType string,
	timestamp time.Time,
	data json.RawMessage,
) error {
	turnCopy := turnID
	return contract.ValidateEvent(domain.Event{
		ProtocolVersion: domain.ProtocolVersion,
		EventID:         eventID,
		Sequence:        1,
		SessionID:       sessionID,
		TurnID:          &turnCopy,
		Type:            domain.EventType(eventType),
		Timestamp:       domain.NewTimestamp(timestamp),
		Data:            data,
	})
}

func (worker *Worker) hasTurnEvent(
	ctx context.Context,
	session application.Session,
	turnID, eventType string,
) (bool, error) {
	var cursor uint64
	for {
		events, err := worker.repository.ListEvents(
			ctx, session.DeviceID, session.ID, cursor, 256,
		)
		if err != nil {
			return false, err
		}
		if len(events) == 0 {
			return false, nil
		}
		for _, event := range events {
			cursor = event.Sequence
			if event.TurnID == nil || *event.TurnID != turnID {
				continue
			}
			if event.Type == eventType {
				return true, nil
			}
			if event.Type == "turn.completed" ||
				event.Type == "turn.cancelled" ||
				event.Type == "turn.error" {
				return false, errTurnStopped
			}
		}
		if len(events) < 256 {
			return false, nil
		}
	}
}

func (worker *Worker) requireActive(
	ctx context.Context,
	sessionID, turnID string,
) error {
	if err := context.Cause(ctx); err != nil {
		if errors.Is(err, errTurnStopped) {
			return errTurnStopped
		}
		return err
	}
	turn, err := worker.repository.GetTurn(ctx, sessionID, turnID)
	if err != nil {
		return err
	}
	if turn.State.Terminal() {
		return errTurnStopped
	}
	return nil
}

func (worker *Worker) watchTurn(
	ctx context.Context,
	cancel context.CancelCauseFunc,
	sessionID, turnID string,
	done chan<- struct{},
) {
	defer close(done)
	ticker := timeNewTicker(worker.config.TurnPoll)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			turn, err := worker.repository.GetTurn(ctx, sessionID, turnID)
			if err == nil && turn.State.Terminal() {
				cancel(errTurnStopped)
				return
			}
		}
	}
}

func (worker *Worker) putState(
	ctx context.Context,
	session application.Session,
	providerState application.ProviderState,
	state persistedState,
) error {
	encoded, err := json.Marshal(state)
	if err != nil {
		return err
	}
	if len(encoded) > 512*1024 {
		return permanent(
			"PROVIDER_STATE_TOO_LARGE",
			"The durable provider conversation state exceeded its limit",
			nil,
		)
	}
	providerState.SessionID = session.ID
	providerState.ProviderKind = session.ProviderKind
	providerState.ProviderProfile = session.ProviderProfile
	providerState.OpaqueState = encoded
	providerState.UpdatedAt = worker.now()
	return worker.repository.PutProviderState(ctx, providerState)
}

func systemPrompt(session application.Session) string {
	var robot struct {
		RobotName string `json:"robotName"`
		Language  string `json:"language"`
	}
	_ = json.Unmarshal(session.Context, &robot)
	name := strings.TrimSpace(robot.RobotName)
	if name == "" {
		name = "Zenbo"
	}
	language := validLanguage(robot.Language)
	if language == "" {
		language = "en-US"
	}
	return fmt.Sprintf(
		"You are a helpful Zenbo robot named %q. Respond concisely in the user's preferred language (%q). Use only the registered device tools when a physical or on-screen action is needed.",
		name,
		language,
	)
}

func (worker *Worker) turnLanguage(
	session application.Session,
	turn application.Turn,
) string {
	if language := validLanguage(turn.Language); language != "" {
		return language
	}
	var robot struct {
		Language string `json:"language"`
	}
	_ = json.Unmarshal(session.Context, &robot)
	if language := validLanguage(robot.Language); language != "" {
		return language
	}
	return "en-US"
}

func validLanguage(value string) string {
	value = strings.TrimSpace(value)
	if !utf8.ValidString(value) {
		return ""
	}
	count := utf8.RuneCountInString(value)
	if count < 2 || count > 35 {
		return ""
	}
	return value
}
