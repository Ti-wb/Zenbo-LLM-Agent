package store

import (
	"context"
	"errors"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) GetProviderState(ctx context.Context, sessionID string) (application.ProviderState, error) {
	var value application.ProviderState
	err := store.pool.QueryRow(ctx, `
		SELECT session_id::text, provider_kind, provider_profile,
		       COALESCE(remote_thread_id, ''), opaque_state, cleanup_state,
		       COALESCE(cleanup_lease_owner, ''), cleanup_lease_until,
		       cleaned_at, updated_at
		FROM provider_state WHERE session_id = $1
	`, sessionID).Scan(
		&value.SessionID, &value.ProviderKind, &value.ProviderProfile,
		&value.RemoteThreadID, &value.OpaqueState, &value.CleanupState,
		&value.CleanupOwner, &value.CleanupUntil, &value.CleanedAt,
		&value.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return application.ProviderState{}, application.ErrNotFound
	}
	if err != nil {
		return application.ProviderState{}, err
	}
	return value, nil
}

func (store *Postgres) PutProviderState(ctx context.Context, value application.ProviderState) error {
	tag, err := store.pool.Exec(ctx, `
		INSERT INTO provider_state (
			session_id, provider_kind, provider_profile, remote_thread_id,
			opaque_state, updated_at
		) VALUES ($1, $2, $3, NULLIF($4, ''), $5, $6)
		ON CONFLICT (session_id) DO UPDATE
		SET remote_thread_id = EXCLUDED.remote_thread_id,
		    opaque_state = EXCLUDED.opaque_state,
		    updated_at = EXCLUDED.updated_at
		WHERE provider_state.provider_kind = EXCLUDED.provider_kind
		  AND provider_state.provider_profile = EXCLUDED.provider_profile
		  AND provider_state.cleanup_state = 'live'
	`, value.SessionID, value.ProviderKind, value.ProviderProfile,
		value.RemoteThreadID, normalizeJSON(value.OpaqueState), value.UpdatedAt)
	if err != nil {
		return mapDatabaseError(err)
	}
	if tag.RowsAffected() == 0 {
		// A session is pinned to the provider selected at creation.
		return application.ErrConflict
	}
	return nil
}

func (store *Postgres) ProviderThreadKnown(
	ctx context.Context,
	remoteThreadID string,
) (bool, error) {
	if remoteThreadID == "" {
		return false, nil
	}
	var exists bool
	if err := store.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1
			FROM provider_state
			WHERE provider_kind = 'codex'
			  AND remote_thread_id = $1
			  AND cleanup_state IN ('live', 'deleting')
		)
	`, remoteThreadID).Scan(&exists); err != nil {
		return false, err
	}
	return exists, nil
}

// LeaseProviderThreadsForDeletion durably claims old Codex threads before the
// worker calls thread/delete. Expired leases are eligible again, so a worker
// crash cannot strand OAuth-backed conversation history.
func (store *Postgres) LeaseProviderThreadsForDeletion(
	ctx context.Context,
	owner string,
	terminalBefore, now time.Time,
	lease time.Duration,
	limit int,
) ([]application.ProviderThreadLease, error) {
	if owner == "" || lease <= 0 {
		return nil, application.ErrInvalidTransition
	}
	if limit <= 0 {
		limit = 100
	}
	rows, err := store.pool.Query(ctx, `
		WITH candidates AS (
			SELECT state.session_id
			FROM provider_state AS state
			JOIN sessions ON sessions.id = state.session_id
			WHERE state.provider_kind = 'codex'
			  AND state.remote_thread_id IS NOT NULL
			  AND sessions.state IN ('closed', 'expired')
			  AND sessions.closed_at <= $1
			  AND (
			    state.cleanup_state = 'live'
			    OR (
			      state.cleanup_state = 'deleting'
			      AND state.cleanup_lease_until <= $2
			    )
			  )
			ORDER BY sessions.closed_at, state.session_id
			FOR UPDATE OF state SKIP LOCKED
			LIMIT $3
		)
		UPDATE provider_state AS state
		SET cleanup_state = 'deleting',
		    cleanup_lease_owner = $4,
		    cleanup_lease_until = $5,
		    updated_at = $2
		FROM candidates
		WHERE state.session_id = candidates.session_id
		RETURNING state.session_id::text, state.provider_kind,
		          state.provider_profile, state.remote_thread_id,
		          state.cleanup_lease_owner, state.cleanup_lease_until
	`, terminalBefore, now, limit, owner, now.Add(lease))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []application.ProviderThreadLease
	for rows.Next() {
		var item application.ProviderThreadLease
		if err := rows.Scan(
			&item.SessionID, &item.ProviderKind, &item.ProviderProfile,
			&item.RemoteThreadID, &item.LeaseOwner, &item.LeaseUntil,
		); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

func (store *Postgres) MarkProviderThreadDeleted(
	ctx context.Context,
	sessionID, owner string,
	now time.Time,
) error {
	tag, err := store.pool.Exec(ctx, `
		UPDATE provider_state
		SET remote_thread_id = NULL,
		    opaque_state = '{}'::jsonb,
		    cleanup_state = 'deleted',
		    cleanup_lease_owner = NULL,
		    cleanup_lease_until = NULL,
		    cleaned_at = $3,
		    updated_at = $3
		WHERE session_id = $1
		  AND provider_kind = 'codex'
		  AND cleanup_state = 'deleting'
		  AND cleanup_lease_owner = $2
	`, sessionID, owner, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return application.ErrLeaseLost
	}
	return nil
}

func (store *Postgres) ReleaseProviderThreadLease(
	ctx context.Context,
	sessionID, owner string,
) error {
	tag, err := store.pool.Exec(ctx, `
		UPDATE provider_state
		SET cleanup_state = 'live',
		    cleanup_lease_owner = NULL,
		    cleanup_lease_until = NULL
		WHERE session_id = $1
		  AND provider_kind = 'codex'
		  AND cleanup_state = 'deleting'
		  AND cleanup_lease_owner = $2
	`, sessionID, owner)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return application.ErrLeaseLost
	}
	return nil
}
