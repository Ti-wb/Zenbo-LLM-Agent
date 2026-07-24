package store

import (
	"testing"
	"time"
)

func TestEventNotifierBroadcastsOnlyToMatchingSession(t *testing.T) {
	notifier := newEventNotifier(nil)
	t.Cleanup(notifier.Close)
	first, unsubscribeFirst := notifier.subscribe("session-a")
	defer unsubscribeFirst()
	second, unsubscribeSecond := notifier.subscribe("session-a")
	defer unsubscribeSecond()
	other, unsubscribeOther := notifier.subscribe("session-b")
	defer unsubscribeOther()

	if count := notifier.subscriberCount("session-a"); count != 2 {
		t.Fatalf("session-a subscribers = %d, want 2", count)
	}
	notifier.broadcast("session-a")
	for index, channel := range []<-chan struct{}{first, second} {
		select {
		case <-channel:
		case <-time.After(time.Second):
			t.Fatalf("matching subscriber %d was not notified", index)
		}
	}
	select {
	case <-other:
		t.Fatal("notification leaked to another session")
	default:
	}

	// Repeated notifications coalesce instead of blocking the shared listener.
	notifier.broadcast("session-a")
	notifier.broadcast("session-a")
	select {
	case <-first:
	case <-time.After(time.Second):
		t.Fatal("coalesced notification was not delivered")
	}
	select {
	case <-first:
		t.Fatal("subscriber channel was not bounded")
	default:
	}
}

func TestNotificationSessionParsing(t *testing.T) {
	tests := map[string]string{
		"10000000-0000-4000-8000-000000000001:42": "10000000-0000-4000-8000-000000000001",
		"missing-sequence":                        "",
		":1":                                      "",
		"":                                        "",
	}
	for payload, expected := range tests {
		if actual := notificationSession(payload); actual != expected {
			t.Fatalf(
				"notificationSession(%q) = %q, want %q",
				payload, actual, expected,
			)
		}
	}
}

func TestListenerRetryIsBounded(t *testing.T) {
	if got := nextListenerRetry(eventListenerRetryMaximum); got != eventListenerRetryMaximum {
		t.Fatalf("retry = %s, want %s", got, eventListenerRetryMaximum)
	}
}
