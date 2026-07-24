package migrations

import (
	"context"
	"database/sql"
	"strings"
	"testing"
)

func TestRunDoesNotExposeDatabaseCredential(t *testing.T) {
	const secret = "gateway-migration-password"
	err := run(
		context.Background(),
		"postgres://gateway:"+secret+"@%zz/database",
		func(database *sql.DB) error {
			return database.Close()
		},
	)
	if err == nil {
		t.Fatal("run unexpectedly succeeded")
	}
	if strings.Contains(err.Error(), secret) {
		t.Fatalf("migration error exposed database credential: %q", err)
	}
}
