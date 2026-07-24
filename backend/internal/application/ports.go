package application

import (
	"context"
	"encoding/json"
	"time"
)

type Repository interface {
	IssueDevice(context.Context, []byte, string, time.Time) (Device, error)
	AuthenticateAndBindDevice(context.Context, []byte, string, time.Time) (Device, error)
	RevokeDevice(context.Context, string, time.Time) error

	CreateSession(context.Context, CreateSessionParams) (session Session, replayed bool, err error)
	GetSession(context.Context, string, string) (Session, error)
	GetSessionForWorker(context.Context, string) (Session, error)
	CloseSession(context.Context, CloseSessionParams) (Session, error)

	CreateTurn(context.Context, CreateTurnParams) (turn Turn, replayed bool, err error)
	GetTurn(context.Context, string, string) (Turn, error)
	SetTurnState(context.Context, string, string, TurnState, time.Time) (Turn, error)
	SetTurnTranscript(context.Context, string, string, string, time.Time) (Turn, error)
	SetTurnResponse(context.Context, string, string, string, time.Time) (Turn, error)
	FinishTurn(context.Context, string, string, TurnState, string, json.RawMessage, string, json.RawMessage, time.Time) (Turn, bool, error)
	CancelTurn(context.Context, CancelTurnParams) (turn Turn, changed bool, replayed bool, err error)

	AppendEvent(context.Context, AppendEventParams) (Event, error)
	ReplayWindow(context.Context, string, string, uint64, int) (ReplayWindow, error)
	ListEvents(context.Context, string, string, uint64, int) ([]Event, error)
	WaitForEvent(context.Context, string, uint64) error

	CreateToolCall(context.Context, CreateToolCallParams) (ToolCall, Event, error)
	GetToolCall(context.Context, string, string) (ToolCall, error)
	UpdateToolCall(context.Context, string, string, ToolStatus, json.RawMessage, json.RawMessage, time.Time) (ToolCall, bool, error)
	ExpireToolCalls(context.Context, time.Time, int) ([]ToolCall, error)

	ReportPlayback(context.Context, Device, string, string, []byte, Playback, time.Time) (Playback, bool, error)

	CreateArtifact(context.Context, Artifact) (Artifact, error)
	GetArtifact(context.Context, string, string, string, time.Time) (Artifact, error)
	LeaseArtifactForDeletion(context.Context, string, string, time.Time, time.Duration) (ArtifactLease, error)
	LeaseExpiredArtifacts(context.Context, string, time.Time, time.Duration, int) ([]ArtifactLease, error)
	MarkArtifactDeleted(context.Context, string, string, time.Time) error
	ReleaseArtifactLease(context.Context, string, string) error

	GetProviderState(context.Context, string) (ProviderState, error)
	PutProviderState(context.Context, ProviderState) error
	ProviderThreadKnown(context.Context, string) (bool, error)
	LeaseProviderThreadsForDeletion(context.Context, string, time.Time, time.Time, time.Duration, int) ([]ProviderThreadLease, error)
	MarkProviderThreadDeleted(context.Context, string, string, time.Time) error
	ReleaseProviderThreadLease(context.Context, string, string) error

	EnqueueJob(context.Context, Job) (Job, bool, error)
	LeaseJobs(context.Context, string, time.Time, time.Duration, int) ([]Job, error)
	CompleteJob(context.Context, string, string, time.Time) error
	RetryJob(context.Context, string, string, time.Time, time.Time, string) (bool, error)
	CancelSessionJobs(context.Context, string, time.Time) error
	WithSessionLock(context.Context, string, func(context.Context) error) error

	ExpireSessions(context.Context, time.Time, int) ([]Session, error)
	Prune(context.Context, time.Time, time.Duration, time.Duration, int) (PruneResult, error)
}

type PruneResult struct {
	Events      int64
	Idempotency int64
	Jobs        int64
	Sessions    int64
}

// Provider ports deliberately live in application. Provider packages implement
// adapters at wiring time, keeping orchestration independent of SDK types.
type Transcriber interface {
	Transcribe(context.Context, TranscriptionRequest) (Transcription, error)
}

type Synthesizer interface {
	Synthesize(context.Context, SpeechRequest) (Speech, error)
}

type LLM interface {
	Step(context.Context, StepRequest) (StepResponse, error)
}

// ThreadEnsurer creates provider-owned durable conversation state without
// starting a model turn. Workers persist the returned ID before calling Step.
type ThreadEnsurer interface {
	EnsureThread(context.Context, StepRequest) (string, error)
}

type Interruptible interface {
	Interrupt(context.Context, string, string) error
}

// ThreadDeleter removes provider-owned durable conversation history after the
// gateway retention lease has made deletion safe.
type ThreadDeleter interface {
	DeleteThread(context.Context, string) error
}

type ProviderThreadInfo struct {
	ID        string
	CreatedAt time.Time
}

type ProviderThreadPage struct {
	Threads    []ProviderThreadInfo
	NextCursor string
}

// ThreadLister lists only provider-owned threads from the Gateway's dedicated
// identity/cwd. Implementations must enforce their own source and page bounds.
type ThreadLister interface {
	ListThreads(context.Context, string, int) (ProviderThreadPage, error)
}

type Providers interface {
	ForSession(context.Context, Session) (ProviderSet, error)
}

type ProviderSet struct {
	LLM           LLM
	ThreadEnsurer ThreadEnsurer
	Transcriber   Transcriber
	Synthesizer   Synthesizer
	Interrupt     Interruptible
	ThreadDeleter ThreadDeleter
	ThreadLister  ThreadLister
	Model         string
	STTModel      string
	TTSModel      string
	Voice         string
}

type TranscriptionRequest struct {
	Audio    []byte
	Filename string
	MIMEType string
	Model    string
	Language string
	Prompt   string
}

type Transcription struct {
	Text       string
	Language   string
	DurationMS int64
}

type SpeechRequest struct {
	Text     string
	Model    string
	Voice    string
	Format   string
	Language string
	Speed    float64
}

type Speech struct {
	Audio    []byte
	MIMEType string
	Format   string
}

type Message struct {
	Role       string          `json:"role"`
	Content    string          `json:"content,omitempty"`
	ToolCallID string          `json:"toolCallId,omitempty"`
	ToolName   string          `json:"toolName,omitempty"`
	ToolOutput json.RawMessage `json:"toolOutput,omitempty"`
}

type ToolDefinition struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	InputSchema json.RawMessage `json:"inputSchema"`
}

type StepRequest struct {
	SessionID    string
	TurnID       string
	ThreadID     string
	Model        string
	SystemPrompt string
	Messages     []Message
	Tools        []ToolDefinition
	OutputSchema json.RawMessage
}

type LLMStepToolCall struct {
	ID        string
	Name      string
	Arguments json.RawMessage
}

type StepResponse struct {
	ThreadID     string
	ResponseID   string
	Text         string
	ToolCalls    []LLMStepToolCall
	InputTokens  int64
	OutputTokens int64
}
