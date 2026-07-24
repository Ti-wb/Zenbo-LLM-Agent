// Package contractassets embeds and compiles the normative Agent Gateway 1.0
// contracts shipped with the client.
package contractassets

import (
	"bytes"
	"crypto/sha256"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

const (
	OpenAPISHA256      = "6ea27878e589c0280597775c3f2ba03b6faffbf682720877f8a2814a2126b40f"
	ToolManifestSHA256 = "f1094088cf75a3604c049f7b41d12f50f9b31fcb734abd626ec78c1f6661ad30"
	WSEnvelopeSHA256   = "0350e1ddef9a0b26a81aa38c2b3104205104d00dcb6b5649a9bf4be5b3199958"

	maxContractAssetBytes = 1 << 20
	maxInstanceBytes      = 64 << 10
	maxInstanceDepth      = 64

	openAPIAsset      = "assets/openapi.json"
	toolManifestAsset = "assets/tool-manifest.schema.json"
	wsEnvelopeAsset   = "assets/ws-envelope.schema.json"

	toolManifestSchemaURL = "https://zenbo-k.local/contracts/agent-gateway/tool-manifest.schema.json"
	wsEnvelopeSchemaURL   = "https://zenbo-k.local/contracts/agent-gateway/ws-envelope.schema.json"
)

var expectedChecksums = map[string]string{
	openAPIAsset:      OpenAPISHA256,
	toolManifestAsset: ToolManifestSHA256,
	wsEnvelopeAsset:   WSEnvelopeSHA256,
}

// FS is immutable and included in the zenbo-gateway binary.
//
//go:embed assets/*.json
var FS embed.FS

type validators struct {
	toolManifest *jsonschema.Schema
	wsEnvelope   *jsonschema.Schema
}

var embeddedValidators struct {
	once  sync.Once
	value *validators
	err   error
}

// Verify checks the reviewed checksums, parses the OpenAPI document, and
// compiles both JSON Schemas. The result is cached, including any error, so
// startup and concurrent request paths never recompile trusted contracts.
func Verify() error {
	_, err := loadEmbeddedValidators()
	return err
}

// ValidateToolManifest applies the embedded structural schema to one
// session-create manifest. Protocol-specific fixed registry checks remain in
// contract.ValidateToolManifest.
func ValidateToolManifest(document []byte) error {
	value, err := loadEmbeddedValidators()
	if err != nil {
		return err
	}
	if err := validateInstance(value.toolManifest, document); err != nil {
		return fmt.Errorf("tool manifest does not satisfy the embedded schema: %w", err)
	}
	return nil
}

// ValidateWSEnvelope applies the embedded structural and per-event data schema
// to an outbound WebSocket envelope. Correlation and cursor semantics remain
// in contract.ValidateEvent.
func ValidateWSEnvelope(document []byte) error {
	value, err := loadEmbeddedValidators()
	if err != nil {
		return err
	}
	if err := validateInstance(value.wsEnvelope, document); err != nil {
		return fmt.Errorf("WebSocket envelope does not satisfy the embedded schema: %w", err)
	}
	return nil
}

func loadEmbeddedValidators() (*validators, error) {
	embeddedValidators.once.Do(func() {
		embeddedValidators.value, embeddedValidators.err = loadValidators(FS, expectedChecksums)
	})
	return embeddedValidators.value, embeddedValidators.err
}

func loadValidators(files fs.FS, checksums map[string]string) (*validators, error) {
	openAPI, err := readVerifiedAsset(files, openAPIAsset, checksums[openAPIAsset])
	if err != nil {
		return nil, err
	}
	toolManifest, err := readVerifiedAsset(
		files, toolManifestAsset, checksums[toolManifestAsset],
	)
	if err != nil {
		return nil, err
	}
	wsEnvelope, err := readVerifiedAsset(
		files, wsEnvelopeAsset, checksums[wsEnvelopeAsset],
	)
	if err != nil {
		return nil, err
	}
	if err := validateOpenAPI(openAPI); err != nil {
		return nil, fmt.Errorf("%s is invalid: %w", openAPIAsset, err)
	}
	toolValidator, err := compileSchema(toolManifestSchemaURL, toolManifest)
	if err != nil {
		return nil, fmt.Errorf("%s is invalid: %w", toolManifestAsset, err)
	}
	wsValidator, err := compileSchema(wsEnvelopeSchemaURL, wsEnvelope)
	if err != nil {
		return nil, fmt.Errorf("%s is invalid: %w", wsEnvelopeAsset, err)
	}
	return &validators{
		toolManifest: toolValidator,
		wsEnvelope:   wsValidator,
	}, nil
}

func readVerifiedAsset(files fs.FS, name, expected string) ([]byte, error) {
	if expected == "" {
		return nil, fmt.Errorf("%s has no reviewed checksum", name)
	}
	content, err := fs.ReadFile(files, name)
	if err != nil {
		return nil, err
	}
	if len(content) == 0 || len(content) > maxContractAssetBytes {
		return nil, fmt.Errorf("%s has an invalid size", name)
	}
	actual := fmt.Sprintf("%x", sha256.Sum256(content))
	if actual != expected {
		return nil, fmt.Errorf("%s checksum mismatch: got %s", name, actual)
	}
	return content, nil
}

func validateOpenAPI(document []byte) error {
	var parsed struct {
		OpenAPI string `json:"openapi"`
		Info    struct {
			Title   string `json:"title"`
			Version string `json:"version"`
		} `json:"info"`
		Paths      map[string]map[string]json.RawMessage `json:"paths"`
		Components map[string]json.RawMessage            `json:"components"`
	}
	if err := decodeJSON(document, &parsed); err != nil {
		return err
	}
	if parsed.OpenAPI != "3.1.0" {
		return fmt.Errorf("openapi must be 3.1.0")
	}
	if strings.TrimSpace(parsed.Info.Title) == "" ||
		strings.TrimSpace(parsed.Info.Version) == "" {
		return fmt.Errorf("info.title and info.version are required")
	}
	if parsed.Paths == nil || parsed.Components == nil {
		return fmt.Errorf("paths and components are required")
	}
	requiredOperations := map[string][]string{
		"/capabilities":                               {"get"},
		"/sessions":                                   {"post"},
		"/sessions/{sessionId}":                       {"get", "delete"},
		"/sessions/{sessionId}/turns":                 {"post"},
		"/sessions/{sessionId}/turns/{turnId}/cancel": {"post"},
		"/sessions/{sessionId}/tool-calls/{callId}":   {"put"},
		"/sessions/{sessionId}/audio/{artifactId}":    {"get"},
		"/sessions/{sessionId}/playback":              {"post"},
		"/sessions/{sessionId}/events":                {"get"},
	}
	for path, methods := range requiredOperations {
		item, exists := parsed.Paths[path]
		if !exists || item == nil {
			return fmt.Errorf("required path %s is missing", path)
		}
		for _, method := range methods {
			if len(item[method]) == 0 {
				return fmt.Errorf("required operation %s %s is missing", method, path)
			}
		}
	}
	return nil
}

func compileSchema(resource string, document []byte) (*jsonschema.Schema, error) {
	decoded, err := jsonschema.UnmarshalJSON(bytes.NewReader(document))
	if err != nil {
		return nil, fmt.Errorf("decode JSON Schema: %w", err)
	}
	compiler := jsonschema.NewCompiler()
	compiler.DefaultDraft(jsonschema.Draft2020)
	compiler.AssertFormat()
	// Embedded schemas use only local fragments. Disallowing all external
	// loaders makes a reviewed schema update incapable of performing I/O.
	compiler.UseLoader(jsonschema.SchemeURLLoader{})
	if err := compiler.AddResource(resource, decoded); err != nil {
		return nil, fmt.Errorf("register JSON Schema: %w", err)
	}
	compiled, err := compiler.Compile(resource)
	if err != nil {
		return nil, fmt.Errorf("compile JSON Schema: %w", err)
	}
	return compiled, nil
}

func validateInstance(schema *jsonschema.Schema, document []byte) error {
	if schema == nil {
		return errors.New("compiled schema is unavailable")
	}
	if err := validateJSONBounds(document); err != nil {
		return err
	}
	decoded, err := jsonschema.UnmarshalJSON(bytes.NewReader(document))
	if err != nil {
		return fmt.Errorf("decode JSON instance: %w", err)
	}
	return schema.Validate(decoded)
}

func validateJSONBounds(document []byte) error {
	if len(document) == 0 {
		return errors.New("JSON document is required")
	}
	if len(document) > maxInstanceBytes {
		return fmt.Errorf("JSON document exceeds %d bytes", maxInstanceBytes)
	}
	decoder := json.NewDecoder(bytes.NewReader(document))
	depth := 0
	for {
		token, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return fmt.Errorf("invalid JSON: %w", err)
		}
		delimiter, ok := token.(json.Delim)
		if !ok {
			continue
		}
		switch delimiter {
		case '{', '[':
			depth++
			if depth > maxInstanceDepth {
				return fmt.Errorf("JSON document exceeds depth %d", maxInstanceDepth)
			}
		case '}', ']':
			depth--
		}
	}
	if depth != 0 {
		return errors.New("invalid JSON nesting")
	}
	return nil
}

func decodeJSON(document []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(document))
	decoder.UseNumber()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("decode JSON: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errors.New("document contains trailing JSON")
	}
	return nil
}
