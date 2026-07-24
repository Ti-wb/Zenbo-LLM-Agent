package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"unicode/utf8"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

func decodeJSON(response http.ResponseWriter, request *http.Request, target any, maximum int64) error {
	contentType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	if err != nil || contentType != "application/json" {
		return domain.NewError(domain.ErrorUnsupported, "UNSUPPORTED_MEDIA_TYPE", "Expected application/json")
	}
	if request.ContentLength > maximum {
		return domain.NewError(domain.ErrorPayloadTooLarge, "PAYLOAD_TOO_LARGE", "Request body exceeds the gateway limit")
	}
	request.Body = http.MaxBytesReader(response, request.Body, maximum)
	body, err := io.ReadAll(request.Body)
	if err != nil {
		var maximumError *http.MaxBytesError
		if errors.As(err, &maximumError) {
			return domain.NewError(domain.ErrorPayloadTooLarge, "PAYLOAD_TOO_LARGE", "Request body exceeds the gateway limit")
		}
		return domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Unable to read request body")
	}
	if !utf8.Valid(body) {
		return domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "JSON request body must be valid UTF-8")
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", safeDecodeDetail(err))
	}
	var trailing json.RawMessage
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		if err == nil {
			return domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Request body must contain exactly one JSON value")
		}
		return domain.NewError(domain.ErrorInvalidArgument, "INVALID_REQUEST", "Malformed trailing JSON data")
	}
	return nil
}

func safeDecodeDetail(err error) string {
	if errors.Is(err, io.EOF) {
		return "Request body is required"
	}
	var syntaxError *json.SyntaxError
	if errors.As(err, &syntaxError) {
		return "Malformed JSON request body"
	}
	var typeError *json.UnmarshalTypeError
	if errors.As(err, &typeError) {
		return "JSON request contains an invalid field type"
	}
	if stringsContains(err.Error(), "unknown field") {
		return "JSON request contains an unexpected field"
	}
	return "Invalid JSON request body"
}

func stringsContains(value, fragment string) bool {
	return bytes.Contains([]byte(value), []byte(fragment))
}

func invalidRequest(code string, err error) error {
	return domain.NewError(domain.ErrorInvalidArgument, code, err.Error())
}

func validatePathUUID(value, name string) error {
	if !contract.IsUUID(value) {
		return domain.NewError(domain.ErrorInvalidArgument, "INVALID_"+name, fmt.Sprintf("%s must be a UUID", name))
	}
	return nil
}
