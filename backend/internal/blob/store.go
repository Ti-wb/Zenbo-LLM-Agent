// Package blob provides the durable byte store used for input and synthesized
// audio. Metadata and authorization live in PostgreSQL; this package owns only
// opaque bytes addressed by an application-generated key.
package blob

import (
	"context"
	"errors"
	"io"
	"time"
)

var (
	ErrInvalidKey = errors.New("blob: invalid key")
	ErrTooLarge   = errors.New("blob: object exceeds limit")
	ErrNotFound   = errors.New("blob: object not found")
)

type Object struct {
	Key       string
	Size      int64
	SHA256    [32]byte
	CreatedAt time.Time
}

type Store interface {
	Put(context.Context, string, io.Reader, int64) (Object, error)
	Open(context.Context, string) (io.ReadCloser, Object, error)
	Delete(context.Context, string) error
}

// IncompleteSweeper is implemented by stores that can retain crash leftovers.
// Maintenance workers may discover it with a type assertion.
type IncompleteSweeper interface {
	SweepIncomplete(context.Context, time.Time, int) (int, error)
}

// FinalObjectLister exposes committed objects for orphan reconciliation.
// Callers must apply a safety age and confirm durable metadata is absent
// before deleting a returned object.
type FinalObjectLister interface {
	ListFinalObjects(context.Context, time.Time, int) ([]Object, error)
}
