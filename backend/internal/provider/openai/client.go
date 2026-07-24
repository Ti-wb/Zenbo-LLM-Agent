// Package openai implements the provider boundary against OpenAI-compatible
// HTTP APIs, including LiteLLM.
package openai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
	openaisdk "github.com/openai/openai-go/v3"
	"github.com/openai/openai-go/v3/option"
	"github.com/openai/openai-go/v3/responses"
	"github.com/openai/openai-go/v3/shared"
)

// Mode selects the OpenAI-compatible LLM endpoint.
type Mode string

const (
	ModeResponses       Mode = "responses"
	ModeChatCompletions Mode = "chat_completions"
)

const (
	defaultJSONLimit  = 4 << 20
	defaultAudioLimit = 10 << 20
	errorBodyLimit    = 64 << 10
)

// Config configures one OpenAI-compatible endpoint. APIKey may be empty for a
// locally authenticated or explicitly unauthenticated LiteLLM deployment.
type Config struct {
	BaseURL            string
	APIKey             string
	Mode               Mode
	Model              string
	TranscriptionModel string
	SpeechModel        string
	Voice              string
	Timeout            time.Duration
	MaxJSONBytes       int64
	MaxAudioBytes      int64
	HTTPClient         *http.Client
	Headers            map[string]string
	// SendSpeechLanguage enables the non-standard language field accepted by
	// some LiteLLM speech backends. It is off by default because strict OpenAI
	// endpoints reject unknown request fields.
	SendSpeechLanguage bool
}

// Client implements provider.LLM, provider.Transcriber and
// provider.Synthesizer.
type Client struct {
	mode               Mode
	model              string
	transcriptionModel string
	speechModel        string
	voice              string
	timeout            time.Duration
	maxJSONBytes       int64
	maxAudioBytes      int64
	sendSpeechLanguage bool
	responses          responses.ResponseService
	chat               openaisdk.ChatCompletionService
	transcriptions     openaisdk.AudioTranscriptionService
	speech             openaisdk.AudioSpeechService
}

// New validates config without making a network request.
func New(config Config) (*Client, error) {
	baseURL, err := NormalizeBaseURL(config.BaseURL)
	if err != nil {
		return nil, err
	}
	mode := config.Mode
	if mode == "" {
		mode = ModeResponses
	}
	if mode != ModeResponses && mode != ModeChatCompletions {
		return nil, fmt.Errorf("unsupported OpenAI-compatible mode %q", mode)
	}
	maxJSON := config.MaxJSONBytes
	if maxJSON <= 0 {
		maxJSON = defaultJSONLimit
	}
	maxAudio := config.MaxAudioBytes
	if maxAudio <= 0 {
		maxAudio = defaultAudioLimit
	}
	httpClient := config.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{}
	}
	headers := make(map[string]string, len(config.Headers))
	for key, value := range config.Headers {
		if strings.EqualFold(key, "Authorization") {
			return nil, fmt.Errorf("custom Authorization header is not allowed; use APIKey")
		}
		headers[key] = value
	}
	options := []option.RequestOption{
		option.WithBaseURL(baseURL),
		// Always set the key, including an empty value, so an explicitly
		// unauthenticated LiteLLM profile never inherits process credentials.
		option.WithAPIKey(config.APIKey),
		option.WithHTTPClient(httpClient),
		// Gateway jobs own retries and backoff. Hidden SDK retries would make
		// cancellation and idempotency timing much harder to reason about.
		option.WithMaxRetries(0),
		option.WithHeader("User-Agent", "zenbo-agent-gateway/1.0"),
		option.WithMiddleware(responseLimitMiddleware(maxJSON, maxAudio)),
	}
	for key, value := range headers {
		options = append(options, option.WithHeader(key, value))
	}
	return &Client{
		mode:               mode,
		model:              config.Model,
		transcriptionModel: config.TranscriptionModel,
		speechModel:        config.SpeechModel,
		voice:              config.Voice,
		timeout:            config.Timeout,
		maxJSONBytes:       maxJSON,
		maxAudioBytes:      maxAudio,
		sendSpeechLanguage: config.SendSpeechLanguage,
		// Construct services directly instead of openai.NewClient. The direct
		// constructors are the SDK-supported way to avoid reading OPENAI_* process
		// variables, keeping each configured provider profile self-contained.
		responses:      responses.NewResponseService(options...),
		chat:           openaisdk.NewChatCompletionService(options...),
		transcriptions: openaisdk.NewAudioTranscriptionService(options...),
		speech:         openaisdk.NewAudioSpeechService(options...),
	}, nil
}

// Step performs one Responses API or Chat Completions API request.
func (c *Client) Step(ctx context.Context, request provider.StepRequest) (provider.StepResponse, error) {
	model := firstNonEmpty(request.Model, c.model)
	if model == "" {
		return provider.StepResponse{}, invalid("step", "model is required")
	}
	if err := validateStepRequest(request); err != nil {
		return provider.StepResponse{}, invalid("step", err.Error())
	}
	ctx, cancel := c.withTimeout(ctx)
	defer cancel()

	var raw []byte
	switch c.mode {
	case ModeResponses:
		params, err := buildResponsesParams(model, request)
		if err != nil {
			return provider.StepResponse{}, internal("step", err)
		}
		_, err = c.responses.New(ctx, params, option.WithResponseBodyInto(&raw))
		if err != nil {
			return provider.StepResponse{}, normalizeSDKError("step", err)
		}
		return parseResponses(raw)
	case ModeChatCompletions:
		params, err := buildChatParams(model, request)
		if err != nil {
			return provider.StepResponse{}, internal("step", err)
		}
		_, err = c.chat.New(ctx, params, option.WithResponseBodyInto(&raw))
		if err != nil {
			return provider.StepResponse{}, normalizeSDKError("step", err)
		}
		return parseChatCompletion(raw)
	default:
		return provider.StepResponse{}, invalid("step", "unsupported endpoint mode")
	}
}

// Transcribe sends a multipart request to /v1/audio/transcriptions.
func (c *Client) Transcribe(ctx context.Context, request provider.TranscriptionRequest) (provider.Transcription, error) {
	if len(request.Audio) == 0 {
		return provider.Transcription{}, invalid("transcribe", "audio is required")
	}
	model := firstNonEmpty(request.Model, c.transcriptionModel)
	if model == "" {
		return provider.Transcription{}, invalid("transcribe", "transcription model is required")
	}
	filename := request.Filename
	if filename == "" {
		filename = "turn.wav"
	}
	contentType := request.MIMEType
	if contentType == "" {
		contentType = "audio/wav"
	}

	ctx, cancel := c.withTimeout(ctx)
	defer cancel()

	params := openaisdk.AudioTranscriptionNewParams{
		File: &namedAudioReader{
			Reader:      bytes.NewReader(request.Audio),
			filename:    safeFilename(filename),
			contentType: contentType,
		},
		Model:          openaisdk.AudioModel(model),
		ResponseFormat: openaisdk.AudioResponseFormatVerboseJSON,
	}
	if request.Language != "" {
		params.Language = openaisdk.String(request.Language)
	}
	if request.Prompt != "" {
		params.Prompt = openaisdk.String(request.Prompt)
	}
	var raw []byte
	_, err := c.transcriptions.New(ctx, params, option.WithResponseBodyInto(&raw))
	if err != nil {
		return provider.Transcription{}, normalizeSDKError("transcribe", err)
	}
	var response struct {
		Text     string  `json:"text"`
		Language string  `json:"language"`
		Duration float64 `json:"duration"`
	}
	if err := json.Unmarshal(raw, &response); err != nil {
		return provider.Transcription{}, malformed("transcribe", "provider returned invalid transcription JSON", err)
	}
	if strings.TrimSpace(response.Text) == "" {
		return provider.Transcription{}, malformed("transcribe", "provider returned an empty transcription", nil)
	}
	return provider.Transcription{
		Text:     response.Text,
		Language: response.Language,
		Duration: time.Duration(response.Duration * float64(time.Second)),
	}, nil
}

// Synthesize sends a request to /v1/audio/speech and returns bounded audio.
func (c *Client) Synthesize(ctx context.Context, request provider.SpeechRequest) (provider.Speech, error) {
	if strings.TrimSpace(request.Text) == "" {
		return provider.Speech{}, invalid("synthesize", "text is required")
	}
	model := firstNonEmpty(request.Model, c.speechModel)
	if model == "" {
		return provider.Speech{}, invalid("synthesize", "speech model is required")
	}
	voice := firstNonEmpty(request.Voice, c.voice)
	if voice == "" {
		return provider.Speech{}, invalid("synthesize", "voice is required")
	}
	format := request.Format
	if format == "" {
		format = "wav"
	}
	params := openaisdk.AudioSpeechNewParams{
		Model: openaisdk.SpeechModel(model),
		Input: request.Text,
		Voice: openaisdk.AudioSpeechNewParamsVoiceUnion{
			OfString: openaisdk.String(voice),
		},
		ResponseFormat: openaisdk.AudioSpeechNewParamsResponseFormat(format),
	}
	if request.Speed > 0 {
		params.Speed = openaisdk.Float(request.Speed)
	}
	if c.sendSpeechLanguage && request.Language != "" {
		params.SetExtraFields(map[string]any{"language": request.Language})
	}

	ctx, cancel := c.withTimeout(ctx)
	defer cancel()
	response, err := c.speech.New(ctx, params, option.WithHeader("Accept", "audio/*"))
	if err != nil {
		return provider.Speech{}, normalizeSDKError("synthesize", err)
	}
	defer response.Body.Close()
	audio, err := readBounded(response.Body, c.maxAudioBytes)
	if err != nil {
		return provider.Speech{}, normalizeSDKError("synthesize", err)
	}
	if len(audio) == 0 {
		return provider.Speech{}, malformed("synthesize", "provider returned empty audio", nil)
	}
	mimeType := response.Header.Get("Content-Type")
	if parsed, _, parseErr := mime.ParseMediaType(mimeType); parseErr == nil {
		mimeType = parsed
	}
	if mimeType == "" || mimeType == "application/octet-stream" {
		mimeType = mimeForFormat(format)
	}
	return provider.Speech{Audio: audio, MIMEType: mimeType, Format: format}, nil
}

func (c *Client) withTimeout(ctx context.Context) (context.Context, context.CancelFunc) {
	if c.timeout > 0 {
		return context.WithTimeout(ctx, c.timeout)
	}
	return context.WithCancel(ctx)
}

var errResponseTooLarge = errors.New("provider response exceeded configured size limit")

// NormalizeBaseURL validates and canonicalizes the configured endpoint exactly
// as provider requests use it. Readiness probes and profile revisions call this
// helper so they cannot drift onto a different path.
func NormalizeBaseURL(raw string) (string, error) {
	base, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || base.Scheme == "" || base.Host == "" {
		return "", fmt.Errorf("openai-compatible base URL must be an absolute HTTP(S) URL")
	}
	if base.Scheme != "http" && base.Scheme != "https" {
		return "", fmt.Errorf("openai-compatible base URL scheme must be http or https")
	}
	if base.RawQuery != "" || base.Fragment != "" {
		return "", fmt.Errorf("openai-compatible base URL must not contain a query or fragment")
	}
	normalized := *base
	basePath := path.Clean("/" + normalized.Path)
	if basePath == "/" {
		basePath = ""
	}
	if basePath == "" {
		basePath = "/v1"
	} else if path.Base(basePath) != "v1" {
		basePath = path.Join(basePath, "v1")
	}
	normalized.Path = basePath + "/"
	normalized.RawPath = ""
	return normalized.String(), nil
}

// responseLimitMiddleware bounds the body before the SDK decodes it. This
// preserves the SDK's request/auth/API surface while preventing both successful
// and error responses from causing unbounded allocations.
func responseLimitMiddleware(maxJSON, maxAudio int64) option.Middleware {
	return func(
		request *http.Request,
		next option.MiddlewareNext,
	) (*http.Response, error) {
		response, err := next(request)
		if err != nil || response == nil || response.Body == nil {
			return response, err
		}
		limit := maxJSON
		if response.StatusCode < http.StatusBadRequest &&
			strings.HasSuffix(request.URL.Path, "/audio/speech") {
			limit = maxAudio
		} else if response.StatusCode >= http.StatusBadRequest {
			limit = errorBodyLimit
		}
		raw, readErr := readBounded(response.Body, limit)
		closeErr := response.Body.Close()
		if readErr != nil {
			return nil, readErr
		}
		if closeErr != nil {
			return nil, closeErr
		}
		response.Body = io.NopCloser(bytes.NewReader(raw))
		response.ContentLength = int64(len(raw))
		return response, nil
	}
}

func readBounded(reader io.Reader, limit int64) ([]byte, error) {
	if limit <= 0 {
		limit = defaultJSONLimit
	}
	raw, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(raw)) > limit {
		return nil, errResponseTooLarge
	}
	return raw, nil
}

func normalizeSDKError(operation string, err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, errResponseTooLarge) {
		return malformed(operation, "provider response exceeded configured size limit", err)
	}
	if normalized := provider.FromContext("openai-compatible", operation, err); normalized != err {
		return normalized
	}
	var apiError *openaisdk.Error
	if errors.As(err, &apiError) {
		message := strings.TrimSpace(apiError.Message)
		if message == "" {
			message = "provider returned HTTP error"
		} else if len(message) > 512 {
			message = message[:512]
		}
		retryAfter := ""
		if apiError.Response != nil {
			retryAfter = apiError.Response.Header.Get("Retry-After")
		}
		return provider.FromHTTPStatus(
			"openai-compatible",
			operation,
			apiError.StatusCode,
			retryAfter,
			message,
		)
	}
	return &provider.Error{
		Provider:  "openai-compatible",
		Operation: operation,
		Kind:      provider.ErrorUnavailable,
		Message:   "provider request failed",
		Err:       err,
	}
}

type namedAudioReader struct {
	*bytes.Reader
	filename    string
	contentType string
}

func (r *namedAudioReader) Filename() string    { return r.filename }
func (r *namedAudioReader) ContentType() string { return r.contentType }

func safeFilename(value string) string {
	value = path.Base(strings.TrimSpace(value))
	value = strings.NewReplacer("\r", "_", "\n", "_", `"`, "_", "\\", "_").Replace(value)
	if value == "" || value == "." || value == "/" {
		return "turn.wav"
	}
	return value
}

func validateStepRequest(request provider.StepRequest) error {
	for index, message := range request.Messages {
		switch message.Role {
		case provider.RoleSystem, provider.RoleUser, provider.RoleAssistant, provider.RoleTool:
		default:
			return fmt.Errorf("message %d has unsupported role %q", index, message.Role)
		}
		if message.Role == provider.RoleTool && message.ToolCallID == "" {
			return fmt.Errorf("tool message %d is missing tool call ID", index)
		}
		if len(message.ToolCalls) > 0 && message.Role != provider.RoleAssistant {
			return fmt.Errorf("message %d has tool calls but is not an assistant message", index)
		}
		for callIndex, call := range message.ToolCalls {
			if strings.TrimSpace(call.ID) == "" || strings.TrimSpace(call.Name) == "" {
				return fmt.Errorf("message %d tool call %d is missing ID or name", index, callIndex)
			}
			if !isJSONObject(call.Arguments) {
				return fmt.Errorf("message %d tool call %d arguments must be a JSON object", index, callIndex)
			}
		}
	}
	seen := make(map[string]struct{}, len(request.Tools))
	for index, tool := range request.Tools {
		if strings.TrimSpace(tool.Name) == "" {
			return fmt.Errorf("tool %d has no name", index)
		}
		if _, duplicate := seen[tool.Name]; duplicate {
			return fmt.Errorf("tool %q is duplicated", tool.Name)
		}
		seen[tool.Name] = struct{}{}
		if !isJSONObject(tool.Parameters) {
			return fmt.Errorf("tool %q parameters must be a JSON object", tool.Name)
		}
	}
	if len(request.OutputSchema) > 0 && !isJSONObject(request.OutputSchema) {
		return errors.New("output schema must be a JSON object")
	}
	return nil
}

func responseRole(role provider.Role) (responses.EasyInputMessageRole, error) {
	switch role {
	case provider.RoleSystem:
		return responses.EasyInputMessageRoleSystem, nil
	case provider.RoleUser:
		return responses.EasyInputMessageRoleUser, nil
	case provider.RoleAssistant:
		return responses.EasyInputMessageRoleAssistant, nil
	default:
		return "", fmt.Errorf("unsupported Responses API message role %q", role)
	}
}

func decodeJSONObject(raw json.RawMessage) (map[string]any, error) {
	var object map[string]any
	if err := json.Unmarshal(raw, &object); err != nil || object == nil {
		if err == nil {
			err = errors.New("JSON object is null")
		}
		return nil, err
	}
	return object, nil
}

func buildResponsesParams(model string, request provider.StepRequest) (responses.ResponseNewParams, error) {
	input := make(responses.ResponseInputParam, 0, len(request.Messages)+1)
	if request.SystemPrompt != "" {
		input = append(input, responses.ResponseInputItemParamOfMessage(
			request.SystemPrompt,
			responses.EasyInputMessageRoleSystem,
		))
	}
	for _, message := range request.Messages {
		if message.Role == provider.RoleTool {
			input = append(input, responses.ResponseInputItemParamOfFunctionCallOutput(
				message.ToolCallID,
				message.Content,
			))
			continue
		}
		role, err := responseRole(message.Role)
		if err != nil {
			return responses.ResponseNewParams{}, err
		}
		input = append(input, responses.ResponseInputItemParamOfMessage(message.Content, role))
		for _, call := range message.ToolCalls {
			input = append(input, responses.ResponseInputItemParamOfFunctionCall(
				string(call.Arguments),
				call.ID,
				call.Name,
			))
		}
	}
	params := responses.ResponseNewParams{
		Model: shared.ResponsesModel(model),
		Input: responses.ResponseNewParamsInputUnion{OfInputItemList: input},
	}
	if len(request.Tools) > 0 {
		params.Tools = make([]responses.ToolUnionParam, 0, len(request.Tools))
		for _, tool := range request.Tools {
			schema, err := decodeJSONObject(tool.Parameters)
			if err != nil {
				return responses.ResponseNewParams{}, err
			}
			params.Tools = append(params.Tools, responses.ToolUnionParam{
				OfFunction: &responses.FunctionToolParam{
					Name:        tool.Name,
					Description: openaisdk.String(tool.Description),
					Parameters:  schema,
					Strict:      openaisdk.Bool(tool.Strict),
				},
			})
		}
		params.ToolChoice = responses.ResponseNewParamsToolChoiceUnion{
			OfToolChoiceMode: openaisdk.Opt(responses.ToolChoiceOptionsAuto),
		}
	}
	if len(request.OutputSchema) > 0 {
		schema, err := decodeJSONObject(request.OutputSchema)
		if err != nil {
			return responses.ResponseNewParams{}, err
		}
		format := responses.ResponseFormatTextConfigParamOfJSONSchema(
			"zenbo_gateway_output",
			schema,
		)
		format.OfJSONSchema.Strict = openaisdk.Bool(true)
		params.Text = responses.ResponseTextConfigParam{Format: format}
	}
	return params, nil
}

func buildChatParams(model string, request provider.StepRequest) (openaisdk.ChatCompletionNewParams, error) {
	messages := make([]openaisdk.ChatCompletionMessageParamUnion, 0, len(request.Messages)+1)
	if request.SystemPrompt != "" {
		messages = append(messages, openaisdk.SystemMessage(request.SystemPrompt))
	}
	for _, message := range request.Messages {
		var item openaisdk.ChatCompletionMessageParamUnion
		switch message.Role {
		case provider.RoleSystem:
			item = openaisdk.SystemMessage(message.Content)
		case provider.RoleUser:
			item = openaisdk.UserMessage(message.Content)
		case provider.RoleAssistant:
			item = openaisdk.AssistantMessage(message.Content)
			item.OfAssistant.ToolCalls = make(
				[]openaisdk.ChatCompletionMessageToolCallUnionParam,
				0,
				len(message.ToolCalls),
			)
			for _, call := range message.ToolCalls {
				item.OfAssistant.ToolCalls = append(
					item.OfAssistant.ToolCalls,
					openaisdk.ChatCompletionMessageToolCallUnionParam{
						OfFunction: &openaisdk.ChatCompletionMessageFunctionToolCallParam{
							ID: call.ID,
							Function: openaisdk.ChatCompletionMessageFunctionToolCallFunctionParam{
								Name:      call.Name,
								Arguments: string(call.Arguments),
							},
						},
					},
				)
			}
		case provider.RoleTool:
			item = openaisdk.ToolMessage(message.Content, message.ToolCallID)
		default:
			return openaisdk.ChatCompletionNewParams{}, fmt.Errorf(
				"unsupported chat message role %q",
				message.Role,
			)
		}
		messages = append(messages, item)
	}
	params := openaisdk.ChatCompletionNewParams{
		Model:    openaisdk.ChatModel(model),
		Messages: messages,
	}
	if len(request.Tools) > 0 {
		params.Tools = make([]openaisdk.ChatCompletionToolUnionParam, 0, len(request.Tools))
		for _, tool := range request.Tools {
			schema, err := decodeJSONObject(tool.Parameters)
			if err != nil {
				return openaisdk.ChatCompletionNewParams{}, err
			}
			params.Tools = append(
				params.Tools,
				openaisdk.ChatCompletionFunctionTool(shared.FunctionDefinitionParam{
					Name:        tool.Name,
					Description: openaisdk.String(tool.Description),
					Parameters:  schema,
					Strict:      openaisdk.Bool(tool.Strict),
				}),
			)
		}
		params.ToolChoice = openaisdk.ChatCompletionToolChoiceOptionUnionParam{
			OfAuto: openaisdk.String("auto"),
		}
	}
	if len(request.OutputSchema) > 0 {
		schema, err := decodeJSONObject(request.OutputSchema)
		if err != nil {
			return openaisdk.ChatCompletionNewParams{}, err
		}
		params.ResponseFormat = openaisdk.ChatCompletionNewParamsResponseFormatUnion{
			OfJSONSchema: &shared.ResponseFormatJSONSchemaParam{
				JSONSchema: shared.ResponseFormatJSONSchemaJSONSchemaParam{
					Name:   "zenbo_gateway_output",
					Strict: openaisdk.Bool(true),
					Schema: schema,
				},
			},
		}
	}
	return params, nil
}

func parseResponses(raw []byte) (provider.StepResponse, error) {
	var response struct {
		ID     string `json:"id"`
		Status string `json:"status"`
		Output []struct {
			Type      string          `json:"type"`
			CallID    string          `json:"call_id"`
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
			Content   []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		} `json:"output"`
		Usage struct {
			InputTokens  int64 `json:"input_tokens"`
			OutputTokens int64 `json:"output_tokens"`
			TotalTokens  int64 `json:"total_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(raw, &response); err != nil {
		return provider.StepResponse{}, malformed("step", "provider returned invalid Responses API JSON", err)
	}
	if response.Status != "completed" {
		return provider.StepResponse{}, malformed(
			"step",
			"provider returned a non-completed Responses API result",
			nil,
		)
	}
	result := provider.StepResponse{
		ResponseID: response.ID,
		Usage: provider.Usage{
			InputTokens:  response.Usage.InputTokens,
			OutputTokens: response.Usage.OutputTokens,
			TotalTokens:  response.Usage.TotalTokens,
		},
	}
	var text strings.Builder
	for _, item := range response.Output {
		switch item.Type {
		case "message":
			for _, content := range item.Content {
				if content.Type == "output_text" {
					text.WriteString(content.Text)
				}
			}
		case "function_call":
			arguments, err := normalizeArguments(item.Arguments)
			if err != nil || item.CallID == "" || item.Name == "" {
				return provider.StepResponse{}, malformed("step", "provider returned a malformed function call", err)
			}
			result.ToolCalls = append(result.ToolCalls, provider.ToolCall{
				ID:        item.CallID,
				Name:      item.Name,
				Arguments: arguments,
			})
		}
	}
	result.Text = text.String()
	if result.Text == "" && len(result.ToolCalls) == 0 {
		return provider.StepResponse{}, malformed("step", "provider returned neither text nor tool calls", nil)
	}
	return result, nil
}

func parseChatCompletion(raw []byte) (provider.StepResponse, error) {
	var response struct {
		ID      string `json:"id"`
		Choices []struct {
			FinishReason string `json:"finish_reason"`
			Message      struct {
				Content json.RawMessage `json:"content"`
				Tools   []struct {
					ID       string `json:"id"`
					Function struct {
						Name      string          `json:"name"`
						Arguments json.RawMessage `json:"arguments"`
					} `json:"function"`
				} `json:"tool_calls"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int64 `json:"prompt_tokens"`
			CompletionTokens int64 `json:"completion_tokens"`
			TotalTokens      int64 `json:"total_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(raw, &response); err != nil {
		return provider.StepResponse{}, malformed("step", "provider returned invalid Chat Completions JSON", err)
	}
	if len(response.Choices) == 0 {
		return provider.StepResponse{}, malformed("step", "provider returned no choices", nil)
	}
	choice := response.Choices[0]
	switch choice.FinishReason {
	case "stop", "tool_calls":
	default:
		return provider.StepResponse{}, malformed(
			"step",
			"provider returned a non-success Chat Completions finish reason",
			nil,
		)
	}
	text, err := decodeChatContent(choice.Message.Content)
	if err != nil {
		return provider.StepResponse{}, malformed("step", "provider returned malformed message content", err)
	}
	result := provider.StepResponse{
		ResponseID: response.ID,
		Text:       text,
		Usage: provider.Usage{
			InputTokens:  response.Usage.PromptTokens,
			OutputTokens: response.Usage.CompletionTokens,
			TotalTokens:  response.Usage.TotalTokens,
		},
	}
	for _, call := range choice.Message.Tools {
		arguments, err := normalizeArguments(call.Function.Arguments)
		if err != nil || call.ID == "" || call.Function.Name == "" {
			return provider.StepResponse{}, malformed("step", "provider returned a malformed function call", err)
		}
		result.ToolCalls = append(result.ToolCalls, provider.ToolCall{
			ID:        call.ID,
			Name:      call.Function.Name,
			Arguments: arguments,
		})
	}
	if result.Text == "" && len(result.ToolCalls) == 0 {
		return provider.StepResponse{}, malformed("step", "provider returned neither text nor tool calls", nil)
	}
	if choice.FinishReason == "tool_calls" && len(result.ToolCalls) == 0 {
		return provider.StepResponse{}, malformed(
			"step",
			"provider returned a tool_calls finish reason without tool calls",
			nil,
		)
	}
	if choice.FinishReason == "stop" && len(result.ToolCalls) != 0 {
		return provider.StepResponse{}, malformed(
			"step",
			"provider returned tool calls with a stop finish reason",
			nil,
		)
	}
	return result, nil
}

func decodeChatContent(raw json.RawMessage) (string, error) {
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return "", nil
	}
	var text string
	if err := json.Unmarshal(raw, &text); err == nil {
		return text, nil
	}
	var parts []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if err := json.Unmarshal(raw, &parts); err != nil {
		return "", err
	}
	var result strings.Builder
	for _, part := range parts {
		if part.Type == "text" || part.Type == "output_text" {
			result.WriteString(part.Text)
		}
	}
	return result.String(), nil
}

func normalizeArguments(raw json.RawMessage) (json.RawMessage, error) {
	if len(raw) == 0 {
		return nil, errors.New("arguments are empty")
	}
	var encoded string
	if json.Unmarshal(raw, &encoded) == nil {
		raw = json.RawMessage(encoded)
	}
	if !isJSONObject(raw) {
		return nil, errors.New("arguments are not a JSON object")
	}
	return append(json.RawMessage(nil), raw...), nil
}

func isJSONObject(raw json.RawMessage) bool {
	raw = bytes.TrimSpace(raw)
	if len(raw) < 2 || raw[0] != '{' || raw[len(raw)-1] != '}' || !json.Valid(raw) {
		return false
	}
	var object map[string]json.RawMessage
	return json.Unmarshal(raw, &object) == nil
}

func mimeForFormat(format string) string {
	switch strings.ToLower(format) {
	case "wav":
		return "audio/wav"
	case "mp3":
		return "audio/mpeg"
	case "opus":
		return "audio/opus"
	case "aac":
		return "audio/aac"
	case "flac":
		return "audio/flac"
	case "pcm":
		return "audio/pcm"
	default:
		return "application/octet-stream"
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func invalid(operation, message string) error {
	return &provider.Error{
		Provider:  "openai-compatible",
		Operation: operation,
		Kind:      provider.ErrorInvalidRequest,
		Message:   message,
	}
}

func malformed(operation, message string, err error) error {
	return &provider.Error{
		Provider:  "openai-compatible",
		Operation: operation,
		Kind:      provider.ErrorMalformed,
		Message:   message,
		Err:       err,
	}
}

func internal(operation string, err error) error {
	return &provider.Error{
		Provider:  "openai-compatible",
		Operation: operation,
		Kind:      provider.ErrorInternal,
		Message:   "failed to prepare provider request",
		Err:       err,
	}
}

var _ provider.LLM = (*Client)(nil)
var _ provider.Transcriber = (*Client)(nil)
var _ provider.Synthesizer = (*Client)(nil)
