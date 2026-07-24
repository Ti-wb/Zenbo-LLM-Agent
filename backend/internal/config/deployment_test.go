package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDockerCodexReleasePinsCannotBeOverridden(t *testing.T) {
	backendRoot := filepath.Join("..", "..")
	dockerfile := readDeploymentFile(t, filepath.Join(backendRoot, "Dockerfile"))
	for _, required := range []string{
		"ENV CODEX_VERSION=0.145.0",
		`codex_asset="codex-x86_64-unknown-linux-musl.tar.gz"`,
		`codex_asset="codex-aarch64-unknown-linux-musl.tar.gz"`,
		`codex_sha256="bfaf13c9ba34f2ad764e4a916c49cf7177aeba329cf0f719e2227566fc8d662a"`,
		`codex_sha256="d384f90bc842450b42bd675feef06a12a46a3b1ca97efcb22566b270e4a11227"`,
		`sha256sum --check --strict`,
		`test "$(/out/codex --version)" = "codex-cli ${CODEX_VERSION}"`,
	} {
		if !strings.Contains(dockerfile, required) {
			t.Errorf("Dockerfile is missing release pin %q", required)
		}
	}
	for _, forbidden := range []string{
		"ARG CODEX_VERSION",
		"ARG CODEX_" + "SHA256",
		"unknown-linux-" + "gnu",
		"${CODEX_" + "SHA256}",
	} {
		if strings.Contains(dockerfile, forbidden) {
			t.Errorf("Dockerfile still permits or references %q", forbidden)
		}
	}

	for _, name := range []string{"compose.yaml", ".env.example"} {
		content := readDeploymentFile(t, filepath.Join(backendRoot, name))
		if strings.Contains(content, "CODEX_"+"SHA256") ||
			strings.Contains(content, "CODEX_VERSION") {
			t.Errorf("%s must not expose Codex release pin overrides", name)
		}
	}
}

func readDeploymentFile(t *testing.T, path string) string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(content)
}
