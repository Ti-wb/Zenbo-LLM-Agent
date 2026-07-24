SQLC_VERSION := v1.31.1
SQLC := go run github.com/sqlc-dev/sqlc/cmd/sqlc@$(SQLC_VERSION)

.PHONY: build test race fuzz config-check compose-config compose-up migrate sqlc-generate sqlc-check

build:
	go build ./cmd/zenbo-gateway

test:
	go test ./...

race:
	go test -race ./...

fuzz:
	go test ./internal/config -run '^$$' -fuzz FuzzDecodeProfiles -fuzztime 30s

config-check:
	go run ./cmd/zenbo-gateway config check

compose-config:
	docker compose config --quiet

compose-up:
	docker compose up --build --detach

migrate:
	go run ./cmd/zenbo-gateway migrate

sqlc-generate:
	$(SQLC) generate

sqlc-check:
	$(SQLC) diff
