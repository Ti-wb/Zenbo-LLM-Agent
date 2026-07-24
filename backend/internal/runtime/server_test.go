package runtime

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestAdminHealthReadinessAndMetrics(t *testing.T) {
	var calls atomic.Int64
	handler := NewAdminHandler(func(context.Context) error {
		calls.Add(1)
		return nil
	}, NewMetrics())
	for _, path := range []string{"/healthz", "/readyz", "/metrics"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("%s returned %d", path, response.Code)
		}
		if path == "/metrics" &&
			(!strings.Contains(response.Body.String(), "\n# TYPE") ||
				strings.Contains(response.Body.String(), `\n`)) {
			t.Fatalf("invalid Prometheus exposition: %q", response.Body.String())
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("expected readiness check, got %d", calls.Load())
	}
}

func TestAdminReadinessDoesNotLeakFailure(t *testing.T) {
	handler := NewAdminHandler(func(context.Context) error {
		return errors.New("postgres://user:password@db/private transcript")
	}, nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/readyz", nil))
	if response.Code != http.StatusServiceUnavailable ||
		strings.Contains(response.Body.String(), "password") {
		t.Fatalf("unsafe readiness response: %d %s", response.Code, response.Body.String())
	}
}

func TestCachedCheckerCachesBriefly(t *testing.T) {
	var calls atomic.Int64
	checker := &CachedChecker{
		TTL: time.Minute,
		Check: func(context.Context) error {
			calls.Add(1)
			return nil
		},
	}
	if err := checker.CheckReady(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := checker.CheckReady(context.Background()); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatalf("expected one check, got %d", calls.Load())
	}
}

func TestCachedCheckerWaitersRespectContextWhenCheckStalls(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	checker := &CachedChecker{
		Check: func(context.Context) error {
			close(started)
			<-release
			return nil
		},
	}
	firstContext, cancelFirst := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancelFirst()
	firstDone := make(chan error, 1)
	go func() { firstDone <- checker.CheckReady(firstContext) }()
	<-started

	secondContext, cancelSecond := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancelSecond()
	if err := checker.CheckReady(secondContext); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("waiting readiness returned %v", err)
	}
	if err := <-firstDone; !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("initial readiness returned %v", err)
	}
	close(release)
	deadline := time.Now().Add(time.Second)
	for {
		if err := checker.CheckReady(context.Background()); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("completed readiness was not cached")
		}
		time.Sleep(time.Millisecond)
	}
}
