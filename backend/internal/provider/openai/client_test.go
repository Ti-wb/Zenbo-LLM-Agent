package openai

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

func TestResponsesStepWithToolCall(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/responses" {
			t.Errorf("path = %q", request.URL.Path)
		}
		if got := request.Header.Get("Authorization"); got != "Bearer secret" {
			t.Errorf("authorization = %q", got)
		}
		var payload map[string]any
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Errorf("decode request: %v", err)
		}
		if payload["model"] != "robot-model" {
			t.Errorf("model = %#v", payload["model"])
		}
		if _, ok := payload["tools"]; !ok {
			t.Error("tools missing")
		}
		text, ok := payload["text"].(map[string]any)
		if !ok {
			t.Fatalf("structured output text config missing: %#v", payload["text"])
		}
		format, ok := text["format"].(map[string]any)
		if !ok || format["type"] != "json_schema" || format["name"] != "zenbo_gateway_output" ||
			format["strict"] != true {
			t.Errorf("structured output format = %#v", text["format"])
		}
		writer.Header().Set("Content-Type", "application/json")
		io.WriteString(writer, `{
			"id":"resp_123",
			"output":[
				{"type":"message","content":[{"type":"output_text","text":"Let me look."}]},
				{"type":"function_call","call_id":"call_123","name":"look_at_user","arguments":"{\"speed\":\"slow\"}"}
			],
			"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}
		}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL: server.URL,
		APIKey:  "secret",
		Mode:    ModeResponses,
		Model:   "robot-model",
	})
	response, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
		Tools: []provider.ToolDefinition{{
			Name:       "look_at_user",
			Parameters: json.RawMessage(`{"type":"object"}`),
			Strict:     true,
		}},
		OutputSchema: json.RawMessage(`{"type":"object","properties":{"ok":{"type":"boolean"}}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.Text != "Let me look." || response.ResponseID != "resp_123" {
		t.Fatalf("response = %#v", response)
	}
	if len(response.ToolCalls) != 1 || response.ToolCalls[0].ID != "call_123" {
		t.Fatalf("tool calls = %#v", response.ToolCalls)
	}
	if string(response.ToolCalls[0].Arguments) != `{"speed":"slow"}` {
		t.Fatalf("arguments = %s", response.ToolCalls[0].Arguments)
	}
	if response.Usage.TotalTokens != 15 {
		t.Fatalf("usage = %#v", response.Usage)
	}
}

func TestChatCompletionsStep(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/custom/v1/chat/completions" {
			t.Errorf("path = %q", request.URL.Path)
		}
		io.WriteString(writer, `{
			"id":"chat_123",
			"choices":[{"message":{"content":"Hi there","tool_calls":[]}}],
			"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}
		}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL: server.URL + "/custom/v1",
		Mode:    ModeChatCompletions,
		Model:   "model",
	})
	response, err := client.Step(context.Background(), provider.StepRequest{
		SystemPrompt: "Be helpful.",
		Messages:     []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.Text != "Hi there" || response.Usage.OutputTokens != 3 {
		t.Fatalf("response = %#v", response)
	}
}

func TestChatCompletionsSerializesToolHistoryAndParsesCall(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		var payload struct {
			Messages []struct {
				Role       string `json:"role"`
				ToolCallID string `json:"tool_call_id"`
				ToolCalls  []struct {
					ID       string `json:"id"`
					Function struct {
						Name      string `json:"name"`
						Arguments string `json:"arguments"`
					} `json:"function"`
				} `json:"tool_calls"`
			} `json:"messages"`
			Tools          []json.RawMessage `json:"tools"`
			ToolChoice     string            `json:"tool_choice"`
			ResponseFormat struct {
				Type       string `json:"type"`
				JSONSchema struct {
					Name   string `json:"name"`
					Strict bool   `json:"strict"`
				} `json:"json_schema"`
			} `json:"response_format"`
		}
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		if len(payload.Messages) != 3 ||
			len(payload.Messages[1].ToolCalls) != 1 ||
			payload.Messages[1].ToolCalls[0].ID != "prior_call" ||
			payload.Messages[2].ToolCallID != "prior_call" {
			t.Fatalf("tool history = %#v", payload.Messages)
		}
		if len(payload.Tools) != 1 || payload.ToolChoice != "auto" {
			t.Fatalf("tools/tool choice = %#v / %q", payload.Tools, payload.ToolChoice)
		}
		if payload.ResponseFormat.Type != "json_schema" ||
			payload.ResponseFormat.JSONSchema.Name != "zenbo_gateway_output" ||
			!payload.ResponseFormat.JSONSchema.Strict {
			t.Fatalf("response format = %#v", payload.ResponseFormat)
		}
		io.WriteString(writer, `{
			"id":"chat_tool",
			"choices":[{"message":{"content":null,"tool_calls":[{
				"id":"next_call",
				"type":"function",
				"function":{"name":"show_emotion","arguments":"{\"emotion\":\"happy\"}"}
			}]}}],
			"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}
		}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL: server.URL,
		Mode:    ModeChatCompletions,
		Model:   "model",
	})
	response, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{
			{Role: provider.RoleUser, Content: "look happy"},
			{
				Role:    provider.RoleAssistant,
				Content: "",
				ToolCalls: []provider.ToolCall{{
					ID:        "prior_call",
					Name:      "get_system_status",
					Arguments: json.RawMessage(`{}`),
				}},
			},
			{Role: provider.RoleTool, ToolCallID: "prior_call", Content: `{"ok":true}`},
		},
		Tools: []provider.ToolDefinition{{
			Name:       "show_emotion",
			Parameters: json.RawMessage(`{"type":"object"}`),
			Strict:     true,
		}},
		OutputSchema: json.RawMessage(`{"type":"object","properties":{"ok":{"type":"boolean"}}}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(response.ToolCalls) != 1 ||
		response.ToolCalls[0].ID != "next_call" ||
		response.ToolCalls[0].Name != "show_emotion" {
		t.Fatalf("tool calls = %#v", response.ToolCalls)
	}
}

func TestResponsesSerializesToolOutput(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		var payload struct {
			Input []map[string]any `json:"input"`
		}
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		found := false
		for _, item := range payload.Input {
			if item["type"] == "function_call_output" {
				found = item["call_id"] == "call_1" && item["output"] == `{"ok":true}`
			}
		}
		if !found {
			t.Errorf("function result missing from %#v", payload.Input)
		}
		io.WriteString(writer, `{"id":"r","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{BaseURL: server.URL, Model: "model"})
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{
			Role:       provider.RoleTool,
			ToolCallID: "call_1",
			Content:    `{"ok":true}`,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestTranscribe(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/audio/transcriptions" {
			t.Errorf("path = %q", request.URL.Path)
		}
		if err := request.ParseMultipartForm(1 << 20); err != nil {
			t.Fatal(err)
		}
		if request.FormValue("model") != "whisper" || request.FormValue("language") != "zh" {
			t.Errorf("form = %#v", request.MultipartForm.Value)
		}
		file, fileHeader, err := request.FormFile("file")
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		if fileHeader.Filename != "speech.wav" ||
			fileHeader.Header.Get("Content-Type") != "audio/wav" {
			t.Errorf("file metadata = %#v", fileHeader)
		}
		audio, _ := io.ReadAll(file)
		if string(audio) != "RIFFfake" {
			t.Errorf("audio = %q", audio)
		}
		io.WriteString(writer, `{"text":"你好","language":"zh","duration":1.25}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{BaseURL: server.URL, TranscriptionModel: "whisper"})
	result, err := client.Transcribe(context.Background(), provider.TranscriptionRequest{
		Audio:    []byte("RIFFfake"),
		Filename: "speech.wav",
		MIMEType: "audio/wav",
		Language: "zh",
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Text != "你好" || result.Duration != 1250*time.Millisecond {
		t.Fatalf("result = %#v", result)
	}
}

func TestSynthesize(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/audio/speech" {
			t.Errorf("path = %q", request.URL.Path)
		}
		var payload map[string]any
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		if payload["model"] != "tts" || payload["voice"] != "alloy" || payload["response_format"] != "wav" {
			t.Errorf("payload = %#v", payload)
		}
		if request.Header.Get("Accept") != "audio/*" {
			t.Errorf("accept = %q", request.Header.Get("Accept"))
		}
		writer.Header().Set("Content-Type", "audio/wav")
		writer.Write([]byte("RIFFaudio"))
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL:     server.URL,
		SpeechModel: "tts",
		Voice:       "alloy",
	})
	result, err := client.Synthesize(context.Background(), provider.SpeechRequest{
		Text:   "你好",
		Format: "wav",
	})
	if err != nil {
		t.Fatal(err)
	}
	if string(result.Audio) != "RIFFaudio" || result.MIMEType != "audio/wav" {
		t.Fatalf("result = %#v", result)
	}
}

func TestHTTPErrorNormalization(t *testing.T) {
	t.Parallel()
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		calls.Add(1)
		writer.Header().Set("Retry-After", "7")
		writer.WriteHeader(http.StatusTooManyRequests)
		io.WriteString(writer, `{"error":{"message":"capacity"}}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{BaseURL: server.URL, Model: "model"})
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	var providerError *provider.Error
	if !errors.As(err, &providerError) {
		t.Fatalf("error = %v", err)
	}
	if providerError.Kind != provider.ErrorRateLimited || providerError.RetryAfter != 7*time.Second {
		t.Fatalf("error = %#v", providerError)
	}
	if calls.Load() != 1 {
		t.Fatalf("provider calls = %d; SDK retries must be disabled", calls.Load())
	}
}

func TestStepTimeout(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		time.Sleep(100 * time.Millisecond)
		io.WriteString(writer, `{"id":"late"}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL: server.URL,
		Model:   "model",
		Timeout: 25 * time.Millisecond,
	})
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorTimeout {
		t.Fatalf("error = %#v", err)
	}
}

func TestStepRejectsOversizeResponse(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Write([]byte(strings.Repeat("x", 17)))
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL:      server.URL,
		Model:        "model",
		MaxJSONBytes: 16,
	})
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("error = %#v", err)
	}
}

func TestSynthesizeRejectsOversizeResponse(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Write([]byte(strings.Repeat("x", 17)))
	}))
	defer server.Close()

	client := newTestClient(t, Config{
		BaseURL:       server.URL,
		SpeechModel:   "tts",
		Voice:         "alloy",
		MaxAudioBytes: 16,
	})
	_, err := client.Synthesize(context.Background(), provider.SpeechRequest{Text: "hi"})
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("error = %#v", err)
	}
}

func TestStepRejectsMalformedJSON(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		io.WriteString(writer, `{"id":`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{BaseURL: server.URL, Model: "model"})
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("error = %#v", err)
	}
}

func TestStepRejectsMalformedToolArguments(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		io.WriteString(writer, `{
			"id":"resp",
			"output":[{"type":"function_call","call_id":"call","name":"tool","arguments":"not json"}]
		}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{BaseURL: server.URL, Model: "model"})
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("error = %#v", err)
	}
}

func TestClientDoesNotInheritOpenAIEnvironment(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "ambient-secret")
	t.Setenv("OPENAI_CUSTOM_HEADERS", "X-Ambient-Secret: leaked")

	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if authorization := request.Header.Get("Authorization"); authorization != "" {
			t.Errorf("ambient authorization was inherited: %q", authorization)
		}
		if ambient := request.Header.Get("X-Ambient-Secret"); ambient != "" {
			t.Errorf("ambient custom header was inherited: %q", ambient)
		}
		io.WriteString(writer, `{
			"id":"response",
			"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]
		}`)
	}))
	defer server.Close()

	client := newTestClient(t, Config{BaseURL: server.URL, Model: "model"})
	if _, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	}); err != nil {
		t.Fatal(err)
	}
}

func newTestClient(t *testing.T, config Config) *Client {
	t.Helper()
	client, err := New(config)
	if err != nil {
		t.Fatal(err)
	}
	return client
}
