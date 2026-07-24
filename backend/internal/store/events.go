package store

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) AppendEvent(ctx context.Context, params application.AppendEventParams) (application.Event, error) {
	return inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (application.Event, error) {
		return appendEventTx(ctx, tx, params)
	})
}

func appendEventTx(ctx context.Context, tx pgx.Tx, params application.AppendEventParams) (application.Event, error) {
	if len(params.Data) == 0 {
		params.Data = []byte(`{}`)
	}
	if params.Timestamp.IsZero() {
		return application.Event{}, fmt.Errorf("event timestamp is required")
	}
	var sequence int64
	err := tx.QueryRow(ctx, `
		UPDATE sessions
		SET last_sequence = last_sequence + 1, updated_at = GREATEST(updated_at, $2)
		WHERE id = $1
		  AND (
		    (
		      $4 = ''
		      AND (
		        ($3 = 'session.closed' AND state = 'closed')
		        OR ($3 = 'session.expired' AND state = 'expired')
		      )
		      AND NOT EXISTS (
		        SELECT 1 FROM events
		        WHERE events.session_id = sessions.id
		          AND events.event_type IN ('session.closed', 'session.expired')
		      )
		    )
		    OR (
		      $4 <> ''
		      AND
		      $3 NOT IN ('session.closed', 'session.expired')
		      AND state IN ('active', 'closing')
		      AND EXISTS (
		        SELECT 1 FROM turns
		        WHERE turns.id = NULLIF($4, '')::uuid
		          AND turns.session_id = sessions.id
		          AND (
		            (
		              $3 IN (
		                'turn.accepted', 'stt.final', 'agent.thinking',
		                'tool.call', 'agent.text.final', 'tts.ready'
		              )
		              AND turns.state IN (
		                'accepted', 'processing', 'waiting_for_tool'
		              )
		            )
		            OR ($3 = 'turn.completed' AND turns.state = 'completed')
		            OR ($3 = 'turn.error' AND turns.state = 'failed')
		            OR ($3 = 'turn.cancelled' AND turns.state = 'cancelled')
		          )
		      )
		    )
		  )
		RETURNING last_sequence
	`, params.SessionID, params.Timestamp, params.Type, params.TurnID).Scan(&sequence)
	if errors.Is(err, pgx.ErrNoRows) {
		if params.TurnID != "" {
			return application.Event{}, application.ErrTurnTerminal
		}
		return application.Event{}, application.ErrSessionNotActive
	}
	if err != nil {
		return application.Event{}, err
	}
	event, err := scanEvent(tx.QueryRow(ctx, `
		INSERT INTO events (
			session_id, sequence, event_id, turn_id, event_type, occurred_at, data
		) VALUES (
			$1, $2, COALESCE(NULLIF($3, '')::uuid, gen_random_uuid()),
			NULLIF($4, '')::uuid, $5, $6, $7
		)
		RETURNING `+eventColumns,
		params.SessionID, sequence, params.EventID, params.TurnID, params.Type,
		params.Timestamp, normalizeJSON(params.Data),
	))
	if err != nil {
		return application.Event{}, mapDatabaseError(err)
	}
	if _, err := tx.Exec(ctx, `SELECT pg_notify('gateway_events', $1)`,
		fmt.Sprintf("%s:%d", params.SessionID, sequence)); err != nil {
		return application.Event{}, err
	}
	return event, nil
}

func (store *Postgres) ReplayWindow(ctx context.Context, deviceID, sessionID string, after uint64, limit int) (application.ReplayWindow, error) {
	if limit <= 0 {
		limit = 256
	}
	return inTransaction(ctx, store.pool, pgx.TxOptions{
		IsoLevel:   pgx.RepeatableRead,
		AccessMode: pgx.ReadOnly,
	}, func(tx pgx.Tx) (application.ReplayWindow, error) {
		var current int64
		var state string
		err := tx.QueryRow(ctx, `
			SELECT last_sequence, state
			FROM sessions WHERE id = $1 AND device_id = $2
		`, sessionID, deviceID).Scan(&current, &state)
		if errors.Is(err, pgx.ErrNoRows) {
			return application.ReplayWindow{}, application.ErrNotFound
		}
		if err != nil {
			return application.ReplayWindow{}, err
		}
		if after > uint64(current) {
			return application.ReplayWindow{}, application.ErrConflict
		}

		var oldest *int64
		if err := tx.QueryRow(ctx,
			`SELECT MIN(sequence) FROM events WHERE session_id = $1`,
			sessionID).Scan(&oldest); err != nil {
			return application.ReplayWindow{}, err
		}
		var retained int64
		if err := tx.QueryRow(ctx, `
			SELECT COUNT(*)
			FROM events
			WHERE session_id = $1 AND sequence > $2 AND sequence <= $3
		`, sessionID, int64(after), current).Scan(&retained); err != nil {
			return application.ReplayWindow{}, err
		}
		stale := replayRangeHasGap(after, current, retained)
		var activeTurnID string
		_ = tx.QueryRow(ctx, `
			SELECT id::text FROM turns
			WHERE session_id = $1
			  AND state IN ('accepted', 'processing', 'waiting_for_tool')
			LIMIT 1
		`, sessionID).Scan(&activeTurnID)

		window := application.ReplayWindow{
			CurrentSequence: uint64(current),
			Stale:           stale,
			SessionState:    application.SessionState(state),
			ActiveTurnID:    activeTurnID,
		}
		if oldest != nil {
			window.OldestSequence = uint64(*oldest)
		}
		if stale {
			return window, nil
		}
		rows, err := tx.Query(ctx, `
			SELECT `+eventColumns+`
			FROM events
			WHERE session_id = $1 AND sequence > $2 AND sequence <= $3
			ORDER BY sequence
			LIMIT $4
		`, sessionID, int64(after), current, limit)
		if err != nil {
			return application.ReplayWindow{}, err
		}
		defer rows.Close()
		for rows.Next() {
			event, err := scanEvent(rows)
			if err != nil {
				return application.ReplayWindow{}, err
			}
			window.Events = append(window.Events, event)
		}
		return window, rows.Err()
	})
}

func (store *Postgres) ListEvents(ctx context.Context, deviceID, sessionID string, after uint64, limit int) ([]application.Event, error) {
	if limit <= 0 {
		limit = 256
	}
	rows, err := store.pool.Query(ctx, `
		SELECT `+eventColumns+`
		FROM events
		WHERE session_id = $1 AND sequence > $2
		  AND EXISTS (
		    SELECT 1 FROM sessions
		    WHERE sessions.id = events.session_id AND sessions.device_id = $3
		  )
		ORDER BY sequence
		LIMIT $4
	`, sessionID, int64(after), deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []application.Event
	for rows.Next() {
		event, err := scanEvent(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, event)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if err := validateContiguousEvents(after, result); err != nil {
		return nil, err
	}
	return result, nil
}

func (store *Postgres) WaitForEvent(ctx context.Context, sessionID string, after uint64) error {
	if after >= math.MaxInt64 {
		return application.ErrConflict
	}
	afterSequence := int64(after)
	nextSequence := afterSequence + 1
	wake, unsubscribe := store.events.subscribe(sessionID)
	defer unsubscribe()
	poll := time.NewTicker(eventPollingFallback)
	defer poll.Stop()
	for {
		var current int64
		var nextExists bool
		err := store.pool.QueryRow(ctx, `
			SELECT
				last_sequence,
				EXISTS (
					SELECT 1
					FROM events
					WHERE events.session_id = sessions.id
					  AND events.sequence = $2
				)
			FROM sessions
			WHERE id = $1
		`, sessionID, nextSequence).Scan(&current, &nextExists)
		if errors.Is(err, pgx.ErrNoRows) {
			return application.ErrNotFound
		}
		if err != nil {
			return err
		}
		if current > afterSequence {
			if !nextExists {
				return application.ErrReplayStale
			}
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-store.events.done:
			return context.Canceled
		case <-wake:
		case <-poll.C:
		}
	}
}

func replayRangeHasGap(after uint64, current, retained int64) bool {
	if current < 0 || after > uint64(current) {
		return true
	}
	return retained != current-int64(after)
}

func validateContiguousEvents(after uint64, events []application.Event) error {
	expected := after
	for _, event := range events {
		if expected == math.MaxUint64 || event.Sequence != expected+1 {
			return application.ErrReplayStale
		}
		expected = event.Sequence
	}
	return nil
}
