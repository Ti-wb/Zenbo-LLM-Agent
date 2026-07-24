package provider

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"
)

func TestFromHTTPStatus(t *testing.T) {
	t.Parallel()
	tests := []struct {
		status int
		want   ErrorKind
	}{
		{http.StatusBadRequest, ErrorInvalidRequest},
		{http.StatusUnauthorized, ErrorAuthentication},
		{http.StatusForbidden, ErrorPermission},
		{http.StatusTooManyRequests, ErrorRateLimited},
		{http.StatusBadGateway, ErrorUnavailable},
		{http.StatusGatewayTimeout, ErrorTimeout},
	}
	for _, test := range tests {
		got := FromHTTPStatus("test", "step", test.status, "", "")
		if got.Kind != test.want {
			t.Errorf("status %d: got %q, want %q", test.status, got.Kind, test.want)
		}
	}
}

func TestFromHTTPStatusRetryAfter(t *testing.T) {
	t.Parallel()
	got := FromHTTPStatus("test", "step", http.StatusTooManyRequests, "12", "slow down")
	if got.RetryAfter != 12*time.Second {
		t.Fatalf("got retry-after %s", got.RetryAfter)
	}
}

func TestFromContext(t *testing.T) {
	t.Parallel()
	var got *Error
	if !errors.As(FromContext("test", "step", context.Canceled), &got) {
		t.Fatal("expected normalized provider error")
	}
	if got.Kind != ErrorCancelled {
		t.Fatalf("got kind %q", got.Kind)
	}
}
