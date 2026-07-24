package worker

import (
	"context"
	"errors"
	"fmt"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

var errTurnStopped = errors.New("worker: turn stopped")

type processError struct {
	Code      string
	Message   string
	Retryable bool
	Cause     error
}

func (err *processError) Error() string {
	if err == nil {
		return "<nil>"
	}
	if err.Cause != nil {
		return fmt.Sprintf("%s: %v", err.Message, err.Cause)
	}
	return err.Message
}

func (err *processError) Unwrap() error { return err.Cause }

func permanent(code, message string, cause error) error {
	return &processError{Code: code, Message: message, Cause: cause}
}

func retryable(code, message string, cause error) error {
	return &processError{Code: code, Message: message, Retryable: true, Cause: cause}
}

func errConfiguration(message string) error {
	return permanent("WORKER_CONFIGURATION", message, nil)
}

func normalizeProcessError(err error) *processError {
	if err == nil {
		return nil
	}
	var existing *processError
	if errors.As(err, &existing) {
		return existing
	}
	var providerError *provider.Error
	if errors.As(err, &providerError) {
		switch providerError.Kind {
		case provider.ErrorRateLimited:
			return &processError{
				Code:      "PROVIDER_RATE_LIMITED",
				Message:   "The model provider is temporarily rate limited",
				Retryable: true,
				Cause:     err,
			}
		case provider.ErrorUnavailable:
			return &processError{
				Code:      "PROVIDER_UNAVAILABLE",
				Message:   "The model provider is temporarily unavailable",
				Retryable: true,
				Cause:     err,
			}
		case provider.ErrorTimeout:
			return &processError{
				Code:      "PROVIDER_TIMEOUT",
				Message:   "The model provider request timed out",
				Retryable: true,
				Cause:     err,
			}
		case provider.ErrorCancelled:
			return &processError{
				Code:    "PROVIDER_CANCELLED",
				Message: "The model provider request was cancelled",
				Cause:   err,
			}
		case provider.ErrorAuthentication:
			return &processError{
				Code:    "PROVIDER_AUTHENTICATION",
				Message: "The model provider credential is invalid",
				Cause:   err,
			}
		case provider.ErrorPermission:
			return &processError{
				Code:    "PROVIDER_PERMISSION",
				Message: "The model provider denied this request",
				Cause:   err,
			}
		case provider.ErrorInvalidRequest:
			return &processError{
				Code:    "PROVIDER_INVALID_REQUEST",
				Message: "The model provider rejected the gateway request",
				Cause:   err,
			}
		case provider.ErrorMalformed:
			return &processError{
				Code:    "PROVIDER_MALFORMED_RESPONSE",
				Message: "The model provider returned an invalid response",
				Cause:   err,
			}
		default:
			return &processError{
				Code:      "PROVIDER_INTERNAL",
				Message:   "The model provider request failed",
				Retryable: true,
				Cause:     err,
			}
		}
	}
	switch {
	case errors.Is(err, errTurnStopped),
		errors.Is(err, application.ErrTurnTerminal),
		errors.Is(err, application.ErrSessionNotActive):
		return &processError{Code: "TURN_STOPPED", Message: "The turn is no longer active", Cause: err}
	case errors.Is(err, context.Canceled):
		return &processError{Code: "WORKER_CANCELLED", Message: "Worker operation was cancelled", Cause: err}
	case errors.Is(err, context.DeadlineExceeded):
		return &processError{
			Code:      "WORKER_TIMEOUT",
			Message:   "Worker operation timed out",
			Retryable: true,
			Cause:     err,
		}
	case errors.Is(err, blob.ErrNotFound):
		return &processError{Code: "AUDIO_NOT_FOUND", Message: "Required audio was not found", Cause: err}
	default:
		return &processError{
			Code:      "GATEWAY_STORAGE",
			Message:   "The gateway could not persist turn progress",
			Retryable: true,
			Cause:     err,
		}
	}
}
