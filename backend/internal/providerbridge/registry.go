// Package providerbridge adapts concrete provider clients to the application
// ports and pins a session to the concrete provider profile selected at
// session creation.
package providerbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/config"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider/codex"
	openaiadapter "github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider/openai"
)

type Options struct {
	CodexBinary       string
	CodexHome         string
	CodexWorkingDir   string
	ProviderTimeout   time.Duration
	HTTPClient        *http.Client
	EnvironmentLookup func(string) (string, bool)
}

type Registry struct {
	profiles       map[string]profileSet
	threadDeleters map[string]application.ThreadDeleter
	threadListers  map[string]application.ThreadLister
	checks         []readinessCheck
	closers        []interface{ Close() error }
	close          sync.Once
}

type profileSet struct {
	kind string
	set  application.ProviderSet
}

type readinessCheck struct {
	name  string
	check func(context.Context) error
}

func New(file config.File, options Options) (*Registry, error) {
	if options.ProviderTimeout <= 0 {
		options.ProviderTimeout = 90 * time.Second
	}
	if options.HTTPClient == nil {
		// Provider clients apply profile timeouts with request contexts. A
		// client-level timeout would incorrectly truncate 60-90s LLM/media
		// requests.
		options.HTTPClient = &http.Client{Timeout: 0}
	}
	readinessHTTP := newReadinessHTTPClient(options.HTTPClient)
	registry := &Registry{
		profiles:       make(map[string]profileSet, len(file.Profiles)),
		threadDeleters: make(map[string]application.ThreadDeleter),
		threadListers:  make(map[string]application.ThreadLister),
	}
	var sharedCodexClient *codex.Client
	for _, profile := range file.Profiles {
		if profile.Kind != "codex" {
			continue
		}
		process, err := verifiedCodexProcess(options)
		if err != nil {
			return nil, fmt.Errorf("configure shared Codex provider: %w", err)
		}
		process.QueueSize = 64
		process.MaxLineBytes = 8 << 20
		sharedCodexClient, err = codex.New(codex.Config{
			Process:          process,
			WorkingDirectory: options.CodexWorkingDir,
		})
		if err != nil {
			return nil, fmt.Errorf("configure shared Codex provider: %w", err)
		}
		registry.closers = append(registry.closers, sharedCodexClient)
		break
	}
	for _, profileConfig := range file.Profiles {
		credentials, err := profileConfig.Credentials(options.EnvironmentLookup)
		if err != nil {
			registry.Close()
			return nil, fmt.Errorf("profile %s: %w", profileConfig.ID, err)
		}
		set, checks, closer, err := buildProfile(
			profileConfig, credentials, options, readinessHTTP, sharedCodexClient,
		)
		if err != nil {
			registry.Close()
			return nil, fmt.Errorf("profile %s: %w", profileConfig.ID, err)
		}
		registry.profiles[profileConfig.ID] = profileSet{kind: profileConfig.Kind, set: set}
		if set.ThreadDeleter != nil {
			if _, exists := registry.threadDeleters[profileConfig.Kind]; !exists {
				// Codex v1 uses one shared CODEX_HOME identity. Retention
				// cleanup therefore resolves by provider kind rather than by a
				// profile ID that may have been renamed after session close.
				registry.threadDeleters[profileConfig.Kind] = set.ThreadDeleter
			}
		}
		if set.ThreadLister != nil {
			if _, exists := registry.threadListers[profileConfig.Kind]; !exists {
				registry.threadListers[profileConfig.Kind] = set.ThreadLister
			}
		}
		registry.checks = append(registry.checks, checks...)
		if closer != nil {
			registry.closers = append(registry.closers, closer)
		}
	}
	return registry, nil
}

func buildProfile(
	profileConfig config.Profile,
	credentials config.Credentials,
	options Options,
	readinessHTTP *http.Client,
	sharedCodexClient *codex.Client,
) (
	application.ProviderSet,
	[]readinessCheck,
	interface{ Close() error },
	error,
) {
	media, err := openaiadapter.New(openaiadapter.Config{
		BaseURL:            profileConfig.Media.BaseURL,
		APIKey:             credentials.MediaAPIKey,
		TranscriptionModel: profileConfig.Media.TranscriptionModel,
		SpeechModel:        profileConfig.Media.SpeechModel,
		Voice:              profileConfig.Media.Voice,
		Timeout:            profileConfig.MediaTimeout(options.ProviderTimeout),
		HTTPClient:         options.HTTPClient,
		SendSpeechLanguage: profileConfig.Media.SendSpeechLanguage,
	})
	if err != nil {
		return application.ProviderSet{}, nil, nil, fmt.Errorf("configure media provider: %w", err)
	}

	set := application.ProviderSet{
		Transcriber: transcriber{client: media},
		Synthesizer: synthesizer{client: media},
		STTModel:    profileConfig.Media.TranscriptionModel,
		TTSModel:    profileConfig.Media.SpeechModel,
		Voice:       profileConfig.Media.Voice,
		Model:       profileConfig.LLM.Model,
	}
	checks := []readinessCheck{endpointReadiness(
		profileConfig.ID+" media",
		profileConfig.Media.BaseURL,
		credentials.MediaAPIKey,
		[]string{
			profileConfig.Media.TranscriptionModel,
			profileConfig.Media.SpeechModel,
		},
		readinessHTTP,
	)}

	switch profileConfig.Kind {
	case "openai-compatible":
		llm, createErr := openaiadapter.New(openaiadapter.Config{
			BaseURL:    profileConfig.LLM.BaseURL,
			APIKey:     credentials.LLMAPIKey,
			Mode:       openaiadapter.Mode(profileConfig.LLM.Mode),
			Model:      profileConfig.LLM.Model,
			Timeout:    profileConfig.LLMTimeout(options.ProviderTimeout),
			HTTPClient: options.HTTPClient,
		})
		if createErr != nil {
			return application.ProviderSet{}, nil, nil, fmt.Errorf("configure LLM provider: %w", createErr)
		}
		set.LLM = llmAdapter{client: llm}
		checks = append(checks, endpointReadiness(
			profileConfig.ID+" llm",
			profileConfig.LLM.BaseURL,
			credentials.LLMAPIKey,
			[]string{profileConfig.LLM.Model},
			readinessHTTP,
		))
		return set, checks, nil, nil
	case "codex":
		if sharedCodexClient == nil {
			return application.ProviderSet{}, nil, nil, errors.New("shared Codex client is not configured")
		}
		set.LLM = llmAdapter{client: sharedCodexClient}
		set.ThreadEnsurer = threadEnsurerAdapter{client: sharedCodexClient}
		set.Interrupt = interruptAdapter{client: sharedCodexClient}
		set.ThreadDeleter = threadDeleterAdapter{client: sharedCodexClient}
		set.ThreadLister = threadListerAdapter{client: sharedCodexClient}
		checks = append(checks, readinessCheck{
			name: profileConfig.ID + " codex",
			check: func(ctx context.Context) error {
				return sharedCodexClient.Ready(ctx, profileConfig.LLM.Model)
			},
		})
		return set, checks, nil, nil
	default:
		return application.ProviderSet{}, nil, nil, fmt.Errorf("unsupported provider kind %q", profileConfig.Kind)
	}
}

const maxModelsResponseBytes = 1 << 20

func endpointReadiness(
	name, baseURL, apiKey string,
	requiredModels []string,
	client *http.Client,
) readinessCheck {
	return readinessCheck{
		name: name,
		check: func(ctx context.Context) error {
			parsed, err := url.Parse(baseURL)
			if err != nil {
				return err
			}
			parsed.Path = path.Join(strings.TrimSuffix(parsed.Path, "/"), "models")
			request, err := http.NewRequestWithContext(ctx, http.MethodGet, parsed.String(), nil)
			if err != nil {
				return err
			}
			if apiKey != "" {
				request.Header.Set("Authorization", "Bearer "+apiKey)
			}
			response, err := client.Do(request)
			if err != nil {
				return err
			}
			defer response.Body.Close()
			if response.StatusCode < 200 || response.StatusCode >= 300 {
				return fmt.Errorf("models probe returned HTTP %d", response.StatusCode)
			}
			raw, err := io.ReadAll(io.LimitReader(response.Body, maxModelsResponseBytes+1))
			if err != nil {
				return fmt.Errorf("read models response: %w", err)
			}
			if len(raw) > maxModelsResponseBytes {
				return errors.New("models response exceeded the size limit")
			}
			var catalog struct {
				Data []struct {
					ID string `json:"id"`
				} `json:"data"`
			}
			decoder := json.NewDecoder(bytes.NewReader(raw))
			if err := decoder.Decode(&catalog); err != nil {
				return errors.New("models response was malformed")
			}
			var trailing json.RawMessage
			if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
				return errors.New("models response contained trailing data")
			}
			if catalog.Data == nil {
				return errors.New("models response did not contain a data array")
			}
			available := make(map[string]struct{}, len(catalog.Data))
			for _, model := range catalog.Data {
				id := strings.TrimSpace(model.ID)
				if id == "" {
					return errors.New("models response contained an invalid model id")
				}
				available[id] = struct{}{}
			}
			for _, required := range requiredModels {
				if _, ok := available[required]; !ok {
					return errors.New("configured model is not advertised by the provider")
				}
			}
			return nil
		},
	}
}

func (registry *Registry) ForSession(_ context.Context, session application.Session) (application.ProviderSet, error) {
	profile, ok := registry.profiles[session.ProviderProfile]
	if !ok {
		return application.ProviderSet{}, fmt.Errorf("provider profile %q is not configured", session.ProviderProfile)
	}
	if profile.kind != session.ProviderKind {
		return application.ProviderSet{}, fmt.Errorf(
			"provider profile %q kind changed from %q to %q",
			session.ProviderProfile, session.ProviderKind, profile.kind,
		)
	}
	return profile.set, nil
}

// ThreadDeleterForKind resolves retention cleanup against the shared Codex
// identity. Live turns still use ForSession and remain strictly profile/kind
// pinned.
func (registry *Registry) ThreadDeleterForKind(
	_ context.Context,
	kind string,
) (application.ThreadDeleter, error) {
	deleter, ok := registry.threadDeleters[kind]
	if !ok {
		return nil, fmt.Errorf("provider kind %q has no configured thread cleanup client", kind)
	}
	return deleter, nil
}

func (registry *Registry) ThreadListerForKind(
	_ context.Context,
	kind string,
) (application.ThreadLister, error) {
	lister, ok := registry.threadListers[kind]
	if !ok {
		return nil, nil
	}
	return lister, nil
}

// Ready checks every advertised concrete profile. The caller should provide a
// short timeout; probes never expose credential values in returned errors.
func (registry *Registry) Ready(ctx context.Context) error {
	for _, check := range registry.checks {
		if err := check.check(ctx); err != nil {
			return fmt.Errorf("%s is not ready: %w", check.name, err)
		}
	}
	return nil
}

func (registry *Registry) Close() error {
	var result error
	registry.close.Do(func() {
		for _, closer := range registry.closers {
			result = errors.Join(result, closer.Close())
		}
	})
	return result
}

// Readiness contains no LLM/media execution clients. Codex account checks
// start a short-lived app-server and close it after each check, so API
// processes never retain a second provider worker.
type Readiness struct {
	checks []readinessCheck
}

func NewReadiness(file config.File, options Options) (*Readiness, error) {
	if options.ProviderTimeout <= 0 {
		options.ProviderTimeout = 90 * time.Second
	}
	readinessHTTP := newReadinessHTTPClient(options.HTTPClient)
	result := &Readiness{}
	for _, profile := range file.Profiles {
		credentials, err := profile.Credentials(options.EnvironmentLookup)
		if err != nil {
			return nil, fmt.Errorf("profile %s: %w", profile.ID, err)
		}
		result.checks = append(result.checks, endpointReadiness(
			profile.ID+" media",
			profile.Media.BaseURL,
			credentials.MediaAPIKey,
			[]string{profile.Media.TranscriptionModel, profile.Media.SpeechModel},
			readinessHTTP,
		))
		switch profile.Kind {
		case "openai-compatible":
			result.checks = append(result.checks, endpointReadiness(
				profile.ID+" llm",
				profile.LLM.BaseURL,
				credentials.LLMAPIKey,
				[]string{profile.LLM.Model},
				readinessHTTP,
			))
		case "codex":
			process, err := verifiedCodexProcess(options)
			if err != nil {
				return nil, fmt.Errorf("profile %s: %w", profile.ID, err)
			}
			profileCopy := profile
			result.checks = append(result.checks, readinessCheck{
				name: profile.ID + " codex",
				check: func(ctx context.Context) error {
					client, err := codex.New(codex.Config{
						Process:          process,
						Model:            profileCopy.LLM.Model,
						WorkingDirectory: options.CodexWorkingDir,
					})
					if err != nil {
						return err
					}
					defer client.Close()
					return client.Ready(ctx, profileCopy.LLM.Model)
				},
			})
		default:
			return nil, fmt.Errorf("profile %s has unsupported kind %q", profile.ID, profile.Kind)
		}
	}
	return result, nil
}

func (readiness *Readiness) Ready(ctx context.Context) error {
	for _, check := range readiness.checks {
		if err := check.check(ctx); err != nil {
			return fmt.Errorf("%s is not ready: %w", check.name, err)
		}
	}
	return nil
}

const RequiredCodexVersion = "codex-cli 0.145.0"

func VerifyCodexBinary(ctx context.Context, binary string) error {
	if strings.TrimSpace(binary) == "" {
		return errors.New("Codex binary is required")
	}
	if !filepath.IsAbs(binary) {
		return errors.New("Codex binary path must be absolute")
	}
	output, err := exec.CommandContext(ctx, binary, "--version").Output()
	if err != nil {
		return fmt.Errorf("run Codex version check: %w", err)
	}
	if strings.TrimSpace(string(output)) != RequiredCodexVersion {
		return fmt.Errorf("Codex binary version must be exactly %q", RequiredCodexVersion)
	}
	return nil
}

func VerifyCodexHome(directory string) error {
	if directory == "" || !filepath.IsAbs(directory) {
		return errors.New("CODEX_HOME must be an absolute path")
	}
	info, err := os.Stat(directory)
	if err != nil {
		return fmt.Errorf("stat CODEX_HOME: %w", err)
	}
	if !info.IsDir() {
		return errors.New("CODEX_HOME must be a directory")
	}
	permissions := info.Mode().Perm()
	if permissions&0o077 != 0 {
		return errors.New("CODEX_HOME must not be accessible by group or other users")
	}
	if permissions&0o700 != 0o700 {
		return errors.New("CODEX_HOME must be owner-readable, writable and searchable")
	}
	return nil
}

func verifiedCodexProcess(options Options) (codex.ProcessConfig, error) {
	verifyContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := VerifyCodexBinary(verifyContext, options.CodexBinary); err != nil {
		return codex.ProcessConfig{}, err
	}
	if err := VerifyCodexWorkingDirectory(options.CodexWorkingDir); err != nil {
		return codex.ProcessConfig{}, err
	}
	if err := VerifyCodexHome(options.CodexHome); err != nil {
		return codex.ProcessConfig{}, err
	}
	return codex.NewSafeProcessConfig(
		options.CodexBinary,
		options.CodexHome,
		options.CodexWorkingDir,
	), nil
}

func VerifyCodexWorkingDirectory(directory string) error {
	if directory == "" || !filepath.IsAbs(directory) {
		return errors.New("Codex working directory must be absolute")
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		return fmt.Errorf("read Codex working directory: %w", err)
	}
	if len(entries) != 0 {
		return errors.New("Codex working directory must be empty")
	}
	info, err := os.Stat(directory)
	if err != nil {
		return fmt.Errorf("stat Codex working directory: %w", err)
	}
	if info.Mode().Perm()&0o222 != 0 {
		return errors.New("Codex working directory must not be writable")
	}
	return nil
}

func newReadinessHTTPClient(base *http.Client) *http.Client {
	client := &http.Client{
		Timeout: 0,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	if base != nil {
		client.Transport = base.Transport
	}
	return client
}

type llmAdapter struct {
	client provider.LLM
}

func (adapter llmAdapter) Step(ctx context.Context, request application.StepRequest) (application.StepResponse, error) {
	result, err := adapter.client.Step(ctx, convertStepRequest(request))
	if err != nil {
		return application.StepResponse{}, err
	}
	calls := make([]application.LLMStepToolCall, 0, len(result.ToolCalls))
	for _, call := range result.ToolCalls {
		calls = append(calls, application.LLMStepToolCall{
			ID:        call.ID,
			Name:      call.Name,
			Arguments: call.Arguments,
		})
	}
	responseID := result.TurnID
	if responseID == "" {
		responseID = result.ResponseID
	}
	return application.StepResponse{
		ThreadID:     result.ThreadID,
		ResponseID:   responseID,
		Text:         result.Text,
		ToolCalls:    calls,
		InputTokens:  result.Usage.InputTokens,
		OutputTokens: result.Usage.OutputTokens,
	}, nil
}

func convertStepRequest(request application.StepRequest) provider.StepRequest {
	messages := make([]provider.Message, 0, len(request.Messages))
	for _, message := range request.Messages {
		converted := provider.Message{
			Role:       provider.Role(message.Role),
			Content:    message.Content,
			ToolCallID: message.ToolCallID,
		}
		if message.Role == string(provider.RoleTool) && len(message.ToolOutput) > 0 {
			converted.Content = string(message.ToolOutput)
		}
		if message.Role == string(provider.RoleAssistant) && message.ToolName != "" {
			arguments := message.ToolOutput
			if len(arguments) == 0 {
				arguments = json.RawMessage(`{}`)
			}
			converted.ToolCalls = []provider.ToolCall{{
				ID:        message.ToolCallID,
				Name:      message.ToolName,
				Arguments: arguments,
			}}
		}
		messages = append(messages, converted)
	}
	tools := make([]provider.ToolDefinition, 0, len(request.Tools))
	for _, tool := range request.Tools {
		tools = append(tools, provider.ToolDefinition{
			Name:        tool.Name,
			Description: tool.Description,
			Parameters:  tool.InputSchema,
			Strict:      true,
		})
	}
	return provider.StepRequest{
		SessionID:    request.SessionID,
		ThreadID:     request.ThreadID,
		Model:        request.Model,
		SystemPrompt: request.SystemPrompt,
		Messages:     messages,
		Tools:        tools,
		OutputSchema: request.OutputSchema,
	}
}

type transcriber struct {
	client provider.Transcriber
}

func (adapter transcriber) Transcribe(ctx context.Context, request application.TranscriptionRequest) (application.Transcription, error) {
	result, err := adapter.client.Transcribe(ctx, provider.TranscriptionRequest{
		Audio:    request.Audio,
		Filename: request.Filename,
		MIMEType: request.MIMEType,
		Model:    request.Model,
		Language: request.Language,
		Prompt:   request.Prompt,
	})
	if err != nil {
		return application.Transcription{}, err
	}
	return application.Transcription{
		Text:       result.Text,
		Language:   result.Language,
		DurationMS: result.Duration.Milliseconds(),
	}, nil
}

type synthesizer struct {
	client provider.Synthesizer
}

func (adapter synthesizer) Synthesize(ctx context.Context, request application.SpeechRequest) (application.Speech, error) {
	result, err := adapter.client.Synthesize(ctx, provider.SpeechRequest{
		Text:     request.Text,
		Model:    request.Model,
		Voice:    request.Voice,
		Format:   request.Format,
		Language: request.Language,
		Speed:    request.Speed,
	})
	if err != nil {
		return application.Speech{}, err
	}
	return application.Speech{
		Audio:    result.Audio,
		MIMEType: result.MIMEType,
		Format:   result.Format,
	}, nil
}

type interruptAdapter struct {
	client provider.Interruptible
}

type threadEnsurerAdapter struct {
	client provider.ThreadEnsurer
}

func (adapter threadEnsurerAdapter) EnsureThread(
	ctx context.Context,
	request application.StepRequest,
) (string, error) {
	return adapter.client.EnsureThread(ctx, convertStepRequest(request))
}

func (adapter interruptAdapter) Interrupt(ctx context.Context, threadID, turnID string) error {
	return adapter.client.Interrupt(ctx, threadID, turnID)
}

type threadDeleteClient interface {
	DeleteThread(context.Context, string) error
}

type threadDeleterAdapter struct {
	client threadDeleteClient
}

func (adapter threadDeleterAdapter) DeleteThread(ctx context.Context, threadID string) error {
	return adapter.client.DeleteThread(ctx, threadID)
}

type threadListerAdapter struct {
	client provider.ThreadLister
}

func (adapter threadListerAdapter) ListThreads(
	ctx context.Context,
	cursor string,
	limit int,
) (application.ProviderThreadPage, error) {
	page, err := adapter.client.ListThreads(ctx, cursor, limit)
	if err != nil {
		return application.ProviderThreadPage{}, err
	}
	threads := make([]application.ProviderThreadInfo, 0, len(page.Threads))
	for _, thread := range page.Threads {
		threads = append(threads, application.ProviderThreadInfo{
			ID:        thread.ID,
			CreatedAt: thread.CreatedAt,
		})
	}
	return application.ProviderThreadPage{
		Threads:    threads,
		NextCursor: page.NextCursor,
	}, nil
}

var _ application.Providers = (*Registry)(nil)
var _ application.LLM = llmAdapter{}
var _ application.ThreadEnsurer = threadEnsurerAdapter{}
var _ application.Transcriber = transcriber{}
var _ application.Synthesizer = synthesizer{}
var _ application.Interruptible = interruptAdapter{}
var _ application.ThreadDeleter = threadDeleterAdapter{}
var _ application.ThreadLister = threadListerAdapter{}
