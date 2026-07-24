package domain

import (
	"errors"
	"fmt"
)

type ErrorKind string

const (
	ErrorInvalidArgument  ErrorKind = "invalid_argument"
	ErrorUnauthenticated  ErrorKind = "unauthenticated"
	ErrorNotFound         ErrorKind = "not_found"
	ErrorMethodNotAllowed ErrorKind = "method_not_allowed"
	ErrorConflict         ErrorKind = "conflict"
	ErrorGone             ErrorKind = "gone"
	ErrorPayloadTooLarge  ErrorKind = "payload_too_large"
	ErrorUnsupported      ErrorKind = "unsupported_media_type"
	ErrorUnprocessable    ErrorKind = "unprocessable"
	ErrorProtocol         ErrorKind = "protocol_mismatch"
	ErrorRateLimited      ErrorKind = "rate_limited"
	ErrorInternal         ErrorKind = "internal"
)

type Error struct {
	Kind      ErrorKind
	Code      string
	Detail    string
	Retryable bool
	Headers   map[string]string
	Cause     error
}

func (e *Error) Error() string {
	if e == nil {
		return "<nil>"
	}
	if e.Detail != "" {
		return fmt.Sprintf("%s: %s", e.Code, e.Detail)
	}
	return e.Code
}

func (e *Error) Unwrap() error {
	return e.Cause
}

func NewError(kind ErrorKind, code, detail string) *Error {
	return &Error{Kind: kind, Code: code, Detail: detail}
}

func WrapError(kind ErrorKind, code, detail string, cause error) *Error {
	return &Error{Kind: kind, Code: code, Detail: detail, Cause: cause}
}

func AsError(err error) *Error {
	if err == nil {
		return nil
	}
	var domainError *Error
	if errors.As(err, &domainError) {
		return domainError
	}
	return WrapError(ErrorInternal, "INTERNAL_ERROR", "The gateway could not complete the request", err)
}
