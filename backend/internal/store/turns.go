package store

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

type turnCreateResult struct {
	turn     application.Turn
	replayed bool
}

func (store *Postgres) CreateTurn(ctx context.Context, params application.CreateTurnParams) (application.Turn, bool, error) {
	result, err := inTransaction(ctx, store.pool, pgx.TxOptions{IsoLevel: pgx.Serializable}, func(tx pgx.Tx) (turnCreateResult, error) {
		if err := lockKey(ctx, tx, "session:"+params.SessionID); err != nil {
			return turnCreateResult{}, err
		}
		var sessionState string
		var expiresAt time.Time
		err := tx.QueryRow(ctx, `
			SELECT state, expires_at
			FROM sessions
			WHERE id = $1 AND device_row_id = $2 AND device_id = $3
			FOR UPDATE
		`, params.SessionID, params.Device.ID, params.Device.DeviceID).Scan(
			&sessionState, &expiresAt,
		)
		if errors.Is(err, pgx.ErrNoRows) {
			return turnCreateResult{}, application.ErrNotFound
		}
		if err != nil {
			return turnCreateResult{}, err
		}

		var storedDigest []byte
		var resourceID string
		var idempotencyExpiresAt time.Time
		err = tx.QueryRow(ctx, `
			SELECT request_digest, COALESCE(resource_id::text, ''), expires_at
			FROM idempotency_records
			WHERE device_row_id = $1 AND operation = 'create_turn:' || $2
			  AND idempotency_key = $3
			FOR UPDATE
		`, params.Device.ID, params.SessionID, params.IdempotencyKey).Scan(
			&storedDigest, &resourceID, &idempotencyExpiresAt,
		)
		if err == nil {
			if !idempotencyExpiresAt.After(params.Now) {
				if _, deleteErr := tx.Exec(ctx, `
					DELETE FROM idempotency_records
					WHERE device_row_id = $1
					  AND operation = 'create_turn:' || $2
					  AND idempotency_key = $3
				`, params.Device.ID, params.SessionID, params.IdempotencyKey); deleteErr != nil {
					return turnCreateResult{}, deleteErr
				}
			} else {
				if subtle.ConstantTimeCompare(storedDigest, params.RequestDigest) != 1 {
					return turnCreateResult{}, application.ErrIdempotencyConflict
				}
				existing, getErr := scanTurn(tx.QueryRow(ctx,
					`SELECT `+turnColumns+` FROM turns WHERE id = $1`, resourceID))
				if getErr != nil {
					return turnCreateResult{}, getErr
				}
				return turnCreateResult{turn: existing, replayed: true}, nil
			}
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return turnCreateResult{}, err
		}
		if sessionState != string(application.SessionActive) || !expiresAt.After(params.Now) {
			return turnCreateResult{}, application.ErrSessionNotActive
		}

		turn, err := scanTurn(tx.QueryRow(ctx, `
			INSERT INTO turns (
				id, session_id, client_turn_id, state, input_kind, input_text,
				input_artifact_id, language, accepted_at, updated_at
			) VALUES (
				COALESCE(NULLIF($1, '')::uuid, gen_random_uuid()), $2, $3,
				'accepted', $4, $5, NULLIF($6, '')::uuid, NULLIF($7, ''), $8, $8
			)
			RETURNING `+turnColumns,
			params.ID, params.SessionID, params.ClientTurnID, params.InputKind,
			nullableString(params.InputText), params.InputArtifactID,
			params.Language, params.Now,
		))
		if err != nil {
			return turnCreateResult{}, mapDatabaseError(err)
		}
		if params.InputArtifact != nil {
			artifact := *params.InputArtifact
			artifact.TurnID = turn.ID
			artifact.SessionID = turn.SessionID
			if _, err := insertArtifactTx(ctx, tx, artifact); err != nil {
				return turnCreateResult{}, err
			}
		}
		if _, err := appendEventTx(ctx, tx, application.AppendEventParams{
			SessionID: turn.SessionID,
			TurnID:    turn.ID,
			Type:      "turn.accepted",
			Timestamp: params.Now,
			Data:      json.RawMessage(`{}`),
		}); err != nil {
			return turnCreateResult{}, err
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO jobs (
				job_type, session_id, turn_id, dedupe_key, payload,
				status, available_at, created_at, updated_at
			) VALUES (
				'process_turn', $1, $2, $2::text, '{}'::jsonb,
				'queued', $3, $3, $3
			)
		`, turn.SessionID, turn.ID, params.Now); err != nil {
			return turnCreateResult{}, mapDatabaseError(err)
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO idempotency_records (
				device_row_id, operation, idempotency_key, request_digest,
				session_id, resource_id, state, created_at, expires_at
			) VALUES (
				$1, 'create_turn:' || $2, $3, $4, $2, $5,
				'completed', $6, $7
			)
		`, params.Device.ID, params.SessionID, params.IdempotencyKey,
			params.RequestDigest, turn.ID, params.Now, params.IdempotencyUntil); err != nil {
			return turnCreateResult{}, mapDatabaseError(err)
		}
		return turnCreateResult{turn: turn}, nil
	})
	if err != nil {
		return application.Turn{}, false, err
	}
	return result.turn, result.replayed, nil
}

func (store *Postgres) GetTurn(ctx context.Context, sessionID, turnID string) (application.Turn, error) {
	value, err := scanTurn(store.pool.QueryRow(ctx, `
		SELECT `+turnColumns+`
		FROM turns WHERE id = $1 AND session_id = $2
	`, turnID, sessionID))
	if err != nil {
		return application.Turn{}, mapDatabaseError(err)
	}
	return value, nil
}

func (store *Postgres) SetTurnState(ctx context.Context, sessionID, turnID string, state application.TurnState, now time.Time) (application.Turn, error) {
	if state.Terminal() {
		return application.Turn{}, application.ErrInvalidTransition
	}
	value, err := scanTurn(store.pool.QueryRow(ctx, `
		UPDATE turns
		SET state = $3, updated_at = $4
		WHERE id = $1 AND session_id = $2
		  AND (
		    state = $3
		    OR (state = 'accepted' AND $3 = 'processing')
		    OR (state = 'processing' AND $3 = 'waiting_for_tool')
		    OR (state = 'waiting_for_tool' AND $3 = 'processing')
		  )
		RETURNING `+turnColumns,
		turnID, sessionID, state, now,
	))
	if errors.Is(err, pgx.ErrNoRows) {
		current, getErr := store.GetTurn(ctx, sessionID, turnID)
		if getErr != nil {
			return application.Turn{}, getErr
		}
		if current.State.Terminal() {
			return application.Turn{}, application.ErrTurnTerminal
		}
		return application.Turn{}, application.ErrInvalidTransition
	}
	return value, mapDatabaseError(err)
}

func (store *Postgres) SetTurnTranscript(ctx context.Context, sessionID, turnID, transcript string, now time.Time) (application.Turn, error) {
	value, err := scanTurn(store.pool.QueryRow(ctx, `
		UPDATE turns
		SET transcript = $3, updated_at = $4
		WHERE id = $1 AND session_id = $2
		  AND state IN ('accepted', 'processing', 'waiting_for_tool')
		RETURNING `+turnColumns,
		turnID, sessionID, transcript, now,
	))
	if errors.Is(err, pgx.ErrNoRows) {
		return application.Turn{}, application.ErrTurnTerminal
	}
	return value, mapDatabaseError(err)
}

func (store *Postgres) SetTurnResponse(ctx context.Context, sessionID, turnID, response string, now time.Time) (application.Turn, error) {
	value, err := scanTurn(store.pool.QueryRow(ctx, `
		UPDATE turns
		SET response_text = $3, updated_at = $4
		WHERE id = $1 AND session_id = $2
		  AND state IN ('accepted', 'processing', 'waiting_for_tool')
		RETURNING `+turnColumns,
		turnID, sessionID, response, now,
	))
	if errors.Is(err, pgx.ErrNoRows) {
		return application.Turn{}, application.ErrTurnTerminal
	}
	return value, mapDatabaseError(err)
}

type finishTurnResult struct {
	turn    application.Turn
	changed bool
}

func (store *Postgres) FinishTurn(
	ctx context.Context,
	sessionID, turnID string,
	state application.TurnState,
	responseText string,
	errorDetail json.RawMessage,
	eventType string,
	eventData json.RawMessage,
	now time.Time,
) (application.Turn, bool, error) {
	if !state.Terminal() {
		return application.Turn{}, false, application.ErrInvalidTransition
	}
	result, err := inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (finishTurnResult, error) {
		if err := lockKey(ctx, tx, "session:"+sessionID); err != nil {
			return finishTurnResult{}, err
		}
		turn, err := scanTurn(tx.QueryRow(ctx, `
			UPDATE turns
			SET state = $3, response_text = NULLIF($4, ''),
			    error = CASE WHEN $5::jsonb = 'null'::jsonb THEN NULL ELSE $5::jsonb END,
			    terminal_at = $6, updated_at = $6
			WHERE id = $1 AND session_id = $2
			  AND state IN ('accepted', 'processing', 'waiting_for_tool')
			RETURNING `+turnColumns,
			turnID, sessionID, state, responseText, jsonOrNull(errorDetail), now,
		))
		if errors.Is(err, pgx.ErrNoRows) {
			current, getErr := scanTurn(tx.QueryRow(ctx,
				`SELECT `+turnColumns+` FROM turns WHERE id = $1 AND session_id = $2`,
				turnID, sessionID))
			if getErr != nil {
				return finishTurnResult{}, mapDatabaseError(getErr)
			}
			return finishTurnResult{turn: current}, nil
		}
		if err != nil {
			return finishTurnResult{}, err
		}
		if _, err := appendEventTx(ctx, tx, application.AppendEventParams{
			SessionID: sessionID,
			TurnID:    turnID,
			Type:      eventType,
			Timestamp: now,
			Data:      normalizeJSON(eventData),
		}); err != nil {
			return finishTurnResult{}, err
		}
		if state == application.TurnCancelled {
			if _, err := tx.Exec(ctx, `
				UPDATE artifacts
				SET expires_at = LEAST(expires_at, $3)
				WHERE session_id = $1 AND turn_id = $2
				  AND kind = 'input_audio'
			`, sessionID, turnID, now); err != nil {
				return finishTurnResult{}, err
			}
			if err := enqueueInputArtifactDeletionJobsTx(
				ctx, tx, sessionID, turnID, now,
			); err != nil {
				return finishTurnResult{}, err
			}
		}
		return finishTurnResult{turn: turn, changed: true}, nil
	})
	if err != nil {
		return application.Turn{}, false, err
	}
	return result.turn, result.changed, nil
}

func (store *Postgres) CancelTurn(ctx context.Context, params application.CancelTurnParams) (application.Turn, bool, bool, error) {
	type result struct {
		turn     application.Turn
		changed  bool
		replayed bool
	}
	value, err := inTransaction(ctx, store.pool, pgx.TxOptions{IsoLevel: pgx.Serializable}, func(tx pgx.Tx) (result, error) {
		if err := lockKey(ctx, tx, "session:"+params.SessionID); err != nil {
			return result{}, err
		}
		var storedDigest []byte
		var resourceID string
		var idempotencyExpiresAt time.Time
		err := tx.QueryRow(ctx, `
			SELECT request_digest, COALESCE(resource_id::text, ''), expires_at
			FROM idempotency_records
			WHERE device_row_id = $1 AND operation = 'cancel_turn:' || $2
			  AND idempotency_key = $3
			FOR UPDATE
		`, params.Device.ID, params.TurnID, params.IdempotencyKey).Scan(
			&storedDigest, &resourceID, &idempotencyExpiresAt,
		)
		if err == nil {
			if !idempotencyExpiresAt.After(params.Now) {
				if _, deleteErr := tx.Exec(ctx, `
					DELETE FROM idempotency_records
					WHERE device_row_id = $1
					  AND operation = 'cancel_turn:' || $2
					  AND idempotency_key = $3
				`, params.Device.ID, params.TurnID, params.IdempotencyKey); deleteErr != nil {
					return result{}, deleteErr
				}
			} else {
				if subtle.ConstantTimeCompare(storedDigest, params.RequestDigest) != 1 {
					return result{}, application.ErrIdempotencyConflict
				}
				turn, getErr := scanTurn(tx.QueryRow(ctx,
					`SELECT `+turnColumns+` FROM turns WHERE id = $1 AND session_id = $2`,
					resourceID, params.SessionID))
				return result{turn: turn, replayed: true}, getErr
			}
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return result{}, err
		}
		turn, err := scanTurn(tx.QueryRow(ctx, `
			SELECT `+turnColumns+`
			FROM turns
			WHERE id = $1 AND session_id = $2
			  AND EXISTS (
			    SELECT 1 FROM sessions
			    WHERE sessions.id = turns.session_id
			      AND sessions.device_row_id = $3
			  )
			FOR UPDATE
		`, params.TurnID, params.SessionID, params.Device.ID))
		if err != nil {
			return result{}, mapDatabaseError(err)
		}
		changed := false
		if !turn.State.Terminal() {
			turn, err = scanTurn(tx.QueryRow(ctx, `
				UPDATE turns
				SET state = 'cancelled', cancel_reason = $3,
				    terminal_at = $4, updated_at = $4
				WHERE id = $1 AND session_id = $2
				RETURNING `+turnColumns,
				params.TurnID, params.SessionID, params.Reason, params.Now,
			))
			if err != nil {
				return result{}, err
			}
			data, _ := json.Marshal(map[string]string{"reason": params.Reason})
			if _, err := appendEventTx(ctx, tx, application.AppendEventParams{
				SessionID: params.SessionID,
				TurnID:    params.TurnID,
				Type:      "turn.cancelled",
				Timestamp: params.Now,
				Data:      data,
			}); err != nil {
				return result{}, err
			}
			if _, err := tx.Exec(ctx, `
				UPDATE artifacts
				SET expires_at = LEAST(expires_at, $3)
				WHERE session_id = $1 AND turn_id = $2
				  AND kind = 'input_audio'
			`, params.SessionID, params.TurnID, params.Now); err != nil {
				return result{}, err
			}
			if _, err := tx.Exec(ctx, `
				UPDATE tool_calls
				SET status = 'failed',
				    error = '{"code":"TURN_CANCELLED","message":"Turn was cancelled","retryable":false}'::jsonb,
				    terminal_at = $3, updated_at = $3
				WHERE session_id = $1 AND turn_id = $2
				  AND status IN ('pending', 'accepted')
			`, params.SessionID, params.TurnID, params.Now); err != nil {
				return result{}, err
			}
			if _, err := tx.Exec(ctx, `
				UPDATE jobs
				SET status = 'cancelled', lease_owner = NULL, lease_until = NULL,
				    completed_at = $3, updated_at = $3
				WHERE session_id = $1 AND turn_id = $2
				  AND status IN ('queued', 'running')
			`, params.SessionID, params.TurnID, params.Now); err != nil {
				return result{}, err
			}
			if err := enqueueInputArtifactDeletionJobsTx(
				ctx, tx, params.SessionID, params.TurnID, params.Now,
			); err != nil {
				return result{}, err
			}
			changed = true
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO idempotency_records (
				device_row_id, operation, idempotency_key, request_digest,
				session_id, resource_id, state, created_at, expires_at
			) VALUES (
				$1, 'cancel_turn:' || $2, $3, $4, $5, $2,
				'completed', $6, $7
			)
		`, params.Device.ID, params.TurnID, params.IdempotencyKey,
			params.RequestDigest, params.SessionID, params.Now,
			params.IdempotencyUntil); err != nil {
			return result{}, mapDatabaseError(err)
		}
		return result{turn: turn, changed: changed}, nil
	})
	if err != nil {
		return application.Turn{}, false, false, err
	}
	return value.turn, value.changed, value.replayed, nil
}

func jsonOrNull(value json.RawMessage) json.RawMessage {
	if len(value) == 0 {
		return json.RawMessage(`null`)
	}
	return value
}
