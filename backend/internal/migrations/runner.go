// Package migrations runs the gateway's embedded PostgreSQL migrations.
package migrations

import (
	"context"
	"database/sql"
	"fmt"

	migrationassets "github.com/Ti-wb/Zenbo-LLM-Agent/backend/migrations"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
)

func Up(ctx context.Context, databaseURL string) error {
	return run(ctx, databaseURL, func(database *sql.DB) error {
		return goose.UpContext(ctx, database, ".")
	})
}

func Status(ctx context.Context, databaseURL string) error {
	return run(ctx, databaseURL, func(database *sql.DB) error {
		return goose.StatusContext(ctx, database, ".")
	})
}

func run(ctx context.Context, databaseURL string, action func(*sql.DB) error) error {
	database, err := sql.Open("pgx", databaseURL)
	if err != nil {
		return fmt.Errorf("open migration database")
	}
	defer database.Close()
	if err := database.PingContext(ctx); err != nil {
		return fmt.Errorf("connect to migration database")
	}
	goose.SetBaseFS(migrationassets.FS)
	if err := goose.SetDialect("postgres"); err != nil {
		return fmt.Errorf("configure migration dialect: %w", err)
	}
	if err := action(database); err != nil {
		return fmt.Errorf("run migrations")
	}
	return nil
}
