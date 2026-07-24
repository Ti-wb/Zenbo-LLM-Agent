package providerbridge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/config"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

type fakeLLM struct {
	request provider.StepRequest
}

type fakeThreadDeleter struct {
	threadID string
}

type fakeThreadProvider struct {
	request provider.StepRequest
}

func (client *fakeThreadProvider) EnsureThread(
	_ context.Context,
	request provider.StepRequest,
) (string, error) {
	client.request = request
	return "thread-ensured", nil
}

func (*fakeThreadProvider) ListThreads(
	_ context.Context,
	cursor string,
	limit int,
) (provider.ThreadPage, error) {
	return provider.ThreadPage{
		Threads: []provider.ThreadInfo{{
			ID: "thread-listed", CreatedAt: time.Unix(1000, 0).UTC(),
		}},
		NextCursor: cursor + "-next",
	}, nil
}

func (deleter *fakeThreadDeleter) DeleteThread(_ context.Context, threadID string) error {
	deleter.threadID = threadID
	return nil
}

func (client *fakeLLM) Step(_ context.Context, request provider.StepRequest) (provider.StepResponse, error) {
	client.request = request
	return provider.StepResponse{
		ThreadID: "thread",
		TurnID:   "remote-turn",
		Text:     "hello",
		ToolCalls: []provider.ToolCall{{
			ID: "opaque", Name: "show_emotion", Arguments: json.RawMessage(`{"emotion":"happy"}`),
		}},
	}, nil
}

func TestLLMAdapterConvertsProviderTypes(t *testing.T) {
	client := &fakeLLM{}
	result, err := (llmAdapter{client: client}).Step(context.Background(), application.StepRequest{
		SessionID: "session",
		Messages: []application.Message{{
			Role: "user", Content: "hi",
		}},
		Tools: []application.ToolDefinition{{
			Name: "show_emotion", InputSchema: json.RawMessage(`{"type":"object"}`),
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ResponseID != "remote-turn" || result.ToolCalls[0].ID != "opaque" ||
		client.request.Tools[0].Strict != true {
		t.Fatalf("unexpected conversion: %#v / %#v", result, client.request)
	}
}

func TestThreadDeleterAdapter(t *testing.T) {
	client := &fakeThreadDeleter{}
	if err := (threadDeleterAdapter{client: client}).DeleteThread(
		context.Background(), "thread-1",
	); err != nil {
		t.Fatal(err)
	}
	if client.threadID != "thread-1" {
		t.Fatalf("unexpected deleted thread %q", client.threadID)
	}
}

func TestThreadEnsureAndListAdapters(t *testing.T) {
	client := &fakeThreadProvider{}
	threadID, err := (threadEnsurerAdapter{client: client}).EnsureThread(
		context.Background(),
		application.StepRequest{
			SessionID: "session",
			Messages: []application.Message{{
				Role: "user", Content: "hello",
			}},
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if threadID != "thread-ensured" || client.request.SessionID != "session" ||
		client.request.Messages[0].Content != "hello" {
		t.Fatalf("unexpected ensure conversion: %q %#v", threadID, client.request)
	}
	page, err := (threadListerAdapter{client: client}).ListThreads(
		context.Background(), "cursor", 10,
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Threads) != 1 || page.Threads[0].ID != "thread-listed" ||
		page.NextCursor != "cursor-next" {
		t.Fatalf("unexpected list conversion: %#v", page)
	}
}

func TestRegistryReadinessProbesModels(t *testing.T) {
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		requests++
		if request.URL.Path != "/v1/models" || request.Header.Get("Authorization") != "Bearer secret" {
			t.Errorf("unexpected request: %s %q", request.URL.Path, request.Header.Get("Authorization"))
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"data":[
			{"id":"model"},{"id":"stt"},{"id":"tts"}
		]}`))
	}))
	defer server.Close()

	raw := strings.ReplaceAll(`{
	  "profiles":[{
	    "id":"default","displayName":"Lite","languages":["zh-TW"],"isDefault":true,
	    "kind":"openai-compatible",
	    "llm":{"baseUrl":"BASE//v1/","apiKeyEnv":"KEY","mode":"responses","model":"model"},
	    "media":{"baseUrl":"BASE//v1/","apiKeyEnv":"KEY","transcriptionModel":"stt","speechModel":"tts","voice":"voice"}
	  }]
	}`, "BASE", server.URL)
	file, err := config.Decode(strings.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	registry, err := New(file, Options{
		HTTPClient: server.Client(),
		EnvironmentLookup: func(name string) (string, bool) {
			return "secret", name == "KEY"
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer registry.Close()
	if err := registry.Ready(context.Background()); err != nil {
		t.Fatal(err)
	}
	if requests != 2 {
		t.Fatalf("expected two profile component probes, got %d", requests)
	}
	if _, err := registry.ForSession(context.Background(), application.Session{ProviderProfile: "missing"}); err == nil {
		t.Fatal("expected unknown pinned profile to fail")
	}
	if _, err := registry.ForSession(context.Background(), application.Session{
		ProviderProfile: "default",
		ProviderKind:    "codex",
	}); err == nil {
		t.Fatal("expected pinned provider kind mismatch to fail")
	}
}

func TestRegistryRejectsChangedPinnedProfileRevision(t *testing.T) {
	original := revisionTestProfile()
	originalRevision, err := ProfileRevision(original, revisionTestOptions())
	if err != nil {
		t.Fatal(err)
	}
	changed := original
	changed.LLM.Model = "replacement-model"
	changedRevision, err := ProfileRevision(changed, revisionTestOptions())
	if err != nil {
		t.Fatal(err)
	}
	if changedRevision == originalRevision {
		t.Fatal("model change did not change provider revision")
	}

	registry := &Registry{profiles: map[string]profileSet{
		"default": {
			kind:     changed.Kind,
			revision: changedRevision,
			set:      application.ProviderSet{Model: changed.LLM.Model},
		},
	}}
	_, err = registry.ForSession(context.Background(), application.Session{
		ProviderKind:     original.Kind,
		ProviderProfile:  original.ID,
		ProviderRevision: originalRevision,
	})
	if err == nil || !strings.Contains(err.Error(), "configuration changed") {
		t.Fatalf("changed profile was accepted: %v", err)
	}
	_, err = registry.ForSession(context.Background(), application.Session{
		ProviderKind:    changed.Kind,
		ProviderProfile: changed.ID,
	})
	if err == nil || !strings.Contains(err.Error(), "no pinned revision") {
		t.Fatalf("legacy unpinned session was accepted: %v", err)
	}

	set, err := registry.ForSession(context.Background(), application.Session{
		ProviderKind:     changed.Kind,
		ProviderProfile:  changed.ID,
		ProviderRevision: changedRevision,
	})
	if err != nil || set.Model != changed.LLM.Model {
		t.Fatalf("current pinned profile = %#v, %v", set, err)
	}
}

func TestProfileRevisionIncludesCredentialReferenceButNotValue(t *testing.T) {
	profile := revisionTestProfile()
	first, err := ProfileRevision(profile, revisionTestOptions())
	if err != nil {
		t.Fatal(err)
	}
	profile.LLM.APIKeyEnv = "REPLACEMENT_LLM_KEY"
	second, err := ProfileRevision(profile, revisionTestOptions())
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("credential environment reference did not change provider revision")
	}
	if strings.Contains(first, "LLM_KEY") || strings.Contains(second, "REPLACEMENT_LLM_KEY") {
		t.Fatal("provider revision exposed a credential environment reference")
	}
}

func TestCodexRuntimePathChangesInvalidatePinnedRevision(t *testing.T) {
	profile := revisionTestProfile()
	profile.Kind = "codex"
	profile.LLM = config.LLM{Model: "gpt-codex"}
	originalOptions := revisionTestOptions()
	originalRevision, err := ProfileRevision(profile, originalOptions)
	if err != nil {
		t.Fatal(err)
	}
	equivalentOptions := originalOptions
	equivalentOptions.CodexHome = "/var/lib/zenbo-gateway/./codex/"
	equivalentOptions.CodexWorkingDir = "/var/empty/./codex/"
	equivalentRevision, err := ProfileRevision(profile, equivalentOptions)
	if err != nil {
		t.Fatal(err)
	}
	if equivalentRevision != originalRevision {
		t.Fatal("equivalent normalized Codex paths changed provider revision")
	}

	tests := []struct {
		name   string
		change func(*RevisionOptions)
	}{
		{
			name: "home",
			change: func(options *RevisionOptions) {
				options.CodexHome = "/var/lib/zenbo-gateway/other-codex-home"
			},
		},
		{
			name: "working directory",
			change: func(options *RevisionOptions) {
				options.CodexWorkingDir = "/var/empty/other-codex-cwd"
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			changedOptions := originalOptions
			test.change(&changedOptions)
			changedRevision, err := ProfileRevision(profile, changedOptions)
			if err != nil {
				t.Fatal(err)
			}
			if changedRevision == originalRevision {
				t.Fatal("Codex runtime path change did not change provider revision")
			}
			registry := &Registry{profiles: map[string]profileSet{
				profile.ID: {
					kind:     profile.Kind,
					revision: changedRevision,
					set:      application.ProviderSet{Model: profile.LLM.Model},
				},
			}}
			_, err = registry.ForSession(context.Background(), application.Session{
				ProviderKind:     profile.Kind,
				ProviderProfile:  profile.ID,
				ProviderRevision: originalRevision,
			})
			if err == nil || !strings.Contains(err.Error(), "configuration changed") {
				t.Fatalf("changed Codex runtime path was accepted: %v", err)
			}
		})
	}
}

func revisionTestOptions() RevisionOptions {
	return RevisionOptions{
		ProviderTimeout: 90 * time.Second,
		CodexHome:       "/var/lib/zenbo-gateway/codex",
		CodexWorkingDir: "/var/empty/codex",
	}
}

func revisionTestProfile() config.Profile {
	return config.Profile{
		ID:          "default",
		DisplayName: "Default",
		Languages:   []string{"zh-TW"},
		IsDefault:   true,
		Kind:        "openai-compatible",
		LLM: config.LLM{
			BaseURL:   "https://llm.example.test",
			APIKeyEnv: "LLM_KEY",
			Mode:      "responses",
			Model:     "model",
		},
		Media: config.Media{
			BaseURL:            "https://media.example.test",
			APIKeyEnv:          "MEDIA_KEY",
			TranscriptionModel: "stt",
			SpeechModel:        "tts",
			Voice:              "voice",
		},
	}
}

func TestReadinessDoesNotFollowCredentialedRedirect(t *testing.T) {
	targetCalls := 0
	target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		targetCalls++
	}))
	defer target.Close()
	source := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		http.Redirect(response, request, target.URL, http.StatusTemporaryRedirect)
	}))
	defer source.Close()

	check := endpointReadiness(
		"redirect",
		source.URL+"/v1",
		"secret",
		[]string{"model"},
		newReadinessHTTPClient(source.Client()),
	)
	if err := check.check(context.Background()); err == nil {
		t.Fatal("expected redirect readiness to fail")
	}
	if targetCalls != 0 {
		t.Fatalf("readiness followed a credentialed redirect %d times", targetCalls)
	}
}

func TestReadinessRequiresBoundedWellFormedConfiguredModels(t *testing.T) {
	tests := []struct {
		name string
		body string
	}{
		{name: "missing", body: `{"data":[{"id":"other"}]}`},
		{name: "malformed", body: `{"data":[{"id":3}]}`},
		{name: "missing data", body: `{}`},
		{name: "trailing", body: `{"data":[{"id":"model"}]} {}`},
		{name: "oversized", body: `{"data":[],"padding":"` + strings.Repeat("x", maxModelsResponseBytes) + `"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
				response.Header().Set("Content-Type", "application/json")
				_, _ = response.Write([]byte(test.body))
			}))
			defer server.Close()
			check := endpointReadiness(
				"models",
				server.URL+"/v1",
				"",
				[]string{"model"},
				newReadinessHTTPClient(server.Client()),
			)
			if err := check.check(context.Background()); err == nil {
				t.Fatal("expected readiness to fail")
			}
		})
	}

	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		_, _ = response.Write([]byte(`{"data":[{"id":"llm"},{"id":"stt"},{"id":"tts"}]}`))
	}))
	defer server.Close()
	check := endpointReadiness(
		"models",
		server.URL+"/v1",
		"",
		[]string{"llm", "stt", "tts"},
		newReadinessHTTPClient(server.Client()),
	)
	if err := check.check(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestThreadDeleterResolvesSharedCodexIdentityByKind(t *testing.T) {
	deleter := &fakeThreadDeleter{}
	registry := &Registry{
		threadDeleters: map[string]application.ThreadDeleter{"codex": deleter},
		threadListers: map[string]application.ThreadLister{
			"codex": threadListerAdapter{client: &fakeThreadProvider{}},
		},
	}
	resolved, err := registry.ThreadDeleterForKind(context.Background(), "codex")
	if err != nil {
		t.Fatal(err)
	}
	if err := resolved.DeleteThread(context.Background(), "thread-retained"); err != nil {
		t.Fatal(err)
	}
	if deleter.threadID != "thread-retained" {
		t.Fatalf("unexpected deleted thread %q", deleter.threadID)
	}
	if _, err := registry.ThreadDeleterForKind(context.Background(), "openai-compatible"); err == nil {
		t.Fatal("expected unsupported cleanup kind to fail")
	}
	if lister, err := registry.ThreadListerForKind(context.Background(), "codex"); err != nil || lister == nil {
		t.Fatalf("shared Codex lister = %#v, %v", lister, err)
	}
	if lister, err := registry.ThreadListerForKind(
		context.Background(), "openai-compatible",
	); err != nil || lister != nil {
		t.Fatalf("openai-only lister = %#v, %v", lister, err)
	}
}

func TestCodexPathPolicy(t *testing.T) {
	if err := VerifyCodexBinary(context.Background(), "codex"); err == nil {
		t.Fatal("relative Codex binary path must be rejected")
	}
	empty := filepath.Join(t.TempDir(), "empty")
	if err := os.Mkdir(empty, 0o550); err != nil {
		t.Fatal(err)
	}
	if err := VerifyCodexWorkingDirectory(empty); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(empty, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := VerifyCodexWorkingDirectory(empty); err == nil {
		t.Fatal("writable Codex cwd must be rejected")
	}

	home := filepath.Join(t.TempDir(), "home")
	if err := os.Mkdir(home, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := VerifyCodexHome(home); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(home, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := VerifyCodexHome(home); err == nil {
		t.Fatal("group-accessible CODEX_HOME must be rejected")
	}
	if err := os.Chmod(home, 0o500); err != nil {
		t.Fatal(err)
	}
	if err := VerifyCodexHome(home); err == nil {
		t.Fatal("non-writable CODEX_HOME must be rejected")
	}
}
