package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/auth"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

type problem struct {
	Type      string `json:"type"`
	Title     string `json:"title"`
	Status    int    `json:"status"`
	Detail    string `json:"detail,omitempty"`
	Code      string `json:"code"`
	Retryable bool   `json:"retryable"`
	RequestID string `json:"requestId"`
}

func writeJSON(response http.ResponseWriter, status int, value any) {
	body, err := json.Marshal(value)
	if err != nil {
		writeRawInternalError(response)
		return
	}
	response.Header().Set("Content-Type", "application/json; charset=utf-8")
	response.Header().Set("Content-Length", strconv.Itoa(len(body)))
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(status)
	_, _ = response.Write(body)
}

func writeEmpty(response http.ResponseWriter, status int) {
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(status)
}

func writeProblem(response http.ResponseWriter, request *http.Request, err error) {
	domainError := domain.AsError(err)
	status := statusForKind(domainError.Kind)
	for name, value := range domainError.Headers {
		response.Header().Set(name, value)
	}
	body := problem{
		Type:      "about:blank",
		Title:     http.StatusText(status),
		Status:    status,
		Detail:    domainError.Detail,
		Code:      domainError.Code,
		Retryable: domainError.Retryable,
		RequestID: auth.RequestIDFromContext(request.Context()),
	}
	if body.RequestID == "" {
		body.RequestID = auth.NewRequestID()
	}
	bytes, marshalError := json.Marshal(body)
	if marshalError != nil {
		writeRawInternalError(response)
		return
	}
	response.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	response.Header().Set("Content-Length", strconv.Itoa(len(bytes)))
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(status)
	_, _ = response.Write(bytes)
}

func statusForKind(kind domain.ErrorKind) int {
	switch kind {
	case domain.ErrorInvalidArgument:
		return http.StatusBadRequest
	case domain.ErrorUnauthenticated:
		return http.StatusUnauthorized
	case domain.ErrorNotFound:
		return http.StatusNotFound
	case domain.ErrorMethodNotAllowed:
		return http.StatusMethodNotAllowed
	case domain.ErrorConflict:
		return http.StatusConflict
	case domain.ErrorGone:
		return http.StatusGone
	case domain.ErrorPayloadTooLarge:
		return http.StatusRequestEntityTooLarge
	case domain.ErrorUnsupported:
		return http.StatusUnsupportedMediaType
	case domain.ErrorUnprocessable:
		return http.StatusUnprocessableEntity
	case domain.ErrorProtocol:
		return http.StatusUpgradeRequired
	case domain.ErrorRateLimited:
		return http.StatusTooManyRequests
	default:
		return http.StatusInternalServerError
	}
}

func writeRawInternalError(response http.ResponseWriter) {
	const body = `{"type":"about:blank","title":"Internal Server Error","status":500,"code":"INTERNAL_ERROR","retryable":false,"requestId":"00000000-0000-4000-8000-000000000000"}`
	response.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	response.Header().Set("Content-Length", strconv.Itoa(len(body)))
	response.Header().Set("Cache-Control", "no-store")
	response.WriteHeader(http.StatusInternalServerError)
	_, _ = response.Write([]byte(body))
}
