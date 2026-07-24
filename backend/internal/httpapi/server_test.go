package httpapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

const (
	testDeviceID  = "zenbo-k-test-device"
	testSessionID = "10000000-0000-4000-8000-000000000001"
	testTurnID    = "20000000-0000-4000-8000-000000000001"
	testClientID  = "30000000-0000-4000-8000-000000000001"
	testArtifact  = "40000000-0000-4000-8000-000000000001"
	testEventID   = "50000000-0000-4000-8000-000000000001"
	testIdem      = "60000000-0000-4000-8000-000000000001"
)

var testNow = time.Date(2026, 7, 24, 12, 34, 56, 789_123_000, time.UTC)

type testAuthenticator struct{}

func (testAuthenticator) Authenticate(_ context.Context, token, deviceID string) (domain.Principal, error) {
	if token != "test-token" || deviceID != testDeviceID {
		return domain.Principal{}, domain.NewError(domain.ErrorUnauthenticated, "UNAUTHORIZED", "Invalid device credential")
	}
	return domain.Principal{TokenID: "token-row", DeviceID: deviceID}, nil
}

type testService struct {
	createSessionCalls int
	audioCalls         int
	playbackCalls      int
	audioInput         domain.AudioTurn
	artifact           domain.AudioArtifact
	stream             EventStream
	streamError        error
}

func (s *testService) Capabilities(context.Context, domain.Principal) (domain.Capabilities, error) {
	return domain.Capabilities{
		ProtocolVersion: domain.ProtocolVersion,
		AudioInput: domain.AudioInput{
			ContentTypes:  []string{"audio/wav"},
			MaxBytes:      domain.MaxInputAudioBytes,
			MaxDurationMS: domain.MaxInputDurationMS,
		},
		AudioOutput: domain.AudioOutput{ContentTypes: []string{"audio/wav"}, MaxBytes: domain.MaxOutputAudioBytes},
		EventTypes:  domain.AllEventTypes,
		AgentProfiles: []domain.AgentProfile{{
			ID: "default", DisplayName: "Test", Languages: []string{"zh-TW"}, IsDefault: true,
		}},
		RetentionPolicy: domain.RetentionPolicy{
			RawAudio:   domain.RetentionRule{Retained: false, MaxAgeSeconds: 0},
			Transcript: domain.RetentionRule{Retained: true, MaxAgeSeconds: 604800},
		},
	}, nil
}

func (s *testService) CreateSession(_ context.Context, principal domain.Principal, _ string, _ domain.CreateSessionRequest) (domain.Session, error) {
	s.createSessionCalls++
	return testSession(principal.DeviceID), nil
}

func (s *testService) GetSession(_ context.Context, principal domain.Principal, _ string) (domain.Session, error) {
	return testSession(principal.DeviceID), nil
}

func (*testService) CloseSession(context.Context, domain.Principal, string) error { return nil }

func (*testService) CreateTextTurn(context.Context, domain.Principal, string, string, domain.TextTurn) (domain.TurnAccepted, error) {
	return acceptedTurn(), nil
}

func (s *testService) CreateAudioTurn(_ context.Context, _ domain.Principal, _ string, _ string, input domain.AudioTurn) (domain.TurnAccepted, error) {
	s.audioCalls++
	s.audioInput = input
	return acceptedTurn(), nil
}

func (*testService) CancelTurn(context.Context, domain.Principal, string, string, string, domain.CancelTurnRequest) (domain.TurnState, error) {
	return domain.TurnState{TurnID: testTurnID, State: "cancelled", UpdatedAt: domain.NewTimestamp(testNow)}, nil
}

func (*testService) PutToolCallUpdate(_ context.Context, _ domain.Principal, _, _ string, input domain.ToolCallUpdate) (domain.ToolCallUpdate, error) {
	return input, nil
}

func (s *testService) GetAudioArtifact(context.Context, domain.Principal, string, string) (domain.AudioArtifact, error) {
	return s.artifact, nil
}

func (s *testService) ReportPlayback(context.Context, domain.Principal, string, string, domain.PlaybackUpdate) error {
	s.playbackCalls++
	return nil
}

func (s *testService) OpenEventStream(context.Context, domain.Principal, string, uint64) (EventStream, error) {
	if s.streamError != nil {
		return nil, s.streamError
	}
	if s.stream == nil {
		return nil, domain.NewError(domain.ErrorNotFound, "SESSION_NOT_FOUND", "Session does not exist")
	}
	return s.stream, nil
}

func testSession(deviceID string) domain.Session {
	return domain.Session{
		SessionID:       testSessionID,
		DeviceID:        deviceID,
		ProtocolVersion: domain.ProtocolVersion,
		State:           "active",
		CreatedAt:       domain.NewTimestamp(testNow),
		ExpiresAt:       domain.NewTimestamp(testNow.Add(24 * time.Hour)),
		LastSequence:    0,
	}
}

func acceptedTurn() domain.TurnAccepted {
	return domain.TurnAccepted{
		SessionID:    testSessionID,
		TurnID:       testTurnID,
		ClientTurnID: testClientID,
		State:        "accepted",
		AcceptedAt:   domain.NewTimestamp(testNow),
	}
}

func newTestServer(service *testService) *httptest.Server {
	return newTestServerWithPing(service, time.Hour)
}

func newTestServerWithPing(service *testService, pingInterval time.Duration) *httptest.Server {
	return httptest.NewServer(NewHandler(service, testAuthenticator{}, Options{
		Clock:        func() time.Time { return testNow },
		PingInterval: pingInterval,
	}))
}

func authorizedRequest(t *testing.T, method, target string, body io.Reader) *http.Request {
	t.Helper()
	request, err := http.NewRequest(method, target, body)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer test-token")
	request.Header.Set("X-Zenbo-Device-Id", testDeviceID)
	request.Header.Set("X-Zenbo-Protocol", domain.ProtocolVersion)
	return request
}

func TestAuthenticationAndProtocolFailuresAreProblems(t *testing.T) {
	server := newTestServer(&testService{})
	defer server.Close()

	response, err := http.Get(server.URL + apiPrefix + "/capabilities")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing auth status = %d, want 401", response.StatusCode)
	}
	if got := response.Header.Get("Content-Type"); !strings.HasPrefix(got, "application/problem+json") {
		t.Fatalf("content type = %q", got)
	}
	var body problem
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "UNAUTHORIZED" || !contract.IsUUID(body.RequestID) {
		t.Fatalf("unexpected problem: %#v", body)
	}

	request := authorizedRequest(t, http.MethodGet, server.URL+apiPrefix+"/capabilities", nil)
	request.Header.Set("X-Zenbo-Protocol", "2.0")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUpgradeRequired {
		t.Fatalf("protocol status = %d, want 426", response.StatusCode)
	}
}

func TestUnknownRoutesAndWrongMethodsAreProblems(t *testing.T) {
	server := newTestServer(&testService{})
	defer server.Close()

	for _, test := range []struct {
		name   string
		method string
		path   string
		status int
		code   string
		allow  string
	}{
		{
			name: "unknown route", method: http.MethodGet,
			path:   apiPrefix + "/does-not-exist",
			status: http.StatusNotFound, code: "ROUTE_NOT_FOUND",
		},
		{
			name: "wrong method", method: http.MethodPatch,
			path:   apiPrefix + "/capabilities",
			status: http.StatusMethodNotAllowed, code: "METHOD_NOT_ALLOWED",
			allow: "GET",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := authorizedRequest(t, test.method, server.URL+test.path, nil)
			response, err := http.DefaultClient.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != test.status {
				t.Fatalf("status = %d, want %d", response.StatusCode, test.status)
			}
			if got := response.Header.Get("Content-Type"); !strings.HasPrefix(
				got, "application/problem+json",
			) {
				t.Fatalf("content type = %q", got)
			}
			if response.Header.Get("Allow") != test.allow {
				t.Fatalf("Allow = %q, want %q", response.Header.Get("Allow"), test.allow)
			}
			var body problem
			if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body.Code != test.code || !contract.IsUUID(body.RequestID) {
				t.Fatalf("problem = %#v", body)
			}
		})
	}
}

func TestEventStreamRequiresWebSocketUpgradeProblem(t *testing.T) {
	server := newTestServer(&testService{})
	defer server.Close()
	request := authorizedRequest(
		t,
		http.MethodGet,
		server.URL+apiPrefix+"/sessions/"+testSessionID+"/events",
		nil,
	)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUpgradeRequired {
		t.Fatalf("status = %d, want 426", response.StatusCode)
	}
	var body problem
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "WEBSOCKET_UPGRADE_REQUIRED" ||
		!contract.IsUUID(body.RequestID) {
		t.Fatalf("problem = %#v", body)
	}
}

func TestEventStreamTerminalStaleProblemIsIndistinguishableFromNotFound(t *testing.T) {
	service := &testService{
		streamError: domain.NewError(
			domain.ErrorNotFound,
			"NOT_FOUND",
			"Requested gateway resource was not found",
		),
	}
	server := newTestServer(service)
	defer server.Close()
	header := http.Header{
		"Authorization":     []string{"Bearer test-token"},
		"X-Zenbo-Device-Id": []string{testDeviceID},
		"X-Zenbo-Protocol":  []string{domain.ProtocolVersion},
	}
	target := "ws" + strings.TrimPrefix(server.URL, "http") +
		apiPrefix + "/sessions/" + testSessionID + "/events"
	connection, response, err := websocket.Dial(
		context.Background(),
		target,
		&websocket.DialOptions{HTTPHeader: header},
	)
	if connection != nil {
		connection.CloseNow()
		t.Fatal("terminal stale stream unexpectedly upgraded")
	}
	if err == nil {
		t.Fatal("terminal stale stream did not fail the handshake")
	}
	if response == nil {
		t.Fatalf("terminal stale handshake has no HTTP response: %v", err)
	}
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", response.StatusCode)
	}
	if got := response.Header.Get("Content-Type"); !strings.HasPrefix(
		got, "application/problem+json",
	) {
		t.Fatalf("content type = %q", got)
	}
	var body problem
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Status != http.StatusNotFound ||
		body.Code != "NOT_FOUND" ||
		body.Detail != "Requested gateway resource was not found" ||
		!contract.IsUUID(body.RequestID) {
		t.Fatalf("problem = %#v", body)
	}
}

func TestCreateSessionRejectsUnknownFieldsAndRequiresFixedManifest(t *testing.T) {
	service := &testService{}
	server := newTestServer(service)
	defer server.Close()

	invalid := validSessionRequest()
	invalid["unexpected"] = true
	body, _ := json.Marshal(invalid)
	request := authorizedRequest(t, http.MethodPost, server.URL+apiPrefix+"/sessions", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", testIdem)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusBadRequest || service.createSessionCalls != 0 {
		t.Fatalf("status=%d calls=%d", response.StatusCode, service.createSessionCalls)
	}

	body, _ = json.Marshal(validSessionRequest())
	request = authorizedRequest(t, http.MethodPost, server.URL+apiPrefix+"/sessions", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", testIdem)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusCreated || service.createSessionCalls != 1 {
		t.Fatalf("status=%d calls=%d", response.StatusCode, service.createSessionCalls)
	}
	if response.Header.Get("Location") != apiPrefix+"/sessions/"+testSessionID {
		t.Fatalf("location = %q", response.Header.Get("Location"))
	}
	var raw map[string]any
	if err := json.NewDecoder(response.Body).Decode(&raw); err != nil {
		t.Fatal(err)
	}
	if raw["createdAt"] != "2026-07-24T12:34:56.789Z" {
		t.Fatalf("createdAt = %#v", raw["createdAt"])
	}
}

func TestJSONBodyRejectsInvalidUTF8(t *testing.T) {
	service := &testService{}
	server := newTestServer(service)
	defer server.Close()
	body := []byte("{\"client\":\"")
	body = append(body, 0xff)
	body = append(body, []byte("\"}")...)
	request := authorizedRequest(
		t, http.MethodPost, server.URL+apiPrefix+"/sessions", bytes.NewReader(body),
	)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", testIdem)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest ||
		service.createSessionCalls != 0 {
		t.Fatalf(
			"status=%d create session calls=%d",
			response.StatusCode, service.createSessionCalls,
		)
	}
}

func TestAudioTurnIsValidatedAndSubmittedExactlyOnce(t *testing.T) {
	service := &testService{}
	server := newTestServer(service)
	defer server.Close()

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("clientTurnId", testClientID)
	_ = writer.WriteField("durationMs", "20")
	_ = writer.WriteField("language", "zh-TW")
	headers := make(textproto.MIMEHeader)
	headers.Set("Content-Disposition", `form-data; name="audio"; filename="turn.wav"`)
	headers.Set("Content-Type", "audio/wav")
	part, err := writer.CreatePart(headers)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(fixtureWAV()); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	request := authorizedRequest(t, http.MethodPost, server.URL+apiPrefix+"/sessions/"+testSessionID+"/turns", &body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	request.Header.Set("Idempotency-Key", testIdem)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusAccepted {
		bytes, _ := io.ReadAll(response.Body)
		t.Fatalf("status=%d body=%s", response.StatusCode, bytes)
	}
	if service.audioCalls != 1 {
		t.Fatalf("CreateAudioTurn calls = %d, want 1", service.audioCalls)
	}
	if service.audioInput.ClientTurnID != testClientID || len(service.audioInput.Audio) != len(fixtureWAV()) {
		t.Fatalf("unexpected audio input: %#v", service.audioInput)
	}
}

func TestAudioTurnRejectsInvalidUTF8Language(t *testing.T) {
	service := &testService{}
	server := newTestServer(service)
	defer server.Close()

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("clientTurnId", testClientID)
	_ = writer.WriteField("durationMs", "20")
	language, err := writer.CreateFormField("language")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := language.Write([]byte{0xff, 0xfe}); err != nil {
		t.Fatal(err)
	}
	headers := make(textproto.MIMEHeader)
	headers.Set("Content-Disposition", `form-data; name="audio"; filename="turn.wav"`)
	headers.Set("Content-Type", "audio/wav")
	part, err := writer.CreatePart(headers)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write(fixtureWAV())
	_ = writer.Close()

	request := authorizedRequest(t, http.MethodPost, server.URL+apiPrefix+"/sessions/"+testSessionID+"/turns", &body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	request.Header.Set("Idempotency-Key", testIdem)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest || service.audioCalls != 0 {
		t.Fatalf("status=%d audio calls=%d", response.StatusCode, service.audioCalls)
	}
}

func TestPlaybackReturnsAndroidCompatibleJSONBody(t *testing.T) {
	service := &testService{}
	server := newTestServer(service)
	defer server.Close()
	input := domain.PlaybackUpdate{
		TurnID: testTurnID, ArtifactID: testArtifact, Status: "completed", Timestamp: domain.NewTimestamp(testNow),
	}
	body, _ := json.Marshal(input)
	request := authorizedRequest(t, http.MethodPost, server.URL+apiPrefix+"/sessions/"+testSessionID+"/playback", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", testIdem)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	responseBody, _ := io.ReadAll(response.Body)
	if response.StatusCode != http.StatusAccepted || string(responseBody) != "{}" || service.playbackCalls != 1 {
		t.Fatalf("status=%d body=%q calls=%d", response.StatusCode, responseBody, service.playbackCalls)
	}
}

func TestAudioArtifactHasExactLengthAndDigest(t *testing.T) {
	audio := fixtureWAV()
	digest := sha256.Sum256(audio)
	service := &testService{artifact: domain.AudioArtifact{
		ArtifactID: testArtifact,
		MIMEType:   "audio/wav",
		ByteLength: int64(len(audio)),
		SHA256Hex:  hex.EncodeToString(digest[:]),
		ExpiresAt:  domain.NewTimestamp(testNow.Add(time.Minute)),
		Body:       io.NopCloser(bytes.NewReader(audio)),
	}}
	server := newTestServer(service)
	defer server.Close()
	request := authorizedRequest(t, http.MethodGet, server.URL+apiPrefix+"/sessions/"+testSessionID+"/audio/"+testArtifact, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	if response.StatusCode != http.StatusOK || len(body) != len(audio) {
		t.Fatalf("status=%d bytes=%d", response.StatusCode, len(body))
	}
	if got := response.Header.Get("Digest"); !strings.HasPrefix(got, "sha-256=:") || !strings.HasSuffix(got, ":") {
		t.Fatalf("Digest = %q", got)
	}
	if got := response.Header.Get("Content-Length"); got != "684" {
		t.Fatalf("Content-Length = %q", got)
	}
}

func TestWebSocketSendsReadyThenOrderedReplay(t *testing.T) {
	closedData, _ := json.Marshal(contract.SessionClosedData{Reason: "client_request"})
	stream := &testEventStream{
		bootstrap: domain.StreamBootstrap{
			AcceptedAfter: 0, CurrentSequence: 1, SessionState: "active",
		},
		events: []domain.Event{{
			ProtocolVersion: domain.ProtocolVersion,
			EventID:         testEventID,
			Sequence:        1,
			SessionID:       testSessionID,
			Type:            domain.EventSessionClosed,
			Timestamp:       domain.NewTimestamp(testNow),
			Data:            closedData,
		}},
	}
	server := newTestServer(&testService{stream: stream})
	defer server.Close()
	header := http.Header{
		"Authorization":     []string{"Bearer test-token"},
		"X-Zenbo-Device-Id": []string{testDeviceID},
		"X-Zenbo-Protocol":  []string{domain.ProtocolVersion},
	}
	target := "ws" + strings.TrimPrefix(server.URL, "http") + apiPrefix + "/sessions/" + testSessionID + "/events"
	connection, response, err := websocket.Dial(context.Background(), target, &websocket.DialOptions{HTTPHeader: header})
	if err != nil {
		if response != nil {
			t.Fatalf("dial status=%d error=%v", response.StatusCode, err)
		}
		t.Fatal(err)
	}
	defer connection.CloseNow()

	var ready domain.Event
	if err := wsjson.Read(context.Background(), connection, &ready); err != nil {
		t.Fatal(err)
	}
	if ready.Type != domain.EventSessionReady || ready.Sequence != 0 {
		t.Fatalf("first event = %#v", ready)
	}
	var replay domain.Event
	if err := wsjson.Read(context.Background(), connection, &replay); err != nil {
		t.Fatal(err)
	}
	if replay.Type != domain.EventSessionClosed || replay.Sequence != 1 {
		t.Fatalf("replay event = %#v", replay)
	}
}

func TestWebSocketStaleCursorSendsAuthoritativeSnapshot(t *testing.T) {
	closedData, _ := json.Marshal(contract.SessionClosedData{Reason: "client_request"})
	stream := &testEventStream{
		bootstrap: domain.StreamBootstrap{
			AcceptedAfter: 1, CurrentSequence: 5, Stale: true, SessionState: "active",
		},
		events: []domain.Event{{
			ProtocolVersion: domain.ProtocolVersion,
			EventID:         testEventID,
			Sequence:        6,
			SessionID:       testSessionID,
			Type:            domain.EventSessionClosed,
			Timestamp:       domain.NewTimestamp(testNow),
			Data:            closedData,
		}},
	}
	server := newTestServer(&testService{stream: stream})
	defer server.Close()
	connection := dialTestWebSocket(t, server.URL, "?after=1")
	defer connection.CloseNow()

	for index, expected := range []struct {
		eventType domain.EventType
		sequence  uint64
	}{
		{domain.EventSessionReady, 1},
		{domain.EventSessionSnapshot, 5},
		{domain.EventSessionClosed, 6},
	} {
		var event domain.Event
		if err := wsjson.Read(context.Background(), connection, &event); err != nil {
			t.Fatalf("read %d: %v", index, err)
		}
		if event.Type != expected.eventType || event.Sequence != expected.sequence {
			t.Fatalf("event %d = %#v", index, event)
		}
		if event.Type == domain.EventSessionSnapshot {
			var data contract.SessionSnapshotData
			if err := json.Unmarshal(event.Data, &data); err != nil || data.LastSequence != 5 {
				t.Fatalf("snapshot data = %#v, error=%v", data, err)
			}
		}
	}
}

func TestWebSocketHeartbeatKeepsActiveStreamAlive(t *testing.T) {
	live := make(chan domain.Event, 1)
	stream := &testEventStream{
		bootstrap: domain.StreamBootstrap{AcceptedAfter: 0, CurrentSequence: 0, SessionState: "active"},
		live:      live,
	}
	server := newTestServerWithPing(&testService{stream: stream}, 5*time.Millisecond)
	defer server.Close()
	connection := dialTestWebSocket(t, server.URL, "")
	defer connection.CloseNow()

	var ready domain.Event
	if err := wsjson.Read(context.Background(), connection, &ready); err != nil {
		t.Fatal(err)
	}
	if ready.Type != domain.EventSessionReady {
		t.Fatalf("first event = %s", ready.Type)
	}

	readResult := make(chan error, 1)
	go func() {
		var event domain.Event
		err := wsjson.Read(context.Background(), connection, &event)
		if err == nil && event.Type != domain.EventSessionClosed {
			err = fmt.Errorf("unexpected event %s", event.Type)
		}
		readResult <- err
	}()
	time.Sleep(30 * time.Millisecond)
	closedData, _ := json.Marshal(contract.SessionClosedData{Reason: "client_request"})
	live <- domain.Event{
		ProtocolVersion: domain.ProtocolVersion,
		EventID:         testEventID,
		Sequence:        1,
		SessionID:       testSessionID,
		Type:            domain.EventSessionClosed,
		Timestamp:       domain.NewTimestamp(testNow),
		Data:            closedData,
	}
	select {
	case err := <-readResult:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("stream did not survive heartbeat intervals")
	}
}

func dialTestWebSocket(t *testing.T, serverURL, query string) *websocket.Conn {
	t.Helper()
	header := http.Header{
		"Authorization":     []string{"Bearer test-token"},
		"X-Zenbo-Device-Id": []string{testDeviceID},
		"X-Zenbo-Protocol":  []string{domain.ProtocolVersion},
	}
	target := "ws" + strings.TrimPrefix(serverURL, "http") + apiPrefix + "/sessions/" + testSessionID + "/events" + query
	connection, response, err := websocket.Dial(context.Background(), target, &websocket.DialOptions{HTTPHeader: header})
	if err != nil {
		if response != nil {
			t.Fatalf("dial status=%d error=%v", response.StatusCode, err)
		}
		t.Fatal(err)
	}
	return connection
}

type testEventStream struct {
	mu        sync.Mutex
	bootstrap domain.StreamBootstrap
	events    []domain.Event
	live      <-chan domain.Event
}

func (s *testEventStream) Bootstrap() domain.StreamBootstrap { return s.bootstrap }

func (s *testEventStream) Next(ctx context.Context) (domain.Event, error) {
	s.mu.Lock()
	if len(s.events) > 0 {
		event := s.events[0]
		s.events = s.events[1:]
		s.mu.Unlock()
		return event, nil
	}
	s.mu.Unlock()
	if s.live != nil {
		select {
		case event := <-s.live:
			return event, nil
		case <-ctx.Done():
			return domain.Event{}, ctx.Err()
		}
	}
	<-ctx.Done()
	return domain.Event{}, ctx.Err()
}

func (*testEventStream) Close() error { return nil }

func validSessionRequest() map[string]any {
	names := []struct {
		name, owner, sideEffect string
		idempotent              bool
	}{
		{"get_system_status", "native", "none", true},
		{"start_robot_following", "native", "physical", false},
		{"stop_robot_following", "native", "physical", true},
		{"look_at_user", "native", "physical", true},
		{"show_emotion", "web", "ui", true},
		{"go_to_sleep", "web", "ui", true},
	}
	tools := make([]map[string]any, 0, len(names))
	for _, item := range names {
		tools = append(tools, map[string]any{
			"name": item.name, "owner": item.owner, "version": "1.0.0",
			"description": "Test tool", "inputSchema": objectSchema(), "resultSchema": objectSchema(),
			"sideEffect": item.sideEffect, "idempotent": item.idempotent, "requiresConfirmation": false, "timeoutMs": 5000,
		})
	}
	return map[string]any{
		"client": map[string]any{
			"appVersion": "1.0.0", "platform": "android", "robotModel": "zenbo-k",
			"osVersion": "6.0.1", "locale": "zh-TW",
		},
		"agentProfile": "default",
		"context":      map[string]any{"robotName": "Kira", "language": "zh-TW"},
		"toolManifest": map[string]any{
			"protocolVersion": "1.0", "manifestVersion": "fixed-1", "tools": tools,
		},
	}
}

func objectSchema() map[string]any {
	return map[string]any{"type": "object", "properties": map[string]any{}, "additionalProperties": false}
}

func fixtureWAV() []byte {
	const sampleCount = 320
	wav := make([]byte, 44+sampleCount*2)
	copy(wav[0:4], "RIFF")
	putLE32(wav[4:8], uint32(len(wav)-8))
	copy(wav[8:12], "WAVE")
	copy(wav[12:16], "fmt ")
	putLE32(wav[16:20], 16)
	putLE16(wav[20:22], 1)
	putLE16(wav[22:24], 1)
	putLE32(wav[24:28], 16000)
	putLE32(wav[28:32], 32000)
	putLE16(wav[32:34], 2)
	putLE16(wav[34:36], 16)
	copy(wav[36:40], "data")
	putLE32(wav[40:44], sampleCount*2)
	return wav
}

func putLE16(target []byte, value uint16) {
	target[0], target[1] = byte(value), byte(value>>8)
}

func putLE32(target []byte, value uint32) {
	target[0], target[1], target[2], target[3] = byte(value), byte(value>>8), byte(value>>16), byte(value>>24)
}
