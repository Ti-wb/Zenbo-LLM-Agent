package codex

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider"
)

const (
	maxThreadListPageSize = 100
	maxThreadListCursor   = 4096
)

// EnsureThread creates the durable Codex thread before any model turn starts.
// The worker must persist the returned id before calling Step, closing the
// app-server-crash window where Step could otherwise lose a newly created id.
func (c *Client) EnsureThread(ctx context.Context, request provider.StepRequest) (string, error) {
	if len(request.Messages) == 0 {
		return "", invalid("thread/ensure", "at least one message is required")
	}
	if err := validateTools(request.Tools); err != nil {
		return "", invalid("thread/ensure", err.Error())
	}
	if strings.TrimSpace(request.ThreadID) != "" {
		return request.ThreadID, nil
	}

	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return "", normalizeTransport("thread/ensure", err)
	}
	return c.startThread(ctx, rpc, request)
}

// ListThreads pages stable thread/list with immutable Gateway ownership
// filters. Callers cannot broaden this to other Codex sessions or cwd values.
func (c *Client) ListThreads(
	ctx context.Context,
	cursor string,
	limit int,
) (provider.ThreadPage, error) {
	cursor = strings.TrimSpace(cursor)
	if len(cursor) > maxThreadListCursor {
		return provider.ThreadPage{}, invalid("thread/list", "cursor is too long")
	}
	if limit <= 0 || limit > maxThreadListPageSize {
		return provider.ThreadPage{}, invalid("thread/list", "limit must be between 1 and 100")
	}

	c.operationMu.Lock()
	defer c.operationMu.Unlock()
	rpc, err := c.supervisor.Client(ctx)
	if err != nil {
		return provider.ThreadPage{}, normalizeTransport("thread/list", err)
	}
	params := map[string]any{
		"sourceKinds":    []string{"appServer"},
		"cwd":            c.workingDirectory,
		"archived":       false,
		"limit":          limit,
		"useStateDbOnly": false,
		"sortKey":        "created_at",
		"sortDirection":  "asc",
	}
	if cursor != "" {
		params["cursor"] = cursor
	}
	var response struct {
		Data []struct {
			ID        string `json:"id"`
			CreatedAt int64  `json:"createdAt"`
			CWD       string `json:"cwd"`
			Ephemeral bool   `json:"ephemeral"`
			Source    string `json:"source"`
		} `json:"data"`
		NextCursor *string `json:"nextCursor"`
	}
	if err := rpc.Call(ctx, "thread/list", params, &response); err != nil {
		return provider.ThreadPage{}, normalizeTransport("thread/list", err)
	}
	if response.Data == nil || len(response.Data) > limit {
		return provider.ThreadPage{}, malformedThreadList("Codex returned an invalid thread page size")
	}
	threads := make([]provider.ThreadInfo, 0, len(response.Data))
	seen := make(map[string]struct{}, len(response.Data))
	for _, item := range response.Data {
		item.ID = strings.TrimSpace(item.ID)
		if item.ID == "" || item.CreatedAt <= 0 || item.CWD != c.workingDirectory ||
			item.Ephemeral || item.Source != "appServer" {
			return provider.ThreadPage{}, malformedThreadList("Codex returned a thread outside the Gateway ownership boundary")
		}
		if _, duplicate := seen[item.ID]; duplicate {
			return provider.ThreadPage{}, malformedThreadList("Codex returned a duplicate thread id")
		}
		seen[item.ID] = struct{}{}
		threads = append(threads, provider.ThreadInfo{
			ID:        item.ID,
			CreatedAt: time.Unix(item.CreatedAt, 0).UTC(),
		})
	}
	nextCursor := ""
	if response.NextCursor != nil {
		nextCursor = strings.TrimSpace(*response.NextCursor)
		if len(nextCursor) > maxThreadListCursor || nextCursor == cursor {
			return provider.ThreadPage{}, malformedThreadList("Codex returned an invalid thread pagination cursor")
		}
	}
	return provider.ThreadPage{Threads: threads, NextCursor: nextCursor}, nil
}

func malformedThreadList(message string) error {
	return &provider.Error{
		Provider:  "codex",
		Operation: "thread/list",
		Kind:      provider.ErrorMalformed,
		Message:   message,
		Err:       errors.New("invalid Codex thread catalog"),
	}
}

var _ provider.ThreadEnsurer = (*Client)(nil)
var _ provider.ThreadLister = (*Client)(nil)
