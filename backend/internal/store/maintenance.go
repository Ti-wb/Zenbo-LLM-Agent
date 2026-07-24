package store

import (
	"context"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) ExpireSessions(ctx context.Context, now time.Time, limit int) ([]application.Session, error) {
	if limit <= 0 {
		limit = 100
	}
	return inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) ([]application.Session, error) {
		rows, err := tx.Query(ctx, `
			SELECT id::text
			FROM sessions
			WHERE state IN ('active', 'closing') AND expires_at <= $1
			ORDER BY expires_at
			LIMIT $2
		`, now, limit)
		if err != nil {
			return nil, err
		}
		var sessionIDs []string
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return nil, err
			}
			sessionIDs = append(sessionIDs, id)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
		var expired []application.Session
		for _, sessionID := range sessionIDs {
			if err := lockKey(ctx, tx, "session:"+sessionID); err != nil {
				return nil, err
			}
			var state string
			var expiresAt time.Time
			err = tx.QueryRow(ctx, `
				SELECT state, expires_at FROM sessions WHERE id = $1 FOR UPDATE
			`, sessionID).Scan(&state, &expiresAt)
			if err != nil {
				return nil, err
			}
			if (state != "active" && state != "closing") || expiresAt.After(now) {
				continue
			}
			turnIDs, err := cancelActiveTurnsTx(
				ctx, tx, sessionID, "timeout", now,
			)
			if err != nil {
				return nil, err
			}
			plan := terminalEventPlan(
				sessionID, turnIDs, application.SessionExpired,
				"idle_timeout", "timeout", now,
			)
			for _, event := range plan[:len(plan)-1] {
				if _, err := appendEventTx(ctx, tx, event); err != nil {
					return nil, err
				}
			}
			if _, err := tx.Exec(ctx, `
				UPDATE tool_calls
				SET status = 'failed',
				    error = '{"code":"TURN_CANCELLED","message":"Session expired","retryable":false}'::jsonb,
				    terminal_at = $2, updated_at = $2
				WHERE session_id = $1 AND status IN ('pending', 'accepted')
			`, sessionID, now); err != nil {
				return nil, err
			}
			if err := cancelSessionJobsTx(ctx, tx, sessionID, now); err != nil {
				return nil, err
			}
			if err := enqueueInputArtifactDeletionJobsTx(
				ctx, tx, sessionID, "", now,
			); err != nil {
				return nil, err
			}
			if _, err := tx.Exec(ctx, `
				UPDATE sessions
				SET state = 'expired', close_reason = 'ttl',
				    closed_at = $2, updated_at = $2
				WHERE id = $1
			`, sessionID, now); err != nil {
				return nil, err
			}
			if _, err := appendEventTx(ctx, tx, plan[len(plan)-1]); err != nil {
				return nil, err
			}
			session, err := scanSession(tx.QueryRow(ctx,
				`SELECT `+sessionColumns+` FROM sessions WHERE id = $1`, sessionID))
			if err != nil {
				return nil, err
			}
			expired = append(expired, session)
		}
		return expired, nil
	})
}

func (store *Postgres) Prune(
	ctx context.Context,
	now time.Time,
	transcriptRetention, terminalRetention time.Duration,
	limit int,
) (application.PruneResult, error) {
	if limit <= 0 {
		limit = 1000
	}
	return inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (application.PruneResult, error) {
		var result application.PruneResult
		transcriptCutoff := now.Add(-transcriptRetention)
		terminalCutoff := now.Add(-terminalRetention)

		tag, err := tx.Exec(ctx, `
			WITH eligibility AS (
				SELECT
					candidate.ctid,
					candidate.session_id,
					candidate.sequence,
					candidate.occurred_at,
					(
						candidate.occurred_at < $1
						AND NOT EXISTS (
							SELECT 1 FROM turns
							WHERE turns.id = candidate.turn_id
							  AND turns.state IN (
							    'accepted', 'processing', 'waiting_for_tool'
							  )
						)
					) AS eligible
				FROM events AS candidate
			),
			prefixes AS (
				SELECT
					ctid,
					session_id,
					occurred_at,
					bool_and(eligible) OVER (
						PARTITION BY session_id
						ORDER BY sequence
						ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
					) AS prefix_eligible,
					row_number() OVER (
						PARTITION BY session_id
						ORDER BY sequence
					) AS prefix_rank
				FROM eligibility
			),
			candidates AS (
				SELECT ctid
				FROM prefixes
				WHERE prefix_eligible
				-- Round-robin by retained-prefix position. A bounded sweep
				-- therefore cannot select a later event without selecting
				-- every earlier retained event from the same session.
				ORDER BY prefix_rank, occurred_at, session_id
				LIMIT $2
			)
			DELETE FROM events
			WHERE ctid IN (SELECT ctid FROM candidates)
		`, terminalCutoff, limit)
		if err != nil {
			return result, err
		}
		result.Events = tag.RowsAffected()

		// Remove retained conversational text only after the turn is terminal.
		// Structural rows remain for idempotency and lifecycle auditing.
		if _, err := tx.Exec(ctx, `
			UPDATE turns
			SET input_text = NULL, transcript = NULL, response_text = NULL,
			    input_redacted_at = $1, updated_at = GREATEST(updated_at, $1)
			WHERE terminal_at < $2 AND input_redacted_at IS NULL
		`, now, transcriptCutoff); err != nil {
			return result, err
		}
		if _, err := tx.Exec(ctx, `
			UPDATE provider_state AS state
			SET remote_thread_id = NULL,
			    opaque_state = '{}'::jsonb,
			    cleanup_state = 'deleted',
			    cleanup_lease_owner = NULL,
			    cleanup_lease_until = NULL,
			    cleaned_at = $1,
			    updated_at = $1
			FROM sessions
			WHERE sessions.id = state.session_id
			  AND sessions.state IN ('closed', 'expired')
			  AND sessions.closed_at < $2
			  AND state.cleanup_state = 'live'
			  AND (
			    state.provider_kind = 'openai-compatible'
			    OR (
			      state.provider_kind = 'codex'
			      AND state.remote_thread_id IS NULL
			    )
			  )
		`, now, transcriptCutoff); err != nil {
			return result, err
		}
		if _, err := tx.Exec(ctx, `
			UPDATE tool_calls AS calls
			SET arguments = '{}'::jsonb, output = NULL, error = NULL,
			    updated_at = GREATEST(calls.updated_at, $1)
			FROM sessions
			WHERE sessions.id = calls.session_id
			  AND sessions.state IN ('closed', 'expired')
			  AND sessions.closed_at < $2
		`, now, transcriptCutoff); err != nil {
			return result, err
		}
		if _, err := tx.Exec(ctx, `
			UPDATE sessions
			SET client = '{}'::jsonb, context = '{}'::jsonb,
			    tool_manifest = '{}'::jsonb, updated_at = GREATEST(updated_at, $1)
			WHERE state IN ('closed', 'expired') AND closed_at < $2
		`, now, transcriptCutoff); err != nil {
			return result, err
		}

		tag, err = tx.Exec(ctx, `
			WITH candidates AS (
				SELECT ctid FROM idempotency_records
				WHERE expires_at <= $1
				ORDER BY expires_at
				LIMIT $2
			)
			DELETE FROM idempotency_records
			WHERE ctid IN (SELECT ctid FROM candidates)
		`, now, limit)
		if err != nil {
			return result, err
		}
		result.Idempotency = tag.RowsAffected()

		tag, err = tx.Exec(ctx, `
			WITH candidates AS (
				SELECT ctid FROM jobs
				WHERE status IN ('succeeded', 'failed', 'cancelled')
				  AND completed_at < $1
				ORDER BY completed_at
				LIMIT $2
			)
			DELETE FROM jobs
			WHERE ctid IN (SELECT ctid FROM candidates)
		`, terminalCutoff, limit)
		if err != nil {
			return result, err
		}
		result.Jobs = tag.RowsAffected()

		tag, err = tx.Exec(ctx, `
			WITH candidates AS (
				SELECT sessions.id
				FROM sessions
				WHERE sessions.state IN ('closed', 'expired')
				  AND sessions.closed_at < $1
				  AND NOT EXISTS (
				    SELECT 1 FROM artifacts
				    WHERE artifacts.session_id = sessions.id
				      AND artifacts.deletion_state <> 'deleted'
				  )
				  AND NOT EXISTS (
				    SELECT 1 FROM provider_state
				    WHERE provider_state.session_id = sessions.id
				      AND provider_state.cleanup_state <> 'deleted'
				  )
				ORDER BY sessions.closed_at
				LIMIT $2
			)
			DELETE FROM sessions
			WHERE id IN (SELECT id FROM candidates)
		`, transcriptCutoff, limit)
		if err != nil {
			return result, err
		}
		result.Sessions = tag.RowsAffected()
		return result, nil
	})
}
