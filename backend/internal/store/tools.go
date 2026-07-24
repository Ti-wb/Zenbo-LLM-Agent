package store

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) CreateToolCall(ctx context.Context, params application.CreateToolCallParams) (application.ToolCall, application.Event, error) {
	type result struct {
		call  application.ToolCall
		event application.Event
	}
	value, err := inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (result, error) {
		if err := lockKey(ctx, tx, "session:"+params.SessionID); err != nil {
			return result{}, err
		}
		var turnState, sessionState string
		err := tx.QueryRow(ctx, `
			SELECT turns.state, sessions.state
			FROM turns JOIN sessions ON sessions.id = turns.session_id
			WHERE turns.id = $1 AND turns.session_id = $2
			FOR UPDATE OF turns, sessions
		`, params.TurnID, params.SessionID).Scan(&turnState, &sessionState)
		if errors.Is(err, pgx.ErrNoRows) {
			return result{}, application.ErrNotFound
		}
		if err != nil {
			return result{}, err
		}
		if sessionState != "active" ||
			(turnState != "accepted" && turnState != "processing" && turnState != "waiting_for_tool") {
			return result{}, application.ErrTurnTerminal
		}
		call, err := scanTool(tx.QueryRow(ctx, `
			INSERT INTO tool_calls (
				id, session_id, turn_id, name, version, owner, side_effect,
				arguments, status, deadline_at, timeout_ms, created_at, updated_at
			) VALUES (
				$1, $2, $3, $4, $5, $6, $7, $8, 'pending',
				$9, $10, $11, $11
			)
			RETURNING `+toolColumns,
			params.ID, params.SessionID, params.TurnID, params.Name, params.Version,
			params.Owner, params.SideEffect, normalizeJSON(params.Arguments),
			params.DeadlineAt, params.TimeoutMS, params.CreatedAt,
		))
		if err != nil {
			return result{}, mapDatabaseError(err)
		}
		if _, err := tx.Exec(ctx, `
			UPDATE turns SET state = 'waiting_for_tool', updated_at = $3
			WHERE id = $1 AND session_id = $2
		`, params.TurnID, params.SessionID, params.CreatedAt); err != nil {
			return result{}, err
		}
		event, err := appendEventTx(ctx, tx, application.AppendEventParams{
			SessionID: params.SessionID,
			TurnID:    params.TurnID,
			EventID:   params.EventID,
			Type:      "tool.call",
			Timestamp: params.CreatedAt,
			Data:      params.EventData,
		})
		if err != nil {
			return result{}, err
		}
		return result{call: call, event: event}, nil
	})
	if err != nil {
		return application.ToolCall{}, application.Event{}, err
	}
	return value.call, value.event, nil
}

func (store *Postgres) GetToolCall(ctx context.Context, sessionID, callID string) (application.ToolCall, error) {
	value, err := scanTool(store.pool.QueryRow(ctx, `
		SELECT `+toolColumns+`
		FROM tool_calls WHERE id = $1 AND session_id = $2
	`, callID, sessionID))
	if err != nil {
		return application.ToolCall{}, mapDatabaseError(err)
	}
	return value, nil
}

func (store *Postgres) UpdateToolCall(
	ctx context.Context,
	sessionID, callID string,
	status application.ToolStatus,
	output, errorDetail json.RawMessage,
	now time.Time,
) (application.ToolCall, bool, error) {
	type result struct {
		call    application.ToolCall
		changed bool
		late    bool
	}
	value, err := inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (result, error) {
		current, err := scanTool(tx.QueryRow(ctx, `
			SELECT `+toolColumns+`
			FROM tool_calls WHERE id = $1 AND session_id = $2 FOR UPDATE
		`, callID, sessionID))
		if err != nil {
			return result{}, mapDatabaseError(err)
		}
		output = jsonOrNull(output)
		errorDetail = jsonOrNull(errorDetail)
		if current.Status.Terminal() {
			if current.Status == status &&
				jsonEquivalent(current.Output, output) &&
				jsonEquivalent(current.Error, errorDetail) {
				return result{call: current}, nil
			}
			return result{}, application.ErrToolTerminal
		}
		if !now.Before(current.DeadlineAt) {
			timedOut, err := scanTool(tx.QueryRow(ctx, `
				UPDATE tool_calls
				SET status = 'failed',
				    output = NULL,
				    error = '{"code":"TIMEOUT","message":"Tool result deadline elapsed","retryable":false}'::jsonb,
				    terminal_at = $3, updated_at = $3
				WHERE id = $1 AND session_id = $2
				RETURNING `+toolColumns,
				callID, sessionID, now,
			))
			if err != nil {
				return result{}, err
			}
			if err := enqueueToolResumeTx(
				ctx, tx, sessionID, timedOut.TurnID, callID, now,
			); err != nil {
				return result{}, err
			}
			return result{call: timedOut, changed: true, late: true}, nil
		}
		if current.Status == status &&
			jsonEquivalent(current.Output, output) &&
			jsonEquivalent(current.Error, errorDetail) {
			return result{call: current}, nil
		}
		if status == application.ToolPending ||
			(current.Status == application.ToolAccepted && status == application.ToolAccepted) {
			return result{}, application.ErrInvalidTransition
		}
		if !status.Terminal() && status != application.ToolAccepted {
			return result{}, application.ErrInvalidTransition
		}
		var terminalAt any
		if status.Terminal() {
			terminalAt = now
		}
		updated, err := scanTool(tx.QueryRow(ctx, `
			UPDATE tool_calls
			SET status = $3,
			    output = CASE WHEN $4::jsonb = 'null'::jsonb THEN NULL ELSE $4::jsonb END,
			    error = CASE WHEN $5::jsonb = 'null'::jsonb THEN NULL ELSE $5::jsonb END,
			    updated_at = $6, terminal_at = $7
			WHERE id = $1 AND session_id = $2
			RETURNING `+toolColumns,
			callID, sessionID, status, output, errorDetail, now, terminalAt,
		))
		if err != nil {
			return result{}, err
		}
		if status.Terminal() {
			if err := enqueueToolResumeTx(
				ctx, tx, sessionID, updated.TurnID, callID, now,
			); err != nil {
				return result{}, err
			}
		}
		return result{call: updated, changed: true}, nil
	})
	if err != nil {
		return application.ToolCall{}, false, err
	}
	if value.late {
		return value.call, value.changed, application.ErrToolTerminal
	}
	return value.call, value.changed, nil
}

func (store *Postgres) ExpireToolCalls(ctx context.Context, now time.Time, limit int) ([]application.ToolCall, error) {
	if limit <= 0 {
		limit = 100
	}
	return inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) ([]application.ToolCall, error) {
		rows, err := tx.Query(ctx, `
			WITH candidates AS (
				SELECT id FROM tool_calls
				WHERE status IN ('pending', 'accepted') AND deadline_at <= $1
				ORDER BY deadline_at
				FOR UPDATE SKIP LOCKED
				LIMIT $2
			)
			UPDATE tool_calls AS calls
			SET status = 'failed',
			    error = '{"code":"TIMEOUT","message":"Tool result deadline elapsed","retryable":false}'::jsonb,
			    terminal_at = $1, updated_at = $1
			FROM candidates
			WHERE calls.id = candidates.id
			RETURNING calls.id::text, calls.session_id::text,
			          calls.turn_id::text, calls.name, calls.version,
			          calls.owner, calls.side_effect, calls.arguments,
			          calls.status, COALESCE(calls.output, 'null'::jsonb),
			          COALESCE(calls.error, 'null'::jsonb), calls.deadline_at,
			          calls.timeout_ms, calls.created_at, calls.updated_at,
			          calls.terminal_at
		`, now, limit,
		)
		if err != nil {
			return nil, err
		}
		var result []application.ToolCall
		for rows.Next() {
			call, err := scanTool(rows)
			if err != nil {
				return nil, err
			}
			result = append(result, call)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
		for _, call := range result {
			if err := enqueueToolResumeTx(
				ctx, tx, call.SessionID, call.TurnID, call.ID, now,
			); err != nil {
				return nil, err
			}
		}
		return result, nil
	})
}

func enqueueToolResumeTx(
	ctx context.Context,
	tx pgx.Tx,
	sessionID, turnID, callID string,
	now time.Time,
) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO jobs (
			job_type, session_id, turn_id, dedupe_key, payload,
			status, available_at, created_at, updated_at
		) VALUES (
			'process_turn', $1, $2, 'tool:' || $3,
			jsonb_build_object('toolCallId', $3),
			'queued', $4, $4, $4
		)
		ON CONFLICT (job_type, dedupe_key)
			WHERE dedupe_key IS NOT NULL
			  AND status IN ('queued', 'running')
		DO NOTHING
	`, sessionID, turnID, callID, now)
	return err
}

func jsonEquivalent(left, right json.RawMessage) bool {
	var leftValue, rightValue any
	if len(left) == 0 {
		left = []byte(`null`)
	}
	if len(right) == 0 {
		right = []byte(`null`)
	}
	if json.Unmarshal(left, &leftValue) != nil || json.Unmarshal(right, &rightValue) != nil {
		return false
	}
	return reflect.DeepEqual(leftValue, rightValue)
}
