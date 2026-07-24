package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

const maximumTokenBytes = 4096

type Authenticator interface {
	// Authenticate validates a device-scoped opaque token and atomically binds
	// an unbound token to deviceID. A different existing binding must return an
	// ErrorUnauthenticated domain error.
	Authenticate(context.Context, string, string) (domain.Principal, error)
}

type FailureHandler func(http.ResponseWriter, *http.Request, error)

type Middleware struct {
	Authenticator Authenticator
	OnFailure     FailureHandler
}

type contextKey uint8

const (
	principalKey contextKey = iota
	requestIDKey
)

func (m Middleware) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		requestID := newUUID()
		request = request.WithContext(context.WithValue(request.Context(), requestIDKey, requestID))

		token, err := bearerToken(request.Header)
		if err != nil {
			m.fail(response, request, domain.NewError(
				domain.ErrorUnauthenticated,
				"UNAUTHORIZED",
				"Missing or invalid device credential",
			))
			return
		}
		deviceValues := request.Header.Values("X-Zenbo-Device-Id")
		if len(deviceValues) != 1 || !contract.IsDeviceID(deviceValues[0]) {
			m.fail(response, request, domain.NewError(
				domain.ErrorUnauthenticated,
				"UNAUTHORIZED",
				"Missing or invalid device binding",
			))
			return
		}
		deviceID := deviceValues[0]
		protocolValues := request.Header.Values("X-Zenbo-Protocol")
		if len(protocolValues) != 1 || protocolValues[0] != domain.ProtocolVersion {
			m.fail(response, request, domain.NewError(
				domain.ErrorProtocol,
				"PROTOCOL_MISMATCH",
				"X-Zenbo-Protocol must be 1.0",
			))
			return
		}
		if m.Authenticator == nil {
			m.fail(response, request, domain.NewError(
				domain.ErrorInternal,
				"AUTH_NOT_CONFIGURED",
				"Device authentication is unavailable",
			))
			return
		}
		principal, err := m.Authenticator.Authenticate(request.Context(), token, deviceID)
		if err != nil {
			m.fail(response, request, err)
			return
		}
		if principal.DeviceID != deviceID {
			m.fail(response, request, domain.NewError(
				domain.ErrorUnauthenticated,
				"UNAUTHORIZED",
				"Device credential binding does not match",
			))
			return
		}
		ctx := context.WithValue(request.Context(), principalKey, principal)
		next.ServeHTTP(response, request.WithContext(ctx))
	})
}

func PrincipalFromContext(ctx context.Context) (domain.Principal, bool) {
	principal, ok := ctx.Value(principalKey).(domain.Principal)
	return principal, ok
}

func RequestIDFromContext(ctx context.Context) string {
	requestID, _ := ctx.Value(requestIDKey).(string)
	return requestID
}

func (m Middleware) fail(response http.ResponseWriter, request *http.Request, err error) {
	if m.OnFailure != nil {
		m.OnFailure(response, request, err)
		return
	}
	http.Error(response, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
}

func bearerToken(header http.Header) (string, error) {
	values := header.Values("Authorization")
	if len(values) != 1 {
		return "", fmt.Errorf("authorization header must occur exactly once")
	}
	value := values[0]
	if len(value) < 8 || !strings.EqualFold(value[:7], "Bearer ") {
		return "", fmt.Errorf("authorization scheme must be bearer")
	}
	token := value[7:]
	if token == "" || len(token) > maximumTokenBytes || strings.TrimSpace(token) != token {
		return "", fmt.Errorf("bearer token is invalid")
	}
	for _, character := range token {
		if character <= ' ' || character == 0x7f {
			return "", fmt.Errorf("bearer token contains invalid whitespace")
		}
	}
	return token, nil
}

var fallbackCounter atomic.Uint64

func newUUID() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		counter := fallbackCounter.Add(1)
		var seed [16]byte
		binary.BigEndian.PutUint64(seed[0:8], uint64(time.Now().UnixNano()))
		binary.BigEndian.PutUint64(seed[8:16], counter)
		digest := sha256.Sum256(seed[:])
		copy(value[:], digest[:16])
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	return fmt.Sprintf(
		"%08x-%04x-%04x-%04x-%012x",
		value[0:4],
		value[4:6],
		value[6:8],
		value[8:10],
		value[10:16],
	)
}

// NewRequestID is exported for transport control events and tests.
func NewRequestID() string {
	return newUUID()
}
