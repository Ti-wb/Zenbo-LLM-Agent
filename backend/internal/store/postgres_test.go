package store

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type stubHealthQuerier struct {
	result int32
	err    error
	calls  int
}

func (stub *stubHealthQuerier) Health(context.Context) (int32, error) {
	stub.calls++
	return stub.result, stub.err
}

func TestPingUsesGeneratedHealthQueryBoundary(t *testing.T) {
	health := &stubHealthQuerier{result: 1}
	repository := &Postgres{queries: health}

	if err := repository.Ping(context.Background()); err != nil {
		t.Fatalf("Ping returned %v", err)
	}
	if health.calls != 1 {
		t.Fatalf("health query calls = %d, want 1", health.calls)
	}
}

func TestPingRejectsUnexpectedHealthResult(t *testing.T) {
	repository := &Postgres{queries: &stubHealthQuerier{result: 0}}

	err := repository.Ping(context.Background())
	if err == nil || !strings.Contains(err.Error(), "returned 0") {
		t.Fatalf("Ping returned %v, want unexpected-result error", err)
	}
}

func TestPingPropagatesHealthQueryError(t *testing.T) {
	expected := errors.New("database unavailable")
	repository := &Postgres{queries: &stubHealthQuerier{err: expected}}

	if err := repository.Ping(context.Background()); !errors.Is(err, expected) {
		t.Fatalf("Ping returned %v, want %v", err, expected)
	}
}

func TestPingRejectsMissingHealthQuery(t *testing.T) {
	var repository *Postgres
	if err := repository.Ping(context.Background()); err == nil {
		t.Fatal("nil repository Ping unexpectedly succeeded")
	}
	if err := (&Postgres{}).Ping(context.Background()); err == nil {
		t.Fatal("unconfigured repository Ping unexpectedly succeeded")
	}
}

func TestOpenDoesNotExposeDatabaseCredential(t *testing.T) {
	const secret = "gateway-database-password"
	_, err := Open(
		context.Background(),
		"postgres://gateway:"+secret+"@%zz/database",
	)
	if err == nil {
		t.Fatal("Open unexpectedly succeeded")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("Open error exposed database credential: %q", err)
	}
}
