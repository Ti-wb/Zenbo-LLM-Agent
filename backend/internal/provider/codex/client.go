package codex

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

const safeDeveloperInstructions = `You are the conversational agent for a Zenbo robot.
Never run commands, read or write files, use MCP, install plugins, invoke skills, request permissions, or ask the app-server client for input.
The only permitted actions are returning a final response or requesting one of the explicitly described device tools.
Return exactly one JSON object matching the supplied output schema, with no Markdown fencing or additional text.`

// Config configures the high-level Codex provider.
type Config struct {
	Process               ProcessConfig
	Model                 string
	WorkingDirectory      string
	DeveloperInstructions string
	InterruptTimeout      time.Duration
}

// Client implements one-step LLM orchestration and account management over a
// supervised Codex app-server process. Operations are serialized because the
// stable notification stream is connection-scoped.
type Client struct {
	supervisor       *Supervisor
	model            string
	workingDirectory string
	instructions     string
	interruptTimeout time.Duration
	operationMu      sync.Mutex
}

// New validates config. WorkingDirectory must be a dedicated absolute,
// read-only-at-runtime directory prepared by deployment.
func New(config Config) (*Client, error) {
	workingDirectory := firstNonEmpty(config.WorkingDirectory, config.Process.WorkingDir)
	if workingDirectory == "" || !filepath.IsAbs(workingDirectory) {
		return nil, errors.New("Codex working directory must be an absolute path")
	}
	if strings.TrimSpace(config.Process.Binary) == "" {
		return nil, errors.New("Codex binary is required")
	}
	config.Process.WorkingDir = workingDirectory
	timeout := config.InterruptTimeout
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	instructions := safeDeveloperInstructions
	if strings.TrimSpace(config.DeveloperInstructions) != "" {
		instructions += "\n\n" + strings.TrimSpace(config.DeveloperInstructions)
	}
	return &Client{
		supervisor:       NewSupervisor(config.Process),
		model:            config.Model,
		workingDirectory: workingDirectory,
		instructions:     instructions,
		interruptTimeout: timeout,
	}, nil
}

// Step creates or continues one Codex thread and waits for the corresponding
// turn/completed notification.
func (c *Client) Step(
	ctx context.Context,
	request provider.StepRequest,
) (response provider.StepResponse, returnErr error) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()

	if len(request.Messages) == 0 {
		return provider.StepResponse{}, invalid("step", "at least one message is required")
	}
	if err := validateTools(request.Tools); err != nil {
		return provider.StepResponse{}, invalid("step", err.Error())
	}
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return provider.StepResponse{}, normalizeTransport("step", err)
	}
	threadID := request.ThreadID
	isNewThread := threadID == ""
	newThreadStarted := false
	if isNewThread {
		threadID, err = c.startThread(ctx, rpc, request)
		if err != nil {
			return provider.StepResponse{}, err
		}
		newThreadStarted = true
		defer func() {
			if returnErr == nil || !newThreadStarted {
				return
			}
			// The Gateway cannot persist a thread id that is only known inside
			// a failed provider Step. Use a fresh uncancelled cleanup window;
			// an existing externally supplied thread is never deleted here.
			cleanupContext, cancel := context.WithTimeout(
				context.WithoutCancel(ctx),
				c.interruptTimeout,
			)
			defer cancel()
			cleanupRPC, cleanupErr := c.supervisor.Client(cleanupContext)
			if cleanupErr == nil {
				_ = c.deleteThreadRPC(cleanupContext, cleanupRPC, threadID)
			}
		}()
	} else if err := c.resumeThread(ctx, rpc, request); err != nil {
		return provider.StepResponse{}, err
	}

	outputSchema, err := actionOutputSchema(request.Tools)
	if err != nil {
		return provider.StepResponse{}, invalid("step", err.Error())
	}
	input := buildInput(request.Messages, isNewThread)
	var started struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	err = rpc.Call(ctx, "turn/start", map[string]any{
		"threadId":     threadID,
		"input":        []any{map[string]any{"type": "text", "text": input}},
		"outputSchema": json.RawMessage(outputSchema),
	}, &started)
	if err != nil {
		return provider.StepResponse{}, normalizeTransport("turn/start", err)
	}
	if started.Turn.ID == "" {
		return provider.StepResponse{}, malformed("turn/start response did not include a turn id", nil)
	}
	return c.waitForTurn(ctx, rpc, request.Tools, threadID, started.Turn.ID)
}

func (c *Client) startThread(ctx context.Context, rpc *RPCClient, request provider.StepRequest) (string, error) {
	model := firstNonEmpty(request.Model, c.model)
	params := map[string]any{
		"cwd":                   c.workingDirectory,
		"approvalPolicy":        "never",
		"approvalsReviewer":     "user",
		"sandbox":               "read-only",
		"ephemeral":             false,
		"developerInstructions": c.threadInstructions(request),
	}
	if model != "" {
		params["model"] = model
	}
	var response struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err := rpc.Call(ctx, "thread/start", params, &response); err != nil {
		return "", normalizeTransport("thread/start", err)
	}
	if response.Thread.ID == "" {
		return "", malformed("thread/start response did not include a thread id", nil)
	}
	return response.Thread.ID, nil
}

// resumeThread is intentionally called for every externally supplied
// ThreadID. A newly supervised app-server process has no in-memory loaded
// threads, and thread/resume safely restores the durable thread from the
// dedicated CODEX_HOME while reasserting the gateway's safety policy.
func (c *Client) resumeThread(ctx context.Context, rpc *RPCClient, request provider.StepRequest) error {
	params := map[string]any{
		"threadId":              request.ThreadID,
		"cwd":                   c.workingDirectory,
		"approvalPolicy":        "never",
		"approvalsReviewer":     "user",
		"sandbox":               "read-only",
		"developerInstructions": c.threadInstructions(request),
	}
	if model := firstNonEmpty(request.Model, c.model); model != "" {
		params["model"] = model
	}
	var response struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err := rpc.Call(ctx, "thread/resume", params, &response); err != nil {
		return normalizeTransport("thread/resume", err)
	}
	if response.Thread.ID != request.ThreadID {
		return malformed("thread/resume returned a mismatched thread id", nil)
	}
	return nil
}

func (c *Client) threadInstructions(request provider.StepRequest) string {
	var result strings.Builder
	result.WriteString(c.instructions)
	if strings.TrimSpace(request.SystemPrompt) != "" {
		result.WriteString("\n\nConversation instructions:\n")
		result.WriteString(strings.TrimSpace(request.SystemPrompt))
	}
	if len(request.Tools) > 0 {
		result.WriteString("\n\nAvailable device tools (these are descriptions only; do not invoke app-server tools):\n")
		for _, tool := range request.Tools {
			result.WriteString("- ")
			result.WriteString(tool.Name)
			if tool.Description != "" {
				result.WriteString(": ")
				result.WriteString(tool.Description)
			}
			result.WriteString("\n  arguments schema: ")
			result.Write(tool.Parameters)
			result.WriteByte('\n')
		}
	}
	return result.String()
}

func (c *Client) waitForTurn(
	ctx context.Context,
	rpc *RPCClient,
	tools []provider.ToolDefinition,
	threadID string,
	turnID string,
) (provider.StepResponse, error) {
	var (
		finalText string
		itemID    string
	)
	for {
		select {
		case notification, ok := <-rpc.Notifications():
			if !ok {
				return provider.StepResponse{}, normalizeTransport("step", rpc.Err())
			}
			switch notification.Method {
			case "item/completed":
				var completed itemCompleted
				if json.Unmarshal(notification.Params, &completed) == nil &&
					completed.ThreadID == threadID && completed.TurnID == turnID &&
					completed.Item.Type == "agentMessage" {
					finalText = completed.Item.Text
					itemID = completed.Item.ID
				}
			case "turn/completed":
				var completed turnCompleted
				if err := json.Unmarshal(notification.Params, &completed); err != nil {
					return provider.StepResponse{}, malformed("invalid turn/completed notification", err)
				}
				if completed.ThreadID != threadID || completed.Turn.ID != turnID {
					continue
				}
				if finalText == "" {
					for _, item := range completed.Turn.Items {
						if item.Type == "agentMessage" {
							finalText = item.Text
							itemID = item.ID
						}
					}
				}
				if completed.Turn.Status != "completed" {
					message := firstNonEmpty(completed.Turn.Error.Message, "Codex turn did not complete")
					return provider.StepResponse{}, &provider.Error{
						Provider:  "codex",
						Operation: "step",
						Kind:      provider.ErrorUnavailable,
						Message:   message,
					}
				}
				result, err := parseAction(finalText, tools)
				if err != nil {
					return provider.StepResponse{}, err
				}
				result.ThreadID = threadID
				result.TurnID = turnID
				result.ResponseID = itemID
				return result, nil
			}
		case <-ctx.Done():
			interruptCtx, cancel := context.WithTimeout(context.Background(), c.interruptTimeout)
			_ = c.interruptRPC(interruptCtx, rpc, threadID, turnID)
			cancel()
			return provider.StepResponse{}, provider.FromContext("codex", "step", ctx.Err())
		case <-rpc.Done():
			return provider.StepResponse{}, normalizeTransport("step", rpc.Err())
		}
	}
}

// Interrupt explicitly interrupts an active Codex turn.
func (c *Client) Interrupt(ctx context.Context, threadID, turnID string) error {
	if threadID == "" || turnID == "" {
		return invalid("interrupt", "thread id and turn id are required")
	}
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return normalizeTransport("interrupt", err)
	}
	return c.interruptRPC(ctx, rpc, threadID, turnID)
}

// DeleteThread permanently deletes a Codex thread and its spawned descendants.
// The stable app-server contract treats a missing rollout as already deleted,
// so repeating this operation after a lost database acknowledgement is safe.
func (c *Client) DeleteThread(ctx context.Context, threadID string) error {
	if strings.TrimSpace(threadID) == "" {
		return invalid("thread/delete", "thread id is required")
	}
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return normalizeTransport("thread/delete", err)
	}
	return c.deleteThreadRPC(ctx, rpc, threadID)
}

func (c *Client) deleteThreadRPC(ctx context.Context, rpc *RPCClient, threadID string) error {
	err := rpc.Call(ctx, "thread/delete", map[string]any{"threadId": threadID}, nil)
	if err == nil {
		return nil
	}
	// The stable protocol uses -32600 when another app-server currently owns
	// the thread (or one of its descendants). That condition is transient and
	// must return to the durable retention queue rather than being discarded.
	var rpcErr *rpcFailure
	if errors.As(err, &rpcErr) && rpcErr.Code == -32600 {
		return &provider.Error{
			Provider:  "codex",
			Operation: "thread/delete",
			Kind:      provider.ErrorUnavailable,
			Message:   "Codex thread is currently owned by another app-server",
			Err:       err,
		}
	}
	return normalizeTransport("thread/delete", err)
}

func (c *Client) interruptRPC(ctx context.Context, rpc *RPCClient, threadID, turnID string) error {
	if err := rpc.Call(ctx, "turn/interrupt", map[string]any{
		"threadId": threadID,
		"turnId":   turnID,
	}, nil); err != nil {
		return normalizeTransport("interrupt", err)
	}
	return nil
}

// Close stops the supervised process without deleting the Codex credential
// store.
func (c *Client) Close() error { return c.supervisor.Close() }

type itemCompleted struct {
	ThreadID string `json:"threadId"`
	TurnID   string `json:"turnId"`
	Item     struct {
		ID   string `json:"id"`
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"item"`
}

type turnCompleted struct {
	ThreadID string `json:"threadId"`
	Turn     struct {
		ID     string `json:"id"`
		Status string `json:"status"`
		Error  struct {
			Message string `json:"message"`
		} `json:"error"`
		Items []struct {
			ID   string `json:"id"`
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"items"`
	} `json:"turn"`
}

type action struct {
	Action    string          `json:"action"`
	Text      string          `json:"text"`
	CallID    string          `json:"callId"`
	Name      string          `json:"name"`
	Arguments json.RawMessage `json:"arguments"`
}

func parseAction(text string, tools []provider.ToolDefinition) (provider.StepResponse, error) {
	if strings.TrimSpace(text) == "" {
		return provider.StepResponse{}, malformed("Codex completed without an agent message", nil)
	}
	var parsed action
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&parsed); err != nil {
		return provider.StepResponse{}, malformed("Codex returned invalid structured output", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return provider.StepResponse{}, malformed("Codex returned multiple structured values", nil)
	}
	switch parsed.Action {
	case "respond":
		if strings.TrimSpace(parsed.Text) == "" {
			return provider.StepResponse{}, malformed("Codex respond action had empty text", nil)
		}
		return provider.StepResponse{Text: parsed.Text}, nil
	case "call_tool":
		if parsed.CallID == "" || parsed.Name == "" || !isJSONObject(parsed.Arguments) {
			return provider.StepResponse{}, malformed("Codex returned a malformed tool call", nil)
		}
		allowed := false
		for _, tool := range tools {
			if tool.Name == parsed.Name {
				allowed = true
				break
			}
		}
		if !allowed {
			return provider.StepResponse{}, malformed("Codex requested an unregistered tool", nil)
		}
		return provider.StepResponse{ToolCalls: []provider.ToolCall{{
			ID:        parsed.CallID,
			Name:      parsed.Name,
			Arguments: append(json.RawMessage(nil), parsed.Arguments...),
		}}}, nil
	default:
		return provider.StepResponse{}, malformed("Codex returned an unknown action", nil)
	}
}

func actionOutputSchema(tools []provider.ToolDefinition) ([]byte, error) {
	actions := []string{"respond"}
	names := make([]string, 0, len(tools))
	argumentSchemas := make([]any, 0, len(tools)+1)
	for _, tool := range tools {
		names = append(names, tool.Name)
		argumentSchemas = append(argumentSchemas, json.RawMessage(tool.Parameters))
	}
	nameSchema := any(map[string]any{"type": "null"})
	argumentSchema := any(map[string]any{"type": "null"})
	if len(tools) > 0 {
		actions = append(actions, "call_tool")
		nameSchema = map[string]any{"anyOf": []any{
			map[string]any{"type": "string", "enum": names},
			map[string]any{"type": "null"},
		}}
		argumentSchemas = append(argumentSchemas, map[string]any{"type": "null"})
		argumentSchema = map[string]any{"anyOf": argumentSchemas}
	}
	// OpenAI Structured Outputs requires an object root and every property to
	// be required. Nullable non-applicable fields keep that shape stable; the
	// parser below enforces the action-specific cross-field invariants.
	return json.Marshal(map[string]any{
		"type":                 "object",
		"additionalProperties": false,
		"required":             []string{"action", "text", "callId", "name", "arguments"},
		"properties": map[string]any{
			"action":    map[string]any{"type": "string", "enum": actions},
			"text":      map[string]any{"type": []string{"string", "null"}},
			"callId":    map[string]any{"type": []string{"string", "null"}},
			"name":      nameSchema,
			"arguments": argumentSchema,
		},
	})
}

func buildInput(messages []provider.Message, includeAll bool) string {
	if !includeAll {
		messages = messages[len(messages)-1:]
	}
	var result strings.Builder
	for _, message := range messages {
		switch message.Role {
		case provider.RoleTool:
			fmt.Fprintf(&result, "Device tool result for call %s:\n%s\n", message.ToolCallID, message.Content)
		default:
			fmt.Fprintf(&result, "%s:\n%s\n", message.Role, message.Content)
		}
	}
	return strings.TrimSpace(result.String())
}

func validateTools(tools []provider.ToolDefinition) error {
	seen := make(map[string]struct{}, len(tools))
	for _, tool := range tools {
		if strings.TrimSpace(tool.Name) == "" {
			return errors.New("tool name is required")
		}
		if _, duplicate := seen[tool.Name]; duplicate {
			return fmt.Errorf("tool %q is duplicated", tool.Name)
		}
		seen[tool.Name] = struct{}{}
		if !isJSONObject(tool.Parameters) {
			return fmt.Errorf("tool %q parameters must be a JSON object", tool.Name)
		}
	}
	return nil
}

func isJSONObject(raw json.RawMessage) bool {
	raw = bytes.TrimSpace(raw)
	if len(raw) < 2 || raw[0] != '{' || raw[len(raw)-1] != '}' || !json.Valid(raw) {
		return false
	}
	var object map[string]json.RawMessage
	return json.Unmarshal(raw, &object) == nil
}

func invalid(operation, message string) error {
	return &provider.Error{Provider: "codex", Operation: operation, Kind: provider.ErrorInvalidRequest, Message: message}
}

func malformed(message string, err error) error {
	return &provider.Error{Provider: "codex", Operation: "step", Kind: provider.ErrorMalformed, Message: message, Err: err}
}

func normalizeTransport(operation string, err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return provider.FromContext("codex", operation, err)
	}
	kind := provider.ErrorUnavailable
	if errors.Is(err, ErrProtocol) {
		kind = provider.ErrorMalformed
	}
	var rpcErr *rpcFailure
	message := "Codex app-server request failed"
	if errors.As(err, &rpcErr) {
		switch rpcErr.Code {
		case -32600, -32601, -32602:
			kind = provider.ErrorInvalidRequest
		case -32001:
			kind = provider.ErrorRateLimited
		}
		message = rpcErr.Message
	}
	return &provider.Error{Provider: "codex", Operation: operation, Kind: kind, Message: message, Err: err}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

var _ provider.LLM = (*Client)(nil)
var _ provider.Interruptible = (*Client)(nil)
