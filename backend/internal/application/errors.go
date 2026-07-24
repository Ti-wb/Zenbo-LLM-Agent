package application

import "errors"

var (
	ErrNotFound            = errors.New("application: not found")
	ErrUnauthorized        = errors.New("application: unauthorized")
	ErrConflict            = errors.New("application: conflict")
	ErrIdempotencyConflict = errors.New("application: idempotency key reused with different request")
	ErrSessionNotActive    = errors.New("application: session is not active")
	ErrTurnTerminal        = errors.New("application: turn is terminal")
	ErrToolTerminal        = errors.New("application: tool call is terminal")
	ErrInvalidTransition   = errors.New("application: invalid state transition")
	ErrLeaseLost           = errors.New("application: job lease lost")
	ErrArtifactGone        = errors.New("application: artifact expired or deleted")
	ErrReplayStale         = errors.New("application: retained event history has a sequence gap")
)
