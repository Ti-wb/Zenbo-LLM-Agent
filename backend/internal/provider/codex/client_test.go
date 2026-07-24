package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

func TestMain(main *testing.M) {
	if os.Getenv("GO_WANT_CODEX_SAFE_HELPER") == "1" {
		if !slices.Equal(os.Args[1:], SafeAppServerArgs()) {
			os.Exit(24)
		}
		runCodexHelperProcess()
	}
	os.Exit(main.Run())
}

func TestSafeAppServerArgsAcceptedByFakeProcess(t *testing.T) {
	process := NewSafeProcessConfig(os.Args[0], t.TempDir(), t.TempDir())
	process.Env = []string{
		"GO_WANT_CODEX_SAFE_HELPER=1",
		"CODEX_HELPER_MODE=account",
	}
	rpc, err := StartRPC(context.Background(), process)
	if err != nil {
		t.Fatal(err)
	}
	defer rpc.Close()
	var account json.RawMessage
	if err := rpc.Call(context.Background(), "account/read", map[string]any{
		"refreshToken": false,
	}, &account); err != nil {
		t.Fatal(err)
	}
}

func TestAppServerWithoutManagedRequirementsIsRejected(t *testing.T) {
	_, err := StartRPC(context.Background(), helperProcessConfig(t, "unsafe_requirements", ""))
	if err == nil || !strings.Contains(err.Error(), "system requirements") {
		t.Fatalf("expected missing requirements rejection, got %v", err)
	}
}

func TestStepRespondsAndUsesSafeThreadSettings(t *testing.T) {
	client := newHelperClient(t, "respond", "")
	defer client.Close()

	response, err := client.Step(context.Background(), provider.StepRequest{
		SessionID:    "session",
		SystemPrompt: "Speak Traditional Chinese.",
		Messages: []provider.Message{{
			Role:    provider.RoleUser,
			Content: "你好",
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.ThreadID != "thread-1" || response.TurnID != "turn-1" {
		t.Fatalf("response ids = %#v", response)
	}
	if response.Text != "哈囉" || len(response.ToolCalls) != 0 {
		t.Fatalf("response = %#v", response)
	}
}

func TestStepReturnsValidatedToolCall(t *testing.T) {
	client := newHelperClient(t, "tool", "")
	defer client.Close()

	response, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "看我"}},
		Tools: []provider.ToolDefinition{{
			Name:        "look_at_user",
			Description: "Turn toward the current user.",
			Parameters:  json.RawMessage(`{"type":"object","additionalProperties":false}`),
			Strict:      true,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.Text != "" || len(response.ToolCalls) != 1 {
		t.Fatalf("response = %#v", response)
	}
	call := response.ToolCalls[0]
	if call.ID != "opaque-call-id" || call.Name != "look_at_user" || string(call.Arguments) != `{}` {
		t.Fatalf("call = %#v", call)
	}
}

func TestRejectsUnexpectedServerRequest(t *testing.T) {
	client := newHelperClient(t, "server_request", "")
	defer client.Close()
	response, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.Text != "request rejected safely" {
		t.Fatalf("response = %#v", response)
	}
}

func TestExistingThreadIsResumedBeforeTurn(t *testing.T) {
	client := newHelperClient(t, "respond", "")
	defer client.Close()
	response, err := client.Step(context.Background(), provider.StepRequest{
		ThreadID: "thread-1",
		Messages: []provider.Message{{Role: provider.RoleTool, ToolCallID: "call", Content: `{"ok":true}`}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if response.ThreadID != "thread-1" || response.Text != "哈囉" {
		t.Fatalf("response = %#v", response)
	}
}

func TestEnsureThreadReturnsIDBeforeAnyTurnAndSurvivesAppServerExit(t *testing.T) {
	client := newHelperClient(t, "ensure_crash", "")
	defer client.Close()
	threadID, err := client.EnsureThread(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if threadID != "thread-1" {
		t.Fatalf("thread id = %q", threadID)
	}
}

func TestListThreadsEnforcesGatewayFiltersAndPagination(t *testing.T) {
	client := newHelperClient(t, "list_threads", "")
	defer client.Close()
	page, err := client.ListThreads(context.Background(), "", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Threads) != 1 || page.Threads[0].ID != "thread-orphan" ||
		page.Threads[0].CreatedAt.Location() != time.UTC || page.NextCursor != "page-2" {
		t.Fatalf("page = %#v", page)
	}
	page, err = client.ListThreads(context.Background(), page.NextCursor, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Threads) != 0 || page.NextCursor != "" {
		t.Fatalf("second page = %#v", page)
	}

	unsafe := newHelperClient(t, "list_threads_unsafe", "")
	defer unsafe.Close()
	_, err = unsafe.ListThreads(context.Background(), "", 10)
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("unsafe list error = %#v", err)
	}
	if _, err := client.ListThreads(context.Background(), "", 101); err == nil {
		t.Fatal("expected oversized page to fail")
	}
}

func TestContextCancellationInterruptsTurn(t *testing.T) {
	client := newHelperClient(t, "interrupt", "")
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	_, err := client.Step(ctx, provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "wait"}},
	})
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorTimeout {
		t.Fatalf("error = %#v", err)
	}
}

func TestReadinessDoesNotWaitBehindActiveTurn(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "turn-started")
	client := newHelperClient(t, "interrupt", marker)
	defer client.Close()
	turnContext, cancelTurn := context.WithCancel(context.Background())
	stepDone := make(chan error, 1)
	go func() {
		_, err := client.Step(turnContext, provider.StepRequest{
			Messages: []provider.Message{{Role: provider.RoleUser, Content: "wait"}},
		})
		stepDone <- err
	}()
	deadline := time.Now().Add(time.Second)
	for {
		if _, err := os.Stat(marker); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("turn did not start")
		}
		time.Sleep(time.Millisecond)
	}

	started := time.Now()
	readyContext, cancelReady := context.WithTimeout(context.Background(), 100*time.Millisecond)
	err := client.Ready(readyContext, "gpt-test")
	cancelReady()
	if err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > 50*time.Millisecond {
		t.Fatalf("readiness waited behind active turn for %s", elapsed)
	}

	cancelTurn()
	select {
	case <-stepDone:
	case <-time.After(time.Second):
		t.Fatal("active turn did not stop")
	}
}

func TestSupervisorRestartsAfterCrashWithoutRetryingFailedCall(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "crashed")
	client := newHelperClient(t, "crash_once", marker)
	defer client.Close()
	request := provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	}
	if _, err := client.Step(context.Background(), request); err == nil {
		t.Fatal("expected the first process to crash")
	}
	response, err := client.Step(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if response.Text != "recovered" {
		t.Fatalf("response = %#v", response)
	}
}

func TestFailedNewThreadIsDeletedButExistingThreadIsPreserved(t *testing.T) {
	newMarker := filepath.Join(t.TempDir(), "new-thread-deleted")
	client := newHelperClient(t, "turn_error", newMarker)
	_, err := client.Step(context.Background(), provider.StepRequest{
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	client.Close()
	if err == nil {
		t.Fatal("expected turn error")
	}
	if _, statErr := os.Stat(newMarker); statErr != nil {
		t.Fatalf("failed new thread was not deleted: %v", statErr)
	}

	existingMarker := filepath.Join(t.TempDir(), "existing-thread-deleted")
	existing := newHelperClient(t, "turn_error", existingMarker)
	_, err = existing.Step(context.Background(), provider.StepRequest{
		ThreadID: "thread-1",
		Messages: []provider.Message{{Role: provider.RoleUser, Content: "hello"}},
	})
	existing.Close()
	if err == nil {
		t.Fatal("expected turn error")
	}
	if _, statErr := os.Stat(existingMarker); !errors.Is(statErr, os.ErrNotExist) {
		t.Fatalf("existing thread was unexpectedly deleted: %v", statErr)
	}
}

func TestAccountDeviceLoginStatusAndLogout(t *testing.T) {
	client := newHelperClient(t, "account", "")
	defer client.Close()

	login, err := client.StartDeviceLogin(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if login.LoginID != "login-1" || login.UserCode != "ABCD-EFGH" {
		t.Fatalf("login = %#v", login)
	}
	result, err := client.WaitDeviceLogin(context.Background(), login.LoginID)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.LoginID != login.LoginID {
		t.Fatalf("result = %#v", result)
	}
	status, err := client.Account(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if !status.LoggedIn || status.Type != "chatgpt" || status.PlanType != "plus" {
		t.Fatalf("status = %#v", status)
	}
	if err := client.Logout(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestModelAvailabilityPagesAndBoundsCatalog(t *testing.T) {
	client := newHelperClient(t, "model_pages", "")
	defer client.Close()
	available, err := client.HasModel(context.Background(), "gpt-test")
	if err != nil {
		t.Fatal(err)
	}
	if !available {
		t.Fatal("expected configured model on the second page")
	}
	available, err = client.HasModel(context.Background(), "missing")
	if err != nil {
		t.Fatal(err)
	}
	if available {
		t.Fatal("unexpected missing model")
	}

	loop := newHelperClient(t, "model_loop", "")
	defer loop.Close()
	_, err = loop.HasModel(context.Background(), "missing")
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("cursor loop error = %#v", err)
	}
}

func TestDeleteThreadIsIdempotentAndRetriesOwnershipConflict(t *testing.T) {
	client := newHelperClient(t, "delete", "")
	defer client.Close()
	if err := client.DeleteThread(context.Background(), "thread-1"); err != nil {
		t.Fatal(err)
	}
	if err := client.DeleteThread(context.Background(), "thread-1"); err != nil {
		t.Fatalf("repeated delete should be idempotent: %v", err)
	}

	busy := newHelperClient(t, "delete_busy", "")
	defer busy.Close()
	err := busy.DeleteThread(context.Background(), "thread-1")
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorUnavailable {
		t.Fatalf("ownership conflict must be retryable: %#v", err)
	}
	if err := busy.DeleteThread(context.Background(), "thread-1"); err != nil {
		t.Fatalf("retry after ownership release failed: %v", err)
	}

	flood := newHelperClient(t, "delete_notification_flood", "")
	defer flood.Close()
	if err := flood.DeleteThread(context.Background(), "thread-1"); err != nil {
		t.Fatalf("irrelevant notification flood broke deletion: %v", err)
	}
}

func TestRPCRequestCorrelation(t *testing.T) {
	rpc, err := StartRPC(context.Background(), helperProcessConfig(t, "correlation", ""))
	if err != nil {
		t.Fatal(err)
	}
	defer rpc.Close()

	type result struct {
		Value string `json:"value"`
	}
	results := make(chan result, 2)
	failures := make(chan error, 2)
	for _, method := range []string{"test/first", "test/second"} {
		method := method
		go func() {
			var response result
			if err := rpc.Call(context.Background(), method, map[string]any{}, &response); err != nil {
				failures <- err
				return
			}
			results <- response
		}()
	}
	got := map[string]bool{}
	for range 2 {
		select {
		case err := <-failures:
			t.Fatal(err)
		case result := <-results:
			got[result.Value] = true
		}
	}
	if !got["test/first"] || !got["test/second"] {
		t.Fatalf("correlated results = %#v", got)
	}
}

func TestParseActionRejectsUnknownTool(t *testing.T) {
	_, err := parseAction(
		`{"action":"call_tool","callId":"not-a-uuid","name":"bad","arguments":{}}`,
		[]provider.ToolDefinition{{Name: "good", Parameters: json.RawMessage(`{"type":"object"}`)}},
	)
	var providerError *provider.Error
	if !errors.As(err, &providerError) || providerError.Kind != provider.ErrorMalformed {
		t.Fatalf("error = %#v", err)
	}
}

func TestCodexHelperProcess(t *testing.T) {
	if os.Getenv("GO_WANT_CODEX_HELPER") != "1" {
		return
	}
	runCodexHelperProcess()
}

func runCodexHelperProcess() {
	mode := os.Getenv("CODEX_HELPER_MODE")
	marker := os.Getenv("CODEX_HELPER_MARKER")
	scanner := bufio.NewScanner(os.Stdin)
	writer := bufio.NewWriter(os.Stdout)
	send := func(value any) {
		raw, _ := json.Marshal(value)
		fmt.Fprintln(writer, string(raw))
		writer.Flush()
	}
	sendResponse := func(id json.RawMessage, result any) {
		send(map[string]any{"id": id, "result": result})
	}
	var correlationID json.RawMessage
	var correlationMethod string
	deleteCalls := 0

	for scanner.Scan() {
		var request struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
			Error  struct {
				Code int `json:"code"`
			} `json:"error"`
		}
		if json.Unmarshal(scanner.Bytes(), &request) != nil {
			os.Exit(20)
		}
		if mode == "correlation" &&
			request.Method != "initialize" &&
			request.Method != "initialized" &&
			request.Method != "configRequirements/read" {
			if len(correlationID) == 0 {
				correlationID = append(json.RawMessage(nil), request.ID...)
				correlationMethod = request.Method
				continue
			}
			// Reply in reverse order to prove the client correlates by id.
			sendResponse(request.ID, map[string]any{"value": request.Method})
			sendResponse(correlationID, map[string]any{"value": correlationMethod})
			correlationID = nil
			correlationMethod = ""
			continue
		}
		switch request.Method {
		case "initialize":
			sendResponse(request.ID, map[string]any{
				"userAgent":      "fake-codex/0.145.0",
				"codexHome":      "/tmp/fake",
				"platformFamily": "unix",
				"platformOs":     "linux",
			})
		case "initialized":
			// Stable initialized notification intentionally has no params.
		case "configRequirements/read":
			if mode == "unsafe_requirements" {
				sendResponse(request.ID, map[string]any{"requirements": nil})
				continue
			}
			features := make(map[string]bool)
			for _, feature := range safeDisabledFeatures() {
				features[feature] = false
			}
			sendResponse(request.ID, map[string]any{
				"requirements": map[string]any{
					"allowManagedHooksOnly":   true,
					"allowRemoteControl":      false,
					"allowedApprovalPolicies": []string{"never"},
					"allowedSandboxModes":     []string{"read-only"},
					"allowedWebSearchModes":   []string{"disabled"},
					"featureRequirements":     features,
				},
			})
		case "thread/start":
			var params map[string]any
			json.Unmarshal(request.Params, &params)
			if params["approvalPolicy"] != "never" || params["sandbox"] != "read-only" ||
				params["cwd"] == "" || params["ephemeral"] != false {
				send(map[string]any{"id": request.ID, "error": map[string]any{"code": -32602, "message": "unsafe thread settings"}})
				continue
			}
			sendResponse(request.ID, map[string]any{
				"thread": map[string]any{"id": "thread-1"},
			})
			if mode == "ensure_crash" {
				os.Exit(0)
			}
		case "thread/resume":
			var params map[string]any
			json.Unmarshal(request.Params, &params)
			if params["threadId"] != "thread-1" || params["approvalPolicy"] != "never" ||
				params["sandbox"] != "read-only" {
				send(map[string]any{"id": request.ID, "error": map[string]any{"code": -32602, "message": "unsafe resume settings"}})
				continue
			}
			sendResponse(request.ID, map[string]any{
				"thread": map[string]any{"id": "thread-1"},
			})
		case "turn/start":
			if mode == "crash_once" {
				if _, err := os.Stat(marker); errors.Is(err, os.ErrNotExist) {
					os.WriteFile(marker, []byte("1"), 0o600)
					os.Exit(4)
				}
			}
			var params struct {
				ThreadID     string          `json:"threadId"`
				OutputSchema json.RawMessage `json:"outputSchema"`
			}
			json.Unmarshal(request.Params, &params)
			var schema map[string]any
			schemaErr := json.Unmarshal(params.OutputSchema, &schema)
			properties, _ := schema["properties"].(map[string]any)
			if params.ThreadID != "thread-1" || schemaErr != nil ||
				schema["type"] != "object" || schema["additionalProperties"] != false ||
				properties["action"] == nil {
				send(map[string]any{"id": request.ID, "error": map[string]any{"code": -32602, "message": "invalid turn params"}})
				continue
			}
			if mode == "turn_error" {
				send(map[string]any{"id": request.ID, "error": map[string]any{
					"code": -32603, "message": "injected turn failure",
				}})
				continue
			}
			sendResponse(request.ID, map[string]any{"turn": map[string]any{"id": "turn-1"}})
			if mode == "interrupt" && marker != "" {
				_ = os.WriteFile(marker, []byte("started"), 0o600)
			}
			if mode == "interrupt" {
				continue
			}
			if mode == "server_request" {
				send(map[string]any{
					"id":     "unsafe-1",
					"method": "item/commandExecution/requestApproval",
					"params": map[string]any{},
				})
				if !scanner.Scan() {
					os.Exit(21)
				}
				var rejection struct {
					ID    string `json:"id"`
					Error struct {
						Code int `json:"code"`
					} `json:"error"`
				}
				if json.Unmarshal(scanner.Bytes(), &rejection) != nil ||
					rejection.ID != "unsafe-1" || rejection.Error.Code != -32601 {
					os.Exit(22)
				}
			}
			text := `{"action":"respond","text":"哈囉","callId":null,"name":null,"arguments":null}`
			switch mode {
			case "tool":
				text = `{"action":"call_tool","text":null,"callId":"opaque-call-id","name":"look_at_user","arguments":{}}`
			case "server_request":
				text = `{"action":"respond","text":"request rejected safely","callId":null,"name":null,"arguments":null}`
			case "crash_once":
				text = `{"action":"respond","text":"recovered","callId":null,"name":null,"arguments":null}`
			}
			sendTurnCompleted(send, text)
		case "turn/interrupt":
			sendResponse(request.ID, map[string]any{})
			sendTurnCompleted(send, `{"action":"respond","text":"interrupted","callId":null,"name":null,"arguments":null}`)
		case "account/login/start":
			sendResponse(request.ID, map[string]any{
				"type":            "chatgptDeviceCode",
				"loginId":         "login-1",
				"verificationUrl": "https://example.test/device",
				"userCode":        "ABCD-EFGH",
			})
			send(map[string]any{
				"method": "account/login/completed",
				"params": map[string]any{"loginId": "login-1", "success": true},
			})
		case "account/read":
			sendResponse(request.ID, map[string]any{
				"requiresOpenaiAuth": true,
				"account": map[string]any{
					"type":     "chatgpt",
					"email":    "operator@example.test",
					"planType": "plus",
				},
			})
		case "model/list":
			var params struct {
				Cursor string `json:"cursor"`
			}
			json.Unmarshal(request.Params, &params)
			if mode == "model_loop" {
				sendResponse(request.ID, map[string]any{
					"data":       []any{map[string]any{"id": "other", "model": "other"}},
					"nextCursor": "repeat",
				})
				continue
			}
			if mode == "model_pages" && params.Cursor == "" {
				sendResponse(request.ID, map[string]any{
					"data":       []any{map[string]any{"id": "other", "model": "other"}},
					"nextCursor": "page-2",
				})
				continue
			}
			nextCursor := any(nil)
			sendResponse(request.ID, map[string]any{
				"data":       []any{map[string]any{"id": "gpt-test", "model": "gpt-test"}},
				"nextCursor": nextCursor,
			})
		case "account/logout", "account/login/cancel":
			sendResponse(request.ID, map[string]any{})
		case "thread/delete":
			var params struct {
				ThreadID string `json:"threadId"`
			}
			json.Unmarshal(request.Params, &params)
			if params.ThreadID != "thread-1" {
				send(map[string]any{"id": request.ID, "error": map[string]any{
					"code": -32602, "message": "invalid thread id",
				}})
				continue
			}
			deleteCalls++
			if marker != "" {
				_ = os.WriteFile(marker, []byte("deleted"), 0o600)
			}
			if mode == "delete_busy" && deleteCalls == 1 {
				send(map[string]any{"id": request.ID, "error": map[string]any{
					"code": -32600, "message": "thread owned by another app-server",
				}})
				continue
			}
			if mode == "delete_notification_flood" {
				for index := 0; index < defaultNotificationBuffer*4; index++ {
					send(map[string]any{
						"method": "thread/status/changed",
						"params": map[string]any{"threadId": "thread-1"},
					})
				}
			}
			// Repeated calls succeed to mirror app-server's "missing rollout
			// means already deleted" contract.
			sendResponse(request.ID, map[string]any{})
		case "thread/list":
			var params struct {
				SourceKinds   []string `json:"sourceKinds"`
				CWD           string   `json:"cwd"`
				Archived      bool     `json:"archived"`
				Cursor        string   `json:"cursor"`
				Limit         int      `json:"limit"`
				UseStateDB    bool     `json:"useStateDbOnly"`
				SortKey       string   `json:"sortKey"`
				SortDirection string   `json:"sortDirection"`
			}
			json.Unmarshal(request.Params, &params)
			if !slices.Equal(params.SourceKinds, []string{"appServer"}) ||
				params.CWD == "" || params.Archived || params.Limit != 10 ||
				params.UseStateDB || params.SortKey != "created_at" ||
				params.SortDirection != "asc" {
				send(map[string]any{"id": request.ID, "error": map[string]any{
					"code": -32602, "message": "unsafe thread list filters",
				}})
				continue
			}
			if params.Cursor == "page-2" {
				sendResponse(request.ID, map[string]any{"data": []any{}, "nextCursor": nil})
				continue
			}
			cwd := params.CWD
			if mode == "list_threads_unsafe" {
				cwd += "/other"
			}
			sendResponse(request.ID, map[string]any{
				"data": []any{map[string]any{
					"id": "thread-orphan", "createdAt": 1000, "cwd": cwd,
					"ephemeral": false, "source": "appServer",
				}},
				"nextCursor": "page-2",
			})
		default:
			// A correlated error is not a client request.
			if len(request.ID) == 0 || request.Error.Code == 0 {
				os.Exit(23)
			}
		}
	}
	os.Exit(0)
}

func sendTurnCompleted(send func(any), text string) {
	item := map[string]any{
		"id":   "item-1",
		"type": "agentMessage",
		"text": text,
	}
	send(map[string]any{
		"method": "item/completed",
		"params": map[string]any{
			"threadId":      "thread-1",
			"turnId":        "turn-1",
			"completedAtMs": 1,
			"item":          item,
		},
	})
	send(map[string]any{
		"method": "turn/completed",
		"params": map[string]any{
			"threadId": "thread-1",
			"turn": map[string]any{
				"id":     "turn-1",
				"status": "completed",
				"items":  []any{item},
			},
		},
	})
}

func newHelperClient(t *testing.T, mode, marker string) *Client {
	t.Helper()
	client, err := New(Config{
		Process:          helperProcessConfig(t, mode, marker),
		WorkingDirectory: t.TempDir(),
		Model:            "gpt-test",
	})
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func helperProcessConfig(t *testing.T, mode, marker string) ProcessConfig {
	t.Helper()
	return ProcessConfig{
		Binary:     os.Args[0],
		Args:       []string{"-test.run=TestCodexHelperProcess"},
		Env:        []string{"GO_WANT_CODEX_HELPER=1", "CODEX_HELPER_MODE=" + mode, "CODEX_HELPER_MARKER=" + marker},
		CodexHome:  t.TempDir(),
		WorkingDir: t.TempDir(),
	}
}

func TestSafeInstructionsForbidServerCapabilities(t *testing.T) {
	for _, forbidden := range []string{"run commands", "read or write files", "use MCP", "request permissions"} {
		if !strings.Contains(safeDeveloperInstructions, forbidden) {
			t.Errorf("safe instructions missing %q", forbidden)
		}
	}
}
