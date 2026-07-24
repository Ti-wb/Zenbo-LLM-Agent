package application

import (
	"encoding/json"
	"time"
)

const ProtocolVersion = "1.0"

type Device struct {
	ID         string
	DeviceID   string
	Label      string
	CreatedAt  time.Time
	BoundAt    *time.Time
	RevokedAt  *time.Time
	LastSeenAt *time.Time
}

type SessionState string

const (
	SessionActive  SessionState = "active"
	SessionClosing SessionState = "closing"
	SessionClosed  SessionState = "closed"
	SessionExpired SessionState = "expired"
)

func (state SessionState) Terminal() bool {
	return state == SessionClosed || state == SessionExpired
}

type Session struct {
	ID              string
	DeviceRowID     string
	DeviceID        string
	ProtocolVersion string
	State           SessionState
	AgentProfile    string
	ProviderKind    string
	ProviderProfile string
	Client          json.RawMessage
	Context         json.RawMessage
	ToolManifest    json.RawMessage
	LastSequence    uint64
	CreatedAt       time.Time
	UpdatedAt       time.Time
	ExpiresAt       time.Time
	ClosedAt        *time.Time
	CloseReason     string
}

type TurnState string

const (
	TurnAccepted       TurnState = "accepted"
	TurnProcessing     TurnState = "processing"
	TurnWaitingForTool TurnState = "waiting_for_tool"
	TurnCompleted      TurnState = "completed"
	TurnCancelled      TurnState = "cancelled"
	TurnFailed         TurnState = "failed"
)

func (state TurnState) Terminal() bool {
	return state == TurnCompleted || state == TurnCancelled || state == TurnFailed
}

type Turn struct {
	ID              string
	SessionID       string
	ClientTurnID    string
	State           TurnState
	InputKind       string
	InputText       string
	InputArtifactID string
	Language        string
	Transcript      string
	ResponseText    string
	CancelReason    string
	Error           json.RawMessage
	AcceptedAt      time.Time
	UpdatedAt       time.Time
	TerminalAt      *time.Time
}

type Event struct {
	ProtocolVersion string          `json:"protocolVersion"`
	EventID         string          `json:"eventId"`
	Sequence        uint64          `json:"sequence"`
	SessionID       string          `json:"sessionId"`
	TurnID          *string         `json:"turnId"`
	Type            string          `json:"type"`
	Timestamp       time.Time       `json:"timestamp"`
	Data            json.RawMessage `json:"data"`
}

type ReplayWindow struct {
	CurrentSequence uint64
	OldestSequence  uint64
	Stale           bool
	SessionState    SessionState
	ActiveTurnID    string
	Events          []Event
}

type IdempotencyRecord struct {
	DeviceID       string
	Operation      string
	Key            string
	RequestDigest  []byte
	SessionID      string
	ResourceID     string
	ResponseStatus int
	ResponseBody   json.RawMessage
	State          string
	CreatedAt      time.Time
	ExpiresAt      time.Time
}

type ToolStatus string

const (
	ToolPending   ToolStatus = "pending"
	ToolAccepted  ToolStatus = "accepted"
	ToolSucceeded ToolStatus = "succeeded"
	ToolFailed    ToolStatus = "failed"
	ToolRejected  ToolStatus = "rejected"
)

func (state ToolStatus) Terminal() bool {
	return state == ToolSucceeded || state == ToolFailed || state == ToolRejected
}

type ToolCall struct {
	ID         string
	SessionID  string
	TurnID     string
	Name       string
	Version    string
	Owner      string
	SideEffect string
	Arguments  json.RawMessage
	Status     ToolStatus
	Output     json.RawMessage
	Error      json.RawMessage
	DeadlineAt time.Time
	TimeoutMS  int
	CreatedAt  time.Time
	UpdatedAt  time.Time
	TerminalAt *time.Time
}

type Playback struct {
	SessionID  string
	TurnID     string
	ArtifactID string
	Status     string
	ReportedAt time.Time
	PositionMS *int
	Reason     string
	UpdatedAt  time.Time
}

type Artifact struct {
	ID                 string
	SessionID          string
	TurnID             string
	Kind               string
	BlobKey            string
	MIMEType           string
	ByteLength         int64
	SHA256             []byte
	CreatedAt          time.Time
	ExpiresAt          time.Time
	DeletionState      string
	DeletionLeaseOwner string
	DeletionLeaseUntil *time.Time
	DeletedAt          *time.Time
}

type ProviderState struct {
	SessionID       string
	ProviderKind    string
	ProviderProfile string
	RemoteThreadID  string
	OpaqueState     json.RawMessage
	CleanupState    string
	CleanupOwner    string
	CleanupUntil    *time.Time
	CleanedAt       *time.Time
	UpdatedAt       time.Time
}

type ProviderThreadLease struct {
	SessionID       string
	ProviderKind    string
	ProviderProfile string
	RemoteThreadID  string
	LeaseOwner      string
	LeaseUntil      time.Time
}

type Job struct {
	ID          string
	Type        string
	SessionID   string
	TurnID      string
	DedupeKey   string
	Payload     json.RawMessage
	Status      string
	Attempts    int
	MaxAttempts int
	AvailableAt time.Time
	LeaseOwner  string
	LeaseUntil  *time.Time
	LastError   string
	CreatedAt   time.Time
	UpdatedAt   time.Time
	CompletedAt *time.Time
}

type CreateSessionParams struct {
	ID               string
	Device           Device
	IdempotencyKey   string
	RequestDigest    []byte
	IdempotencyUntil time.Time
	AgentProfile     string
	ProviderKind     string
	ProviderProfile  string
	Client           json.RawMessage
	Context          json.RawMessage
	ToolManifest     json.RawMessage
	Now              time.Time
	ExpiresAt        time.Time
}

type CreateTurnParams struct {
	ID               string
	SessionID        string
	Device           Device
	ClientTurnID     string
	IdempotencyKey   string
	RequestDigest    []byte
	IdempotencyUntil time.Time
	InputKind        string
	InputText        string
	InputArtifactID  string
	InputArtifact    *Artifact
	Language         string
	Now              time.Time
}

type CloseSessionParams struct {
	SessionID string
	DeviceID  string
	State     SessionState
	Reason    string
	Now       time.Time
}

type CancelTurnParams struct {
	SessionID        string
	TurnID           string
	Device           Device
	IdempotencyKey   string
	RequestDigest    []byte
	IdempotencyUntil time.Time
	Reason           string
	Now              time.Time
}

type AppendEventParams struct {
	SessionID string
	TurnID    string
	EventID   string
	Type      string
	Timestamp time.Time
	Data      json.RawMessage
}

type CreateToolCallParams struct {
	ToolCall
	EventID   string
	EventData json.RawMessage
}

type ArtifactLease struct {
	Artifact
}
