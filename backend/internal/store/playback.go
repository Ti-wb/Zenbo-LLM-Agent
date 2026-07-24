package store

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"reflect"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) ReportPlayback(
	ctx context.Context,
	device application.Device,
	sessionID, idempotencyKey string,
	requestDigest []byte,
	update application.Playback,
	now time.Time,
) (application.Playback, bool, error) {
	type result struct {
		playback application.Playback
		replayed bool
	}
	value, err := inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (result, error) {
		operation := "playback:" + sessionID
		if err := lockKey(ctx, tx, "session:"+sessionID); err != nil {
			return result{}, err
		}
		var authorized bool
		err := tx.QueryRow(ctx, `
			SELECT EXISTS (
				SELECT 1 FROM sessions
				WHERE id = $1 AND device_row_id = $2 AND device_id = $3
				  AND state = 'active'
			)
		`, sessionID, device.ID, device.DeviceID).Scan(&authorized)
		if err != nil {
			return result{}, err
		}
		if !authorized {
			return result{}, application.ErrNotFound
		}
		var storedDigest []byte
		var response json.RawMessage
		var idempotencyExpiresAt time.Time
		err = tx.QueryRow(ctx, `
			SELECT request_digest, response_body, expires_at
			FROM idempotency_records
			WHERE device_row_id = $1 AND operation = $2
			  AND idempotency_key = $3
			FOR UPDATE
		`, device.ID, operation, idempotencyKey).Scan(
			&storedDigest, &response, &idempotencyExpiresAt,
		)
		if err == nil {
			if !idempotencyExpiresAt.After(now) {
				if _, deleteErr := tx.Exec(ctx, `
					DELETE FROM idempotency_records
					WHERE device_row_id = $1 AND operation = $2
					  AND idempotency_key = $3
				`, device.ID, operation, idempotencyKey); deleteErr != nil {
					return result{}, deleteErr
				}
			} else {
				if subtle.ConstantTimeCompare(storedDigest, requestDigest) != 1 {
					return result{}, application.ErrIdempotencyConflict
				}
				var prior application.Playback
				if err := json.Unmarshal(response, &prior); err != nil {
					return result{}, err
				}
				return result{playback: prior, replayed: true}, nil
			}
		}
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return result{}, err
		}

		var current application.Playback
		err = tx.QueryRow(ctx, `
			SELECT playback.session_id::text, playback.turn_id::text,
			       playback.artifact_id::text, playback.status,
			       playback.reported_at, playback.position_ms,
			       COALESCE(playback.reason, ''), playback.updated_at
			FROM playback
			WHERE playback.session_id = $1 AND playback.turn_id = $2
			  AND playback.artifact_id = $3
			FOR UPDATE
		`, sessionID, update.TurnID, update.ArtifactID).Scan(
			&current.SessionID, &current.TurnID, &current.ArtifactID, &current.Status,
			&current.ReportedAt, &current.PositionMS, &current.Reason, &current.UpdatedAt,
		)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return result{}, err
		}
		if errors.Is(err, pgx.ErrNoRows) {
			if update.Status != "started" {
				return result{}, application.ErrInvalidTransition
			}
			err = tx.QueryRow(ctx, `
				INSERT INTO playback (
					session_id, turn_id, artifact_id, status, reported_at,
					position_ms, reason, updated_at
				)
				SELECT $1, $2, $3, $4, $5, $6, NULLIF($7, ''), $8
				WHERE EXISTS (
					SELECT 1 FROM sessions
					WHERE id = $1 AND device_row_id = $9 AND state = 'active'
				)
				  AND EXISTS (
					SELECT 1 FROM artifacts
					WHERE id = $3 AND session_id = $1 AND turn_id = $2
					  AND kind = 'tts_audio'
				  )
				RETURNING session_id::text, turn_id::text, artifact_id::text,
				          status, reported_at, position_ms, COALESCE(reason, ''),
				          updated_at
			`, sessionID, update.TurnID, update.ArtifactID, update.Status,
				update.ReportedAt, update.PositionMS, update.Reason, now, device.ID).Scan(
				&current.SessionID, &current.TurnID, &current.ArtifactID, &current.Status,
				&current.ReportedAt, &current.PositionMS, &current.Reason, &current.UpdatedAt,
			)
			if errors.Is(err, pgx.ErrNoRows) {
				return result{}, application.ErrNotFound
			}
			if err != nil {
				return result{}, mapDatabaseError(err)
			}
		} else {
			if playbackEquivalent(current, update) {
				// Still persist the idempotency response for this new key.
			} else {
				if current.Status != "started" ||
					(update.Status != "completed" && update.Status != "interrupted") {
					return result{}, application.ErrInvalidTransition
				}
				err = tx.QueryRow(ctx, `
					UPDATE playback
					SET status = $4, reported_at = $5, position_ms = $6,
					    reason = NULLIF($7, ''), updated_at = $8
					WHERE session_id = $1 AND turn_id = $2 AND artifact_id = $3
					RETURNING session_id::text, turn_id::text, artifact_id::text,
					          status, reported_at, position_ms, COALESCE(reason, ''),
					          updated_at
				`, sessionID, update.TurnID, update.ArtifactID, update.Status,
					update.ReportedAt, update.PositionMS, update.Reason, now).Scan(
					&current.SessionID, &current.TurnID, &current.ArtifactID, &current.Status,
					&current.ReportedAt, &current.PositionMS, &current.Reason, &current.UpdatedAt,
				)
				if err != nil {
					return result{}, err
				}
			}
		}
		body, err := json.Marshal(current)
		if err != nil {
			return result{}, err
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO idempotency_records (
				device_row_id, operation, idempotency_key, request_digest,
				session_id, resource_id, response_status, response_body,
				state, created_at, expires_at
			) VALUES (
				$1, $2, $3, $4, $5, $6, 202, $7, 'completed', $8, $9
			)
		`, device.ID, operation, idempotencyKey, requestDigest, sessionID,
			update.ArtifactID, body, now, now.Add(24*time.Hour)); err != nil {
			return result{}, mapDatabaseError(err)
		}
		return result{playback: current}, nil
	})
	if err != nil {
		return application.Playback{}, false, err
	}
	return value.playback, value.replayed, nil
}

func playbackEquivalent(left, right application.Playback) bool {
	return left.SessionID == right.SessionID &&
		left.TurnID == right.TurnID &&
		left.ArtifactID == right.ArtifactID &&
		left.Status == right.Status &&
		left.ReportedAt.Equal(right.ReportedAt) &&
		reflect.DeepEqual(left.PositionMS, right.PositionMS) &&
		left.Reason == right.Reason
}
