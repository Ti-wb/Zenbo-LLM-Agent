// Package migrations exposes the SQL migration set as immutable binary
// assets, so production images do not depend on a writable source checkout.
package migrations

import "embed"

// FS contains every numbered Goose migration in this directory.
//
//go:embed *.sql
var FS embed.FS
