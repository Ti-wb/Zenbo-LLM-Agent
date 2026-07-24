package httpapi

import (
	"context"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/wsstream"
)

// Service is the transport-neutral application boundary used by all HTTP
// handlers. Implementations own transactions, idempotency and state machines.
type Service interface {
	Capabilities(context.Context, domain.Principal) (domain.Capabilities, error)
	CreateSession(context.Context, domain.Principal, string, domain.CreateSessionRequest) (domain.Session, error)
	GetSession(context.Context, domain.Principal, string) (domain.Session, error)
	CloseSession(context.Context, domain.Principal, string) error
	CreateTextTurn(context.Context, domain.Principal, string, string, domain.TextTurn) (domain.TurnAccepted, error)
	CreateAudioTurn(context.Context, domain.Principal, string, string, domain.AudioTurn) (domain.TurnAccepted, error)
	CancelTurn(context.Context, domain.Principal, string, string, string, domain.CancelTurnRequest) (domain.TurnState, error)
	PutToolCallUpdate(context.Context, domain.Principal, string, string, domain.ToolCallUpdate) (domain.ToolCallUpdate, error)
	GetAudioArtifact(context.Context, domain.Principal, string, string) (domain.AudioArtifact, error)
	ReportPlayback(context.Context, domain.Principal, string, string, domain.PlaybackUpdate) error
	OpenEventStream(context.Context, domain.Principal, string, uint64) (wsstream.EventStream, error)
}

type EventStream = wsstream.EventStream
