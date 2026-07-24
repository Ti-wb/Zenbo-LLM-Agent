package store

import (
	"context"
	"errors"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) EnqueueJob(ctx context.Context, job application.Job) (application.Job, bool, error) {
	value, err := scanJob(store.pool.QueryRow(ctx, `
		INSERT INTO jobs (
			id, job_type, session_id, turn_id, dedupe_key, payload, status,
			attempts, max_attempts, available_at, created_at, updated_at
		) VALUES (
			COALESCE(NULLIF($1, '')::uuid, gen_random_uuid()), $2,
			NULLIF($3, '')::uuid, NULLIF($4, '')::uuid, NULLIF($5, ''),
			$6, 'queued', 0, $7, $8, $9, $9
		)
		ON CONFLICT (job_type, dedupe_key)
			WHERE dedupe_key IS NOT NULL AND status IN ('queued', 'running')
		DO UPDATE SET updated_at = jobs.updated_at
		RETURNING `+jobColumns,
		job.ID, job.Type, job.SessionID, job.TurnID, job.DedupeKey,
		normalizeJSON(job.Payload), positiveOr(job.MaxAttempts, 5),
		job.AvailableAt, job.CreatedAt,
	))
	if err != nil {
		return application.Job{}, false, mapDatabaseError(err)
	}
	replayed := job.ID != "" && value.ID != job.ID
	// When the caller omitted the ID, a deduplicated row is observable through
	// attempts/created_at but not reliably distinguishable. Dedupe behavior,
	// not this advisory boolean, is the durable guarantee.
	return value, replayed, nil
}

func (store *Postgres) LeaseJobs(ctx context.Context, owner string, now time.Time, lease time.Duration, limit int) ([]application.Job, error) {
	if owner == "" {
		return nil, application.ErrInvalidTransition
	}
	if limit <= 0 {
		limit = 1
	}
	rows, err := store.pool.Query(ctx, `
		WITH candidates AS (
			SELECT id FROM jobs
			WHERE (
				(
				  status = 'queued'
				  AND available_at <= $1
				  AND attempts < max_attempts
				)
				OR (status = 'running' AND lease_until <= $1)
			)
			ORDER BY available_at, created_at
			FOR UPDATE SKIP LOCKED
			LIMIT $2
		)
		UPDATE jobs AS leased
		SET status = 'running', attempts = leased.attempts + 1,
		    lease_owner = $3, lease_until = $4, updated_at = $1
		FROM candidates
		WHERE leased.id = candidates.id
		RETURNING leased.id::text, leased.job_type,
		          COALESCE(leased.session_id::text, ''),
		          COALESCE(leased.turn_id::text, ''),
		          COALESCE(leased.dedupe_key, ''), leased.payload, leased.status,
		          leased.attempts, leased.max_attempts, leased.available_at,
		          COALESCE(leased.lease_owner, ''), leased.lease_until,
		          COALESCE(leased.last_error, ''), leased.created_at,
		          leased.updated_at, leased.completed_at
	`, now, limit, owner, now.Add(lease))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []application.Job
	for rows.Next() {
		job, err := scanJob(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, job)
	}
	return result, rows.Err()
}

func (store *Postgres) CompleteJob(ctx context.Context, jobID, owner string, now time.Time) error {
	tag, err := store.pool.Exec(ctx, `
		UPDATE jobs
		SET status = 'succeeded', lease_owner = NULL, lease_until = NULL,
		    completed_at = $3, updated_at = $3
		WHERE id = $1 AND status = 'running' AND lease_owner = $2
	`, jobID, owner, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return application.ErrLeaseLost
	}
	return nil
}

func (store *Postgres) RetryJob(ctx context.Context, jobID, owner string, now, availableAt time.Time, lastError string) (bool, error) {
	type result struct{ final bool }
	value, err := inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (result, error) {
		var attempts, maximum int
		err := tx.QueryRow(ctx, `
			SELECT attempts, max_attempts FROM jobs
			WHERE id = $1 AND status = 'running' AND lease_owner = $2
			FOR UPDATE
		`, jobID, owner).Scan(&attempts, &maximum)
		if errors.Is(err, pgx.ErrNoRows) {
			return result{}, application.ErrLeaseLost
		}
		if err != nil {
			return result{}, err
		}
		if attempts >= maximum {
			_, err = tx.Exec(ctx, `
				UPDATE jobs
				SET last_error = $3, updated_at = $4
				WHERE id = $1 AND status = 'running' AND lease_owner = $2
			`, jobID, owner, lastError, now)
			return result{final: true}, err
		}
		_, err = tx.Exec(ctx, `
			UPDATE jobs
			SET status = 'queued', lease_owner = NULL, lease_until = NULL,
			    last_error = $3, available_at = $4, updated_at = $5
			WHERE id = $1 AND lease_owner = $2
		`, jobID, owner, lastError, availableAt, now)
		return result{}, err
	})
	if err != nil {
		return false, err
	}
	return value.final, nil
}

func (store *Postgres) CancelSessionJobs(ctx context.Context, sessionID string, now time.Time) error {
	_, err := store.pool.Exec(ctx, `
		UPDATE jobs
		SET status = 'cancelled', lease_owner = NULL, lease_until = NULL,
		    completed_at = $2, updated_at = $2
		WHERE session_id = $1 AND status IN ('queued', 'running')
	`, sessionID, now)
	return err
}

func cancelSessionJobsTx(ctx context.Context, tx pgx.Tx, sessionID string, now time.Time) error {
	_, err := tx.Exec(ctx, `
		UPDATE jobs
		SET status = 'cancelled', lease_owner = NULL, lease_until = NULL,
		    completed_at = $2, updated_at = $2
		WHERE session_id = $1 AND status IN ('queued', 'running')
	`, sessionID, now)
	return err
}

func (store *Postgres) WithSessionLock(ctx context.Context, sessionID string, action func(context.Context) error) error {
	connection, err := store.pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer connection.Release()
	var acquired bool
	// A distinct namespace keeps the worker mutex independent from short
	// transaction-level state locks used by API mutations.
	if err := connection.QueryRow(ctx, `
		SELECT pg_try_advisory_lock(hashtextextended('worker:' || $1, 0))
	`, sessionID).Scan(&acquired); err != nil {
		return err
	}
	if !acquired {
		return application.ErrConflict
	}
	defer func() {
		_, _ = connection.Exec(context.Background(), `
			SELECT pg_advisory_unlock(hashtextextended('worker:' || $1, 0))
		`, sessionID)
	}()
	return action(ctx)
}

func positiveOr(value, fallback int) int {
	if value > 0 {
		return value
	}
	return fallback
}
