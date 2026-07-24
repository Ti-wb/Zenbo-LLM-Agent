package toolvalidation

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCompileSubsetAndValidateValues(t *testing.T) {
	registry, err := Compile(json.RawMessage(`{
		"protocolVersion":"1.0",
		"manifestVersion":"test-1",
		"tools":[{
			"name":"show_emotion",
			"owner":"web",
			"version":"1.0.0",
			"description":"Show an expression",
			"inputSchema":{
				"type":"object",
				"properties":{"expression":{"type":"string","enum":["HAPPY","DEFAULT"]}},
				"required":["expression"],
				"additionalProperties":false
			},
			"resultSchema":{
				"type":"object",
				"properties":{"applied":{"type":"boolean"}},
				"required":["applied"],
				"additionalProperties":false
			},
			"sideEffect":"ui",
			"idempotent":true,
			"requiresConfirmation":false,
			"timeoutMs":5000
		}]
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if names := registry.Names(); len(names) != 1 || names[0] != "show_emotion" {
		t.Fatalf("unexpected names: %v", names)
	}
	if err := registry.ValidateArguments("show_emotion", json.RawMessage(`{"expression":"HAPPY"}`)); err != nil {
		t.Fatalf("valid arguments rejected: %v", err)
	}
	if err := registry.ValidateArguments("show_emotion", json.RawMessage(`{"expression":"ANGRY"}`)); err == nil {
		t.Fatal("invalid enum was accepted")
	}
	if err := registry.ValidateResult("show_emotion", json.RawMessage(`{"applied":true}`)); err != nil {
		t.Fatalf("valid result rejected: %v", err)
	}
	if err := registry.ValidateResult("show_emotion", json.RawMessage(`{"applied":"yes"}`)); err == nil {
		t.Fatal("invalid result type was accepted")
	}
}

func TestCompileAllowsEmptySubset(t *testing.T) {
	registry, err := Compile(json.RawMessage(`{
		"protocolVersion":"1.0",
		"manifestVersion":"none",
		"tools":[]
	}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(registry.Names()) != 0 {
		t.Fatal("empty registry was not preserved")
	}
}

func TestCompileRejectsRemoteReference(t *testing.T) {
	_, err := Compile(json.RawMessage(`{
		"protocolVersion":"1.0",
		"manifestVersion":"bad",
		"tools":[{
			"name":"go_to_sleep",
			"owner":"web",
			"version":"1.0.0",
			"description":"sleep",
			"inputSchema":{
				"type":"object",
				"properties":{"x":{"$ref":"https://attacker.invalid/schema"}},
				"additionalProperties":false
			},
			"resultSchema":{"type":"object","properties":{},"additionalProperties":false},
			"sideEffect":"ui",
			"idempotent":true,
			"requiresConfirmation":false,
			"timeoutMs":5000
		}]
	}`))
	if err == nil || !strings.Contains(err.Error(), "not permitted") {
		t.Fatalf("remote reference was not rejected: %v", err)
	}
}

func TestValidateJSONBoundsRejectsDepthAndTrailingValue(t *testing.T) {
	if err := ValidateJSONBounds([]byte(`[[[0]]]`), 64, 2); err == nil {
		t.Fatal("excessive depth was accepted")
	}
	if err := ValidateJSONBounds([]byte(`{} {}`), 64, 4); err == nil {
		t.Fatal("trailing JSON was accepted")
	}
}

func FuzzValidateJSONBounds(f *testing.F) {
	for _, seed := range [][]byte{
		[]byte(`{}`),
		[]byte(`{"nested":[{"value":1}]}`),
		[]byte(`{} {}`),
		[]byte(`[[[[0]]]]`),
		{0xff, 0x00, '{'},
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, raw []byte) {
		// The security property is total, bounded parsing: arbitrary provider
		// or device bytes must return an error rather than panic.
		_ = ValidateJSONBounds(raw, 1024, 8)
	})
}
