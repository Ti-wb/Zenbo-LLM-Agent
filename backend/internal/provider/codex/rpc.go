// Package codex implements a narrowly scoped Codex app-server client. It uses
// the stable JSONL-over-stdio transport from Codex CLI 0.145.0.
package codex

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	// ErrClosed indicates an intentional client shutdown.
	ErrClosed = errors.New("codex app-server client closed")
	// ErrProcessExited indicates the app-server stopped before the client.
	ErrProcessExited = errors.New("codex app-server process exited")
	// ErrProtocol indicates malformed or unsafe app-server protocol data.
	ErrProtocol = errors.New("codex app-server protocol error")
)

const (
	defaultQueueSize          = 64
	defaultNotificationBuffer = 256
	defaultMaxLineBytes       = 8 << 20
	defaultStartupTimeout     = 15 * time.Second
	maxIgnoredRequestIDs      = 1024
)

// ProcessConfig controls the supervised Codex app-server process. Args
// defaults to SafeAppServerArgs. Environment is deny-by-default so
// unrelated provider credentials cannot leak into Codex; explicitly set
// InheritEnvironment only when a controlled deployment requires it. Env
// entries override inherited values. No shell is involved.
type ProcessConfig struct {
	Binary             string
	Args               []string
	Env                []string
	InheritEnvironment bool
	CodexHome          string
	WorkingDir         string
	QueueSize          int
	NotificationBuffer int
	MaxLineBytes       int
	StartupTimeout     time.Duration
}

// Notification is a server-to-client JSONL notification.
type Notification struct {
	Method string
	Params json.RawMessage
}

type rpcFailure struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *rpcFailure) Error() string {
	if e == nil {
		return "<nil>"
	}
	return fmt.Sprintf("codex app-server error %d: %s", e.Code, e.Message)
}

type callResult struct {
	result json.RawMessage
	err    error
}

// RPCClient owns one initialized Codex app-server process.
type RPCClient struct {
	config        ProcessConfig
	command       *exec.Cmd
	stdin         io.WriteCloser
	writes        chan []byte
	notifications chan Notification
	done          chan struct{}

	nextID  atomic.Int64
	once    sync.Once
	mu      sync.Mutex
	pending map[string]chan callResult
	ignored map[string]struct{}
	// ignoredOrder caps cancelled request IDs whose server response may never
	// arrive. If a response arrives after eviction, the transport is closed as
	// an unknown-ID protocol violation instead of retaining memory forever.
	ignoredOrder []string
	err          error
}

// StartRPC starts app-server, performs initialize, then sends initialized.
func StartRPC(ctx context.Context, config ProcessConfig) (*RPCClient, error) {
	if strings.TrimSpace(config.Binary) == "" {
		return nil, errors.New("codex binary is required")
	}
	if len(config.Args) == 0 {
		config.Args = SafeAppServerArgs()
	}
	if config.QueueSize <= 0 {
		config.QueueSize = defaultQueueSize
	}
	if config.NotificationBuffer <= 0 {
		config.NotificationBuffer = defaultNotificationBuffer
	}
	if config.MaxLineBytes <= 0 {
		config.MaxLineBytes = defaultMaxLineBytes
	}
	if config.StartupTimeout <= 0 {
		config.StartupTimeout = defaultStartupTimeout
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	command := exec.Command(config.Binary, config.Args...)
	command.Dir = config.WorkingDir
	baseEnvironment := []string(nil)
	if config.InheritEnvironment {
		baseEnvironment = os.Environ()
	}
	command.Env = mergeEnvironment(baseEnvironment, config.Env)
	if config.CodexHome != "" {
		command.Env = mergeEnvironment(command.Env, []string{"CODEX_HOME=" + config.CodexHome})
	}
	stdin, err := command.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("open codex stdin: %w", err)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("open codex stdout: %w", err)
	}
	stderr, err := command.StderrPipe()
	if err != nil {
		return nil, fmt.Errorf("open codex stderr: %w", err)
	}
	if err := command.Start(); err != nil {
		return nil, fmt.Errorf("start codex app-server: %w", err)
	}

	client := &RPCClient{
		config:        config,
		command:       command,
		stdin:         stdin,
		writes:        make(chan []byte, config.QueueSize),
		notifications: make(chan Notification, config.NotificationBuffer),
		done:          make(chan struct{}),
		pending:       make(map[string]chan callResult),
		ignored:       make(map[string]struct{}),
	}
	go client.writeLoop()
	go client.readLoop(stdout)
	// stderr may contain user or model data. Drain it to prevent process
	// deadlock, but deliberately do not log or retain it.
	go func() { _, _ = io.Copy(io.Discard, stderr) }()
	go func() {
		waitErr := command.Wait()
		if waitErr != nil {
			client.shutdown(fmt.Errorf("%w: %v", ErrProcessExited, waitErr), true)
		} else {
			client.shutdown(ErrProcessExited, false)
		}
	}()

	startupCtx, cancel := context.WithTimeout(ctx, config.StartupTimeout)
	defer cancel()
	var initialized struct {
		UserAgent string `json:"userAgent"`
	}
	err = client.Call(startupCtx, "initialize", map[string]any{
		"clientInfo": map[string]any{
			"name":    "zenbo-agent-gateway",
			"title":   "Zenbo Agent Gateway",
			"version": "1.0",
		},
		"capabilities": map[string]any{
			"experimentalApi": false,
		},
	}, &initialized)
	if err == nil {
		err = client.Notify(startupCtx, "initialized", nil)
	}
	if err == nil {
		err = verifyAppServerRequirements(startupCtx, client)
	}
	if err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("initialize codex app-server: %w", err)
	}
	return client, nil
}

func mergeEnvironment(base, overrides []string) []string {
	combined := append(append([]string(nil), base...), overrides...)
	seen := make(map[string]struct{}, len(combined))
	reversed := make([]string, 0, len(combined))
	for index := len(combined) - 1; index >= 0; index-- {
		entry := combined[index]
		key, _, ok := strings.Cut(entry, "=")
		if !ok || key == "" {
			continue
		}
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		reversed = append(reversed, entry)
	}
	result := make([]string, len(reversed))
	for index := range reversed {
		result[len(reversed)-1-index] = reversed[index]
	}
	return result
}

// Call sends one request and waits for its correlated response.
func (c *RPCClient) Call(ctx context.Context, method string, params any, result any) error {
	if strings.TrimSpace(method) == "" {
		return errors.New("RPC method is required")
	}
	id := c.nextID.Add(1)
	idRaw := json.RawMessage(strconv.FormatInt(id, 10))
	key := string(idRaw)
	response := make(chan callResult, 1)

	c.mu.Lock()
	if c.err != nil {
		err := c.err
		c.mu.Unlock()
		return err
	}
	c.pending[key] = response
	c.mu.Unlock()

	message := map[string]any{"id": id, "method": method}
	if params != nil {
		message["params"] = params
	}
	raw, err := json.Marshal(message)
	if err != nil {
		c.removePending(key, false)
		return err
	}
	if err := c.enqueue(ctx, raw); err != nil {
		c.removePending(key, false)
		return err
	}

	var completed callResult
	select {
	case completed = <-response:
	case <-ctx.Done():
		c.removePending(key, true)
		return ctx.Err()
	case <-c.done:
		// stdout may have delivered the correlated response immediately
		// before process exit. Prefer that completed response so callers can
		// persist externally-created identifiers such as a new thread id.
		select {
		case completed = <-response:
		default:
			c.removePending(key, false)
			return c.Err()
		}
	}
	if completed.err != nil {
		return completed.err
	}
	if result == nil || len(completed.result) == 0 || bytes.Equal(completed.result, []byte("null")) {
		return nil
	}
	if err := json.Unmarshal(completed.result, result); err != nil {
		return fmt.Errorf("%w: decode %s response: %v", ErrProtocol, method, err)
	}
	return nil
}

// Notify sends one client notification. Nil params are omitted, as required
// for the stable initialized notification.
func (c *RPCClient) Notify(ctx context.Context, method string, params any) error {
	message := map[string]any{"method": method}
	if params != nil {
		message["params"] = params
	}
	raw, err := json.Marshal(message)
	if err != nil {
		return err
	}
	return c.enqueue(ctx, raw)
}

// Notifications returns the bounded server notification stream. Consumers
// must also select on Done.
func (c *RPCClient) Notifications() <-chan Notification { return c.notifications }

// Done closes when the process exits, the protocol fails, or Close is called.
func (c *RPCClient) Done() <-chan struct{} { return c.done }

// Err returns the terminal transport error.
func (c *RPCClient) Err() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err == nil {
		return ErrClosed
	}
	return c.err
}

// Close terminates app-server. Credentials in CODEX_HOME are not removed.
func (c *RPCClient) Close() error {
	c.shutdown(ErrClosed, true)
	return nil
}

func (c *RPCClient) enqueue(ctx context.Context, raw []byte) error {
	select {
	case c.writes <- raw:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-c.done:
		return c.Err()
	}
}

func (c *RPCClient) writeLoop() {
	writer := bufio.NewWriter(c.stdin)
	for {
		select {
		case raw := <-c.writes:
			if _, err := writer.Write(raw); err != nil {
				c.shutdown(fmt.Errorf("%w: write request: %v", ErrProcessExited, err), true)
				return
			}
			if err := writer.WriteByte('\n'); err != nil {
				c.shutdown(fmt.Errorf("%w: write newline: %v", ErrProcessExited, err), true)
				return
			}
			if err := writer.Flush(); err != nil {
				c.shutdown(fmt.Errorf("%w: flush request: %v", ErrProcessExited, err), true)
				return
			}
		case <-c.done:
			return
		}
	}
}

func (c *RPCClient) readLoop(stdout io.Reader) {
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 64<<10), c.config.MaxLineBytes)
	for scanner.Scan() {
		line := append([]byte(nil), scanner.Bytes()...)
		if err := c.handleLine(line); err != nil {
			c.shutdown(err, true)
			return
		}
	}
	if err := scanner.Err(); err != nil {
		c.shutdown(fmt.Errorf("%w: read response: %v", ErrProtocol, err), true)
		return
	}
	c.shutdown(ErrProcessExited, false)
}

func (c *RPCClient) handleLine(line []byte) error {
	var envelope struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params json.RawMessage `json:"params"`
		Result json.RawMessage `json:"result"`
		Error  *rpcFailure     `json:"error"`
	}
	if len(bytes.TrimSpace(line)) == 0 {
		return nil
	}
	if err := json.Unmarshal(line, &envelope); err != nil {
		return fmt.Errorf("%w: invalid JSON line", ErrProtocol)
	}
	if len(envelope.ID) > 0 && envelope.Method != "" {
		// The gateway never grants app-server command, patch, permission,
		// input, MCP, dynamic-tool, token-refresh, or attestation authority.
		// Reject every server request, including methods added in future CLI
		// releases, with a correlated JSON-RPC error.
		rejection, _ := json.Marshal(map[string]any{
			"id": json.RawMessage(envelope.ID),
			"error": map[string]any{
				"code":    -32601,
				"message": "client does not permit server requests",
			},
		})
		select {
		case c.writes <- rejection:
			return nil
		default:
			return fmt.Errorf("%w: outbound queue full while rejecting server request", ErrProtocol)
		}
	}
	if len(envelope.ID) > 0 {
		key := string(envelope.ID)
		c.mu.Lock()
		waiter := c.pending[key]
		delete(c.pending, key)
		_, ignore := c.ignored[key]
		delete(c.ignored, key)
		c.mu.Unlock()
		if waiter == nil {
			if ignore {
				return nil
			}
			return fmt.Errorf("%w: response for unknown request id", ErrProtocol)
		}
		if envelope.Error != nil {
			waiter <- callResult{err: envelope.Error}
		} else if len(envelope.Result) > 0 {
			waiter <- callResult{result: append(json.RawMessage(nil), envelope.Result...)}
		} else {
			return fmt.Errorf("%w: response has neither result nor error", ErrProtocol)
		}
		return nil
	}
	if envelope.Method == "" {
		return fmt.Errorf("%w: message has neither id nor method", ErrProtocol)
	}
	if !consumedNotification(envelope.Method) {
		// The Gateway consumes only terminal model output and device-login
		// completion. App-server also emits lifecycle notifications for
		// thread/delete, thread/list, account/read, remote control and many
		// other RPCs. Retaining notifications with no consumer would
		// eventually fill the bounded queue during normal maintenance.
		return nil
	}
	notification := Notification{
		Method: envelope.Method,
		Params: append(json.RawMessage(nil), envelope.Params...),
	}
	select {
	case c.notifications <- notification:
		return nil
	default:
		return fmt.Errorf("%w: notification queue overflow", ErrProtocol)
	}
}

func consumedNotification(method string) bool {
	switch method {
	case "item/completed", "turn/completed", "account/login/completed":
		return true
	default:
		return false
	}
}

func (c *RPCClient) removePending(key string, ignoreLateResponse bool) {
	c.mu.Lock()
	_, existed := c.pending[key]
	delete(c.pending, key)
	if ignoreLateResponse && existed {
		c.ignored[key] = struct{}{}
		c.ignoredOrder = append(c.ignoredOrder, key)
		if len(c.ignoredOrder) > maxIgnoredRequestIDs {
			oldest := c.ignoredOrder[0]
			c.ignoredOrder = c.ignoredOrder[1:]
			delete(c.ignored, oldest)
		}
	}
	c.mu.Unlock()
}

func (c *RPCClient) shutdown(err error, kill bool) {
	c.once.Do(func() {
		c.mu.Lock()
		c.err = err
		pending := c.pending
		c.pending = make(map[string]chan callResult)
		c.ignored = make(map[string]struct{})
		c.ignoredOrder = nil
		c.mu.Unlock()

		close(c.done)
		for _, waiter := range pending {
			waiter <- callResult{err: err}
		}
		_ = c.stdin.Close()
		if kill && c.command.Process != nil {
			_ = c.command.Process.Kill()
		}
	})
}

// Supervisor lazily starts app-server and starts a fresh initialized process
// after a crash. It never retries a failed RPC automatically because the
// request may have taken effect before the process exited.
type Supervisor struct {
	config ProcessConfig
	mu     sync.Mutex
	client *RPCClient
	closed bool
}

func NewSupervisor(config ProcessConfig) *Supervisor {
	return &Supervisor{config: config}
}

func (s *Supervisor) Client(ctx context.Context) (*RPCClient, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, ErrClosed
	}
	if s.client != nil {
		select {
		case <-s.client.Done():
			s.client = nil
		default:
			return s.client, nil
		}
	}
	client, err := StartRPC(ctx, s.config)
	if err != nil {
		return nil, err
	}
	s.client = client
	return client, nil
}

func (s *Supervisor) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.client != nil {
		return s.client.Close()
	}
	return nil
}
