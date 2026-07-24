package store

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5/pgconn"
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

func TestRetryTransactionRetriesSerializationAndDeadlockErrors(t *testing.T) {
	for _, code := range []string{"40001", "40P01"} {
		t.Run(code, func(t *testing.T) {
			attempts := 0
			value, err := retryTransaction(context.Background(), func() (string, error) {
				attempts++
				if attempts < 3 {
					return "", fmt.Errorf(
						"transaction stage failed: %w",
						&pgconn.PgError{Code: code},
					)
				}
				return "committed", nil
			})
			if err != nil {
				t.Fatal(err)
			}
			if value != "committed" || attempts != 3 {
				t.Fatalf("result = %q after %d attempts", value, attempts)
			}
		})
	}
}

func TestRetryTransactionDoesNotRetryDomainErrors(t *testing.T) {
	attempts := 0
	_, err := retryTransaction(context.Background(), func() (struct{}, error) {
		attempts++
		return struct{}{}, application.ErrConflict
	})
	if !errors.Is(err, application.ErrConflict) {
		t.Fatalf("error = %v, want conflict", err)
	}
	if attempts != 1 {
		t.Fatalf("attempts = %d, want 1", attempts)
	}
}

func TestRetryTransactionHasABoundedAttemptCount(t *testing.T) {
	attempts := 0
	expected := &pgconn.PgError{Code: "40001"}
	_, err := retryTransaction(context.Background(), func() (struct{}, error) {
		attempts++
		return struct{}{}, expected
	})
	if !errors.Is(err, expected) {
		t.Fatalf("error = %v, want final serialization error", err)
	}
	if attempts != maxTransactionAttempts {
		t.Fatalf("attempts = %d, want %d", attempts, maxTransactionAttempts)
	}
}

func TestRetryTransactionStopsWhenContextIsCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	attempts := 0
	_, err := retryTransaction(ctx, func() (struct{}, error) {
		attempts++
		cancel()
		return struct{}{}, &pgconn.PgError{Code: "40001"}
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("error = %v, want context cancellation", err)
	}
	if attempts != 1 {
		t.Fatalf("attempts = %d, want 1", attempts)
	}
}
