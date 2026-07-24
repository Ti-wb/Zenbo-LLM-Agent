package httpapi

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/auth"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/wsstream"
)

const apiPrefix = "/agent/v1"

type Options struct {
	Clock        func() time.Time
	PingInterval time.Duration
}

type Server struct {
	service Service
	clock   func() time.Time
}

func NewHandler(service Service, authenticator auth.Authenticator, options Options) http.Handler {
	server := &Server{service: service, clock: options.Clock}
	if server.clock == nil {
		server.clock = time.Now
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET "+apiPrefix+"/capabilities", server.getCapabilities)
	mux.HandleFunc("POST "+apiPrefix+"/sessions", server.createSession)
	mux.HandleFunc("GET "+apiPrefix+"/sessions/{sessionId}", server.getSession)
	mux.HandleFunc("DELETE "+apiPrefix+"/sessions/{sessionId}", server.closeSession)
	mux.HandleFunc("POST "+apiPrefix+"/sessions/{sessionId}/turns", server.createTurn)
	mux.HandleFunc("POST "+apiPrefix+"/sessions/{sessionId}/turns/{turnId}/cancel", server.cancelTurn)
	mux.HandleFunc("PUT "+apiPrefix+"/sessions/{sessionId}/tool-calls/{callId}", server.putToolCallUpdate)
	mux.HandleFunc("GET "+apiPrefix+"/sessions/{sessionId}/audio/{artifactId}", server.getAudioArtifact)
	mux.HandleFunc("POST "+apiPrefix+"/sessions/{sessionId}/playback", server.reportPlayback)
	streamHandler := wsstream.Handler{
		Source:       service,
		Clock:        server.clock,
		PingInterval: options.PingInterval,
		OnFailure:    writeProblem,
	}
	mux.Handle("GET "+apiPrefix+"/sessions/{sessionId}/events", streamHandler)
	registerFallbacks(mux, server)

	middleware := auth.Middleware{Authenticator: authenticator, OnFailure: writeProblem}
	return middleware.Wrap(mux)
}

func registerFallbacks(mux *http.ServeMux, server *Server) {
	routes := []struct {
		path    string
		methods []string
	}{
		{apiPrefix + "/capabilities", []string{"GET"}},
		{apiPrefix + "/sessions", []string{"POST"}},
		{apiPrefix + "/sessions/{sessionId}", []string{"GET", "DELETE"}},
		{apiPrefix + "/sessions/{sessionId}/turns", []string{"POST"}},
		{apiPrefix + "/sessions/{sessionId}/turns/{turnId}/cancel", []string{"POST"}},
		{apiPrefix + "/sessions/{sessionId}/tool-calls/{callId}", []string{"PUT"}},
		{apiPrefix + "/sessions/{sessionId}/audio/{artifactId}", []string{"GET"}},
		{apiPrefix + "/sessions/{sessionId}/playback", []string{"POST"}},
		{apiPrefix + "/sessions/{sessionId}/events", []string{"GET"}},
	}
	for _, route := range routes {
		allowed := strings.Join(route.methods, ", ")
		handler := func(response http.ResponseWriter, request *http.Request) {
			response.Header().Set("Allow", allowed)
			writeProblem(response, request, domain.NewError(
				domain.ErrorMethodNotAllowed,
				"METHOD_NOT_ALLOWED",
				"HTTP method is not supported for this resource",
			))
		}
		// Go's ServeMux treats HEAD as matching a GET pattern. Protocol routes
		// are strict, so install a more-specific HEAD rejection.
		if slices.Contains(route.methods, "GET") {
			mux.HandleFunc("HEAD "+route.path, handler)
		}
		mux.HandleFunc(route.path, handler)
	}
	mux.HandleFunc("/", func(response http.ResponseWriter, request *http.Request) {
		writeProblem(response, request, domain.NewError(
			domain.ErrorNotFound,
			"ROUTE_NOT_FOUND",
			"Requested gateway route does not exist",
		))
	})
}

func (s *Server) getCapabilities(response http.ResponseWriter, request *http.Request) {
	value, err := s.service.Capabilities(request.Context(), principal(request))
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateCapabilities(value); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Gateway capabilities are invalid", err))
		return
	}
	writeJSON(response, http.StatusOK, value)
}

func (s *Server) createSession(response http.ResponseWriter, request *http.Request) {
	key, ok := idempotencyKey(response, request)
	if !ok {
		return
	}
	var input domain.CreateSessionRequest
	if err := decodeJSON(response, request, &input, domain.MaxJSONBytes); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateCreateSession(input); err != nil {
		writeProblem(response, request, invalidRequest("INVALID_SESSION_REQUEST", err))
		return
	}
	value, err := s.service.CreateSession(request.Context(), principal(request), key, input)
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateSession(value); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Created session is invalid", err))
		return
	}
	response.Header().Set("Location", apiPrefix+"/sessions/"+value.SessionID)
	writeJSON(response, http.StatusCreated, value)
}

func (s *Server) getSession(response http.ResponseWriter, request *http.Request) {
	sessionID := request.PathValue("sessionId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	value, err := s.service.GetSession(request.Context(), principal(request), sessionID)
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateSession(value); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Session response is invalid", err))
		return
	}
	writeJSON(response, http.StatusOK, value)
}

func (s *Server) closeSession(response http.ResponseWriter, request *http.Request) {
	sessionID := request.PathValue("sessionId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := s.service.CloseSession(request.Context(), principal(request), sessionID); err != nil {
		writeProblem(response, request, err)
		return
	}
	writeEmpty(response, http.StatusNoContent)
}

func (s *Server) createTurn(response http.ResponseWriter, request *http.Request) {
	sessionID := request.PathValue("sessionId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	key, ok := idempotencyKey(response, request)
	if !ok {
		return
	}
	contentType := contract.ContentType(request.Header.Get("Content-Type"))
	var (
		value domain.TurnAccepted
		err   error
	)
	switch contentType {
	case "application/json":
		var input domain.TextTurn
		if decodeError := decodeJSON(response, request, &input, domain.MaxJSONBytes); decodeError != nil {
			writeProblem(response, request, decodeError)
			return
		}
		if validationError := contract.ValidateTextTurn(input); validationError != nil {
			writeProblem(response, request, invalidRequest("INVALID_TEXT_TURN", validationError))
			return
		}
		value, err = s.service.CreateTextTurn(request.Context(), principal(request), sessionID, key, input)
	case "multipart/form-data":
		var input domain.AudioTurn
		input, err = decodeAudioTurn(response, request)
		if err == nil {
			value, err = s.service.CreateAudioTurn(request.Context(), principal(request), sessionID, key, input)
		}
	default:
		writeProblem(response, request, domain.NewError(domain.ErrorUnsupported, "UNSUPPORTED_MEDIA_TYPE", "Expected application/json or multipart/form-data"))
		return
	}
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateTurnAccepted(value); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Accepted turn response is invalid", err))
		return
	}
	response.Header().Set("Location", fmt.Sprintf("%s/sessions/%s/turns/%s", apiPrefix, sessionID, value.TurnID))
	writeJSON(response, http.StatusAccepted, value)
}

func (s *Server) cancelTurn(response http.ResponseWriter, request *http.Request) {
	sessionID, turnID := request.PathValue("sessionId"), request.PathValue("turnId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := validatePathUUID(turnID, "TURN_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	key, ok := idempotencyKey(response, request)
	if !ok {
		return
	}
	var input domain.CancelTurnRequest
	if err := decodeJSON(response, request, &input, domain.MaxJSONBytes); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateCancelTurn(input); err != nil {
		writeProblem(response, request, invalidRequest("INVALID_CANCEL_REQUEST", err))
		return
	}
	value, err := s.service.CancelTurn(request.Context(), principal(request), sessionID, turnID, key, input)
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateTurnState(value); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Turn state response is invalid", err))
		return
	}
	writeJSON(response, http.StatusAccepted, value)
}

func (s *Server) putToolCallUpdate(response http.ResponseWriter, request *http.Request) {
	sessionID, callID := request.PathValue("sessionId"), request.PathValue("callId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := validatePathUUID(callID, "CALL_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	var input domain.ToolCallUpdate
	if err := decodeJSON(response, request, &input, domain.MaxJSONBytes); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateToolCallUpdate(&input); err != nil {
		if errors.Is(err, contract.ErrToolOutputTooLarge) {
			writeProblem(response, request, domain.NewError(domain.ErrorPayloadTooLarge, "PAYLOAD_TOO_LARGE", err.Error()))
		} else {
			writeProblem(response, request, domain.NewError(domain.ErrorUnprocessable, "INVALID_TOOL_RESULT", err.Error()))
		}
		return
	}
	value, err := s.service.PutToolCallUpdate(request.Context(), principal(request), sessionID, callID, input)
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidateToolCallUpdate(&value); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Tool-call response is invalid", err))
		return
	}
	writeJSON(response, http.StatusOK, value)
}

func (s *Server) getAudioArtifact(response http.ResponseWriter, request *http.Request) {
	sessionID, artifactID := request.PathValue("sessionId"), request.PathValue("artifactId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := validatePathUUID(artifactID, "ARTIFACT_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	artifact, err := s.service.GetAudioArtifact(request.Context(), principal(request), sessionID, artifactID)
	if err != nil {
		writeProblem(response, request, err)
		return
	}
	if artifact.Body != nil {
		defer artifact.Body.Close()
	}
	if err := contract.ValidateAudioArtifact(artifact); err != nil {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_SERVICE_RESPONSE", "Audio artifact metadata is invalid", err))
		return
	}
	if !artifact.ExpiresAt.Time.After(s.clock()) {
		writeProblem(response, request, domain.NewError(domain.ErrorGone, "AUDIO_EXPIRED", "Audio artifact has expired"))
		return
	}
	body, err := io.ReadAll(io.LimitReader(artifact.Body, artifact.ByteLength+1))
	if err != nil || int64(len(body)) != artifact.ByteLength {
		writeProblem(response, request, domain.WrapError(domain.ErrorInternal, "INVALID_ARTIFACT", "Audio artifact length does not match metadata", err))
		return
	}
	digest := sha256.Sum256(body)
	expected, _ := hex.DecodeString(artifact.SHA256Hex)
	if !strings.EqualFold(hex.EncodeToString(digest[:]), hex.EncodeToString(expected)) {
		writeProblem(response, request, domain.NewError(domain.ErrorInternal, "INVALID_ARTIFACT", "Audio artifact digest does not match metadata"))
		return
	}
	response.Header().Set("Content-Type", artifact.MIMEType)
	response.Header().Set("Content-Length", strconv.FormatInt(artifact.ByteLength, 10))
	response.Header().Set("Digest", "sha-256=:"+base64.StdEncoding.EncodeToString(digest[:])+":")
	response.Header().Set("Cache-Control", "private, no-store")
	response.WriteHeader(http.StatusOK)
	_, _ = response.Write(body)
}

func (s *Server) reportPlayback(response http.ResponseWriter, request *http.Request) {
	sessionID := request.PathValue("sessionId")
	if err := validatePathUUID(sessionID, "SESSION_ID"); err != nil {
		writeProblem(response, request, err)
		return
	}
	key, ok := idempotencyKey(response, request)
	if !ok {
		return
	}
	var input domain.PlaybackUpdate
	if err := decodeJSON(response, request, &input, domain.MaxJSONBytes); err != nil {
		writeProblem(response, request, err)
		return
	}
	if err := contract.ValidatePlayback(input); err != nil {
		writeProblem(response, request, invalidRequest("INVALID_PLAYBACK_UPDATE", err))
		return
	}
	if err := s.service.ReportPlayback(request.Context(), principal(request), sessionID, key, input); err != nil {
		writeProblem(response, request, err)
		return
	}
	// Android's JSONObject parser rejects a successful empty 202 body.
	writeJSON(response, http.StatusAccepted, struct{}{})
}

func principal(request *http.Request) domain.Principal {
	value, _ := auth.PrincipalFromContext(request.Context())
	return value
}

func idempotencyKey(response http.ResponseWriter, request *http.Request) (string, bool) {
	values := request.Header.Values("Idempotency-Key")
	if len(values) != 1 || contract.ValidateIdempotencyKey(values[0]) != nil {
		writeProblem(response, request, domain.NewError(domain.ErrorInvalidArgument, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be a UUID"))
		return "", false
	}
	return values[0], true
}

func decodeAudioTurn(response http.ResponseWriter, request *http.Request) (domain.AudioTurn, error) {
	mediaType, parameters, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	if err != nil || mediaType != "multipart/form-data" || parameters["boundary"] == "" {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Missing multipart boundary")
	}
	const maximumMultipartBytes = domain.MaxInputAudioBytes + 128*1024
	if request.ContentLength > maximumMultipartBytes {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorPayloadTooLarge, "PAYLOAD_TOO_LARGE", "Multipart upload exceeds the gateway limit")
	}
	request.Body = http.MaxBytesReader(response, request.Body, maximumMultipartBytes)
	reader := multipart.NewReader(request.Body, parameters["boundary"])
	fields := map[string][]byte{}
	var audioContentType string
	for {
		part, nextError := reader.NextPart()
		if errors.Is(nextError, io.EOF) {
			break
		}
		if nextError != nil {
			var maximumError *http.MaxBytesError
			if errors.As(nextError, &maximumError) {
				return domain.AudioTurn{}, domain.NewError(domain.ErrorPayloadTooLarge, "PAYLOAD_TOO_LARGE", "Multipart upload exceeds the gateway limit")
			}
			return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Malformed multipart upload")
		}
		name := part.FormName()
		if name != "clientTurnId" && name != "durationMs" && name != "language" && name != "audio" {
			part.Close()
			return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Unexpected multipart field")
		}
		if _, duplicate := fields[name]; duplicate {
			part.Close()
			return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Duplicate multipart field")
		}
		limit := int64(512)
		if name == "audio" {
			limit = domain.MaxInputAudioBytes
			audioContentType = contract.ContentType(part.Header.Get("Content-Type"))
		}
		value, readError := io.ReadAll(io.LimitReader(part, limit+1))
		part.Close()
		if readError != nil {
			return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Unable to read multipart field")
		}
		if int64(len(value)) > limit {
			kind := domain.ErrorInvalidArgument
			if name == "audio" {
				kind = domain.ErrorPayloadTooLarge
			}
			return domain.AudioTurn{}, domain.NewError(kind, "PAYLOAD_TOO_LARGE", "Multipart field exceeds the gateway limit")
		}
		fields[name] = value
	}
	for _, required := range []string{"clientTurnId", "durationMs", "audio"} {
		if _, ok := fields[required]; !ok {
			return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Missing multipart field "+required)
		}
	}
	if audioContentType != "audio/wav" {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorUnsupported, "UNSUPPORTED_MEDIA_TYPE", "Audio part must use audio/wav")
	}
	clientTurnID := string(fields["clientTurnId"])
	if !contract.IsUUID(clientTurnID) {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_AUDIO_TURN", "clientTurnId must be a UUID")
	}
	durationText := string(fields["durationMs"])
	if durationText == "" || strings.Trim(durationText, "0123456789") != "" {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_AUDIO_TURN", "durationMs must be an integer")
	}
	durationMS, parseError := strconv.Atoi(durationText)
	if parseError != nil || durationMS < 1 || durationMS > domain.MaxInputDurationMS {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_AUDIO_TURN", "durationMs must be between 1 and 30000")
	}
	language := string(fields["language"])
	if !utf8.ValidString(language) {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_AUDIO_TURN", "language must be valid UTF-8")
	}
	if language != "" && (len([]rune(language)) < 2 || len([]rune(language)) > 35) {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorInvalidArgument, "INVALID_AUDIO_TURN", "language must contain 2 to 35 characters")
	}
	audio := fields["audio"]
	if err := contract.ValidateWAV(audio, durationMS); err != nil {
		return domain.AudioTurn{}, domain.NewError(domain.ErrorUnprocessable, "INVALID_AUDIO", err.Error())
	}
	return domain.AudioTurn{
		ClientTurnID: clientTurnID,
		Audio:        audio,
		DurationMS:   durationMS,
		Language:     language,
	}, nil
}
