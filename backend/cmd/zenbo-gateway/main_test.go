package main

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/config"
)

func TestHelpAndUnknownCommand(t *testing.T) {
	var output, errors bytes.Buffer
	runner := command{
		lookup: func(string) (string, bool) { return "", false },
		stdout: &output,
		stderr: &errors,
	}
	if status := execute(context.Background(), []string{"help"}, runner); status != 0 {
		t.Fatalf("help returned %d", status)
	}
	if !strings.Contains(output.String(), "device issue") {
		t.Fatalf("unexpected help: %s", output.String())
	}
	if status := execute(context.Background(), []string{"unknown"}, runner); status != 2 {
		t.Fatalf("unknown command returned %d", status)
	}
}

func TestConfigCheckDoesNotPrintCredentials(t *testing.T) {
	var output, errors bytes.Buffer
	values := map[string]string{
		"GATEWAY_DATABASE_URL":    "postgres://db/gateway",
		"GATEWAY_PROFILES_FILE":   "/profiles.json",
		"GATEWAY_ARTIFACT_DIR":    "/artifacts",
		"GATEWAY_DEVICE_HMAC_KEY": "01234567890123456789012345678901",
		"MEDIA_SECRET":            "do-not-print",
	}
	profiles := config.File{Profiles: []config.Profile{{
		ID: "default", DisplayName: "Lite", Languages: []string{"zh-TW"},
		IsDefault: true, Kind: "openai-compatible", LLM: config.LLM{
			BaseURL: "https://example.test/v1", APIKeyEnv: "MEDIA_SECRET",
			Mode: "responses", Model: "gpt",
		},
		Media: config.Media{
			BaseURL: "https://example.test/v1", APIKeyEnv: "MEDIA_SECRET",
			TranscriptionModel: "stt", SpeechModel: "tts", Voice: "voice",
		},
	}}}
	status := execute(context.Background(), []string{"config", "check"}, command{
		lookup: func(name string) (string, bool) {
			value, ok := values[name]
			return value, ok
		},
		stdout: &output,
		stderr: &errors,
		profiles: func(string) (config.File, error) {
			return profiles, nil
		},
	})
	if status != 0 {
		t.Fatalf("config check returned %d: %s", status, errors.String())
	}
	if strings.Contains(output.String(), "do-not-print") || !strings.Contains(output.String(), `"status":"ok"`) {
		t.Fatalf("unsafe config output: %s", output.String())
	}
}
