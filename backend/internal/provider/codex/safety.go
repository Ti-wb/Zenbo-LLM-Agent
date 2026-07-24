package codex

import (
	"context"
	"errors"
	"slices"
)

func safeDisabledFeatures() []string {
	return []string{
		"shell_tool",
		"unified_exec",
		"shell_snapshot",
		"apps",
		"enable_mcp_apps",
		"plugins",
		"plugin_sharing",
		"remote_plugin",
		"multi_agent",
		"multi_agent_v2",
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
		"code_mode",
		"code_mode_buffered_exec",
		"code_mode_host",
		"code_mode_only",
		"artifact",
		"auth_elicitation",
		"request_permissions_tool",
		"tool_call_mcp_elicitation",
		"standalone_web_search",
	}
}

// SafeAppServerArgs returns the complete, audited command line used for every
// production app-server process. Keep this list explicit: Codex features can
// otherwise be enabled by defaults or by CODEX_HOME/config.toml.
//
// A fresh slice is returned so callers cannot mutate the policy globally.
func SafeAppServerArgs() []string {
	args := []string{
		"app-server",
		"--stdio",
		"--strict-config",
		"-c", `web_search="disabled"`,
		"-c", "agents.enabled=false",
		"-c", "mcp_servers={}",
		"-c", `shell_environment_policy.inherit="none"`,
		"-c", "check_for_update_on_startup=false",
		"-c", "analytics.enabled=false",
		"-c", `approval_policy="never"`,
		"-c", `sandbox_mode="read-only"`,
	}
	for _, feature := range safeDisabledFeatures() {
		args = append(args, "--disable", feature)
	}
	return args
}

// verifyAppServerRequirements confirms that a system-managed
// requirements.toml was actually loaded. CLI overrides are defense in depth;
// only the system requirement can force-disable app-server remote control
// before user configuration is considered.
func verifyAppServerRequirements(ctx context.Context, client *RPCClient) error {
	var response struct {
		Requirements *struct {
			AllowManagedHooksOnly   *bool           `json:"allowManagedHooksOnly"`
			AllowRemoteControl      *bool           `json:"allowRemoteControl"`
			AllowedApprovalPolicies []string        `json:"allowedApprovalPolicies"`
			AllowedSandboxModes     []string        `json:"allowedSandboxModes"`
			AllowedWebSearchModes   []string        `json:"allowedWebSearchModes"`
			FeatureRequirements     map[string]bool `json:"featureRequirements"`
		} `json:"requirements"`
	}
	if err := client.Call(ctx, "configRequirements/read", nil, &response); err != nil {
		return err
	}
	requirements := response.Requirements
	if requirements == nil {
		return errors.New("Codex system requirements are not configured")
	}
	if requirements.AllowRemoteControl == nil || *requirements.AllowRemoteControl {
		return errors.New("Codex requirements must force-disable remote control")
	}
	if requirements.AllowManagedHooksOnly == nil || !*requirements.AllowManagedHooksOnly {
		return errors.New("Codex requirements must allow managed hooks only")
	}
	if !slices.Equal(requirements.AllowedApprovalPolicies, []string{"never"}) {
		return errors.New("Codex requirements must allow only the never approval policy")
	}
	if !slices.Equal(requirements.AllowedSandboxModes, []string{"read-only"}) {
		return errors.New("Codex requirements must allow only the read-only sandbox")
	}
	if !slices.Equal(requirements.AllowedWebSearchModes, []string{"disabled"}) {
		return errors.New("Codex requirements must allow only disabled web search")
	}
	for _, feature := range safeDisabledFeatures() {
		enabled, pinned := requirements.FeatureRequirements[feature]
		if !pinned || enabled {
			return errors.New("Codex requirements do not pin every unsafe feature off")
		}
	}
	return nil
}

// NewSafeProcessConfig constructs the only ProcessConfig shape production
// worker, readiness and OAuth paths should use.
func NewSafeProcessConfig(binary, codexHome, workingDirectory string) ProcessConfig {
	return ProcessConfig{
		Binary:     binary,
		Args:       SafeAppServerArgs(),
		CodexHome:  codexHome,
		WorkingDir: workingDirectory,
	}
}
