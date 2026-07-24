package contract

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contractassets"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

type SessionReadyData struct {
	ResumedAfter uint64           `json:"resumedAfter"`
	GatewayTime  domain.Timestamp `json:"gatewayTime"`
}

type SessionSnapshotData struct {
	State        string  `json:"state"`
	LastSequence uint64  `json:"lastSequence"`
	ActiveTurnID *string `json:"activeTurnId,omitempty"`
}

type STTFinalData struct {
	Text     string `json:"text"`
	Language string `json:"language"`
}

type AgentTextFinalData struct {
	Text string `json:"text"`
}

type TTSReadyData struct {
	ArtifactID string           `json:"artifactId"`
	MIMEType   string           `json:"mimeType"`
	ByteLength int              `json:"byteLength"`
	SHA256     string           `json:"sha256"`
	ExpiresAt  domain.Timestamp `json:"expiresAt"`
}

type ToolCallData struct {
	CallID      string           `json:"callId"`
	ToolName    string           `json:"toolName"`
	ToolVersion string           `json:"toolVersion"`
	Arguments   json.RawMessage  `json:"arguments"`
	TimeoutMS   int              `json:"timeoutMs"`
	DeadlineAt  domain.Timestamp `json:"deadlineAt"`
}

type TurnErrorData struct {
	Error domain.ErrorDetail `json:"error"`
}

type TurnCancelledData struct {
	Reason string `json:"reason"`
}

type SessionExpiredData struct {
	Reason    string           `json:"reason"`
	ExpiredAt domain.Timestamp `json:"expiredAt"`
}

type SessionClosedData struct {
	Reason string `json:"reason"`
}

func ValidateEvent(event domain.Event) error {
	encoded, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("event cannot be encoded: %w", err)
	}
	if err := contractassets.ValidateWSEnvelope(encoded); err != nil {
		return fmt.Errorf("event schema validation failed: %w", err)
	}
	if event.ProtocolVersion != domain.ProtocolVersion {
		return fmt.Errorf("event protocolVersion must be %s", domain.ProtocolVersion)
	}
	if !IsUUID(event.EventID) || !IsUUID(event.SessionID) {
		return fmt.Errorf("eventId and sessionId must be UUIDs")
	}
	if event.Timestamp.Time.IsZero() {
		return fmt.Errorf("event timestamp is required")
	}
	if len(event.Data) == 0 {
		return fmt.Errorf("event data is required")
	}

	sessionScoped := event.Type == domain.EventSessionReady ||
		event.Type == domain.EventSessionSnapshot ||
		event.Type == domain.EventSessionExpired ||
		event.Type == domain.EventSessionClosed
	if sessionScoped {
		if event.TurnID != nil {
			return fmt.Errorf("%s must be session-scoped", event.Type)
		}
	} else if event.TurnID == nil || !IsUUID(*event.TurnID) {
		return fmt.Errorf("%s must carry a turnId", event.Type)
	}

	switch event.Type {
	case domain.EventSessionReady:
		var data SessionReadyData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if event.Sequence != data.ResumedAfter || data.GatewayTime.Time.IsZero() {
			return fmt.Errorf("session.ready cursor or gatewayTime is invalid")
		}
	case domain.EventSessionSnapshot:
		var data SessionSnapshotData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if event.Sequence != data.LastSequence || (data.State != "active" && data.State != "closing") {
			return fmt.Errorf("session.snapshot state or cursor is invalid")
		}
		if data.ActiveTurnID != nil && !IsUUID(*data.ActiveTurnID) {
			return fmt.Errorf("session.snapshot activeTurnId is invalid")
		}
	case domain.EventTurnAccepted, domain.EventAgentThinking, domain.EventTurnCompleted:
		var data struct{}
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
	case domain.EventSTTFinal:
		var data STTFinalData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if length(data.Text, 1, 16_000) != nil || length(data.Language, 2, 35) != nil {
			return fmt.Errorf("stt.final data is invalid")
		}
	case domain.EventAgentTextFinal:
		var data AgentTextFinalData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if length(data.Text, 1, 16_000) != nil {
			return fmt.Errorf("agent.text.final text is invalid")
		}
	case domain.EventTTSReady:
		var data TTSReadyData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if !IsUUID(data.ArtifactID) || (data.MIMEType != "audio/mpeg" && data.MIMEType != "audio/wav") ||
			data.ByteLength < 1 || data.ByteLength > domain.MaxOutputAudioBytes ||
			!sha256Pattern.MatchString(data.SHA256) || data.ExpiresAt.Time.IsZero() {
			return fmt.Errorf("tts.ready data is invalid")
		}
	case domain.EventToolCall:
		var data ToolCallData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if !IsUUID(data.CallID) {
			return fmt.Errorf("tool.call callId is invalid")
		}
		if _, allowed := allowedTools[data.ToolName]; !allowed || !versionPattern.MatchString(data.ToolVersion) {
			return fmt.Errorf("tool.call tool identity is invalid")
		}
		var arguments map[string]json.RawMessage
		if err := json.Unmarshal(data.Arguments, &arguments); err != nil || arguments == nil {
			return fmt.Errorf("tool.call arguments must be an object")
		}
		if data.TimeoutMS < 100 || data.TimeoutMS > 15_000 || data.DeadlineAt.Time.IsZero() {
			return fmt.Errorf("tool.call timeout is invalid")
		}
	case domain.EventTurnError:
		var data TurnErrorData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if err := ValidateErrorDetail(data.Error); err != nil {
			return err
		}
	case domain.EventTurnCancelled:
		var data TurnCancelledData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		if err := ValidateCancelTurn(domain.CancelTurnRequest{Reason: data.Reason}); err != nil {
			return err
		}
	case domain.EventSessionExpired:
		var data SessionExpiredData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		switch data.Reason {
		case "idle_timeout", "credential_revoked", "server_policy":
		default:
			return fmt.Errorf("session.expired reason is invalid")
		}
		if data.ExpiredAt.Time.IsZero() {
			return fmt.Errorf("session.expired expiredAt is invalid")
		}
	case domain.EventSessionClosed:
		var data SessionClosedData
		if err := decodeEventData(event.Data, &data); err != nil {
			return err
		}
		switch data.Reason {
		case "client_request", "expired", "replaced", "policy":
		default:
			return fmt.Errorf("session.closed reason is invalid")
		}
	default:
		return fmt.Errorf("event type is invalid")
	}
	return nil
}

func decodeEventData(data json.RawMessage, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("event data is invalid: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return fmt.Errorf("event data contains trailing JSON")
	}
	return nil
}
