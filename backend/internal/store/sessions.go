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

type sessionCreateResult struct {
	session  application.Session
	replayed bool
}

func (store *Postgres) CreateSession(ctx context.Context, params application.CreateSessionParams) (application.Session, bool, error) {
	result, err := inTransaction(ctx, store.pool, pgx.TxOptions{IsoLevel: pgx.Serializable}, func(tx pgx.Tx) (sessionCreateResult, error) {
		if err := lockKey(ctx, tx, "device:"+params.Device.ID); err != nil {
			return sessionCreateResult{}, err
		}
		var boundDeviceID string
		var revokedAt *time.Time
		err := tx.QueryRow(ctx, `
			SELECT COALESCE(device_id, ''), revoked_at
			FROM devices
			WHERE id = $1
			FOR UPDATE
		`, params.Device.ID).Scan(&boundDeviceID, &revokedAt)
		if errors.Is(err, pgx.ErrNoRows) ||
			revokedAt != nil ||
			boundDeviceID == "" ||
			boundDeviceID != params.Device.DeviceID {
			return sessionCreateResult{}, application.ErrUnauthorized
		}
		if err != nil {
			return sessionCreateResult{}, err
		}

		var storedDigest []byte
		var resourceID string
		var idempotencyExpiresAt time.Time
		err = tx.QueryRow(ctx, `
			SELECT request_digest, COALESCE(resource_id::text, ''), expires_at
			FROM idempotency_records
			WHERE device_row_id = $1 AND operation = 'create_session'
			  AND idempotency_key = $2
			FOR UPDATE
		`, params.Device.ID, params.IdempotencyKey).Scan(
			&storedDigest, &resourceID, &idempotencyExpiresAt,
		)
		if err == nil {
			if !idempotencyExpiresAt.After(params.Now) {
				if _, deleteErr := tx.Exec(ctx, `
					DELETE FROM idempotency_records
					WHERE device_row_id = $1 AND operation = 'create_session'
					  AND idempotency_key = $2
				`, params.Device.ID, params.IdempotencyKey); deleteErr != nil {
					return sessionCreateResult{}, deleteErr
				}
			} else {
				if subtle.ConstantTimeCompare(storedDigest, params.RequestDigest) != 1 {
					return sessionCreateResult{}, application.ErrIdempotencyConflict
				}
				existing, getErr := scanSession(tx.QueryRow(ctx,
					`SELECT `+sessionColumns+` FROM sessions WHERE id = $1`, resourceID))
				if getErr == nil && !existing.State.Terminal() {
					return sessionCreateResult{session: existing, replayed: true}, nil
				}
				// Native currently reuses its create-session key after a terminal
				// session. Releasing only terminal results preserves that wire
				// compatibility without weakening active request deduplication.
				if _, deleteErr := tx.Exec(ctx, `
				DELETE FROM idempotency_records
				WHERE device_row_id = $1 AND operation = 'create_session'
				  AND idempotency_key = $2
			`, params.Device.ID, params.IdempotencyKey); deleteErr != nil {
					return sessionCreateResult{}, deleteErr
				}
			}
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return sessionCreateResult{}, err
		}

		rows, err := tx.Query(ctx, `
			SELECT id::text FROM sessions
			WHERE device_row_id = $1 AND state IN ('active', 'closing')
		`, params.Device.ID)
		if err != nil {
			return sessionCreateResult{}, err
		}
		var replaced []string
		for rows.Next() {
			var sessionID string
			if err := rows.Scan(&sessionID); err != nil {
				rows.Close()
				return sessionCreateResult{}, err
			}
			replaced = append(replaced, sessionID)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return sessionCreateResult{}, err
		}
		for _, sessionID := range replaced {
			if err := lockKey(ctx, tx, "session:"+sessionID); err != nil {
				return sessionCreateResult{}, err
			}
			var state string
			err = tx.QueryRow(ctx, `
				SELECT state FROM sessions WHERE id = $1 FOR UPDATE
			`, sessionID).Scan(&state)
			if err != nil {
				return sessionCreateResult{}, err
			}
			if state != "active" && state != "closing" {
				continue
			}
			turnIDs, err := cancelActiveTurnsTx(
				ctx, tx, sessionID, "superseded", params.Now,
			)
			if err != nil {
				return sessionCreateResult{}, err
			}
			plan := terminalEventPlan(
				sessionID, turnIDs, application.SessionClosed,
				"replaced", "superseded", params.Now,
			)
			for _, event := range plan[:len(plan)-1] {
				if _, err := appendEventTx(ctx, tx, event); err != nil {
					return sessionCreateResult{}, err
				}
			}
			if _, err := tx.Exec(ctx, `
				UPDATE tool_calls
				SET status = 'failed',
				    error = '{"code":"TURN_CANCELLED","message":"Turn was superseded","retryable":false}'::jsonb,
				    terminal_at = $2, updated_at = $2
				WHERE session_id = $1 AND status IN ('pending', 'accepted')
			`, sessionID, params.Now); err != nil {
				return sessionCreateResult{}, err
			}
			if err := cancelSessionJobsTx(ctx, tx, sessionID, params.Now); err != nil {
				return sessionCreateResult{}, err
			}
			if err := enqueueInputArtifactDeletionJobsTx(
				ctx, tx, sessionID, "", params.Now,
			); err != nil {
				return sessionCreateResult{}, err
			}
			if _, err := tx.Exec(ctx, `
				UPDATE sessions
				SET state = 'closed', closed_at = $2, updated_at = $2,
				    close_reason = 'replaced'
				WHERE id = $1
			`, sessionID, params.Now); err != nil {
				return sessionCreateResult{}, err
			}
			if _, err := appendEventTx(ctx, tx, plan[len(plan)-1]); err != nil {
				return sessionCreateResult{}, err
			}
		}

		created, err := scanSession(tx.QueryRow(ctx, `
			INSERT INTO sessions (
				id, device_row_id, device_id, protocol_version, state,
				agent_profile, provider_kind, provider_profile, client, context,
				tool_manifest, created_at, updated_at, expires_at
			) VALUES (
				COALESCE(NULLIF($1, '')::uuid, gen_random_uuid()), $2, $3, '1.0',
				'active', $4, $5, $6, $7, $8, $9, $10, $10, $11
			)
			RETURNING `+sessionColumns,
			params.ID, params.Device.ID, params.Device.DeviceID, params.AgentProfile,
			params.ProviderKind, params.ProviderProfile, normalizeJSON(params.Client),
			normalizeJSON(params.Context), normalizeJSON(params.ToolManifest),
			params.Now, params.ExpiresAt,
		))
		if err != nil {
			return sessionCreateResult{}, mapDatabaseError(err)
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO provider_state (
				session_id, provider_kind, provider_profile, updated_at
			) VALUES ($1, $2, $3, $4)
		`, created.ID, created.ProviderKind, created.ProviderProfile, params.Now); err != nil {
			return sessionCreateResult{}, err
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO idempotency_records (
				device_row_id, operation, idempotency_key, request_digest,
				session_id, resource_id, state, created_at, expires_at
			) VALUES ($1, 'create_session', $2, $3, $4, $4, 'completed', $5, $6)
		`, params.Device.ID, params.IdempotencyKey, params.RequestDigest,
			created.ID, params.Now, params.IdempotencyUntil); err != nil {
			return sessionCreateResult{}, mapDatabaseError(err)
		}
		return sessionCreateResult{session: created}, nil
	})
	if err != nil {
		return application.Session{}, false, err
	}
	return result.session, result.replayed, nil
}

func (store *Postgres) GetSession(ctx context.Context, deviceID, sessionID string) (application.Session, error) {
	value, err := scanSession(store.pool.QueryRow(ctx, `
		SELECT `+sessionColumns+`
		FROM sessions
		WHERE id = $1 AND device_id = $2
	`, sessionID, deviceID))
	if err != nil {
		return application.Session{}, mapDatabaseError(err)
	}
	return value, nil
}

func (store *Postgres) GetSessionForWorker(ctx context.Context, sessionID string) (application.Session, error) {
	value, err := scanSession(store.pool.QueryRow(ctx, `
		SELECT `+sessionColumns+`
		FROM sessions
		WHERE id = $1
	`, sessionID))
	if err != nil {
		return application.Session{}, mapDatabaseError(err)
	}
	return value, nil
}

func (store *Postgres) CloseSession(ctx context.Context, params application.CloseSessionParams) (application.Session, error) {
	return inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (application.Session, error) {
		if err := lockKey(ctx, tx, "session:"+params.SessionID); err != nil {
			return application.Session{}, err
		}
		current, err := scanSession(tx.QueryRow(ctx, `
			SELECT `+sessionColumns+`
			FROM sessions WHERE id = $1 AND device_id = $2 FOR UPDATE
		`, params.SessionID, params.DeviceID))
		if err != nil {
			return application.Session{}, mapDatabaseError(err)
		}
		if current.State.Terminal() {
			return current, nil
		}
		if params.State != application.SessionClosed && params.State != application.SessionExpired {
			return application.Session{}, application.ErrInvalidTransition
		}

		turnReason := "client_request"
		if params.State == application.SessionExpired {
			turnReason = "timeout"
		} else if params.Reason == "replaced" {
			turnReason = "superseded"
		}
		turnIDs, err := cancelActiveTurnsTx(
			ctx, tx, params.SessionID, turnReason, params.Now,
		)
		if err != nil {
			return application.Session{}, err
		}
		plan := terminalEventPlan(
			params.SessionID, turnIDs, params.State, params.Reason,
			turnReason, params.Now,
		)
		for _, event := range plan[:len(plan)-1] {
			if _, err := appendEventTx(ctx, tx, event); err != nil {
				return application.Session{}, err
			}
		}
		if _, err := tx.Exec(ctx, `
			UPDATE tool_calls
			SET status = 'failed',
			    error = '{"code":"TURN_CANCELLED","message":"Session terminated","retryable":false}'::jsonb,
			    terminal_at = $2, updated_at = $2
			WHERE session_id = $1 AND status IN ('pending', 'accepted')
		`, params.SessionID, params.Now); err != nil {
			return application.Session{}, err
		}
		if err := cancelSessionJobsTx(ctx, tx, params.SessionID, params.Now); err != nil {
			return application.Session{}, err
		}
		if err := enqueueInputArtifactDeletionJobsTx(
			ctx, tx, params.SessionID, "", params.Now,
		); err != nil {
			return application.Session{}, err
		}
		if _, err := scanSession(tx.QueryRow(ctx, `
			UPDATE sessions
			SET state = $2, close_reason = $3, closed_at = $4, updated_at = $4
			WHERE id = $1
			RETURNING `+sessionColumns,
			params.SessionID, params.State, params.Reason, params.Now,
		)); err != nil {
			return application.Session{}, err
		}
		if _, err := appendEventTx(ctx, tx, plan[len(plan)-1]); err != nil {
			return application.Session{}, err
		}
		// appendEventTx increments the session cursor after terminal was
		// scanned, so return the authoritative final value.
		return scanSession(tx.QueryRow(ctx,
			`SELECT `+sessionColumns+` FROM sessions WHERE id = $1`, params.SessionID))
	})
}

func cancelActiveTurnsTx(
	ctx context.Context,
	tx pgx.Tx,
	sessionID, reason string,
	now time.Time,
) ([]string, error) {
	rows, err := tx.Query(ctx, `
		UPDATE turns
		SET state = 'cancelled', cancel_reason = $2,
		    terminal_at = $3, updated_at = $3
		WHERE session_id = $1
		  AND state IN ('accepted', 'processing', 'waiting_for_tool')
		RETURNING id::text
	`, sessionID, reason, now)
	if err != nil {
		return nil, err
	}
	var turnIDs []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		turnIDs = append(turnIDs, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(turnIDs) > 0 {
		if _, err := tx.Exec(ctx, `
			UPDATE artifacts
			SET expires_at = LEAST(expires_at, $2)
			FROM turns
			WHERE artifacts.turn_id = turns.id
			  AND artifacts.session_id = $1
			  AND artifacts.kind = 'input_audio'
			  AND turns.session_id = $1
			  AND turns.state = 'cancelled'
			  AND turns.terminal_at = $2
		`, sessionID, now); err != nil {
			return nil, err
		}
	}
	return turnIDs, nil
}

func terminalEventPlan(
	sessionID string,
	turnIDs []string,
	state application.SessionState,
	sessionReason, turnReason string,
	now time.Time,
) []application.AppendEventParams {
	events := make([]application.AppendEventParams, 0, len(turnIDs)+1)
	turnData, _ := json.Marshal(map[string]string{"reason": turnReason})
	for _, turnID := range turnIDs {
		events = append(events, application.AppendEventParams{
			SessionID: sessionID,
			TurnID:    turnID,
			Type:      "turn.cancelled",
			Timestamp: now,
			Data:      turnData,
		})
	}
	if state == application.SessionExpired {
		switch sessionReason {
		case "idle_timeout", "credential_revoked", "server_policy":
		default:
			sessionReason = "server_policy"
		}
		data, _ := json.Marshal(map[string]string{
			"reason":    sessionReason,
			"expiredAt": now.UTC().Format("2006-01-02T15:04:05.000Z"),
		})
		events = append(events, application.AppendEventParams{
			SessionID: sessionID,
			Type:      "session.expired",
			Timestamp: now,
			Data:      data,
		})
		return events
	}
	switch sessionReason {
	case "client_request", "expired", "replaced", "policy":
	default:
		sessionReason = "policy"
	}
	data, _ := json.Marshal(map[string]string{"reason": sessionReason})
	events = append(events, application.AppendEventParams{
		SessionID: sessionID,
		Type:      "session.closed",
		Timestamp: now,
		Data:      data,
	})
	return events
}
