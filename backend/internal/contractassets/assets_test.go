package contractassets

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/fstest"
)

func TestEmbeddedContractsMatchNormativeSources(t *testing.T) {
	if err := Verify(); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		embedded string
		source   string
		checksum string
	}{
		{"assets/openapi.json", "../../../contracts/agent-gateway/openapi.json", OpenAPISHA256},
		{"assets/tool-manifest.schema.json", "../../../contracts/agent-gateway/schemas/tool-manifest.schema.json", ToolManifestSHA256},
		{"assets/ws-envelope.schema.json", "../../../contracts/agent-gateway/schemas/ws-envelope.schema.json", WSEnvelopeSHA256},
	}
	for _, test := range cases {
		embedded, err := FS.ReadFile(test.embedded)
		if err != nil {
			t.Fatal(err)
		}
		source, err := os.ReadFile(filepath.Clean(test.source))
		if err != nil {
			t.Fatal(err)
		}
		if string(embedded) != string(source) {
			t.Fatalf("%s drifted from %s; update the embedded copy and reviewed checksum", test.embedded, test.source)
		}
		actual := fmt.Sprintf("%x", sha256.Sum256(source))
		if actual != test.checksum {
			t.Fatalf("%s checksum drift: got %s", test.source, actual)
		}
	}
}

func TestEmbeddedSchemaValidatorsAcceptNormativeInstances(t *testing.T) {
	manifest := []byte(`{
		"protocolVersion":"1.0",
		"manifestVersion":"empty-1",
		"tools":[]
	}`)
	if err := ValidateToolManifest(manifest); err != nil {
		t.Fatalf("valid tool manifest rejected: %v", err)
	}
	envelope := []byte(`{
		"protocolVersion":"1.0",
		"eventId":"50000000-0000-4000-8000-000000000001",
		"sequence":0,
		"sessionId":"10000000-0000-4000-8000-000000000001",
		"turnId":null,
		"type":"session.ready",
		"timestamp":"2026-07-24T01:02:03.004Z",
		"data":{"resumedAfter":0,"gatewayTime":"2026-07-24T01:02:03.004Z"}
	}`)
	if err := ValidateWSEnvelope(envelope); err != nil {
		t.Fatalf("valid WebSocket envelope rejected: %v", err)
	}
}

func TestEmbeddedSchemaValidatorsRejectStructuralViolations(t *testing.T) {
	manifest := []byte(`{
		"protocolVersion":"1.0",
		"manifestVersion":"empty-1",
		"tools":[],
		"unexpected":true
	}`)
	if err := ValidateToolManifest(manifest); err == nil {
		t.Fatal("manifest with an additional property was accepted")
	}
	envelope := []byte(`{
		"protocolVersion":"1.0",
		"eventId":"not-a-uuid",
		"sequence":0,
		"sessionId":"10000000-0000-4000-8000-000000000001",
		"turnId":null,
		"type":"session.ready",
		"timestamp":"2026-07-24T01:02:03.004Z",
		"data":{"resumedAfter":0,"gatewayTime":"2026-07-24T01:02:03.004Z"}
	}`)
	if err := ValidateWSEnvelope(envelope); err == nil {
		t.Fatal("envelope with an invalid UUID format was accepted")
	}
}

func TestWSEnvelopeValidatorAcceptsMaximumEscapedText(t *testing.T) {
	envelope, err := json.Marshal(map[string]any{
		"protocolVersion": "1.0",
		"eventId":         "50000000-0000-4000-8000-000000000001",
		"sequence":        1,
		"sessionId":       "10000000-0000-4000-8000-000000000001",
		"turnId":          "20000000-0000-4000-8000-000000000001",
		"type":            "agent.text.final",
		"timestamp":       "2026-07-24T01:02:03.004Z",
		"data": map[string]any{
			"text": strings.Repeat("<", 16_000),
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(envelope) <= 64<<10 {
		t.Fatalf("escaped envelope is only %d bytes; test did not cross the old limit", len(envelope))
	}
	if err := ValidateWSEnvelope(envelope); err != nil {
		t.Fatalf("valid maximum-length escaped text was rejected: %v", err)
	}
}

func TestWSEnvelopeValidatorRetainsBoundForUnboundedArguments(t *testing.T) {
	envelope, err := json.Marshal(map[string]any{
		"protocolVersion": "1.0",
		"eventId":         "50000000-0000-4000-8000-000000000001",
		"sequence":        1,
		"sessionId":       "10000000-0000-4000-8000-000000000001",
		"turnId":          "20000000-0000-4000-8000-000000000001",
		"type":            "tool.call",
		"timestamp":       "2026-07-24T01:02:03.004Z",
		"data": map[string]any{
			"callId":      "60000000-0000-4000-8000-000000000001",
			"toolName":    "show_emotion",
			"toolVersion": "1.0.0",
			"arguments": map[string]any{
				"unbounded": strings.Repeat("x", maxInstanceBytes),
			},
			"timeoutMs":  5000,
			"deadlineAt": "2026-07-24T01:02:08.004Z",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	err = ValidateWSEnvelope(envelope)
	if err == nil || !strings.Contains(err.Error(), "exceeds") {
		t.Fatalf("oversized unbounded arguments were not rejected: %v", err)
	}
}

func TestLoadValidatorsRejectsChecksumDrift(t *testing.T) {
	files := cloneEmbeddedContracts(t)
	files[toolManifestAsset].Data = append(
		files[toolManifestAsset].Data,
		'\n',
	)
	if _, err := loadValidators(files, expectedChecksums); err == nil ||
		!strings.Contains(err.Error(), "checksum mismatch") {
		t.Fatalf("checksum drift was not rejected: %v", err)
	}
}

func TestLoadValidatorsRejectsMalformedSchemasWithReviewedChecksum(t *testing.T) {
	for _, asset := range []string{toolManifestAsset, wsEnvelopeAsset} {
		t.Run(asset, func(t *testing.T) {
			files := cloneEmbeddedContracts(t)
			files[asset].Data = []byte(`{
				"$schema":"https://json-schema.org/draft/2020-12/schema",
				"type":42
			}`)
			if _, err := loadValidators(files, checksumsFor(files)); err == nil ||
				!strings.Contains(err.Error(), asset+" is invalid") {
				t.Fatalf("malformed JSON Schema was not rejected: %v", err)
			}
		})
	}
}

func TestLoadValidatorsRejectsMalformedOpenAPIStructureWithReviewedChecksum(t *testing.T) {
	files := cloneEmbeddedContracts(t)
	files[openAPIAsset].Data = []byte(`{
		"openapi":"3.1.0",
		"info":{"title":"Gateway","version":"0.1.0"},
		"paths":{},
		"components":{}
	}`)
	if _, err := loadValidators(files, checksumsFor(files)); err == nil ||
		!strings.Contains(err.Error(), "required path") {
		t.Fatalf("malformed OpenAPI structure was not rejected: %v", err)
	}
}

func TestConcurrentSchemaValidationReusesCompiledValidators(t *testing.T) {
	envelope := []byte(`{
		"protocolVersion":"1.0",
		"eventId":"50000000-0000-4000-8000-000000000001",
		"sequence":0,
		"sessionId":"10000000-0000-4000-8000-000000000001",
		"turnId":null,
		"type":"session.ready",
		"timestamp":"2026-07-24T01:02:03.004Z",
		"data":{"resumedAfter":0,"gatewayTime":"2026-07-24T01:02:03.004Z"}
	}`)
	const goroutines = 32
	errors := make(chan error, goroutines)
	var wait sync.WaitGroup
	for range goroutines {
		wait.Add(1)
		go func() {
			defer wait.Done()
			for range 50 {
				if err := ValidateWSEnvelope(envelope); err != nil {
					errors <- err
					return
				}
			}
		}()
	}
	wait.Wait()
	close(errors)
	for err := range errors {
		t.Errorf("concurrent validation failed: %v", err)
	}
}

func cloneEmbeddedContracts(t *testing.T) fstest.MapFS {
	t.Helper()
	result := make(fstest.MapFS, len(expectedChecksums))
	for name := range expectedChecksums {
		content, err := FS.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		result[name] = &fstest.MapFile{Data: append([]byte(nil), content...)}
	}
	return result
}

func checksumsFor(files fstest.MapFS) map[string]string {
	result := make(map[string]string, len(files))
	for name, file := range files {
		result[name] = fmt.Sprintf("%x", sha256.Sum256(file.Data))
	}
	return result
}
