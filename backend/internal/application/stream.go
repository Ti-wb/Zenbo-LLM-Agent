package application

import (
	"context"
	"errors"
	"io"
	"sync"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/httpapi"
)

type eventStream struct {
	repository Repository
	deviceID   string
	sessionID  string
	bootstrap  domain.StreamBootstrap

	mu       sync.Mutex
	cursor   uint64
	queue    []Event
	current  uint64
	terminal bool
	closed   bool
	ctx      context.Context
	cancel   context.CancelFunc
}

func (stream *eventStream) Bootstrap() domain.StreamBootstrap {
	return stream.bootstrap
}

func (stream *eventStream) Next(ctx context.Context) (domain.Event, error) {
	for {
		stream.mu.Lock()
		if stream.closed {
			stream.mu.Unlock()
			return domain.Event{}, io.EOF
		}
		if len(stream.queue) > 0 {
			next := stream.queue[0]
			stream.queue = stream.queue[1:]
			stream.cursor = next.Sequence
			stream.mu.Unlock()
			return domainEvent(next), nil
		}
		cursor := stream.cursor
		if stream.terminal && cursor >= stream.current {
			stream.mu.Unlock()
			return domain.Event{}, io.EOF
		}
		stream.mu.Unlock()

		waitContext, cancel := context.WithCancel(ctx)
		stop := context.AfterFunc(stream.ctx, cancel)
		events, err := stream.repository.ListEvents(
			waitContext, stream.deviceID, stream.sessionID, cursor, 256,
		)
		if err == nil && len(events) == 0 {
			err = stream.repository.WaitForEvent(waitContext, stream.sessionID, cursor)
		}
		stop()
		cancel()
		if err != nil {
			if errors.Is(err, context.Canceled) && stream.ctx.Err() != nil {
				return domain.Event{}, io.EOF
			}
			return domain.Event{}, err
		}
		if len(events) > 0 {
			stream.mu.Lock()
			stream.queue = append(stream.queue, events...)
			stream.mu.Unlock()
		}
	}
}

func (stream *eventStream) Close() error {
	stream.mu.Lock()
	defer stream.mu.Unlock()
	if !stream.closed {
		stream.closed = true
		stream.cancel()
	}
	return nil
}

func domainEvent(value Event) domain.Event {
	return domain.Event{
		ProtocolVersion: domain.ProtocolVersion,
		EventID:         value.EventID,
		Sequence:        value.Sequence,
		SessionID:       value.SessionID,
		TurnID:          value.TurnID,
		Type:            domain.EventType(value.Type),
		Timestamp:       domain.NewTimestamp(value.Timestamp),
		Data:            value.Data,
	}
}

var _ httpapi.EventStream = (*eventStream)(nil)
