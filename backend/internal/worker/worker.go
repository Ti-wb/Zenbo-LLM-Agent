package worker

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

// Run leases process_turn jobs until ctx is cancelled. Maintenance failures
// are logged with bounded metadata and retried on the next interval; a
// transient database outage therefore does not terminate the worker process.
func (worker *Worker) Run(ctx context.Context) error {
	maintenanceDone := make(chan struct{})
	go func() {
		defer close(maintenanceDone)
		worker.runMaintenance(ctx)
	}()

	for {
		processed, err := worker.RunOnce(ctx)
		if worker.config.OnLeaseCycle != nil {
			worker.config.OnLeaseCycle(processed, err)
		}
		if err != nil && !errors.Is(err, context.Canceled) {
			worker.config.Logger.Error("gateway worker lease cycle failed", "error", safeError(err))
		}
		if ctx.Err() != nil {
			<-maintenanceDone
			return nil
		}
		if processed == 0 {
			if err := waitContext(ctx, worker.config.IdlePoll); err != nil {
				<-maintenanceDone
				return nil
			}
		}
	}
}

// RunOnce leases and handles at most Config.LeaseBatchSize ready jobs.
func (worker *Worker) RunOnce(ctx context.Context) (int, error) {
	now := worker.now()
	jobs, err := worker.repository.LeaseJobs(
		ctx,
		worker.config.WorkerID,
		now,
		worker.config.LeaseDuration,
		worker.config.LeaseBatchSize,
	)
	if err != nil {
		return 0, err
	}
	var cycleError error
	for _, job := range jobs {
		if err := worker.handleJob(ctx, job); err != nil && ctx.Err() == nil {
			cycleError = errors.Join(cycleError, err)
			worker.config.Logger.Error(
				"gateway worker job bookkeeping failed",
				"job_id", job.ID,
				"job_type", job.Type,
				"attempt", job.Attempts,
				"error", safeError(err),
			)
		}
	}
	return len(jobs), cycleError
}

func (worker *Worker) handleJob(ctx context.Context, job application.Job) error {
	if job.Type == "delete_artifact" {
		return worker.handleDeleteArtifactJob(ctx, job)
	}
	if job.MaxAttempts > 0 && job.Attempts > job.MaxAttempts {
		processErr := &processError{
			Code:    "WORKER_RETRY_EXHAUSTED",
			Message: "The gateway could not complete the turn after its retry limit",
		}
		return worker.failJob(ctx, job, processErr)
	}
	if job.Type != "process_turn" {
		processErr := &processError{
			Code:    "UNKNOWN_JOB_TYPE",
			Message: "The gateway worker received an unsupported job type",
		}
		return worker.failJob(ctx, job, processErr)
	}
	err := worker.repository.WithSessionLock(ctx, job.SessionID, func(lockCtx context.Context) error {
		return worker.processTurn(lockCtx, job)
	})
	if err == nil {
		err = worker.repository.CompleteJob(
			ctx, job.ID, worker.config.WorkerID, worker.now(),
		)
		if errors.Is(err, application.ErrLeaseLost) {
			// Cancellation marks a running job cancelled in the same
			// transaction as the terminal turn event.
			return nil
		}
		return err
	}
	if ctx.Err() != nil {
		// Leave the lease to expire. Mutating durable state with a cancelled
		// process context can hide an incomplete provider operation.
		return ctx.Err()
	}
	processErr := normalizeProcessError(err)
	if processErr.Code == "TURN_STOPPED" || processErr.Code == "WORKER_CANCELLED" {
		if cleanupErr := worker.cleanupTurnInput(ctx, job.SessionID, job.TurnID); cleanupErr != nil {
			worker.config.Logger.Error(
				"gateway cancelled-turn raw audio cleanup failed",
				"turn_id", job.TurnID,
				"error", safeError(cleanupErr),
			)
		}
		completeErr := worker.repository.CompleteJob(
			ctx, job.ID, worker.config.WorkerID, worker.now(),
		)
		if errors.Is(completeErr, application.ErrLeaseLost) {
			return nil
		}
		return completeErr
	}
	if processErr.Retryable {
		now := worker.now()
		final, retryErr := worker.repository.RetryJob(
			ctx,
			job.ID,
			worker.config.WorkerID,
			now,
			now.Add(worker.retryDelay(job.Attempts, processErr)),
			processErr.Code,
		)
		if retryErr != nil {
			return retryErr
		}
		if !final {
			return nil
		}
		// Retry exhaustion is a terminal outcome, not a turn left forever in
		// processing state.
		processErr.Retryable = false
		return worker.failJob(ctx, job, processErr)
	}
	return worker.failJob(ctx, job, processErr)
}

func (worker *Worker) handleDeleteArtifactJob(
	ctx context.Context,
	job application.Job,
) error {
	// Expired artifacts remain eligible for the periodic sweeper, so retry
	// exhaustion can safely complete this wakeup job without retaining bytes.
	if job.MaxAttempts > 0 && job.Attempts > job.MaxAttempts {
		return worker.completeJob(ctx, job)
	}
	var payload struct {
		ArtifactID string `json:"artifactId"`
	}
	decoder := json.NewDecoder(bytes.NewReader(job.Payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&payload); err != nil {
		return worker.completeJob(ctx, job)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) ||
		!contract.IsUUID(payload.ArtifactID) {
		return worker.completeJob(ctx, job)
	}
	err := worker.deleteInputArtifact(ctx, payload.ArtifactID)
	if err == nil {
		return worker.completeJob(ctx, job)
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	processErr := normalizeProcessError(err)
	if !processErr.Retryable {
		return worker.completeJob(ctx, job)
	}
	now := worker.now()
	final, retryErr := worker.repository.RetryJob(
		ctx,
		job.ID,
		worker.config.WorkerID,
		now,
		now.Add(worker.retryDelay(job.Attempts, processErr)),
		processErr.Code,
	)
	if retryErr != nil {
		return retryErr
	}
	if final {
		return worker.completeJob(ctx, job)
	}
	return nil
}

func (worker *Worker) completeJob(ctx context.Context, job application.Job) error {
	err := worker.repository.CompleteJob(
		ctx, job.ID, worker.config.WorkerID, worker.now(),
	)
	if errors.Is(err, application.ErrLeaseLost) {
		return nil
	}
	return err
}

func (worker *Worker) failJob(
	ctx context.Context,
	job application.Job,
	processErr *processError,
) error {
	if err := worker.failTurn(ctx, job.SessionID, job.TurnID, processErr); err != nil {
		return err
	}
	err := worker.repository.CompleteJob(
		ctx, job.ID, worker.config.WorkerID, worker.now(),
	)
	if errors.Is(err, application.ErrLeaseLost) {
		return nil
	}
	return err
}

func (worker *Worker) failTurn(
	ctx context.Context,
	sessionID, turnID string,
	processErr *processError,
) error {
	if sessionID == "" || turnID == "" {
		return nil
	}
	current, err := worker.repository.GetTurn(ctx, sessionID, turnID)
	if err != nil {
		if errors.Is(err, application.ErrNotFound) {
			return nil
		}
		return err
	}
	if current.State.Terminal() {
		return nil
	}
	if cleanupErr := worker.cleanupTurnInput(ctx, sessionID, turnID); cleanupErr != nil {
		if current.InputKind == "audio" {
			worker.config.Logger.Error(
				"gateway failed-turn raw audio cleanup failed",
				"turn_id", turnID,
				"error", safeError(cleanupErr),
			)
		}
	}
	detail := domain.ErrorDetail{
		Code:      processErr.Code,
		Message:   processErr.Message,
		Retryable: false,
	}
	errorJSON, _ := json.Marshal(detail)
	eventJSON, _ := json.Marshal(map[string]any{"error": detail})
	_, _, err = worker.repository.FinishTurn(
		ctx,
		sessionID,
		turnID,
		application.TurnFailed,
		current.ResponseText,
		errorJSON,
		"turn.error",
		eventJSON,
		worker.now(),
	)
	if errors.Is(err, application.ErrTurnTerminal) {
		return nil
	}
	return err
}

func (worker *Worker) cleanupTurnInput(
	ctx context.Context,
	sessionID, turnID string,
) error {
	turn, err := worker.repository.GetTurn(ctx, sessionID, turnID)
	if err != nil {
		return err
	}
	if turn.InputKind != "audio" || turn.InputArtifactID == "" {
		return nil
	}
	return worker.deleteInputArtifact(ctx, turn.InputArtifactID)
}

func (worker *Worker) retryDelay(attempt int, processErr *processError) time.Duration {
	if attempt < 1 {
		attempt = 1
	}
	exponent := math.Min(float64(attempt-1), 16)
	delay := time.Duration(float64(worker.config.RetryBase) * math.Pow(2, exponent))
	if delay > worker.config.RetryMaximum {
		delay = worker.config.RetryMaximum
	}
	var providerError *provider.Error
	if errors.As(processErr.Cause, &providerError) &&
		providerError.RetryAfter > delay {
		delay = providerError.RetryAfter
	}
	// Deterministic per-job jitter is unnecessary for the initial single
	// worker deployment; capping keeps later horizontal replicas bounded.
	return delay
}

func (worker *Worker) now() time.Time {
	return worker.config.Clock().UTC()
}

func waitContext(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func safeError(err error) string {
	if err == nil {
		return ""
	}
	var processErr *processError
	if errors.As(err, &processErr) {
		return processErr.Code
	}
	return fmt.Sprintf("%T", err)
}
