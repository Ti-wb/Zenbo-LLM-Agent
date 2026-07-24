package contract

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contractassets"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

func TestValidateToolManifestAllowsFixedProtocolSubset(t *testing.T) {
	var manifest domain.ToolManifest
	if err := json.Unmarshal([]byte(`{
		"protocolVersion":"1.0",
		"manifestVersion":"fixed-1",
		"tools":[
			{"name":"get_system_status","owner":"native","version":"1.0.0","description":"status","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"resultSchema":{"type":"object","properties":{},"additionalProperties":false},"sideEffect":"none","idempotent":true,"requiresConfirmation":false,"timeoutMs":5000},
			{"name":"start_robot_following","owner":"native","version":"1.0.0","description":"start","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"resultSchema":{"type":"object","properties":{},"additionalProperties":false},"sideEffect":"physical","idempotent":false,"requiresConfirmation":false,"timeoutMs":5000},
			{"name":"stop_robot_following","owner":"native","version":"1.0.0","description":"stop","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"resultSchema":{"type":"object","properties":{},"additionalProperties":false},"sideEffect":"physical","idempotent":true,"requiresConfirmation":false,"timeoutMs":5000},
			{"name":"look_at_user","owner":"native","version":"1.0.0","description":"look","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"resultSchema":{"type":"object","properties":{},"additionalProperties":false},"sideEffect":"physical","idempotent":true,"requiresConfirmation":false,"timeoutMs":5000},
			{"name":"show_emotion","owner":"web","version":"1.0.0","description":"emotion","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"resultSchema":{"type":"object","properties":{},"additionalProperties":false},"sideEffect":"ui","idempotent":true,"requiresConfirmation":false,"timeoutMs":5000},
			{"name":"go_to_sleep","owner":"web","version":"1.0.0","description":"sleep","inputSchema":{"type":"object","properties":{},"additionalProperties":false},"resultSchema":{"type":"object","properties":{},"additionalProperties":false},"sideEffect":"ui","idempotent":true,"requiresConfirmation":false,"timeoutMs":5000}
		]
	}`), &manifest); err != nil {
		t.Fatal(err)
	}
	if err := ValidateToolManifest(manifest); err != nil {
		t.Fatalf("valid manifest rejected: %v", err)
	}
	manifest.Tools[0].Owner = "web"
	encoded, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	if err := contractassets.ValidateToolManifest(encoded); err != nil {
		t.Fatalf("structurally valid manifest rejected before semantic check: %v", err)
	}
	if err := ValidateToolManifest(manifest); err == nil {
		t.Fatal("wrong fixed owner was accepted")
	}
	manifest.Tools[0].Owner = "native"
	manifest.Tools = manifest.Tools[:5]
	if err := ValidateToolManifest(manifest); err != nil {
		t.Fatalf("valid subset rejected: %v", err)
	}
	manifest.Tools = []domain.ToolDefinition{}
	if err := ValidateToolManifest(manifest); err != nil {
		t.Fatalf("empty subset rejected: %v", err)
	}
}

func TestValidateEventCombinesSchemaAndCursorSemantics(t *testing.T) {
	event := domain.Event{
		ProtocolVersion: domain.ProtocolVersion,
		EventID:         "50000000-0000-4000-8000-000000000001",
		Sequence:        7,
		SessionID:       "10000000-0000-4000-8000-000000000001",
		Type:            domain.EventSessionReady,
		Timestamp:       domain.NewTimestamp(time.Now()),
		Data: json.RawMessage(
			`{"resumedAfter":6,"gatewayTime":"2026-07-24T01:02:03.004Z"}`,
		),
	}
	encoded, err := json.Marshal(event)
	if err != nil {
		t.Fatal(err)
	}
	if err := contractassets.ValidateWSEnvelope(encoded); err != nil {
		t.Fatalf("structurally valid envelope rejected before semantic check: %v", err)
	}
	err = ValidateEvent(event)
	if err == nil || !strings.Contains(err.Error(), "cursor") {
		t.Fatalf("schema-valid cursor mismatch was not rejected semantically: %v", err)
	}
}

func TestValidateWAVChecksFormatAndDeclaredDuration(t *testing.T) {
	wav := contractFixtureWAV()
	if err := ValidateWAV(wav, 20); err != nil {
		t.Fatalf("fixture rejected: %v", err)
	}
	if err := ValidateWAV(wav, 1000); err == nil {
		t.Fatal("mismatched duration was accepted")
	}
	wav[22] = 2
	if err := ValidateWAV(wav, 20); err == nil {
		t.Fatal("stereo WAV was accepted")
	}
}

func TestValidateEventRejectsUnexpectedData(t *testing.T) {
	turnID := "20000000-0000-4000-8000-000000000001"
	event := domain.Event{
		ProtocolVersion: domain.ProtocolVersion,
		EventID:         "50000000-0000-4000-8000-000000000001",
		Sequence:        1,
		SessionID:       "10000000-0000-4000-8000-000000000001",
		TurnID:          &turnID,
		Type:            domain.EventAgentThinking,
		Timestamp:       domain.NewTimestamp(time.Now()),
		Data:            json.RawMessage(`{"unexpected":true}`),
	}
	if err := ValidateEvent(event); err == nil {
		t.Fatal("unexpected event data field was accepted")
	}
	event.Data = json.RawMessage(`{}`)
	if err := ValidateEvent(event); err != nil {
		t.Fatalf("valid empty data rejected: %v", err)
	}
}

func contractFixtureWAV() []byte {
	const samples = 320
	wav := make([]byte, 44+samples*2)
	copy(wav[0:4], "RIFF")
	testPutLE32(wav[4:8], uint32(len(wav)-8))
	copy(wav[8:12], "WAVE")
	copy(wav[12:16], "fmt ")
	testPutLE32(wav[16:20], 16)
	testPutLE16(wav[20:22], 1)
	testPutLE16(wav[22:24], 1)
	testPutLE32(wav[24:28], 16000)
	testPutLE32(wav[28:32], 32000)
	testPutLE16(wav[32:34], 2)
	testPutLE16(wav[34:36], 16)
	copy(wav[36:40], "data")
	testPutLE32(wav[40:44], samples*2)
	return wav
}

func testPutLE16(target []byte, value uint16) {
	target[0], target[1] = byte(value), byte(value>>8)
}

func testPutLE32(target []byte, value uint32) {
	target[0], target[1], target[2], target[3] = byte(value), byte(value>>8), byte(value>>16), byte(value>>24)
}
