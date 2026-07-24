package config

import (
	"strings"
	"testing"
	"time"
)

const validProfiles = `{
  "profiles": [
    {
      "id": "default",
      "displayName": "Codex",
      "languages": ["zh-TW", "en"],
      "isDefault": true,
      "kind": "codex",
      "llm": {"model": "gpt-5.6-terra"},
      "media": {
        "baseUrl": "https://litellm.example/v1",
        "apiKeyEnv": "LITELLM_KEY",
        "transcriptionModel": "whisper-1",
        "speechModel": "tts-1",
        "voice": "alloy"
      }
    },
    {
      "id": "lite",
      "displayName": "LiteLLM",
      "languages": ["zh-TW"],
      "isDefault": false,
      "kind": "openai-compatible",
      "llm": {
        "baseUrl": "https://litellm.example/v1",
        "apiKeyEnv": "LITELLM_KEY",
        "mode": "responses",
        "model": "gpt-5-mini"
      },
      "media": {
        "baseUrl": "http://media:4000/v1",
        "allowUnauthenticated": true,
        "transcriptionModel": "whisper",
        "speechModel": "piper",
        "voice": "zh-TW"
      }
    }
  ]
}`

func TestDecodeAndResolveDefault(t *testing.T) {
	file, err := Decode(strings.NewReader(validProfiles))
	if err != nil {
		t.Fatal(err)
	}
	profile, ok := file.Profile("default")
	if !ok || profile.ID != "default" || !profile.IsDefault {
		t.Fatalf("unexpected default profile: %#v", profile)
	}
	advertised := file.Advertised()
	if advertised[0].ID != "default" || len(advertised) != 2 {
		t.Fatalf("unexpected advertised profiles: %#v", advertised)
	}
}

func TestCredentialsOnlyResolveNamedEnvironment(t *testing.T) {
	file, err := Decode(strings.NewReader(validProfiles))
	if err != nil {
		t.Fatal(err)
	}
	profile, _ := file.Profile("default")
	credentials, err := profile.Credentials(func(name string) (string, bool) {
		if name != "LITELLM_KEY" {
			t.Fatalf("unexpected environment lookup %q", name)
		}
		return "secret", true
	})
	if err != nil {
		t.Fatal(err)
	}
	if credentials.LLMAPIKey != "" || credentials.MediaAPIKey != "secret" {
		t.Fatalf("unexpected credentials: %#v", credentials)
	}
}

func TestDecodeRejectsLiteralCredentialAndUnknownFields(t *testing.T) {
	literal := strings.Replace(validProfiles, `"apiKeyEnv": "LITELLM_KEY"`, `"apiKey": "secret"`, 1)
	if _, err := Decode(strings.NewReader(literal)); err == nil {
		t.Fatal("expected literal credential to be rejected")
	}
	urlCredential := strings.Replace(validProfiles, "https://litellm.example", "https://user:secret@litellm.example", 1)
	if _, err := Decode(strings.NewReader(urlCredential)); err == nil {
		t.Fatal("expected URL credential to be rejected")
	}
}

func TestRuntimeEnvironmentValidation(t *testing.T) {
	values := map[string]string{
		"GATEWAY_DATABASE_URL":    "postgres://gateway@db/gateway",
		"GATEWAY_DEVICE_HMAC_KEY": "base64:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	}
	runtime, err := FromEnvironment(func(name string) (string, bool) {
		value, ok := values[name]
		return value, ok
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := runtime.ValidateForServe(); err != nil {
		t.Fatal(err)
	}
	if runtime.WorkerPoll != time.Second || len(runtime.DeviceHMACKey) != 32 {
		t.Fatalf("unexpected runtime config: %#v", runtime)
	}
}

func TestMissingCredentialVariablesAreDeduplicated(t *testing.T) {
	file, err := Decode(strings.NewReader(validProfiles))
	if err != nil {
		t.Fatal(err)
	}
	missing := file.MissingCredentialVariables(func(string) (string, bool) { return "", false })
	if len(missing) != 1 || missing[0] != "LITELLM_KEY" {
		t.Fatalf("unexpected missing variables: %#v", missing)
	}
}

func FuzzDecodeProfiles(f *testing.F) {
	f.Add(validProfiles)
	f.Add(`{"profiles":[]}`)
	f.Fuzz(func(t *testing.T, input string) {
		// The only invariant for arbitrary untrusted config bytes is that the
		// strict decoder returns normally without panicking or allocating an
		// unbounded body (Decode applies a 1 MiB limit).
		_, _ = Decode(strings.NewReader(input))
	})
}
