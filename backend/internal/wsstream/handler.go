package wsstream

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/auth"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

type EventStream interface {
	Bootstrap() domain.StreamBootstrap
	Next(context.Context) (domain.Event, error)
	Close() error
}

type Source interface {
	OpenEventStream(context.Context, domain.Principal, string, uint64) (EventStream, error)
}

type FailureHandler func(http.ResponseWriter, *http.Request, error)

type Handler struct {
	Source       Source
	Clock        func() time.Time
	PingInterval time.Duration
	OnFailure    FailureHandler
}

func (h Handler) ServeHTTP(response http.ResponseWriter, request *http.Request) {
	if !isWebSocketUpgrade(request) {
		h.fail(response, request, domain.NewError(
			domain.ErrorProtocol,
			"WEBSOCKET_UPGRADE_REQUIRED",
			"Event streams require a WebSocket upgrade",
		))
		return
	}
	sessionID := request.PathValue("sessionId")
	if !contract.IsUUID(sessionID) {
		h.fail(response, request, domain.NewError(domain.ErrorInvalidArgument, "INVALID_SESSION_ID", "sessionId must be a UUID"))
		return
	}
	after, err := parseAfter(request)
	if err != nil {
		h.fail(response, request, err)
		return
	}
	principal, ok := auth.PrincipalFromContext(request.Context())
	if !ok {
		h.fail(response, request, domain.NewError(domain.ErrorUnauthenticated, "UNAUTHORIZED", "Device authentication is required"))
		return
	}
	if h.Source == nil {
		h.fail(response, request, domain.NewError(domain.ErrorInternal, "STREAM_NOT_CONFIGURED", "Event streaming is unavailable"))
		return
	}
	stream, err := h.Source.OpenEventStream(request.Context(), principal, sessionID, after)
	if err != nil {
		h.fail(response, request, err)
		return
	}
	defer stream.Close()

	bootstrap := stream.Bootstrap()
	if err := validateBootstrap(bootstrap, after); err != nil {
		h.fail(response, request, err)
		return
	}

	connection, err := websocket.Accept(response, request, &websocket.AcceptOptions{
		CompressionMode: websocket.CompressionDisabled,
	})
	if err != nil {
		return
	}
	connection.SetReadLimit(1024)
	defer connection.CloseNow()
	readContext := connection.CloseRead(request.Context())
	ctx, cancel := context.WithCancel(readContext)
	defer cancel()
	pingInterval := h.PingInterval
	if pingInterval <= 0 {
		pingInterval = 20 * time.Second
	}
	pingErrors := make(chan error, 1)
	go ping(ctx, connection, pingInterval, pingErrors, cancel)

	now := time.Now
	if h.Clock != nil {
		now = h.Clock
	}
	ready, err := controlEvent(
		sessionID,
		domain.EventSessionReady,
		bootstrap.AcceptedAfter,
		contract.SessionReadyData{
			ResumedAfter: bootstrap.AcceptedAfter,
			GatewayTime:  domain.NewTimestamp(now()),
		},
		now(),
	)
	if err != nil || contract.ValidateEvent(ready) != nil {
		_ = connection.Close(websocket.StatusInternalError, "invalid ready event")
		return
	}
	if err := wsjson.Write(ctx, connection, ready); err != nil {
		return
	}

	expectedSequence := bootstrap.AcceptedAfter + 1
	if bootstrap.Stale {
		snapshot, snapshotError := controlEvent(
			sessionID,
			domain.EventSessionSnapshot,
			bootstrap.CurrentSequence,
			contract.SessionSnapshotData{
				State:        bootstrap.SessionState,
				LastSequence: bootstrap.CurrentSequence,
				ActiveTurnID: bootstrap.ActiveTurnID,
			},
			now(),
		)
		if snapshotError != nil || contract.ValidateEvent(snapshot) != nil {
			_ = connection.Close(websocket.StatusInternalError, "invalid snapshot event")
			return
		}
		if err := wsjson.Write(ctx, connection, snapshot); err != nil {
			return
		}
		expectedSequence = bootstrap.CurrentSequence + 1
	}

	for {
		event, nextError := stream.Next(ctx)
		if nextError != nil {
			if errors.Is(nextError, context.Canceled) {
				select {
				case <-pingErrors:
					_ = connection.Close(websocket.StatusInternalError, "heartbeat failed")
				default:
				}
				return
			}
			if errors.Is(nextError, io.EOF) {
				_ = connection.Close(websocket.StatusNormalClosure, "session stream ended")
				return
			}
			_ = connection.Close(websocket.StatusInternalError, "event stream failure")
			return
		}
		if event.SessionID != sessionID || event.Sequence != expectedSequence {
			_ = connection.Close(websocket.StatusProtocolError, "event sequence mismatch")
			return
		}
		if err := contract.ValidateEvent(event); err != nil {
			_ = connection.Close(websocket.StatusProtocolError, "invalid event envelope")
			return
		}
		if err := wsjson.Write(ctx, connection, event); err != nil {
			return
		}
		expectedSequence++
		if contract.IsSessionTerminalEvent(event.Type) {
			_ = connection.Close(websocket.StatusNormalClosure, string(event.Type))
			return
		}
	}
}

func isWebSocketUpgrade(request *http.Request) bool {
	if !strings.EqualFold(strings.TrimSpace(request.Header.Get("Upgrade")), "websocket") {
		return false
	}
	for _, value := range request.Header.Values("Connection") {
		for _, token := range strings.Split(value, ",") {
			if strings.EqualFold(strings.TrimSpace(token), "upgrade") {
				return true
			}
		}
	}
	return false
}

func ping(
	ctx context.Context,
	connection *websocket.Conn,
	interval time.Duration,
	errors chan<- error,
	cancel context.CancelFunc,
) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := connection.Ping(ctx); err != nil {
				select {
				case errors <- err:
				default:
				}
				cancel()
				return
			}
		}
	}
}

func parseAfter(request *http.Request) (uint64, error) {
	values, exists := request.URL.Query()["after"]
	if !exists {
		return 0, nil
	}
	if len(values) != 1 || values[0] == "" {
		return 0, domain.NewError(domain.ErrorInvalidArgument, "INVALID_CURSOR", "after must be a non-negative integer")
	}
	value := values[0]
	for _, character := range value {
		if character < '0' || character > '9' {
			return 0, domain.NewError(domain.ErrorInvalidArgument, "INVALID_CURSOR", "after must be a non-negative integer")
		}
	}
	after, err := strconv.ParseUint(value, 10, 64)
	if err != nil {
		return 0, domain.NewError(domain.ErrorInvalidArgument, "INVALID_CURSOR", "after is outside the supported range")
	}
	return after, nil
}

func validateBootstrap(bootstrap domain.StreamBootstrap, requestedAfter uint64) error {
	if bootstrap.AcceptedAfter != requestedAfter {
		return fmt.Errorf("accepted cursor differs from requested cursor")
	}
	if bootstrap.CurrentSequence < requestedAfter {
		return domain.NewError(domain.ErrorConflict, "CURSOR_AHEAD", "Replay cursor is ahead of the session cursor")
	}
	active := bootstrap.SessionState == "active" || bootstrap.SessionState == "closing"
	terminal := bootstrap.SessionState == "closed" || bootstrap.SessionState == "expired"
	if terminal && bootstrap.Stale {
		return domain.NewError(
			domain.ErrorNotFound,
			"NOT_FOUND",
			"Requested gateway resource was not found",
		)
	}
	if !active && !(terminal && !bootstrap.Stale) {
		return fmt.Errorf("stream session state is invalid")
	}
	if bootstrap.ActiveTurnID != nil && !contract.IsUUID(*bootstrap.ActiveTurnID) {
		return fmt.Errorf("active turn ID is invalid")
	}
	return nil
}

func controlEvent(sessionID string, eventType domain.EventType, sequence uint64, data any, timestamp time.Time) (domain.Event, error) {
	encoded, err := json.Marshal(data)
	if err != nil {
		return domain.Event{}, err
	}
	return domain.Event{
		ProtocolVersion: domain.ProtocolVersion,
		EventID:         auth.NewRequestID(),
		Sequence:        sequence,
		SessionID:       sessionID,
		TurnID:          nil,
		Type:            eventType,
		Timestamp:       domain.NewTimestamp(timestamp),
		Data:            encoded,
	}, nil
}

func (h Handler) fail(response http.ResponseWriter, request *http.Request, err error) {
	if h.OnFailure != nil {
		h.OnFailure(response, request, err)
		return
	}
	http.Error(response, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
}
