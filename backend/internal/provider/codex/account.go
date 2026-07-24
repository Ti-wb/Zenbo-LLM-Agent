package codex

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

const (
	modelListPageSize   = 100
	maxModelListPages   = 32
	maxModelListEntries = modelListPageSize * maxModelListPages
)

// DeviceLogin is the operator-facing portion of Codex device-code OAuth. It
// intentionally contains no access or refresh token.
type DeviceLogin struct {
	LoginID         string
	VerificationURL string
	UserCode        string
}

// LoginResult reports completion of a previously started device-code flow.
type LoginResult struct {
	LoginID string
	Success bool
	Error   string
}

// AccountStatus contains non-secret Codex account metadata.
type AccountStatus struct {
	RequiresOpenAIAuth bool
	LoggedIn           bool
	Type               string
	Email              string
	PlanType           string
}

// HasModel pages the stable model/list API and checks both the public model id
// and underlying model slug. Page and entry bounds prevent a malformed
// app-server from keeping readiness busy indefinitely.
func (c *Client) HasModel(ctx context.Context, model string) (bool, error) {
	model = strings.TrimSpace(model)
	if model == "" {
		return false, invalid("model/list", "model is required")
	}

	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	return c.hasModel(ctx, model)
}

func (c *Client) hasModel(ctx context.Context, model string) (bool, error) {
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return false, normalizeTransport("model/list", err)
	}

	cursor := ""
	seenCursors := make(map[string]struct{})
	total := 0
	for page := 0; page < maxModelListPages; page++ {
		params := map[string]any{
			"includeHidden": true,
			"limit":         modelListPageSize,
		}
		if cursor != "" {
			params["cursor"] = cursor
		}
		var response struct {
			Data []struct {
				ID    string `json:"id"`
				Model string `json:"model"`
			} `json:"data"`
			NextCursor *string `json:"nextCursor"`
		}
		if err := rpc.Call(ctx, "model/list", params, &response); err != nil {
			return false, normalizeTransport("model/list", err)
		}
		total += len(response.Data)
		if total > maxModelListEntries {
			return false, malformedModelList("Codex model catalog exceeded the bounded entry limit")
		}
		found := false
		for _, item := range response.Data {
			item.ID = strings.TrimSpace(item.ID)
			item.Model = strings.TrimSpace(item.Model)
			if item.ID == "" || item.Model == "" {
				return false, malformedModelList("Codex model catalog contained an invalid entry")
			}
			if item.ID == model || item.Model == model {
				found = true
			}
		}
		if found {
			return true, nil
		}
		if response.NextCursor == nil || strings.TrimSpace(*response.NextCursor) == "" {
			return false, nil
		}
		cursor = strings.TrimSpace(*response.NextCursor)
		if _, duplicate := seenCursors[cursor]; duplicate {
			return false, malformedModelList("Codex model catalog repeated a pagination cursor")
		}
		seenCursors[cursor] = struct{}{}
	}
	return false, malformedModelList("Codex model catalog exceeded the bounded page limit")
}

func malformedModelList(message string) error {
	return &provider.Error{
		Provider:  "codex",
		Operation: "model/list",
		Kind:      provider.ErrorMalformed,
		Message:   message,
	}
}

// StartDeviceLogin starts the stable chatgptDeviceCode account flow.
func (c *Client) StartDeviceLogin(ctx context.Context) (DeviceLogin, error) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return DeviceLogin{}, normalizeTransport("account/login/start", err)
	}
	var response struct {
		Type            string `json:"type"`
		LoginID         string `json:"loginId"`
		VerificationURL string `json:"verificationUrl"`
		UserCode        string `json:"userCode"`
	}
	if err := rpc.Call(ctx, "account/login/start", map[string]any{
		"type": "chatgptDeviceCode",
	}, &response); err != nil {
		return DeviceLogin{}, normalizeTransport("account/login/start", err)
	}
	if response.Type != "chatgptDeviceCode" || response.LoginID == "" ||
		response.VerificationURL == "" || response.UserCode == "" {
		return DeviceLogin{}, &provider.Error{
			Provider:  "codex",
			Operation: "account/login/start",
			Kind:      provider.ErrorMalformed,
			Message:   "Codex returned an invalid device login response",
		}
	}
	return DeviceLogin{
		LoginID:         response.LoginID,
		VerificationURL: response.VerificationURL,
		UserCode:        response.UserCode,
	}, nil
}

// WaitDeviceLogin holds the app-server process open until the matching
// account/login/completed notification arrives.
func (c *Client) WaitDeviceLogin(ctx context.Context, loginID string) (LoginResult, error) {
	if strings.TrimSpace(loginID) == "" {
		return LoginResult{}, invalid("account/login/wait", "login id is required")
	}
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return LoginResult{}, normalizeTransport("account/login/wait", err)
	}
	for {
		select {
		case notification, ok := <-rpc.Notifications():
			if !ok {
				return LoginResult{}, normalizeTransport("account/login/wait", rpc.Err())
			}
			if notification.Method != "account/login/completed" {
				continue
			}
			var result LoginResult
			if err := json.Unmarshal(notification.Params, &result); err != nil {
				return LoginResult{}, &provider.Error{
					Provider:  "codex",
					Operation: "account/login/wait",
					Kind:      provider.ErrorMalformed,
					Message:   "Codex returned an invalid login notification",
					Err:       err,
				}
			}
			if result.LoginID != "" && result.LoginID != loginID {
				continue
			}
			result.LoginID = loginID
			return result, nil
		case <-ctx.Done():
			return LoginResult{}, provider.FromContext("codex", "account/login/wait", ctx.Err())
		case <-rpc.Done():
			return LoginResult{}, normalizeTransport("account/login/wait", rpc.Err())
		}
	}
}

// CancelDeviceLogin cancels a pending device-code flow.
func (c *Client) CancelDeviceLogin(ctx context.Context, loginID string) error {
	if strings.TrimSpace(loginID) == "" {
		return invalid("account/login/cancel", "login id is required")
	}
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return normalizeTransport("account/login/cancel", err)
	}
	if err := rpc.Call(ctx, "account/login/cancel", map[string]any{"loginId": loginID}, nil); err != nil {
		return normalizeTransport("account/login/cancel", err)
	}
	return nil
}

// Account reads non-secret login status. refresh requests a proactive
// Codex-managed token refresh before returning.
func (c *Client) Account(ctx context.Context, refresh bool) (AccountStatus, error) {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	return c.account(ctx, refresh)
}

func (c *Client) account(ctx context.Context, refresh bool) (AccountStatus, error) {
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return AccountStatus{}, normalizeTransport("account/read", err)
	}
	var response struct {
		Account            json.RawMessage `json:"account"`
		RequiresOpenAIAuth bool            `json:"requiresOpenaiAuth"`
	}
	if err := rpc.Call(ctx, "account/read", map[string]any{"refreshToken": refresh}, &response); err != nil {
		return AccountStatus{}, normalizeTransport("account/read", err)
	}
	status := AccountStatus{RequiresOpenAIAuth: response.RequiresOpenAIAuth}
	if len(response.Account) == 0 || string(response.Account) == "null" {
		return status, nil
	}
	var account struct {
		Type     string `json:"type"`
		Email    string `json:"email"`
		PlanType string `json:"planType"`
	}
	if err := json.Unmarshal(response.Account, &account); err != nil || account.Type == "" {
		return AccountStatus{}, &provider.Error{
			Provider:  "codex",
			Operation: "account/read",
			Kind:      provider.ErrorMalformed,
			Message:   "Codex returned invalid account metadata",
			Err:       err,
		}
	}
	status.LoggedIn = true
	status.Type = account.Type
	status.Email = account.Email
	status.PlanType = account.PlanType
	return status, nil
}

// Ready performs a bounded account and model check when the shared app-server
// is idle. If a safe provider operation currently owns the single supervisor,
// readiness returns immediately: startup readiness is always checked before
// workers accept jobs, and a live operation must not be blocked or marked
// unhealthy merely because its serialized turn lasts longer than the probe.
func (c *Client) Ready(ctx context.Context, model string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !c.operationMu.TryLock() {
		return nil
	}
	defer c.operationMu.Unlock()

	status, err := c.account(ctx, false)
	if err != nil {
		return err
	}
	if status.RequiresOpenAIAuth && !status.LoggedIn {
		return errors.New("Codex OAuth login is required")
	}
	available, err := c.hasModel(ctx, strings.TrimSpace(model))
	if err != nil {
		return err
	}
	if !available {
		return errors.New("configured Codex model is unavailable")
	}
	return nil
}

// Logout removes Codex-managed account credentials from the configured
// CODEX_HOME. It does not touch gateway database records.
func (c *Client) Logout(ctx context.Context) error {
	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return normalizeTransport("account/logout", err)
	}
	if err := rpc.Call(ctx, "account/logout", nil, nil); err != nil {
		return normalizeTransport("account/logout", err)
	}
	return nil
}
