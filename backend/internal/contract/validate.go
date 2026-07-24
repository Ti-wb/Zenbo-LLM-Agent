package contract

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contractassets"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

var (
	deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)
	profilePattern  = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
	versionPattern  = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`)
	errorPattern    = regexp.MustCompile(`^[A-Z][A-Z0-9_]{1,63}$`)
	uuidPattern     = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	sha256Pattern   = regexp.MustCompile(`^[a-f0-9]{64}$`)
)

var allowedTools = map[string]struct{}{
	"get_system_status":     {},
	"start_robot_following": {},
	"stop_robot_following":  {},
	"look_at_user":          {},
	"show_emotion":          {},
	"go_to_sleep":           {},
}

type fixedTool struct {
	owner                string
	sideEffect           string
	idempotent           bool
	requiresConfirmation bool
}

var fixedTools = map[string]fixedTool{
	"get_system_status":     {owner: "native", sideEffect: "none", idempotent: true},
	"start_robot_following": {owner: "native", sideEffect: "physical", idempotent: false},
	"stop_robot_following":  {owner: "native", sideEffect: "physical", idempotent: true},
	"look_at_user":          {owner: "native", sideEffect: "physical", idempotent: true},
	"show_emotion":          {owner: "web", sideEffect: "ui", idempotent: true},
	"go_to_sleep":           {owner: "web", sideEffect: "ui", idempotent: true},
}

func IsDeviceID(value string) bool {
	return deviceIDPattern.MatchString(value)
}

func IsUUID(value string) bool {
	return uuidPattern.MatchString(value)
}

func ValidateIdempotencyKey(value string) error {
	if !IsUUID(value) {
		return fmt.Errorf("Idempotency-Key must be a UUID")
	}
	return nil
}

func ValidateCreateSession(request domain.CreateSessionRequest) error {
	if length(request.Client.AppVersion, 1, 64) != nil {
		return fmt.Errorf("client.appVersion must contain 1 to 64 characters")
	}
	if request.Client.Platform != "android" {
		return fmt.Errorf("client.platform must be android")
	}
	if request.Client.RobotModel != "zenbo-k" {
		return fmt.Errorf("client.robotModel must be zenbo-k")
	}
	if length(request.Client.OSVersion, 0, 64) != nil {
		return fmt.Errorf("client.osVersion exceeds 64 characters")
	}
	if err := length(request.Client.Locale, 2, 35); err != nil {
		return fmt.Errorf("client.locale must contain 2 to 35 characters")
	}
	if !profilePattern.MatchString(request.AgentProfile) {
		return fmt.Errorf("agentProfile is invalid")
	}
	if err := length(request.Context.RobotName, 1, 64); err != nil {
		return fmt.Errorf("context.robotName must contain 1 to 64 characters")
	}
	if err := length(request.Context.Language, 2, 35); err != nil {
		return fmt.Errorf("context.language must contain 2 to 35 characters")
	}
	return ValidateToolManifest(request.ToolManifest)
}

func ValidateToolManifest(manifest domain.ToolManifest) error {
	encoded, err := json.Marshal(manifest)
	if err != nil {
		return fmt.Errorf("toolManifest cannot be encoded: %w", err)
	}
	if err := contractassets.ValidateToolManifest(encoded); err != nil {
		return fmt.Errorf("toolManifest schema validation failed: %w", err)
	}
	if manifest.ProtocolVersion != domain.ProtocolVersion {
		return fmt.Errorf("toolManifest.protocolVersion must be %s", domain.ProtocolVersion)
	}
	if !profilePattern.MatchString(manifest.ManifestVersion) {
		return fmt.Errorf("toolManifest.manifestVersion is invalid")
	}
	if manifest.Tools == nil {
		return fmt.Errorf("toolManifest.tools is required")
	}
	if len(manifest.Tools) > len(fixedTools) {
		return fmt.Errorf("toolManifest.tools may contain at most six tools")
	}
	seen := make(map[string]struct{}, len(manifest.Tools))
	for index, tool := range manifest.Tools {
		prefix := fmt.Sprintf("toolManifest.tools[%d]", index)
		if _, ok := allowedTools[tool.Name]; !ok {
			return fmt.Errorf("%s.name is not allowlisted", prefix)
		}
		if _, duplicate := seen[tool.Name]; duplicate {
			return fmt.Errorf("%s.name is duplicated", prefix)
		}
		seen[tool.Name] = struct{}{}
		fixed := fixedTools[tool.Name]
		if tool.Owner != fixed.owner {
			return fmt.Errorf("%s.owner must be %s", prefix, fixed.owner)
		}
		if tool.Version != domain.DeviceToolVersion {
			return fmt.Errorf("%s.version must be %s", prefix, domain.DeviceToolVersion)
		}
		if err := length(tool.Description, 1, 512); err != nil {
			return fmt.Errorf("%s.description must contain 1 to 512 characters", prefix)
		}
		if err := validateObjectSchema(tool.InputSchema); err != nil {
			return fmt.Errorf("%s.inputSchema: %w", prefix, err)
		}
		if err := validateObjectSchema(tool.ResultSchema); err != nil {
			return fmt.Errorf("%s.resultSchema: %w", prefix, err)
		}
		if tool.SideEffect != fixed.sideEffect {
			return fmt.Errorf("%s.sideEffect must be %s", prefix, fixed.sideEffect)
		}
		if !tool.HasRequiredBooleans() {
			return fmt.Errorf("%s.idempotent and requiresConfirmation are required", prefix)
		}
		if tool.Idempotent != fixed.idempotent || tool.RequiresConfirmation != fixed.requiresConfirmation {
			return fmt.Errorf("%s safety flags do not match protocol 1.0", prefix)
		}
		if tool.TimeoutMS != domain.DefaultToolTimeoutMS {
			return fmt.Errorf("%s.timeoutMs must be %d", prefix, domain.DefaultToolTimeoutMS)
		}
	}
	return nil
}

func validateObjectSchema(schema domain.ObjectSchema) error {
	if schema.Type != "object" {
		return fmt.Errorf("type must be object")
	}
	if !schema.HasAdditionalProperties() || schema.AdditionalProperties {
		return fmt.Errorf("additionalProperties must be present and false")
	}
	seen := make(map[string]struct{}, len(schema.Required))
	for _, field := range schema.Required {
		if field == "" {
			return fmt.Errorf("required contains an empty property name")
		}
		if _, duplicate := seen[field]; duplicate {
			return fmt.Errorf("required contains a duplicate property")
		}
		seen[field] = struct{}{}
	}
	for name, definition := range schema.Properties {
		if name == "" {
			return fmt.Errorf("properties contains an empty property name")
		}
		var object map[string]json.RawMessage
		if err := json.Unmarshal(definition, &object); err != nil || object == nil {
			return fmt.Errorf("property %q must contain a JSON object schema", name)
		}
	}
	return nil
}

func ValidateTextTurn(turn domain.TextTurn) error {
	if !IsUUID(turn.ClientTurnID) {
		return fmt.Errorf("clientTurnId must be a UUID")
	}
	if err := length(turn.Text, 1, 16_000); err != nil {
		return fmt.Errorf("text must contain 1 to 16000 characters")
	}
	if turn.Language != "" {
		if err := length(turn.Language, 2, 35); err != nil {
			return fmt.Errorf("language must contain 2 to 35 characters")
		}
	}
	return nil
}

func ValidateCancelTurn(request domain.CancelTurnRequest) error {
	switch request.Reason {
	case "client_request", "superseded", "timeout":
		return nil
	default:
		return fmt.Errorf("reason is invalid")
	}
}

func ValidateToolCallUpdate(update *domain.ToolCallUpdate) error {
	if update == nil {
		return fmt.Errorf("tool-call update is required")
	}
	if update.UpdatedAt.Time.IsZero() {
		return fmt.Errorf("updatedAt is required")
	}
	switch update.Status {
	case "accepted":
		if len(update.Output) != 0 || update.Error != nil {
			return fmt.Errorf("accepted update cannot contain output or error")
		}
	case "succeeded":
		if len(update.Output) == 0 {
			return fmt.Errorf("succeeded update requires output")
		}
		if update.Error != nil {
			return fmt.Errorf("succeeded update cannot contain error")
		}
	case "failed", "rejected":
		if update.Error == nil {
			return fmt.Errorf("%s update requires error", update.Status)
		}
		if len(update.Output) != 0 {
			return fmt.Errorf("%s update cannot contain output", update.Status)
		}
		if err := ValidateErrorDetail(*update.Error); err != nil {
			return err
		}
	default:
		return fmt.Errorf("status is invalid")
	}
	if len(update.Output) != 0 {
		var compact bytes.Buffer
		if err := json.Compact(&compact, update.Output); err != nil {
			return fmt.Errorf("output must be valid JSON")
		}
		if compact.Len() > domain.MaxToolOutputBytes {
			return ErrToolOutputTooLarge
		}
		update.Output = append(update.Output[:0], compact.Bytes()...)
	}
	return nil
}

var ErrToolOutputTooLarge = fmt.Errorf("tool output exceeds 16 KiB")

func ValidateErrorDetail(detail domain.ErrorDetail) error {
	if !errorPattern.MatchString(detail.Code) {
		return fmt.Errorf("error.code is invalid")
	}
	if err := length(detail.Message, 1, 512); err != nil {
		return fmt.Errorf("error.message must contain 1 to 512 characters")
	}
	return nil
}

func ValidatePlayback(update domain.PlaybackUpdate) error {
	if !IsUUID(update.TurnID) || !IsUUID(update.ArtifactID) {
		return fmt.Errorf("turnId and artifactId must be UUIDs")
	}
	if update.Timestamp.Time.IsZero() {
		return fmt.Errorf("timestamp is required")
	}
	if update.PositionMS != nil && *update.PositionMS < 0 {
		return fmt.Errorf("positionMs must be non-negative")
	}
	switch update.Status {
	case "started", "completed":
		if update.Reason != "" {
			return fmt.Errorf("%s playback cannot contain reason", update.Status)
		}
	case "interrupted":
		switch update.Reason {
		case "barge_in", "screen_off", "playback_error", "client_cancelled":
		default:
			return fmt.Errorf("interrupted playback requires a valid reason")
		}
	default:
		return fmt.Errorf("status is invalid")
	}
	return nil
}

func ValidateCapabilities(value domain.Capabilities) error {
	if value.ProtocolVersion != domain.ProtocolVersion {
		return fmt.Errorf("invalid capabilities protocol version")
	}
	if len(value.AudioInput.ContentTypes) != 1 || value.AudioInput.ContentTypes[0] != "audio/wav" ||
		value.AudioInput.MaxBytes != domain.MaxInputAudioBytes ||
		value.AudioInput.MaxDurationMS != domain.MaxInputDurationMS {
		return fmt.Errorf("invalid capabilities audio input")
	}
	if value.AudioOutput.MaxBytes != domain.MaxOutputAudioBytes || len(value.AudioOutput.ContentTypes) == 0 {
		return fmt.Errorf("invalid capabilities audio output")
	}
	seenMIME := map[string]struct{}{}
	for _, contentType := range value.AudioOutput.ContentTypes {
		if contentType != "audio/mpeg" && contentType != "audio/wav" {
			return fmt.Errorf("invalid capabilities audio output content type")
		}
		if _, duplicate := seenMIME[contentType]; duplicate {
			return fmt.Errorf("duplicate capabilities audio output content type")
		}
		seenMIME[contentType] = struct{}{}
	}
	if len(value.EventTypes) == 0 || len(value.AgentProfiles) == 0 {
		return fmt.Errorf("capabilities eventTypes and agentProfiles cannot be empty")
	}
	return nil
}

func ValidateSession(value domain.Session) error {
	if !IsUUID(value.SessionID) || !IsDeviceID(value.DeviceID) {
		return fmt.Errorf("invalid session identity")
	}
	if value.ProtocolVersion != domain.ProtocolVersion {
		return fmt.Errorf("invalid session protocol version")
	}
	switch value.State {
	case "active", "closing", "closed", "expired":
	default:
		return fmt.Errorf("invalid session state")
	}
	if value.CreatedAt.Time.IsZero() || value.ExpiresAt.Time.IsZero() {
		return fmt.Errorf("invalid session timestamps")
	}
	return nil
}

func ValidateTurnAccepted(value domain.TurnAccepted) error {
	if !IsUUID(value.SessionID) || !IsUUID(value.TurnID) || !IsUUID(value.ClientTurnID) {
		return fmt.Errorf("invalid accepted turn identity")
	}
	if value.State != "accepted" || value.AcceptedAt.Time.IsZero() {
		return fmt.Errorf("invalid accepted turn state")
	}
	return nil
}

func ValidateTurnState(value domain.TurnState) error {
	if !IsUUID(value.TurnID) || value.UpdatedAt.Time.IsZero() {
		return fmt.Errorf("invalid turn state")
	}
	switch value.State {
	case "accepted", "processing", "waiting_for_tool", "completed", "cancelled", "failed":
		return nil
	default:
		return fmt.Errorf("invalid turn state")
	}
}

func ValidateAudioArtifact(value domain.AudioArtifact) error {
	if !IsUUID(value.ArtifactID) || value.Body == nil {
		return fmt.Errorf("invalid audio artifact")
	}
	if value.MIMEType != "audio/mpeg" && value.MIMEType != "audio/wav" {
		return fmt.Errorf("invalid audio artifact content type")
	}
	if value.ByteLength < 1 || value.ByteLength > domain.MaxOutputAudioBytes {
		return fmt.Errorf("invalid audio artifact length")
	}
	if !sha256Pattern.MatchString(value.SHA256Hex) {
		return fmt.Errorf("invalid audio artifact digest")
	}
	if value.ExpiresAt.Time.IsZero() {
		return fmt.Errorf("invalid audio artifact expiry")
	}
	return nil
}

func IsTerminalEvent(eventType domain.EventType) bool {
	switch eventType {
	case domain.EventTurnCompleted, domain.EventTurnError, domain.EventTurnCancelled,
		domain.EventSessionExpired, domain.EventSessionClosed:
		return true
	default:
		return false
	}
}

func IsSessionTerminalEvent(eventType domain.EventType) bool {
	return eventType == domain.EventSessionExpired || eventType == domain.EventSessionClosed
}

func length(value string, minimum, maximum int) error {
	if !utf8.ValidString(value) {
		return fmt.Errorf("must be valid UTF-8")
	}
	count := utf8.RuneCountInString(value)
	if count < minimum || count > maximum {
		return fmt.Errorf("length is outside bounds")
	}
	return nil
}

func ContentType(value string) string {
	if delimiter := strings.IndexByte(value, ';'); delimiter >= 0 {
		value = value[:delimiter]
	}
	return strings.ToLower(strings.TrimSpace(value))
}
