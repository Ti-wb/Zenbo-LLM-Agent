// Package provider defines the provider-neutral boundary used by the gateway
// worker. Concrete provider implementations live in subpackages.
package provider

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Role is a model conversation role.
type Role string

const (
	RoleSystem    Role = "system"
	RoleUser      Role = "user"
	RoleAssistant Role = "assistant"
	RoleTool      Role = "tool"
)

// Message is one item in a provider-neutral model conversation.
//
// Tool responses set RoleTool and ToolCallID. Assistant messages that invoked
// tools retain the calls in ToolCalls, allowing stateless providers to rebuild
// the complete conversation on every request.
type Message struct {
	Role       Role
	Content    string
	ToolCallID string
	ToolCalls  []ToolCall
}

// ToolDefinition describes one function tool exposed to the model. Parameters
// must contain a JSON Schema object.
type ToolDefinition struct {
	Name        string
	Description string
	Parameters  json.RawMessage
	Strict      bool
}

// ToolCall is a model-requested invocation. Arguments is always a JSON object
// when returned by a conforming implementation.
type ToolCall struct {
	ID        string
	Name      string
	Arguments json.RawMessage
}

// Usage is normalized token accounting when supplied by the provider.
type Usage struct {
	InputTokens  int64
	OutputTokens int64
	TotalTokens  int64
}

// StepRequest asks an LLM for the next agent step. ThreadID is opaque
// provider state: it is empty for a new Codex conversation and ignored by
// stateless OpenAI-compatible providers.
type StepRequest struct {
	SessionID    string
	ThreadID     string
	Model        string
	SystemPrompt string
	Messages     []Message
	Tools        []ToolDefinition
	OutputSchema json.RawMessage
}

// StepResponse is either a final textual response or one or more tool calls.
// A response may include both for providers that emit commentary with calls.
type StepResponse struct {
	ThreadID   string
	TurnID     string
	ResponseID string
	Text       string
	ToolCalls  []ToolCall
	Usage      Usage
}

// LLM performs one provider-neutral agent step.
type LLM interface {
	Step(context.Context, StepRequest) (StepResponse, error)
}

// ThreadEnsurer creates a durable remote thread without starting a model turn.
type ThreadEnsurer interface {
	EnsureThread(context.Context, StepRequest) (string, error)
}

// Interruptible is implemented by providers, such as Codex app-server, that
// expose explicit cancellation in addition to context cancellation.
type Interruptible interface {
	Interrupt(ctx context.Context, threadID, turnID string) error
}

type ThreadInfo struct {
	ID        string
	CreatedAt time.Time
}

type ThreadPage struct {
	Threads    []ThreadInfo
	NextCursor string
}

type ThreadLister interface {
	ListThreads(context.Context, string, int) (ThreadPage, error)
}

// TranscriptionRequest contains a bounded, complete audio upload. The gateway
// contract limits this value to 2 MiB, so keeping it in memory makes ownership
// and retry behavior explicit.
type TranscriptionRequest struct {
	Audio    []byte
	Filename string
	MIMEType string
	Model    string
	Language string
	Prompt   string
}

// Transcription is normalized speech-to-text output.
type Transcription struct {
	Text     string
	Language string
	Duration time.Duration
}

// Transcriber converts speech audio to text.
type Transcriber interface {
	Transcribe(context.Context, TranscriptionRequest) (Transcription, error)
}

// SpeechRequest contains provider-neutral text-to-speech options.
type SpeechRequest struct {
	Text     string
	Model    string
	Voice    string
	Format   string
	Language string
	Speed    float64
}

// Speech is a complete, bounded audio artifact.
type Speech struct {
	Audio    []byte
	MIMEType string
	Format   string
}

// Synthesizer converts text to speech.
type Synthesizer interface {
	Synthesize(context.Context, SpeechRequest) (Speech, error)
}

// ErrorKind classifies provider failures without leaking vendor-specific
// response shapes into the application.
type ErrorKind string

const (
	ErrorInvalidRequest ErrorKind = "invalid_request"
	ErrorAuthentication ErrorKind = "authentication"
	ErrorPermission     ErrorKind = "permission"
	ErrorRateLimited    ErrorKind = "rate_limited"
	ErrorUnavailable    ErrorKind = "unavailable"
	ErrorTimeout        ErrorKind = "timeout"
	ErrorCancelled      ErrorKind = "cancelled"
	ErrorMalformed      ErrorKind = "malformed_response"
	ErrorInternal       ErrorKind = "internal"
)

// Error is the normalized failure returned by concrete providers. Message
// must be safe to log and must never contain credentials or full request
// payloads.
type Error struct {
	Provider   string
	Operation  string
	Kind       ErrorKind
	StatusCode int
	RetryAfter time.Duration
	Message    string
	Err        error
}

func (e *Error) Error() string {
	if e == nil {
		return "<nil>"
	}
	prefix := strings.TrimSpace(strings.Join([]string{e.Provider, e.Operation}, " "))
	if prefix == "" {
		prefix = "provider"
	}
	if e.Message != "" {
		return fmt.Sprintf("%s: %s", prefix, e.Message)
	}
	if e.Err != nil {
		return fmt.Sprintf("%s: %v", prefix, e.Err)
	}
	return prefix + ": request failed"
}

func (e *Error) Unwrap() error { return e.Err }

// FromHTTPStatus classifies a provider HTTP failure.
func FromHTTPStatus(providerName, operation string, status int, retryAfter string, message string) *Error {
	kind := ErrorInternal
	switch {
	case status == http.StatusBadRequest || status == http.StatusUnprocessableEntity:
		kind = ErrorInvalidRequest
	case status == http.StatusUnauthorized:
		kind = ErrorAuthentication
	case status == http.StatusForbidden:
		kind = ErrorPermission
	case status == http.StatusRequestTimeout || status == http.StatusGatewayTimeout:
		kind = ErrorTimeout
	case status == http.StatusTooManyRequests:
		kind = ErrorRateLimited
	case status >= 500:
		kind = ErrorUnavailable
	}
	return &Error{
		Provider:   providerName,
		Operation:  operation,
		Kind:       kind,
		StatusCode: status,
		RetryAfter: parseRetryAfter(retryAfter),
		Message:    message,
	}
}

// FromContext normalizes cancellation and deadline failures.
func FromContext(providerName, operation string, err error) error {
	switch {
	case errors.Is(err, context.Canceled):
		return &Error{Provider: providerName, Operation: operation, Kind: ErrorCancelled, Message: "request cancelled", Err: err}
	case errors.Is(err, context.DeadlineExceeded):
		return &Error{Provider: providerName, Operation: operation, Kind: ErrorTimeout, Message: "request timed out", Err: err}
	default:
		return err
	}
}

func parseRetryAfter(value string) time.Duration {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0
	}
	if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
		return time.Duration(seconds) * time.Second
	}
	if when, err := http.ParseTime(value); err == nil {
		delay := time.Until(when)
		if delay > 0 {
			return delay
		}
	}
	return 0
}
