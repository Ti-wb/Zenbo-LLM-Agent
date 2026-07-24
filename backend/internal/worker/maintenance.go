package worker

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
)

func (worker *Worker) runMaintenance(ctx context.Context) {
	// Run once at startup so crash-left artifacts and deadlines do not wait a
	// full interval after deployment.
	worker.maintenanceOnce(ctx)
	ticker := time.NewTicker(worker.config.MaintenanceInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			worker.maintenanceOnce(ctx)
		}
	}
}

func (worker *Worker) maintenanceOnce(ctx context.Context) {
	operations := []struct {
		name string
		run  func(context.Context) error
	}{
		{name: "expire_sessions", run: worker.SweepSessionsOnce},
		{name: "expire_tools", run: worker.SweepToolsOnce},
		{name: "delete_artifacts", run: worker.SweepArtifactsOnce},
		{name: "delete_incomplete_blobs", run: worker.SweepIncompleteBlobsOnce},
		{name: "delete_orphan_blobs", run: worker.SweepOrphanBlobsOnce},
		{name: "delete_untracked_provider_threads", run: worker.SweepUntrackedProviderThreadsOnce},
		{name: "delete_provider_threads", run: worker.SweepProviderThreadsOnce},
		{name: "prune", run: worker.PruneOnce},
	}
	for _, operation := range operations {
		if ctx.Err() != nil {
			return
		}
		if err := operation.run(ctx); err != nil {
			worker.config.Logger.Error(
				"gateway maintenance sweep failed",
				"operation", operation.name,
				"error", safeError(err),
			)
		}
	}
}

// SweepUntrackedProviderThreadsOnce closes the narrow crash window between a
// successful remote thread/start and PutProviderState. Codex owns the catalog
// boundary (appServer source plus the dedicated read-only cwd); the worker
// applies an additional age grace before comparing each ID with durable
// Gateway state.
//
// One bounded provider page is inspected per maintenance cycle. The opaque,
// stable cursor is retained in memory so a large catalog cannot make later
// pages starve while keeping every cycle bounded. A restart safely begins at
// the first page again.
func (worker *Worker) SweepUntrackedProviderThreadsOnce(ctx context.Context) error {
	const providerKind = "codex"

	listerResolver, ok := worker.providers.(interface {
		ThreadListerForKind(context.Context, string) (application.ThreadLister, error)
	})
	if !ok {
		return nil
	}
	deleterResolver, ok := worker.providers.(interface {
		ThreadDeleterForKind(context.Context, string) (application.ThreadDeleter, error)
	})
	if !ok {
		return nil
	}

	lister, err := listerResolver.ThreadListerForKind(ctx, providerKind)
	if err != nil {
		return err
	}
	if lister == nil {
		return nil
	}
	deleter, err := deleterResolver.ThreadDeleterForKind(ctx, providerKind)
	if err != nil {
		return err
	}
	if deleter == nil {
		return nil
	}

	worker.threadCatalogMu.Lock()
	defer worker.threadCatalogMu.Unlock()
	cursor := worker.threadCatalogCursors[providerKind]
	page, err := lister.ListThreads(ctx, cursor, worker.config.MaintenanceBatchSize)
	if err != nil {
		// An external catalog mutation may invalidate an opaque cursor. Reset
		// it for the next bounded sweep, while preserving all remote threads on
		// this failed pass.
		if cursor != "" {
			delete(worker.threadCatalogCursors, providerKind)
		}
		return err
	}
	if len(page.Threads) > worker.config.MaintenanceBatchSize {
		return fmt.Errorf("provider thread catalog exceeded requested page size")
	}
	if page.NextCursor == cursor && page.NextCursor != "" {
		return fmt.Errorf("provider thread catalog repeated its pagination cursor")
	}
	if len(page.NextCursor) > 4096 {
		return fmt.Errorf("provider thread catalog returned an oversized pagination cursor")
	}

	cutoff := worker.now().Add(-worker.config.ProviderOrphanGrace)
	seen := make(map[string]struct{}, len(page.Threads))
	for _, thread := range page.Threads {
		thread.ID = strings.TrimSpace(thread.ID)
		if thread.ID == "" || len(thread.ID) > 256 || thread.CreatedAt.IsZero() {
			return fmt.Errorf("provider thread catalog returned invalid thread metadata")
		}
		if _, duplicate := seen[thread.ID]; duplicate {
			return fmt.Errorf("provider thread catalog returned a duplicate thread id")
		}
		seen[thread.ID] = struct{}{}
		if thread.CreatedAt.After(cutoff) {
			continue
		}
		known, lookupErr := worker.repository.ProviderThreadKnown(ctx, thread.ID)
		if lookupErr != nil {
			return lookupErr
		}
		if known {
			continue
		}
		deleteContext, cancel := context.WithTimeout(ctx, worker.config.ProviderTimeout)
		deleteErr := deleter.DeleteThread(deleteContext, thread.ID)
		cancel()
		if deleteErr != nil {
			return deleteErr
		}
	}
	if page.NextCursor == "" {
		delete(worker.threadCatalogCursors, providerKind)
	} else {
		worker.threadCatalogCursors[providerKind] = page.NextCursor
	}
	return nil
}

// SweepProviderThreadsOnce removes Codex app-server threads only after the
// transcript retention window. The durable lease makes the external delete
// crash-retryable; DeleteThread implementations must treat an already absent
// thread as success.
func (worker *Worker) SweepProviderThreadsOnce(ctx context.Context) error {
	now := worker.now()
	leases, err := worker.repository.LeaseProviderThreadsForDeletion(
		ctx,
		worker.config.WorkerID,
		now.Add(-worker.config.TranscriptRetention),
		now,
		worker.config.ProviderCleanupLease,
		worker.config.MaintenanceBatchSize,
	)
	if err != nil {
		return err
	}
	var firstError error
	for _, lease := range leases {
		if err := worker.deleteProviderThread(ctx, lease); err != nil && firstError == nil {
			firstError = err
		}
	}
	return firstError
}

func (worker *Worker) deleteProviderThread(
	ctx context.Context,
	lease application.ProviderThreadLease,
) error {
	var (
		deleter application.ThreadDeleter
		err     error
	)
	if resolver, ok := worker.providers.(interface {
		ThreadDeleterForKind(context.Context, string) (application.ThreadDeleter, error)
	}); ok {
		deleter, err = resolver.ThreadDeleterForKind(ctx, lease.ProviderKind)
	} else {
		var set application.ProviderSet
		set, err = worker.providers.ForSession(ctx, application.Session{
			ID:              lease.SessionID,
			ProviderKind:    lease.ProviderKind,
			ProviderProfile: lease.ProviderProfile,
		})
		deleter = set.ThreadDeleter
	}
	if err != nil {
		_ = worker.repository.ReleaseProviderThreadLease(
			context.WithoutCancel(ctx), lease.SessionID, worker.config.WorkerID,
		)
		return err
	}
	if deleter == nil {
		_ = worker.repository.ReleaseProviderThreadLease(
			context.WithoutCancel(ctx), lease.SessionID, worker.config.WorkerID,
		)
		return permanent(
			"PROVIDER_CLEANUP_UNAVAILABLE",
			"The pinned provider cannot delete its retained conversation thread",
			nil,
		)
	}
	deleteContext, cancel := context.WithTimeout(ctx, worker.config.ProviderTimeout)
	err = deleter.DeleteThread(deleteContext, lease.RemoteThreadID)
	cancel()
	if err != nil {
		_ = worker.repository.ReleaseProviderThreadLease(
			context.WithoutCancel(ctx), lease.SessionID, worker.config.WorkerID,
		)
		return err
	}
	err = worker.repository.MarkProviderThreadDeleted(
		ctx, lease.SessionID, worker.config.WorkerID, worker.now(),
	)
	if errors.Is(err, application.ErrLeaseLost) {
		return nil
	}
	return err
}

func (worker *Worker) SweepIncompleteBlobsOnce(ctx context.Context) error {
	sweeper, ok := worker.blobs.(blob.IncompleteSweeper)
	if !ok {
		return nil
	}
	_, err := sweeper.SweepIncomplete(
		ctx,
		worker.now().Add(-worker.config.IncompleteBlobAge),
		worker.config.MaintenanceBatchSize,
	)
	return err
}

// SweepOrphanBlobsOnce closes the crash window between an atomic final-file
// rename and its artifact metadata transaction. The age grace prevents racing
// an in-flight insert; a database lookup failure always preserves the object.
func (worker *Worker) SweepOrphanBlobsOnce(ctx context.Context) error {
	lister, ok := worker.blobs.(blob.FinalObjectLister)
	if !ok {
		return nil
	}
	objects, err := lister.ListFinalObjects(
		ctx,
		worker.now().Add(-worker.config.IncompleteBlobAge),
		worker.config.MaintenanceBatchSize,
	)
	if err != nil {
		return err
	}
	for _, object := range objects {
		exists, err := worker.repository.BlobKeyExists(ctx, object.Key)
		if err != nil {
			return err
		}
		if exists {
			continue
		}
		if err := worker.blobs.Delete(ctx, object.Key); err != nil {
			return err
		}
	}
	return nil
}

func (worker *Worker) SweepSessionsOnce(ctx context.Context) error {
	_, err := worker.repository.ExpireSessions(
		ctx, worker.now(), worker.config.MaintenanceBatchSize,
	)
	return err
}

func (worker *Worker) SweepToolsOnce(ctx context.Context) error {
	_, err := worker.repository.ExpireToolCalls(
		ctx, worker.now(), worker.config.MaintenanceBatchSize,
	)
	return err
}

func (worker *Worker) SweepArtifactsOnce(ctx context.Context) error {
	leases, err := worker.repository.LeaseExpiredArtifacts(
		ctx,
		worker.config.WorkerID,
		worker.now(),
		worker.config.ArtifactDeletionLease,
		worker.config.MaintenanceBatchSize,
	)
	if err != nil {
		return err
	}
	var firstError error
	for _, lease := range leases {
		if err := worker.deleteArtifactLease(ctx, lease); err != nil && firstError == nil {
			firstError = err
		}
	}
	return firstError
}

func (worker *Worker) deleteArtifactLease(
	ctx context.Context,
	lease application.ArtifactLease,
) error {
	if err := worker.blobs.Delete(ctx, lease.BlobKey); err != nil {
		_ = worker.repository.ReleaseArtifactLease(
			context.WithoutCancel(ctx), lease.ID, worker.config.WorkerID,
		)
		return err
	}
	err := worker.repository.MarkArtifactDeleted(
		ctx, lease.ID, worker.config.WorkerID, worker.now(),
	)
	if errors.Is(err, application.ErrLeaseLost) {
		return nil
	}
	return err
}

func (worker *Worker) PruneOnce(ctx context.Context) error {
	_, err := worker.repository.Prune(
		ctx,
		worker.now(),
		worker.config.TranscriptRetention,
		worker.config.TerminalResourceRetention,
		worker.config.MaintenanceBatchSize,
	)
	return err
}
