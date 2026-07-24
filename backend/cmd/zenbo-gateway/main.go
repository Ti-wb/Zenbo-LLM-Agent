package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/config"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contractassets"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/migrations"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/provider/codex"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/providerbridge"
	gatewayruntime "github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/runtime"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/store"
)

var version = "dev"

type command struct {
	lookup   func(string) (string, bool)
	stdout   io.Writer
	stderr   io.Writer
	logger   *slog.Logger
	profiles func(string) (config.File, error)
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	exit := execute(ctx, os.Args[1:], command{
		lookup:   os.LookupEnv,
		stdout:   os.Stdout,
		stderr:   os.Stderr,
		profiles: config.Load,
	})
	os.Exit(exit)
}

func execute(ctx context.Context, args []string, runner command) int {
	if runner.lookup == nil {
		runner.lookup = os.LookupEnv
	}
	if runner.stdout == nil {
		runner.stdout = io.Discard
	}
	if runner.stderr == nil {
		runner.stderr = io.Discard
	}
	if runner.profiles == nil {
		runner.profiles = config.Load
	}
	if len(args) == 0 {
		usage(runner.stderr)
		return 2
	}
	switch args[0] {
	case "version", "--version", "-version":
		_, _ = fmt.Fprintln(runner.stdout, version)
		return 0
	case "help", "--help", "-h":
		usage(runner.stdout)
		return 0
	}
	runtimeConfig, err := config.FromEnvironment(runner.lookup)
	if err != nil {
		return fail(runner.stderr, err)
	}
	if runner.logger == nil {
		level := slog.LevelInfo
		switch runtimeConfig.LogLevel {
		case "debug":
			level = slog.LevelDebug
		case "warn":
			level = slog.LevelWarn
		case "error":
			level = slog.LevelError
		}
		runner.logger = slog.New(slog.NewJSONHandler(runner.stderr, &slog.HandlerOptions{Level: level}))
	}
	switch args[0] {
	case "serve":
		if len(args) != 1 {
			return usageError(runner.stderr, "serve does not accept arguments")
		}
		if err := runtimeConfig.ValidateForServe(); err != nil {
			return fail(runner.stderr, err)
		}
		profiles, err := runner.profiles(runtimeConfig.ProfilesFile)
		if err != nil {
			return fail(runner.stderr, err)
		}
		err = gatewayruntime.Serve(ctx, gatewayruntime.ServerOptions{
			Config: runtimeConfig, Profiles: profiles, Logger: runner.logger,
		})
		return fail(runner.stderr, err)
	case "worker":
		if len(args) != 1 {
			return usageError(runner.stderr, "worker does not accept arguments")
		}
		if err := runtimeConfig.ValidateForWorker(); err != nil {
			return fail(runner.stderr, err)
		}
		profiles, err := runner.profiles(runtimeConfig.ProfilesFile)
		if err != nil {
			return fail(runner.stderr, err)
		}
		return fail(runner.stderr, runWorker(ctx, runtimeConfig, profiles, runner.logger))
	case "migrate":
		if len(args) != 1 {
			return usageError(runner.stderr, "migrate does not accept arguments")
		}
		if err := runtimeConfig.ValidateForDatabase(); err != nil {
			return fail(runner.stderr, err)
		}
		return fail(runner.stderr, migrations.Up(ctx, runtimeConfig.DatabaseURL))
	case "device":
		return runDevice(ctx, args[1:], runtimeConfig, runner)
	case "codex":
		return runCodex(ctx, args[1:], runtimeConfig, runner)
	case "config":
		return runConfig(args[1:], runtimeConfig, runner)
	default:
		return usageError(runner.stderr, "unknown command "+args[0])
	}
}

func runDevice(ctx context.Context, args []string, runtimeConfig config.Runtime, runner command) int {
	if len(args) == 0 {
		return usageError(runner.stderr, "device requires issue or revoke")
	}
	if err := runtimeConfig.ValidateForDatabase(); err != nil {
		return fail(runner.stderr, err)
	}
	if len(runtimeConfig.DeviceHMACKey) < 32 {
		return fail(runner.stderr, errors.New("GATEWAY_DEVICE_HMAC_KEY must contain at least 32 bytes"))
	}
	repository, err := store.Open(ctx, runtimeConfig.DatabaseURL)
	if err != nil {
		return fail(runner.stderr, err)
	}
	defer repository.Close()
	authenticator, err := application.NewDeviceAuthenticator(repository, runtimeConfig.DeviceHMACKey, nil)
	if err != nil {
		return fail(runner.stderr, err)
	}

	switch args[0] {
	case "issue":
		flags := flag.NewFlagSet("device issue", flag.ContinueOnError)
		flags.SetOutput(runner.stderr)
		label := flags.String("label", "", "operator-facing device label")
		if err := flags.Parse(args[1:]); err != nil || flags.NArg() != 0 {
			return 2
		}
		device, token, err := authenticator.Issue(ctx, strings.TrimSpace(*label))
		if err != nil {
			return fail(runner.stderr, err)
		}
		// This is the only place the plaintext device token is emitted.
		return writeJSON(runner.stdout, map[string]any{
			"deviceRecordId": device.ID,
			"token":          token,
			"createdAt":      device.CreatedAt.UTC().Format(time.RFC3339Nano),
		})
	case "revoke":
		if len(args) != 2 || !contract.IsUUID(args[1]) {
			return usageError(runner.stderr, "device revoke requires one device record UUID")
		}
		if err := authenticator.Revoke(ctx, args[1]); err != nil {
			return fail(runner.stderr, err)
		}
		return writeJSON(runner.stdout, map[string]string{
			"deviceRecordId": args[1], "status": "revoked",
		})
	default:
		return usageError(runner.stderr, "unknown device command "+args[0])
	}
}

func runCodex(ctx context.Context, args []string, runtimeConfig config.Runtime, runner command) int {
	if len(args) == 0 {
		return usageError(runner.stderr, "codex requires login, status or logout")
	}
	flags := flag.NewFlagSet("codex "+args[0], flag.ContinueOnError)
	flags.SetOutput(runner.stderr)
	profileID := flags.String("profile", "default", "Codex profile id")
	if err := flags.Parse(args[1:]); err != nil || flags.NArg() != 0 {
		return 2
	}
	profiles, err := runner.profiles(runtimeConfig.ProfilesFile)
	if err != nil {
		return fail(runner.stderr, err)
	}
	profile, ok := profiles.Profile(*profileID)
	if !ok || profile.Kind != "codex" {
		return fail(runner.stderr, fmt.Errorf("profile %q is not a configured Codex profile", *profileID))
	}
	client, err := newCodexClient(runtimeConfig, profile)
	if err != nil {
		return fail(runner.stderr, err)
	}
	defer client.Close()

	switch args[0] {
	case "login":
		login, err := client.StartDeviceLogin(ctx)
		if err != nil {
			return fail(runner.stderr, err)
		}
		if err := writeJSON(runner.stdout, map[string]string{
			"loginId":         login.LoginID,
			"verificationUrl": login.VerificationURL,
			"userCode":        login.UserCode,
			"status":          "pending",
		}); err != 0 {
			return err
		}
		result, waitErr := client.WaitDeviceLogin(ctx, login.LoginID)
		if waitErr != nil {
			cancelContext, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			_ = client.CancelDeviceLogin(cancelContext, login.LoginID)
			cancel()
			return fail(runner.stderr, waitErr)
		}
		if !result.Success {
			return fail(runner.stderr, errors.New("Codex OAuth login did not complete"))
		}
		return writeJSON(runner.stdout, map[string]string{"status": "logged_in"})
	case "status":
		status, err := client.Account(ctx, true)
		if err != nil {
			return fail(runner.stderr, err)
		}
		return writeJSON(runner.stdout, status)
	case "logout":
		if err := client.Logout(ctx); err != nil {
			return fail(runner.stderr, err)
		}
		return writeJSON(runner.stdout, map[string]string{"status": "logged_out"})
	default:
		return usageError(runner.stderr, "unknown codex command "+args[0])
	}
}

func newCodexClient(runtimeConfig config.Runtime, profile config.Profile) (*codex.Client, error) {
	if !filepath.IsAbs(runtimeConfig.CodexHome) || !filepath.IsAbs(runtimeConfig.CodexWorkingDir) {
		return nil, errors.New("GATEWAY_CODEX_HOME and GATEWAY_CODEX_CWD must be absolute paths")
	}
	if err := os.MkdirAll(runtimeConfig.CodexHome, 0o700); err != nil {
		return nil, fmt.Errorf("create CODEX_HOME: %w", err)
	}
	if err := os.MkdirAll(runtimeConfig.CodexWorkingDir, 0o550); err != nil {
		return nil, fmt.Errorf("create Codex working directory: %w", err)
	}
	if info, statErr := os.Stat(runtimeConfig.CodexWorkingDir); statErr != nil {
		return nil, fmt.Errorf("stat Codex working directory: %w", statErr)
	} else if info.Mode().Perm()&0o222 != 0 {
		if err := os.Chmod(runtimeConfig.CodexWorkingDir, 0o550); err != nil {
			return nil, fmt.Errorf("make Codex working directory read-only: %w", err)
		}
	}
	verifyContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := providerbridge.VerifyCodexBinary(verifyContext, runtimeConfig.CodexBinary); err != nil {
		return nil, err
	}
	if err := providerbridge.VerifyCodexWorkingDirectory(runtimeConfig.CodexWorkingDir); err != nil {
		return nil, err
	}
	if err := providerbridge.VerifyCodexHome(runtimeConfig.CodexHome); err != nil {
		return nil, err
	}
	process := codex.NewSafeProcessConfig(
		runtimeConfig.CodexBinary,
		runtimeConfig.CodexHome,
		runtimeConfig.CodexWorkingDir,
	)
	return codex.New(codex.Config{
		Process:          process,
		Model:            profile.LLM.Model,
		WorkingDirectory: runtimeConfig.CodexWorkingDir,
	})
}

func runConfig(args []string, runtimeConfig config.Runtime, runner command) int {
	if len(args) != 1 || args[0] != "check" {
		return usageError(runner.stderr, "config requires check")
	}
	if err := runtimeConfig.ValidateForWorker(); err != nil {
		return fail(runner.stderr, err)
	}
	profiles, err := runner.profiles(runtimeConfig.ProfilesFile)
	if err != nil {
		return fail(runner.stderr, err)
	}
	if missing := profiles.MissingCredentialVariables(runner.lookup); len(missing) > 0 {
		return fail(runner.stderr, fmt.Errorf("missing credential environment variables: %s", strings.Join(missing, ", ")))
	}
	if err := contractassets.Verify(); err != nil {
		return fail(runner.stderr, err)
	}
	for _, profile := range profiles.Profiles {
		if profile.Kind != "codex" {
			continue
		}
		if !filepath.IsAbs(runtimeConfig.CodexHome) || !filepath.IsAbs(runtimeConfig.CodexWorkingDir) {
			return fail(runner.stderr, errors.New("GATEWAY_CODEX_HOME and GATEWAY_CODEX_CWD must be absolute paths"))
		}
		verifyContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		err := providerbridge.VerifyCodexBinary(verifyContext, runtimeConfig.CodexBinary)
		cancel()
		if err != nil {
			return fail(runner.stderr, err)
		}
		if err := providerbridge.VerifyCodexWorkingDirectory(runtimeConfig.CodexWorkingDir); err != nil {
			return fail(runner.stderr, err)
		}
		if err := providerbridge.VerifyCodexHome(runtimeConfig.CodexHome); err != nil {
			return fail(runner.stderr, err)
		}
		break
	}
	return writeJSON(runner.stdout, map[string]any{
		"status":   "ok",
		"profiles": len(profiles.Profiles),
	})
}

func writeJSON(output io.Writer, value any) int {
	encoder := json.NewEncoder(output)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return 1
	}
	return 0
}

func fail(output io.Writer, err error) int {
	if err == nil {
		return 0
	}
	_, _ = fmt.Fprintln(output, "error:", err)
	return 1
}

func usageError(output io.Writer, message string) int {
	_, _ = fmt.Fprintln(output, "error:", message)
	usage(output)
	return 2
}

func usage(output io.Writer) {
	_, _ = fmt.Fprintln(output, `Usage: zenbo-gateway COMMAND

Commands:
  serve                         run the public API and internal health server
  worker                        process durable STT/LLM/tool/TTS jobs
  migrate                       apply embedded PostgreSQL migrations
  device issue [--label TEXT]   issue a one-time device bearer token
  device revoke UUID            revoke a device credential
  codex login [--profile ID]    complete Codex device-code OAuth
  codex status [--profile ID]   read non-secret Codex account status
  codex logout [--profile ID]   remove Codex-managed credentials
  config check                  validate profiles and credential references
  version                       print the binary version`)
}
