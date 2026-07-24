package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

type testAuthenticator struct {
	calls int
}

func (a *testAuthenticator) Authenticate(_ context.Context, token, deviceID string) (domain.Principal, error) {
	a.calls++
	if token != "secret" {
		return domain.Principal{}, domain.NewError(domain.ErrorUnauthenticated, "UNAUTHORIZED", "invalid")
	}
	return domain.Principal{TokenID: "row", DeviceID: deviceID}, nil
}

func TestMiddlewareRejectsDuplicateSecurityHeaders(t *testing.T) {
	authenticator := &testAuthenticator{}
	failures := 0
	handler := Middleware{
		Authenticator: authenticator,
		OnFailure: func(response http.ResponseWriter, _ *http.Request, _ error) {
			failures++
			response.WriteHeader(http.StatusUnauthorized)
		},
	}.Wrap(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Fatal("request reached handler")
	}))
	request := httptest.NewRequest(http.MethodGet, "/agent/v1/capabilities", nil)
	request.Header.Add("Authorization", "Bearer secret")
	request.Header.Add("X-Zenbo-Device-Id", "device-one")
	request.Header.Add("X-Zenbo-Device-Id", "device-two")
	request.Header.Add("X-Zenbo-Protocol", "1.0")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized || failures != 1 || authenticator.calls != 0 {
		t.Fatalf("status=%d failures=%d auth calls=%d", response.Code, failures, authenticator.calls)
	}
}

func TestMiddlewareAuthenticatesAndPublishesPrincipal(t *testing.T) {
	authenticator := &testAuthenticator{}
	handler := Middleware{
		Authenticator: authenticator,
		OnFailure: func(http.ResponseWriter, *http.Request, error) {
			t.Fatal("unexpected failure")
		},
	}.Wrap(http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		principal, ok := PrincipalFromContext(request.Context())
		if !ok || principal.DeviceID != "device-one" || RequestIDFromContext(request.Context()) == "" {
			t.Fatalf("principal=%#v ok=%t requestID=%q", principal, ok, RequestIDFromContext(request.Context()))
		}
	}))
	request := httptest.NewRequest(http.MethodGet, "/agent/v1/capabilities", nil)
	request.Header.Set("Authorization", "Bearer secret")
	request.Header.Set("X-Zenbo-Device-Id", "device-one")
	request.Header.Set("X-Zenbo-Protocol", "1.0")
	handler.ServeHTTP(httptest.NewRecorder(), request)
	if authenticator.calls != 1 {
		t.Fatalf("auth calls=%d", authenticator.calls)
	}
}

func TestMiddlewareRejectsProtocolBeforeBindingCredential(t *testing.T) {
	authenticator := &testAuthenticator{}
	handler := Middleware{
		Authenticator: authenticator,
		OnFailure: func(response http.ResponseWriter, _ *http.Request, _ error) {
			response.WriteHeader(http.StatusUpgradeRequired)
		},
	}.Wrap(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Fatal("request reached handler")
	}))
	request := httptest.NewRequest(http.MethodGet, "/agent/v1/capabilities", nil)
	request.Header.Set("Authorization", "Bearer secret")
	request.Header.Set("X-Zenbo-Device-Id", "device-one")
	request.Header.Set("X-Zenbo-Protocol", "0.9")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusUpgradeRequired || authenticator.calls != 0 {
		t.Fatalf("status=%d auth calls=%d", response.Code, authenticator.calls)
	}
}
