package codex

import (
	"slices"
	"testing"
)

func TestSafeAppServerArgsContainAuditedBoundary(t *testing.T) {
	args := SafeAppServerArgs()
	if len(args) < 3 || !slices.Equal(args[:3], []string{
		"app-server", "--stdio", "--strict-config",
	}) {
		t.Fatalf("unsafe app-server prefix: %#v", args)
	}

	configs := map[string]bool{}
	disabled := map[string]bool{}
	for index := 3; index < len(args); {
		switch args[index] {
		case "-c":
			if index+1 >= len(args) {
				t.Fatal("dangling -c")
			}
			configs[args[index+1]] = true
			index += 2
		case "--disable":
			if index+1 >= len(args) {
				t.Fatal("dangling --disable")
			}
			disabled[args[index+1]] = true
			index += 2
		default:
			t.Fatalf("unexpected app-server argument %q", args[index])
		}
	}
	for _, setting := range []string{
		`web_search="disabled"`,
		"agents.enabled=false",
		"mcp_servers={}",
		`shell_environment_policy.inherit="none"`,
		"check_for_update_on_startup=false",
		`approval_policy="never"`,
		`sandbox_mode="read-only"`,
	} {
		if !configs[setting] {
			t.Errorf("missing safe config %q", setting)
		}
	}
	for _, feature := range []string{
		"shell_tool",
		"unified_exec",
		"shell_snapshot",
		"apps",
		"plugins",
		"remote_plugin",
		"multi_agent",
		"hooks",
		"goals",
		"browser_use",
		"browser_use_external",
		"browser_use_full_cdp_access",
		"in_app_browser",
		"computer_use",
		"image_generation",
		"workspace_dependencies",
		"skill_search",
		"skill_mcp_dependency_install",
		"tool_suggest",
		"code_mode_host",
	} {
		if !disabled[feature] {
			t.Errorf("feature %q is not disabled", feature)
		}
	}

	process := NewSafeProcessConfig("/absolute/codex", "/private/home", "/empty")
	if !slices.Equal(process.Args, args) {
		t.Fatal("safe process constructor did not use audited arguments")
	}
	args[0] = "mutated"
	if SafeAppServerArgs()[0] != "app-server" {
		t.Fatal("SafeAppServerArgs returned shared mutable state")
	}
}

func TestCancelledRequestIDsAreBounded(t *testing.T) {
	client := &RPCClient{
		pending: make(map[string]chan callResult),
		ignored: make(map[string]struct{}),
	}
	for index := 0; index < maxIgnoredRequestIDs+500; index++ {
		key := string(rune(index + 1))
		client.pending[key] = make(chan callResult, 1)
		client.removePending(key, true)
	}
	if len(client.ignored) != maxIgnoredRequestIDs {
		t.Fatalf("ignored request IDs grew to %d", len(client.ignored))
	}
	if len(client.ignoredOrder) != maxIgnoredRequestIDs {
		t.Fatalf("ignored request order grew to %d", len(client.ignoredOrder))
	}
}
