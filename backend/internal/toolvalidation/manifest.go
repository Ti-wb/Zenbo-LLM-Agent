// Package toolvalidation compiles and applies the device-owned JSON Schemas
// registered with an Agent Gateway session.
//
// The package deliberately rejects remote references. Device manifests are an
// untrusted protocol input, and schema validation must never become an
// outbound-network primitive.
package toolvalidation

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/santhosh-tekuri/jsonschema/v6"
)

const (
	MaxManifestBytes = domain.MaxJSONBytes
	MaxInstanceBytes = domain.MaxToolOutputBytes
	MaxJSONDepth     = 32
)

type fixedDefinition struct {
	Owner      string
	SideEffect string
	Idempotent bool
}

var fixedDefinitions = map[string]fixedDefinition{
	"get_system_status":     {Owner: "native", SideEffect: "none", Idempotent: true},
	"start_robot_following": {Owner: "native", SideEffect: "physical", Idempotent: false},
	"stop_robot_following":  {Owner: "native", SideEffect: "physical", Idempotent: true},
	"look_at_user":          {Owner: "native", SideEffect: "physical", Idempotent: true},
	"show_emotion":          {Owner: "web", SideEffect: "ui", Idempotent: true},
	"go_to_sleep":           {Owner: "web", SideEffect: "ui", Idempotent: true},
}

// Definition is one validated, compiled device tool. Raw schemas are retained
// so the worker can pass the exact registered input contract to the LLM.
type Definition struct {
	Name         string
	Owner        string
	Version      string
	Description  string
	SideEffect   string
	Idempotent   bool
	TimeoutMS    int
	InputSchema  json.RawMessage
	ResultSchema json.RawMessage

	input  *jsonschema.Schema
	result *jsonschema.Schema
}

type Registry struct {
	definitions map[string]*Definition
	names       []string
}

type manifestDocument struct {
	ProtocolVersion string         `json:"protocolVersion"`
	ManifestVersion string         `json:"manifestVersion"`
	Tools           []manifestTool `json:"tools"`
}

type manifestTool struct {
	Name                 string          `json:"name"`
	Owner                string          `json:"owner"`
	Version              string          `json:"version"`
	Description          string          `json:"description"`
	InputSchema          json.RawMessage `json:"inputSchema"`
	ResultSchema         json.RawMessage `json:"resultSchema"`
	SideEffect           string          `json:"sideEffect"`
	Idempotent           *bool           `json:"idempotent"`
	RequiresConfirmation *bool           `json:"requiresConfirmation"`
	TimeoutMS            int             `json:"timeoutMs"`
}

// Compile validates a complete session manifest and compiles every input and
// result schema once. Protocol 1.0 permits any subset of the six fixed device
// tools, including the empty set.
func Compile(raw json.RawMessage) (*Registry, error) {
	if err := ValidateJSONBounds(raw, MaxManifestBytes, MaxJSONDepth); err != nil {
		return nil, fmt.Errorf("tool manifest: %w", err)
	}
	var document manifestDocument
	if err := decodeStrict(raw, &document); err != nil {
		return nil, fmt.Errorf("tool manifest: %w", err)
	}
	if document.ProtocolVersion != domain.ProtocolVersion {
		return nil, fmt.Errorf("tool manifest protocolVersion must be %s", domain.ProtocolVersion)
	}
	if !validManifestVersion(document.ManifestVersion) {
		return nil, errors.New("tool manifest manifestVersion is invalid")
	}
	if document.Tools == nil {
		return nil, errors.New("tool manifest tools is required")
	}

	registry := &Registry{definitions: make(map[string]*Definition, len(document.Tools))}
	for index, tool := range document.Tools {
		if err := validateToolMetadata(tool); err != nil {
			return nil, fmt.Errorf("tool manifest tools[%d]: %w", index, err)
		}
		if _, duplicate := registry.definitions[tool.Name]; duplicate {
			return nil, fmt.Errorf("tool manifest contains duplicate tool %q", tool.Name)
		}
		input, err := compileSchema(tool.Name, "input", tool.InputSchema)
		if err != nil {
			return nil, fmt.Errorf("tool %q input schema: %w", tool.Name, err)
		}
		result, err := compileSchema(tool.Name, "result", tool.ResultSchema)
		if err != nil {
			return nil, fmt.Errorf("tool %q result schema: %w", tool.Name, err)
		}
		registry.definitions[tool.Name] = &Definition{
			Name:         tool.Name,
			Owner:        tool.Owner,
			Version:      tool.Version,
			Description:  tool.Description,
			SideEffect:   tool.SideEffect,
			Idempotent:   *tool.Idempotent,
			TimeoutMS:    tool.TimeoutMS,
			InputSchema:  append(json.RawMessage(nil), tool.InputSchema...),
			ResultSchema: append(json.RawMessage(nil), tool.ResultSchema...),
			input:        input,
			result:       result,
		}
		registry.names = append(registry.names, tool.Name)
	}
	sort.Strings(registry.names)
	return registry, nil
}

func validateToolMetadata(tool manifestTool) error {
	fixed, allowed := fixedDefinitions[tool.Name]
	if !allowed {
		return fmt.Errorf("tool %q is not allowlisted", tool.Name)
	}
	if tool.Owner != fixed.Owner {
		return fmt.Errorf("owner must be %s", fixed.Owner)
	}
	if tool.Version != domain.DeviceToolVersion {
		return fmt.Errorf("version must be %s", domain.DeviceToolVersion)
	}
	if tool.SideEffect != fixed.SideEffect {
		return fmt.Errorf("sideEffect must be %s", fixed.SideEffect)
	}
	if tool.Idempotent == nil || *tool.Idempotent != fixed.Idempotent {
		return errors.New("idempotent flag does not match the fixed registry")
	}
	if tool.RequiresConfirmation == nil {
		return errors.New("requiresConfirmation is required")
	}
	if *tool.RequiresConfirmation {
		return errors.New("requiresConfirmation must be false for protocol 1.0")
	}
	if tool.TimeoutMS != domain.DefaultToolTimeoutMS {
		return fmt.Errorf("timeoutMs must be %d", domain.DefaultToolTimeoutMS)
	}
	if strings.TrimSpace(tool.Description) == "" ||
		utf8.RuneCountInString(tool.Description) > 512 {
		return errors.New("description must contain 1 to 512 characters")
	}
	return nil
}

func validManifestVersion(value string) bool {
	if value == "" || len(value) > 64 {
		return false
	}
	for _, character := range value {
		if (character >= 'a' && character <= 'z') ||
			(character >= 'A' && character <= 'Z') ||
			(character >= '0' && character <= '9') ||
			character == '.' || character == '_' || character == '-' {
			continue
		}
		return false
	}
	return true
}

func compileSchema(toolName, direction string, raw json.RawMessage) (*jsonschema.Schema, error) {
	if err := ValidateJSONBounds(raw, MaxManifestBytes, MaxJSONDepth); err != nil {
		return nil, err
	}
	var structural map[string]json.RawMessage
	if err := json.Unmarshal(raw, &structural); err != nil || structural == nil {
		return nil, errors.New("schema must be a JSON object")
	}
	var schemaType string
	if err := json.Unmarshal(structural["type"], &schemaType); err != nil || schemaType != "object" {
		return nil, errors.New("root type must be object")
	}
	var additional bool
	if value, present := structural["additionalProperties"]; !present ||
		json.Unmarshal(value, &additional) != nil || additional {
		return nil, errors.New("additionalProperties must be present and false")
	}
	if containsReference(structural) {
		return nil, errors.New("schema reference and custom metaschema keywords are not permitted")
	}
	document, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("decode schema: %w", err)
	}
	compiler := jsonschema.NewCompiler()
	resource := fmt.Sprintf("urn:zenbo:device-tool:%s:%s", toolName, direction)
	if err := compiler.AddResource(resource, document); err != nil {
		return nil, fmt.Errorf("register schema: %w", err)
	}
	compiled, err := compiler.Compile(resource)
	if err != nil {
		return nil, fmt.Errorf("compile schema: %w", err)
	}
	return compiled, nil
}

func containsReference(value any) bool {
	switch typed := value.(type) {
	case map[string]json.RawMessage:
		for key, child := range typed {
			if unsafeSchemaKeyword(key) {
				return true
			}
			var decoded any
			if json.Unmarshal(child, &decoded) == nil && containsReference(decoded) {
				return true
			}
		}
	case map[string]any:
		for key, child := range typed {
			if unsafeSchemaKeyword(key) || containsReference(child) {
				return true
			}
		}
	case []any:
		for _, child := range typed {
			if containsReference(child) {
				return true
			}
		}
	}
	return false
}

func unsafeSchemaKeyword(key string) bool {
	switch key {
	case "$ref", "$dynamicRef", "$recursiveRef", "$schema":
		return true
	default:
		return false
	}
}

func (registry *Registry) Names() []string {
	if registry == nil {
		return nil
	}
	return append([]string(nil), registry.names...)
}

func (registry *Registry) Definition(name string) (Definition, bool) {
	if registry == nil {
		return Definition{}, false
	}
	value, exists := registry.definitions[name]
	if !exists {
		return Definition{}, false
	}
	copy := *value
	copy.InputSchema = append(json.RawMessage(nil), value.InputSchema...)
	copy.ResultSchema = append(json.RawMessage(nil), value.ResultSchema...)
	return copy, true
}

func (registry *Registry) ValidateArguments(name string, raw json.RawMessage) error {
	return registry.validate(name, raw, false)
}

func (registry *Registry) ValidateResult(name string, raw json.RawMessage) error {
	return registry.validate(name, raw, true)
}

func (registry *Registry) validate(name string, raw json.RawMessage, result bool) error {
	if err := ValidateJSONBounds(raw, MaxInstanceBytes, MaxJSONDepth); err != nil {
		return err
	}
	definition, exists := registry.definitions[name]
	if !exists {
		return fmt.Errorf("tool %q is not registered for this session", name)
	}
	instance, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return fmt.Errorf("invalid JSON value: %w", err)
	}
	schema := definition.input
	label := "arguments"
	if result {
		schema = definition.result
		label = "result"
	}
	if err := schema.Validate(instance); err != nil {
		return fmt.Errorf("tool %q %s do not match the registered schema: %w", name, label, err)
	}
	return nil
}

func decodeStrict(raw []byte, target any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("document contains trailing JSON")
	}
	return nil
}
