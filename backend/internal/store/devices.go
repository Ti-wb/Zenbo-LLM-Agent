package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

func (store *Postgres) IssueDevice(ctx context.Context, tokenDigest []byte, label string, now time.Time) (application.Device, error) {
	var value application.Device
	err := store.pool.QueryRow(ctx, `
		INSERT INTO devices (token_digest, label, created_at)
		VALUES ($1, $2, $3)
		RETURNING id::text, COALESCE(device_id, ''), label, created_at,
		          bound_at, revoked_at, last_seen_at
	`, tokenDigest, label, now).Scan(
		&value.ID, &value.DeviceID, &value.Label, &value.CreatedAt,
		&value.BoundAt, &value.RevokedAt, &value.LastSeenAt,
	)
	if err != nil {
		return application.Device{}, mapDatabaseError(err)
	}
	return value, nil
}

func (store *Postgres) AuthenticateAndBindDevice(ctx context.Context, tokenDigest []byte, deviceID string, now time.Time) (application.Device, error) {
	if deviceID == "" {
		return application.Device{}, application.ErrUnauthorized
	}
	var value application.Device
	err := store.pool.QueryRow(ctx, `
		UPDATE devices
		SET device_id = COALESCE(device_id, $2),
		    bound_at = COALESCE(bound_at, $3),
		    last_seen_at = $3
		WHERE token_digest = $1
		  AND revoked_at IS NULL
		  AND (device_id IS NULL OR device_id = $2)
		RETURNING id::text, device_id, label, created_at,
		          bound_at, revoked_at, last_seen_at
	`, tokenDigest, deviceID, now).Scan(
		&value.ID, &value.DeviceID, &value.Label, &value.CreatedAt,
		&value.BoundAt, &value.RevokedAt, &value.LastSeenAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return application.Device{}, application.ErrUnauthorized
	}
	if err != nil {
		var databaseError *pgconn.PgError
		if errors.As(err, &databaseError) && databaseError.Code == "23505" {
			return application.Device{}, application.ErrUnauthorized
		}
		return application.Device{}, fmt.Errorf("authenticate device: %w", err)
	}
	return value, nil
}

func (store *Postgres) RevokeDevice(ctx context.Context, deviceRecordID string, now time.Time) error {
	_, err := inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (struct{}, error) {
		if err := lockKey(ctx, tx, "device:"+deviceRecordID); err != nil {
			return struct{}{}, err
		}
		var revokedAt *time.Time
		err := tx.QueryRow(ctx, `
			SELECT revoked_at FROM devices WHERE id = $1 FOR UPDATE
		`, deviceRecordID).Scan(&revokedAt)
		if errors.Is(err, pgx.ErrNoRows) {
			return struct{}{}, application.ErrNotFound
		}
		if err != nil {
			return struct{}{}, err
		}
		if revokedAt != nil {
			return struct{}{}, nil
		}

		rows, err := tx.Query(ctx, `
			SELECT id::text FROM sessions
			WHERE device_row_id = $1 AND state IN ('active', 'closing')
			ORDER BY created_at
		`, deviceRecordID)
		if err != nil {
			return struct{}{}, err
		}
		var sessionIDs []string
		for rows.Next() {
			var id string
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return struct{}{}, err
			}
			sessionIDs = append(sessionIDs, id)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return struct{}{}, err
		}
		for _, sessionID := range sessionIDs {
			if err := lockKey(ctx, tx, "session:"+sessionID); err != nil {
				return struct{}{}, err
			}
			var state string
			err = tx.QueryRow(ctx, `
				SELECT state FROM sessions WHERE id = $1 FOR UPDATE
			`, sessionID).Scan(&state)
			if err != nil {
				return struct{}{}, err
			}
			if state != "active" && state != "closing" {
				continue
			}
			turnIDs, err := cancelActiveTurnsTx(
				ctx, tx, sessionID, "timeout", now,
			)
			if err != nil {
				return struct{}{}, err
			}
			plan := terminalEventPlan(
				sessionID, turnIDs, application.SessionExpired,
				"credential_revoked", "timeout", now,
			)
			for _, event := range plan[:len(plan)-1] {
				if _, err := appendEventTx(ctx, tx, event); err != nil {
					return struct{}{}, err
				}
			}
			if _, err := tx.Exec(ctx, `
				UPDATE tool_calls
				SET status = 'failed',
				    error = '{"code":"TURN_CANCELLED","message":"Device credential was revoked","retryable":false}'::jsonb,
				    terminal_at = $2, updated_at = $2
				WHERE session_id = $1 AND status IN ('pending', 'accepted')
			`, sessionID, now); err != nil {
				return struct{}{}, err
			}
			if err := cancelSessionJobsTx(ctx, tx, sessionID, now); err != nil {
				return struct{}{}, err
			}
			if err := enqueueInputArtifactDeletionJobsTx(
				ctx, tx, sessionID, "", now,
			); err != nil {
				return struct{}{}, err
			}
			if _, err := tx.Exec(ctx, `
				UPDATE sessions
				SET state = 'expired', close_reason = 'credential_revoked',
				    closed_at = $2, updated_at = $2
				WHERE id = $1
			`, sessionID, now); err != nil {
				return struct{}{}, err
			}
			if _, err := appendEventTx(ctx, tx, plan[len(plan)-1]); err != nil {
				return struct{}{}, err
			}
		}
		if _, err := tx.Exec(ctx, `
			UPDATE devices SET revoked_at = $2 WHERE id = $1
		`, deviceRecordID, now); err != nil {
			return struct{}{}, err
		}
		return struct{}{}, nil
	})
	return err
}
