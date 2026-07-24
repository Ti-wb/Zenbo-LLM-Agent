package worker

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contractassets"
)

// Repository is the durable boundary required by the process-turn worker and
// its maintenance sweepers. *store.Postgres implements this interface.
type Repository interface {
	GetSessionForWorker(context.Context, string) (application.Session, error)
	GetTurn(context.Context, string, string) (application.Turn, error)
	SetTurnState(context.Context, string, string, application.TurnState, time.Time) (application.Turn, error)
	SetTurnTranscript(context.Context, string, string, string, time.Time) (application.Turn, error)
	SetTurnResponse(context.Context, string, string, string, time.Time) (application.Turn, error)
	FinishTurn(
		context.Context,
		string,
		string,
		application.TurnState,
		string,
		json.RawMessage,
		string,
		json.RawMessage,
		time.Time,
	) (application.Turn, bool, error)

	AppendEvent(context.Context, application.AppendEventParams) (application.Event, error)
	ListEvents(context.Context, string, string, uint64, int) ([]application.Event, error)

	CreateToolCall(context.Context, application.CreateToolCallParams) (application.ToolCall, application.Event, error)
	GetToolCall(context.Context, string, string) (application.ToolCall, error)
	UpdateToolCall(
		context.Context,
		string,
		string,
		application.ToolStatus,
		json.RawMessage,
		json.RawMessage,
		time.Time,
	) (application.ToolCall, bool, error)
	ExpireToolCalls(context.Context, time.Time, int) ([]application.ToolCall, error)

	CreateArtifact(context.Context, application.Artifact) (application.Artifact, error)
	GetArtifact(context.Context, string, string, string, time.Time) (application.Artifact, error)
	LeaseArtifactForDeletion(context.Context, string, string, time.Time, time.Duration) (application.ArtifactLease, error)
	LeaseExpiredArtifacts(context.Context, string, time.Time, time.Duration, int) ([]application.ArtifactLease, error)
	MarkArtifactDeleted(context.Context, string, string, time.Time) error
	ReleaseArtifactLease(context.Context, string, string) error
	BlobKeyExists(context.Context, string) (bool, error)

	GetProviderState(context.Context, string) (application.ProviderState, error)
	PutProviderState(context.Context, application.ProviderState) error
	LeaseProviderThreadsForDeletion(context.Context, string, time.Time, time.Time, time.Duration, int) ([]application.ProviderThreadLease, error)
	MarkProviderThreadDeleted(context.Context, string, string, time.Time) error
	ReleaseProviderThreadLease(context.Context, string, string) error
	ProviderThreadKnown(context.Context, string) (bool, error)

	LeaseJobs(context.Context, string, time.Time, time.Duration, int) ([]application.Job, error)
	CompleteJob(context.Context, string, string, time.Time) error
	RetryJob(context.Context, string, string, time.Time, time.Time, string) (bool, error)
	WithSessionLock(context.Context, string, func(context.Context) error) error

	ExpireSessions(context.Context, time.Time, int) ([]application.Session, error)
	Prune(context.Context, time.Time, time.Duration, time.Duration, int) (application.PruneResult, error)
}

type Config struct {
	WorkerID string
	Clock    func() time.Time
	Logger   *slog.Logger
	// OnLeaseCycle is an optional, non-blocking metrics hook. It must not log
	// job payloads or other conversational data.
	OnLeaseCycle func(processed int, err error)

	LeaseDuration   time.Duration
	LeaseBatchSize  int
	IdlePoll        time.Duration
	TurnPoll        time.Duration
	ToolPoll        time.Duration
	ProviderTimeout time.Duration

	MaxToolSteps              int
	MaxConversationMessages   int
	MaxToolTimeout            time.Duration
	TTSLifetime               time.Duration
	ArtifactDeletionLease     time.Duration
	ProviderCleanupLease      time.Duration
	ProviderOrphanGrace       time.Duration
	IncompleteBlobAge         time.Duration
	RetryBase                 time.Duration
	RetryMaximum              time.Duration
	MaintenanceInterval       time.Duration
	MaintenanceBatchSize      int
	TranscriptRetention       time.Duration
	TerminalResourceRetention time.Duration
}

func (config *Config) defaults() {
	if config.Clock == nil {
		config.Clock = time.Now
	}
	if config.Logger == nil {
		config.Logger = slog.Default()
	}
	if config.WorkerID == "" {
		config.WorkerID = "worker-" + newUUID()
	}
	if config.LeaseDuration <= 0 {
		config.LeaseDuration = 10 * time.Minute
	}
	if config.LeaseBatchSize <= 0 {
		config.LeaseBatchSize = 1
	}
	if config.IdlePoll <= 0 {
		config.IdlePoll = 250 * time.Millisecond
	}
	if config.TurnPoll <= 0 {
		config.TurnPoll = 100 * time.Millisecond
	}
	if config.ToolPoll <= 0 {
		config.ToolPoll = 100 * time.Millisecond
	}
	if config.ProviderTimeout <= 0 {
		config.ProviderTimeout = 2 * time.Minute
	}
	if config.MaxToolSteps <= 0 {
		config.MaxToolSteps = 8
	}
	if config.MaxConversationMessages <= 0 {
		config.MaxConversationMessages = 80
	}
	if config.MaxToolTimeout <= 0 || config.MaxToolTimeout > 5*time.Second {
		config.MaxToolTimeout = 5 * time.Second
	}
	if config.TTSLifetime <= 0 {
		config.TTSLifetime = 30 * time.Minute
	}
	if config.ArtifactDeletionLease <= 0 {
		config.ArtifactDeletionLease = time.Minute
	}
	if config.ProviderCleanupLease <= 0 {
		config.ProviderCleanupLease = config.ProviderTimeout + time.Minute
	}
	if config.ProviderOrphanGrace <= 0 {
		config.ProviderOrphanGrace = 10 * time.Minute
	}
	if config.IncompleteBlobAge <= 0 {
		config.IncompleteBlobAge = 10 * time.Minute
	}
	if config.RetryBase <= 0 {
		config.RetryBase = time.Second
	}
	if config.RetryMaximum <= 0 {
		config.RetryMaximum = time.Minute
	}
	if config.MaintenanceInterval <= 0 {
		config.MaintenanceInterval = 30 * time.Second
	}
	if config.MaintenanceBatchSize <= 0 {
		config.MaintenanceBatchSize = 100
	}
	if config.TranscriptRetention <= 0 {
		config.TranscriptRetention = 7 * 24 * time.Hour
	}
	if config.TerminalResourceRetention <= 0 {
		config.TerminalResourceRetention = 24 * time.Hour
	}
}

type Worker struct {
	repository Repository
	blobs      blob.Store
	providers  application.Providers
	config     Config

	threadCatalogMu      sync.Mutex
	threadCatalogCursors map[string]string
}

func New(
	repository Repository,
	blobs blob.Store,
	providers application.Providers,
	config Config,
) (*Worker, error) {
	if err := contractassets.Verify(); err != nil {
		return nil, errConfiguration("embedded contracts are invalid: " + err.Error())
	}
	if repository == nil {
		return nil, errConfiguration("worker repository is required")
	}
	if blobs == nil {
		return nil, errConfiguration("worker blob store is required")
	}
	if providers == nil {
		return nil, errConfiguration("worker providers registry is required")
	}
	config.defaults()
	if config.LeaseDuration <= config.ProviderTimeout {
		return nil, errConfiguration("job lease duration must exceed provider timeout")
	}
	if config.ProviderCleanupLease <= config.ProviderTimeout {
		return nil, errConfiguration("provider cleanup lease must exceed provider timeout")
	}
	if config.RetryMaximum < config.RetryBase {
		return nil, errConfiguration("retry maximum must not be less than retry base")
	}
	return &Worker{
		repository:           repository,
		blobs:                blobs,
		providers:            providers,
		config:               config,
		threadCatalogCursors: make(map[string]string),
	}, nil
}
