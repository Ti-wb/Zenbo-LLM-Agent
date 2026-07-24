// Package store contains the PostgreSQL durable repository. PostgreSQL is the
// source of truth for state; processes may restart without reconstructing
// session or turn state from memory.
package store

import (
	"context"
	"errors"
	"fmt"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/store/sqlcgen"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Postgres struct {
	pool    *pgxpool.Pool
	queries sqlcgen.Querier
	events  *eventNotifier
}

func Open(ctx context.Context, databaseURL string) (*Postgres, error) {
	config, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, errors.New("parse postgres configuration")
	}
	config.ConnConfig.RuntimeParams["timezone"] = "UTC"
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, errors.New("initialize postgres connection pool")
	}
	repository := New(pool)
	if err := repository.Ping(ctx); err != nil {
		repository.Close()
		return nil, errors.New("connect to postgres")
	}
	return repository, nil
}

func New(pool *pgxpool.Pool) *Postgres {
	return &Postgres{
		pool:    pool,
		queries: sqlcgen.New(pool),
		events:  newEventNotifier(pool),
	}
}

func (store *Postgres) Ping(ctx context.Context) error {
	if store == nil || store.queries == nil {
		return errors.New("postgres health query is not configured")
	}
	result, err := store.queries.Health(ctx)
	if err != nil {
		return err
	}
	if result != 1 {
		return fmt.Errorf("postgres health query returned %d", result)
	}
	return nil
}

func (store *Postgres) Close() {
	if store == nil {
		return
	}
	if store.events != nil {
		store.events.Close()
	}
	if store.pool != nil {
		store.pool.Close()
	}
}

type rowScanner interface {
	Scan(...any) error
}

func inTransaction[T any](ctx context.Context, pool *pgxpool.Pool, options pgx.TxOptions, action func(pgx.Tx) (T, error)) (T, error) {
	var zero T
	tx, err := pool.BeginTx(ctx, options)
	if err != nil {
		return zero, fmt.Errorf("begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(context.Background()) }()
	value, err := action(tx)
	if err != nil {
		return zero, err
	}
	if err := tx.Commit(ctx); err != nil {
		return zero, mapDatabaseError(err)
	}
	return value, nil
}

func mapDatabaseError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return application.ErrNotFound
	}
	var postgresError *pgconn.PgError
	if errors.As(err, &postgresError) {
		switch postgresError.Code {
		case "23505", "23P01":
			return fmt.Errorf("%w: %s", application.ErrConflict, postgresError.ConstraintName)
		case "23503":
			return fmt.Errorf("%w: related resource does not exist", application.ErrNotFound)
		case "23514", "22P02":
			return fmt.Errorf("%w: database constraint rejected value", application.ErrInvalidTransition)
		case "40001", "40P01":
			return fmt.Errorf("retryable postgres transaction: %w", err)
		}
	}
	return err
}

func lockKey(ctx context.Context, tx pgx.Tx, key string) error {
	_, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, key)
	if err != nil {
		return fmt.Errorf("acquire advisory lock: %w", err)
	}
	return nil
}

var _ application.Repository = (*Postgres)(nil)
