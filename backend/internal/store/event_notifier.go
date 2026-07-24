package store

import (
	"context"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	eventListenerRetryMinimum = 100 * time.Millisecond
	eventListenerRetryMaximum = 5 * time.Second
	eventPollingFallback      = time.Second
)

// eventNotifier owns the process-wide PostgreSQL LISTEN connection and fans a
// session notification out to in-process waiters. Notifications are only
// hints: every waiter re-reads the durable session sequence before returning.
type eventNotifier struct {
	pool   *pgxpool.Pool
	ctx    context.Context
	cancel context.CancelFunc
	done   chan struct{}
	start  sync.Once
	close  sync.Once

	mu          sync.Mutex
	nextID      uint64
	subscribers map[string]map[uint64]chan struct{}
}

func newEventNotifier(pool *pgxpool.Pool) *eventNotifier {
	ctx, cancel := context.WithCancel(context.Background())
	notifier := &eventNotifier{
		pool:        pool,
		ctx:         ctx,
		cancel:      cancel,
		done:        make(chan struct{}),
		subscribers: make(map[string]map[uint64]chan struct{}),
	}
	return notifier
}

func (notifier *eventNotifier) Close() {
	if notifier == nil {
		return
	}
	notifier.close.Do(func() {
		notifier.cancel()
		// If no event stream ever subscribed, no listener goroutine owns done.
		notifier.start.Do(func() { close(notifier.done) })
		<-notifier.done
	})
}

func (notifier *eventNotifier) subscribe(sessionID string) (<-chan struct{}, func()) {
	notifier.start.Do(func() { go notifier.run() })
	channel := make(chan struct{}, 1)
	notifier.mu.Lock()
	notifier.nextID++
	id := notifier.nextID
	session := notifier.subscribers[sessionID]
	if session == nil {
		session = make(map[uint64]chan struct{})
		notifier.subscribers[sessionID] = session
	}
	session[id] = channel
	notifier.mu.Unlock()

	var once sync.Once
	return channel, func() {
		once.Do(func() {
			notifier.mu.Lock()
			session := notifier.subscribers[sessionID]
			delete(session, id)
			if len(session) == 0 {
				delete(notifier.subscribers, sessionID)
			}
			notifier.mu.Unlock()
		})
	}
}

func (notifier *eventNotifier) broadcast(sessionID string) {
	notifier.mu.Lock()
	defer notifier.mu.Unlock()
	for _, channel := range notifier.subscribers[sessionID] {
		select {
		case channel <- struct{}{}:
		default:
		}
	}
}

func (notifier *eventNotifier) subscriberCount(sessionID string) int {
	notifier.mu.Lock()
	defer notifier.mu.Unlock()
	return len(notifier.subscribers[sessionID])
}

func (notifier *eventNotifier) run() {
	defer close(notifier.done)
	if notifier.pool == nil {
		<-notifier.ctx.Done()
		return
	}
	retry := eventListenerRetryMinimum
	for notifier.ctx.Err() == nil {
		connection, err := notifier.pool.Acquire(notifier.ctx)
		if err != nil {
			if !waitForRetry(notifier.ctx, retry) {
				return
			}
			retry = nextListenerRetry(retry)
			continue
		}
		if _, err = connection.Exec(notifier.ctx, `LISTEN gateway_events`); err != nil {
			connection.Release()
			if !waitForRetry(notifier.ctx, retry) {
				return
			}
			retry = nextListenerRetry(retry)
			continue
		}
		retry = eventListenerRetryMinimum
		for notifier.ctx.Err() == nil {
			notification, waitErr := connection.Conn().WaitForNotification(notifier.ctx)
			if waitErr != nil {
				break
			}
			if sessionID := notificationSession(notification.Payload); sessionID != "" {
				notifier.broadcast(sessionID)
			}
		}
		cleanupContext, cancel := context.WithTimeout(
			context.Background(), time.Second,
		)
		_, _ = connection.Exec(cleanupContext, `UNLISTEN *`)
		cancel()
		connection.Release()
		if notifier.ctx.Err() == nil && !waitForRetry(notifier.ctx, retry) {
			return
		}
		retry = nextListenerRetry(retry)
	}
}

func notificationSession(payload string) string {
	index := strings.LastIndexByte(payload, ':')
	if index <= 0 {
		return ""
	}
	return payload[:index]
}

func waitForRetry(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func nextListenerRetry(current time.Duration) time.Duration {
	current *= 2
	if current > eventListenerRetryMaximum {
		return eventListenerRetryMaximum
	}
	return current
}
