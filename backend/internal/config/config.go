// Package config loads and validates the gateway's environment and provider
// profile configuration. Provider credentials are never accepted as literal
// JSON values; profiles may only name an environment variable.
package config

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

const (
	DefaultHTTPAddress  = ":8080"
	DefaultAdminAddress = ":9090"
)

var (
	profileIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
	envNamePattern   = regexp.MustCompile(`^[A-Z_][A-Z0-9_]*$`)
)

// Runtime is process-level configuration shared by serve, worker and
// administrative commands.
type Runtime struct {
	DatabaseURL       string
	ProfilesFile      string
	ArtifactDirectory string
	HTTPAddress       string
	AdminAddress      string
	PublicBaseURL     string
	DeviceHMACKey     []byte
	CodexBinary       string
	CodexHome         string
	CodexWorkingDir   string
	WorkerID          string
	WorkerPoll        time.Duration
	WorkerLease       time.Duration
	ProviderTimeout   time.Duration
	LogLevel          string
}

// FromEnvironment reads process configuration. It does not require values
// used by only a subset of commands; command-specific validation does that.
func FromEnvironment(lookup func(string) (string, bool)) (Runtime, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	value := func(name, fallback string) string {
		if current, ok := lookup(name); ok && strings.TrimSpace(current) != "" {
			return strings.TrimSpace(current)
		}
		return fallback
	}
	duration := func(name string, fallback time.Duration) (time.Duration, error) {
		raw := value(name, "")
		if raw == "" {
			return fallback, nil
		}
		parsed, err := time.ParseDuration(raw)
		if err != nil || parsed <= 0 {
			return 0, fmt.Errorf("%s must be a positive Go duration", name)
		}
		return parsed, nil
	}

	poll, err := duration("GATEWAY_WORKER_POLL_INTERVAL", time.Second)
	if err != nil {
		return Runtime{}, err
	}
	lease, err := duration("GATEWAY_WORKER_LEASE", 10*time.Minute)
	if err != nil {
		return Runtime{}, err
	}
	providerTimeout, err := duration("GATEWAY_PROVIDER_TIMEOUT", 90*time.Second)
	if err != nil {
		return Runtime{}, err
	}

	var hmacKey []byte
	if raw := value("GATEWAY_DEVICE_HMAC_KEY", ""); raw != "" {
		hmacKey, err = decodeKey(raw)
		if err != nil {
			return Runtime{}, fmt.Errorf("GATEWAY_DEVICE_HMAC_KEY: %w", err)
		}
	}

	return Runtime{
		DatabaseURL:       value("GATEWAY_DATABASE_URL", ""),
		ProfilesFile:      value("GATEWAY_PROFILES_FILE", "/etc/zenbo-gateway/profiles.json"),
		ArtifactDirectory: value("GATEWAY_ARTIFACT_DIR", "/var/lib/zenbo-gateway/artifacts"),
		HTTPAddress:       value("GATEWAY_HTTP_ADDR", DefaultHTTPAddress),
		AdminAddress:      value("GATEWAY_ADMIN_ADDR", DefaultAdminAddress),
		PublicBaseURL:     value("GATEWAY_PUBLIC_BASE_URL", ""),
		DeviceHMACKey:     hmacKey,
		CodexBinary:       value("GATEWAY_CODEX_BINARY", "/usr/local/bin/codex"),
		CodexHome:         value("GATEWAY_CODEX_HOME", "/var/lib/zenbo-gateway/codex"),
		CodexWorkingDir:   value("GATEWAY_CODEX_CWD", "/var/empty/codex"),
		WorkerID:          value("GATEWAY_WORKER_ID", hostname()),
		WorkerPoll:        poll,
		WorkerLease:       lease,
		ProviderTimeout:   providerTimeout,
		LogLevel:          strings.ToLower(value("GATEWAY_LOG_LEVEL", "info")),
	}, nil
}

func hostname() string {
	value, err := os.Hostname()
	if err != nil || strings.TrimSpace(value) == "" {
		return "zenbo-worker"
	}
	return value
}

func decodeKey(raw string) ([]byte, error) {
	var decoded []byte
	var err error
	switch {
	case strings.HasPrefix(raw, "base64:"):
		decoded, err = base64.StdEncoding.DecodeString(strings.TrimPrefix(raw, "base64:"))
	case strings.HasPrefix(raw, "hex:"):
		decoded, err = hex.DecodeString(strings.TrimPrefix(raw, "hex:"))
	default:
		decoded = []byte(raw)
	}
	if err != nil {
		return nil, errors.New("invalid encoded key")
	}
	if len(decoded) < 32 {
		return nil, errors.New("must contain at least 32 bytes")
	}
	return decoded, nil
}

// ValidateForServe validates configuration needed by the public API.
func (runtime Runtime) ValidateForServe() error {
	if err := runtime.validateCommon(); err != nil {
		return err
	}
	if len(runtime.DeviceHMACKey) < 32 {
		return errors.New("GATEWAY_DEVICE_HMAC_KEY must contain at least 32 bytes")
	}
	return nil
}

// ValidateForWorker validates configuration needed by a worker process.
func (runtime Runtime) ValidateForWorker() error {
	if err := runtime.validateCommon(); err != nil {
		return err
	}
	if runtime.WorkerID == "" || runtime.WorkerPoll <= 0 || runtime.WorkerLease <= 0 {
		return errors.New("worker identity, poll interval and lease must be configured")
	}
	return nil
}

// ValidateForDatabase validates configuration needed by database commands.
func (runtime Runtime) ValidateForDatabase() error {
	if strings.TrimSpace(runtime.DatabaseURL) == "" {
		return errors.New("GATEWAY_DATABASE_URL is required")
	}
	return nil
}

func (runtime Runtime) validateCommon() error {
	if err := runtime.ValidateForDatabase(); err != nil {
		return err
	}
	if strings.TrimSpace(runtime.ProfilesFile) == "" {
		return errors.New("GATEWAY_PROFILES_FILE is required")
	}
	if strings.TrimSpace(runtime.ArtifactDirectory) == "" {
		return errors.New("GATEWAY_ARTIFACT_DIR is required")
	}
	if runtime.ProviderTimeout <= 0 {
		return errors.New("provider timeout must be positive")
	}
	switch runtime.LogLevel {
	case "debug", "info", "warn", "error":
	default:
		return errors.New("GATEWAY_LOG_LEVEL must be debug, info, warn or error")
	}
	return nil
}

// File is the complete profiles.json format. Exactly one externally
// advertised profile has id "default" and isDefault true.
type File struct {
	Profiles []Profile `json:"profiles"`
}

type Profile struct {
	ID          string   `json:"id"`
	DisplayName string   `json:"displayName"`
	Languages   []string `json:"languages"`
	IsDefault   bool     `json:"isDefault"`
	Kind        string   `json:"kind"`
	LLM         LLM      `json:"llm"`
	Media       Media    `json:"media"`
}

type LLM struct {
	BaseURL              string `json:"baseUrl,omitempty"`
	APIKeyEnv            string `json:"apiKeyEnv,omitempty"`
	AllowUnauthenticated bool   `json:"allowUnauthenticated,omitempty"`
	Mode                 string `json:"mode,omitempty"`
	Model                string `json:"model"`
	Timeout              string `json:"timeout,omitempty"`
}

type Media struct {
	BaseURL              string `json:"baseUrl"`
	APIKeyEnv            string `json:"apiKeyEnv,omitempty"`
	AllowUnauthenticated bool   `json:"allowUnauthenticated,omitempty"`
	TranscriptionModel   string `json:"transcriptionModel"`
	SpeechModel          string `json:"speechModel"`
	Voice                string `json:"voice"`
	Timeout              string `json:"timeout,omitempty"`
	SendSpeechLanguage   bool   `json:"sendSpeechLanguage,omitempty"`
}

// Credentials contains resolved environment values and is never serialized.
type Credentials struct {
	LLMAPIKey   string
	MediaAPIKey string
}

func Load(path string) (File, error) {
	file, err := os.Open(filepath.Clean(path))
	if err != nil {
		return File{}, fmt.Errorf("open profiles: %w", err)
	}
	defer file.Close()
	return Decode(file)
}

func Decode(reader io.Reader) (File, error) {
	content, err := io.ReadAll(io.LimitReader(reader, (1<<20)+1))
	if err != nil {
		return File{}, fmt.Errorf("read profiles: %w", err)
	}
	if len(content) > 1<<20 {
		return File{}, errors.New("profiles file exceeds 1 MiB")
	}
	decoder := json.NewDecoder(bytes.NewReader(content))
	decoder.DisallowUnknownFields()
	var file File
	if err := decoder.Decode(&file); err != nil {
		return File{}, fmt.Errorf("decode profiles: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return File{}, errors.New("decode profiles: trailing JSON value")
	}
	if err := file.Validate(); err != nil {
		return File{}, err
	}
	return file, nil
}

func (file File) Validate() error {
	if len(file.Profiles) == 0 {
		return errors.New("profiles must contain at least one profile")
	}
	if len(file.Profiles) > 64 {
		return errors.New("profiles must not contain more than 64 profiles")
	}
	seen := make(map[string]struct{}, len(file.Profiles))
	defaults := 0
	for index, profile := range file.Profiles {
		if err := profile.validate(); err != nil {
			return fmt.Errorf("profiles[%d]: %w", index, err)
		}
		if _, exists := seen[profile.ID]; exists {
			return fmt.Errorf("profiles[%d].id is duplicated", index)
		}
		seen[profile.ID] = struct{}{}
		if profile.IsDefault {
			defaults++
			if profile.ID != "default" {
				return fmt.Errorf("profiles[%d].id must be default when isDefault is true", index)
			}
		} else if profile.ID == "default" {
			return fmt.Errorf("profiles[%d].isDefault must be true for the default alias", index)
		}
	}
	if defaults != 1 {
		return errors.New("profiles must contain exactly one default alias")
	}
	return nil
}

func (profile Profile) validate() error {
	if !profileIDPattern.MatchString(profile.ID) {
		return errors.New("id is invalid")
	}
	if strings.TrimSpace(profile.DisplayName) == "" || len(profile.DisplayName) > 128 {
		return errors.New("displayName is required and must not exceed 128 bytes")
	}
	if len(profile.Languages) == 0 {
		return errors.New("languages must not be empty")
	}
	if len(profile.Languages) > 32 {
		return errors.New("languages must not contain more than 32 values")
	}
	for _, language := range profile.Languages {
		if len(language) < 2 || len(language) > 35 {
			return fmt.Errorf("language %q is invalid", language)
		}
	}
	switch profile.Kind {
	case "codex":
		if profile.LLM.BaseURL != "" || profile.LLM.APIKeyEnv != "" ||
			profile.LLM.AllowUnauthenticated || profile.LLM.Mode != "" {
			return errors.New("codex llm may only set model and timeout")
		}
	case "openai-compatible":
		if err := validateEndpoint("llm", profile.LLM.BaseURL, profile.LLM.APIKeyEnv, profile.LLM.AllowUnauthenticated); err != nil {
			return err
		}
		switch profile.LLM.Mode {
		case "responses", "chat_completions":
		default:
			return errors.New("llm.mode must be responses or chat_completions")
		}
	default:
		return errors.New("kind must be codex or openai-compatible")
	}
	if strings.TrimSpace(profile.LLM.Model) == "" {
		return errors.New("llm.model is required")
	}
	if err := validateDuration("llm.timeout", profile.LLM.Timeout); err != nil {
		return err
	}
	if err := validateEndpoint("media", profile.Media.BaseURL, profile.Media.APIKeyEnv, profile.Media.AllowUnauthenticated); err != nil {
		return err
	}
	if strings.TrimSpace(profile.Media.TranscriptionModel) == "" ||
		strings.TrimSpace(profile.Media.SpeechModel) == "" ||
		strings.TrimSpace(profile.Media.Voice) == "" {
		return errors.New("media transcriptionModel, speechModel and voice are required")
	}
	return validateDuration("media.timeout", profile.Media.Timeout)
}

func validateEndpoint(prefix, baseURL, keyEnv string, unauthenticated bool) error {
	parsed, err := url.Parse(strings.TrimSpace(baseURL))
	if err != nil || parsed.Scheme == "" || parsed.Host == "" ||
		(parsed.Scheme != "http" && parsed.Scheme != "https") ||
		parsed.RawQuery != "" || parsed.Fragment != "" || parsed.User != nil {
		return fmt.Errorf("%s.baseUrl must be an absolute HTTP(S) URL without credentials, query or fragment", prefix)
	}
	if unauthenticated {
		if keyEnv != "" {
			return fmt.Errorf("%s.apiKeyEnv must be empty when allowUnauthenticated is true", prefix)
		}
		return nil
	}
	if !envNamePattern.MatchString(keyEnv) {
		return fmt.Errorf("%s.apiKeyEnv must name an environment variable", prefix)
	}
	return nil
}

func validateDuration(name, raw string) error {
	if raw == "" {
		return nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil || value <= 0 {
		return fmt.Errorf("%s must be a positive Go duration", name)
	}
	return nil
}

func (file File) Profile(id string) (Profile, bool) {
	for _, profile := range file.Profiles {
		if profile.ID == id {
			return profile, true
		}
	}
	return Profile{}, false
}

// Advertised returns independent copies of every configured profile.
func (file File) Advertised() []Profile {
	return append([]Profile(nil), file.Profiles...)
}

func (profile Profile) Credentials(lookup func(string) (string, bool)) (Credentials, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	resolve := func(name string, optional bool) (string, error) {
		if optional {
			return "", nil
		}
		value, ok := lookup(name)
		if !ok || strings.TrimSpace(value) == "" {
			return "", fmt.Errorf("credential environment variable %s is not set", name)
		}
		return value, nil
	}
	var result Credentials
	var err error
	if profile.Kind == "openai-compatible" {
		result.LLMAPIKey, err = resolve(profile.LLM.APIKeyEnv, profile.LLM.AllowUnauthenticated)
		if err != nil {
			return Credentials{}, err
		}
	}
	result.MediaAPIKey, err = resolve(profile.Media.APIKeyEnv, profile.Media.AllowUnauthenticated)
	if err != nil {
		return Credentials{}, err
	}
	return result, nil
}

func (profile Profile) LLMTimeout(fallback time.Duration) time.Duration {
	return parsedDuration(profile.LLM.Timeout, fallback)
}

func (profile Profile) MediaTimeout(fallback time.Duration) time.Duration {
	return parsedDuration(profile.Media.Timeout, fallback)
}

func parsedDuration(raw string, fallback time.Duration) time.Duration {
	if raw == "" {
		return fallback
	}
	value, err := time.ParseDuration(raw)
	if err != nil {
		return fallback
	}
	return value
}

// MissingCredentialVariables returns names only, never credential values.
func (file File) MissingCredentialVariables(lookup func(string) (string, bool)) []string {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	var missing []string
	for _, profile := range file.Profiles {
		names := []string{profile.Media.APIKeyEnv}
		if profile.Kind == "openai-compatible" {
			names = append(names, profile.LLM.APIKeyEnv)
		}
		for _, name := range names {
			if name == "" {
				continue
			}
			if value, ok := lookup(name); !ok || strings.TrimSpace(value) == "" {
				missing = append(missing, name)
			}
		}
	}
	slices.Sort(missing)
	return slices.Compact(missing)
}

func BoolEnvironment(lookup func(string) (string, bool), name string, fallback bool) (bool, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	raw, ok := lookup(name)
	if !ok || strings.TrimSpace(raw) == "" {
		return fallback, nil
	}
	value, err := strconv.ParseBool(strings.TrimSpace(raw))
	if err != nil {
		return false, fmt.Errorf("%s must be a boolean", name)
	}
	return value, nil
}
