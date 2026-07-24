package domain

import (
	"encoding/json"
	"fmt"
	"io"
	"time"
)

const (
	ProtocolVersion      = "1.0"
	MaxJSONBytes         = 64 * 1024
	MaxInputAudioBytes   = 2 * 1024 * 1024
	MaxOutputAudioBytes  = 10 * 1024 * 1024
	MaxToolOutputBytes   = 16 * 1024
	MaxInputDurationMS   = 30_000
	DeviceToolVersion    = "1.0.0"
	DefaultToolTimeoutMS = 5_000
)

type Principal struct {
	TokenID  string
	DeviceID string
}

// Timestamp always serializes in the Android-compatible UTC millisecond form.
type Timestamp struct {
	time.Time
}

func NewTimestamp(value time.Time) Timestamp {
	return Timestamp{Time: value.UTC()}
}

func (t Timestamp) MarshalJSON() ([]byte, error) {
	if t.Time.IsZero() {
		return nil, fmt.Errorf("timestamp is zero")
	}
	return json.Marshal(t.UTC().Format("2006-01-02T15:04:05.000Z"))
}

func (t *Timestamp) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil {
		return fmt.Errorf("timestamp must be a string: %w", err)
	}
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return fmt.Errorf("timestamp must be RFC 3339: %w", err)
	}
	t.Time = parsed.UTC()
	return nil
}

type Capabilities struct {
	ProtocolVersion string          `json:"protocolVersion"`
	AudioInput      AudioInput      `json:"audioInput"`
	AudioOutput     AudioOutput     `json:"audioOutput"`
	EventTypes      []EventType     `json:"eventTypes"`
	AgentProfiles   []AgentProfile  `json:"agentProfiles"`
	RetentionPolicy RetentionPolicy `json:"retentionPolicy"`
}

type AudioInput struct {
	ContentTypes  []string `json:"contentTypes"`
	MaxBytes      int      `json:"maxBytes"`
	MaxDurationMS int      `json:"maxDurationMs"`
}

type AudioOutput struct {
	ContentTypes []string `json:"contentTypes"`
	MaxBytes     int      `json:"maxBytes"`
}

type AgentProfile struct {
	ID          string   `json:"id"`
	DisplayName string   `json:"displayName"`
	Languages   []string `json:"languages"`
	IsDefault   bool     `json:"isDefault,omitempty"`
}

type RetentionPolicy struct {
	RawAudio   RetentionRule `json:"rawAudio"`
	Transcript RetentionRule `json:"transcript"`
}

type RetentionRule struct {
	Retained      bool `json:"retained"`
	MaxAgeSeconds int  `json:"maxAgeSeconds"`
}

type CreateSessionRequest struct {
	Client       ClientDescriptor `json:"client"`
	AgentProfile string           `json:"agentProfile"`
	Context      RobotContext     `json:"context"`
	ToolManifest ToolManifest     `json:"toolManifest"`
}

type ClientDescriptor struct {
	AppVersion string `json:"appVersion"`
	Platform   string `json:"platform"`
	RobotModel string `json:"robotModel"`
	OSVersion  string `json:"osVersion,omitempty"`
	Locale     string `json:"locale"`
}

type RobotContext struct {
	RobotName string `json:"robotName"`
	Language  string `json:"language"`
}

type ToolManifest struct {
	ProtocolVersion string           `json:"protocolVersion"`
	ManifestVersion string           `json:"manifestVersion"`
	Tools           []ToolDefinition `json:"tools"`
}

type ToolDefinition struct {
	Name                 string       `json:"name"`
	Owner                string       `json:"owner"`
	Version              string       `json:"version"`
	Description          string       `json:"description"`
	InputSchema          ObjectSchema `json:"inputSchema"`
	ResultSchema         ObjectSchema `json:"resultSchema"`
	SideEffect           string       `json:"sideEffect"`
	Idempotent           bool         `json:"idempotent"`
	RequiresConfirmation bool         `json:"requiresConfirmation"`
	TimeoutMS            int          `json:"timeoutMs"`
	requiredBooleansSet  bool
}

type ObjectSchema struct {
	Type                 string                     `json:"type"`
	Properties           map[string]json.RawMessage `json:"properties,omitempty"`
	Required             []string                   `json:"required,omitempty"`
	AdditionalProperties bool                       `json:"additionalProperties"`
	additionalSet        bool
}

func (t ToolDefinition) HasRequiredBooleans() bool {
	return t.requiredBooleansSet
}

func (t *ToolDefinition) UnmarshalJSON(data []byte) error {
	type alias ToolDefinition
	var decoded alias
	if err := json.Unmarshal(data, &decoded); err != nil {
		return err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return err
	}
	_, hasIdempotent := fields["idempotent"]
	_, hasConfirmation := fields["requiresConfirmation"]
	*t = ToolDefinition(decoded)
	t.requiredBooleansSet = hasIdempotent && hasConfirmation
	return nil
}

func (s ObjectSchema) HasAdditionalProperties() bool {
	return s.additionalSet
}

func (s *ObjectSchema) UnmarshalJSON(data []byte) error {
	type alias ObjectSchema
	var decoded alias
	if err := json.Unmarshal(data, &decoded); err != nil {
		return err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return err
	}
	_, hasAdditional := fields["additionalProperties"]
	*s = ObjectSchema(decoded)
	s.additionalSet = hasAdditional
	return nil
}

type Session struct {
	SessionID       string    `json:"sessionId"`
	DeviceID        string    `json:"deviceId"`
	ProtocolVersion string    `json:"protocolVersion"`
	State           string    `json:"state"`
	CreatedAt       Timestamp `json:"createdAt"`
	ExpiresAt       Timestamp `json:"expiresAt"`
	LastSequence    uint64    `json:"lastSequence"`
}

type TextTurn struct {
	ClientTurnID string `json:"clientTurnId"`
	Text         string `json:"text"`
	Language     string `json:"language,omitempty"`
}

type AudioTurn struct {
	ClientTurnID string
	Audio        []byte
	DurationMS   int
	Language     string
}

type TurnAccepted struct {
	SessionID    string    `json:"sessionId"`
	TurnID       string    `json:"turnId"`
	ClientTurnID string    `json:"clientTurnId"`
	State        string    `json:"state"`
	AcceptedAt   Timestamp `json:"acceptedAt"`
}

type CancelTurnRequest struct {
	Reason string `json:"reason"`
}

type TurnState struct {
	TurnID    string    `json:"turnId"`
	State     string    `json:"state"`
	UpdatedAt Timestamp `json:"updatedAt"`
}

type ErrorDetail struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

type ToolCallUpdate struct {
	Status    string          `json:"status"`
	UpdatedAt Timestamp       `json:"updatedAt"`
	Output    json.RawMessage `json:"output,omitempty"`
	Error     *ErrorDetail    `json:"error,omitempty"`
}

type PlaybackUpdate struct {
	TurnID     string    `json:"turnId"`
	ArtifactID string    `json:"artifactId"`
	Status     string    `json:"status"`
	Timestamp  Timestamp `json:"timestamp"`
	PositionMS *int      `json:"positionMs,omitempty"`
	Reason     string    `json:"reason,omitempty"`
}

type AudioArtifact struct {
	ArtifactID string
	MIMEType   string
	ByteLength int64
	SHA256Hex  string
	ExpiresAt  Timestamp
	Body       io.ReadCloser
}

type EventType string

const (
	EventSessionReady    EventType = "session.ready"
	EventSessionSnapshot EventType = "session.snapshot"
	EventTurnAccepted    EventType = "turn.accepted"
	EventSTTFinal        EventType = "stt.final"
	EventAgentThinking   EventType = "agent.thinking"
	EventToolCall        EventType = "tool.call"
	EventAgentTextFinal  EventType = "agent.text.final"
	EventTTSReady        EventType = "tts.ready"
	EventTurnCompleted   EventType = "turn.completed"
	EventTurnError       EventType = "turn.error"
	EventSessionExpired  EventType = "session.expired"
	EventTurnCancelled   EventType = "turn.cancelled"
	EventSessionClosed   EventType = "session.closed"
)

var AllEventTypes = []EventType{
	EventSessionReady,
	EventSessionSnapshot,
	EventTurnAccepted,
	EventSTTFinal,
	EventAgentThinking,
	EventToolCall,
	EventAgentTextFinal,
	EventTTSReady,
	EventTurnCompleted,
	EventTurnError,
	EventSessionExpired,
	EventTurnCancelled,
	EventSessionClosed,
}

type Event struct {
	ProtocolVersion string          `json:"protocolVersion"`
	EventID         string          `json:"eventId"`
	Sequence        uint64          `json:"sequence"`
	SessionID       string          `json:"sessionId"`
	TurnID          *string         `json:"turnId"`
	Type            EventType       `json:"type"`
	Timestamp       Timestamp       `json:"timestamp"`
	Data            json.RawMessage `json:"data"`
}

type StreamBootstrap struct {
	AcceptedAfter   uint64
	CurrentSequence uint64
	Stale           bool
	SessionState    string
	ActiveTurnID    *string
}
