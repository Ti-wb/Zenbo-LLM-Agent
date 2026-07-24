package toolvalidation

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

// ValidateJSONBounds rejects oversized, excessively nested, invalid, or
// multi-value JSON before handing it to the schema compiler/validator.
func ValidateJSONBounds(raw []byte, maxBytes, maxDepth int) error {
	if len(raw) == 0 {
		return errors.New("JSON value is required")
	}
	if maxBytes <= 0 || len(raw) > maxBytes {
		return fmt.Errorf("JSON value exceeds %d bytes", maxBytes)
	}
	if maxDepth <= 0 {
		return errors.New("JSON depth limit must be positive")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
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
			if depth > maxDepth {
				return fmt.Errorf("JSON value exceeds depth %d", maxDepth)
			}
		case '}', ']':
			depth--
			if depth < 0 {
				return errors.New("invalid JSON nesting")
			}
		}
	}
	if depth != 0 {
		return errors.New("invalid JSON nesting")
	}
	decoder = json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return fmt.Errorf("invalid JSON: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("document contains trailing JSON")
	}
	return nil
}
