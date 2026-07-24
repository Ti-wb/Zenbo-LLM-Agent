package worker

import (
	"encoding/json"
	"errors"
	"fmt"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
)

const stateVersion = 1

const (
	phaseInput    = "input"
	phaseLLM      = "llm"
	phaseTool     = "tool"
	phaseTTS      = "tts"
	phaseComplete = "complete"
)

type pendingTool struct {
	GatewayCallID  string          `json:"gatewayCallId"`
	ProviderCallID string          `json:"providerCallId"`
	Name           string          `json:"name"`
	Arguments      json.RawMessage `json:"arguments"`
}

type persistedState struct {
	Version          int                   `json:"version"`
	TurnID           string                `json:"turnId,omitempty"`
	Phase            string                `json:"phase,omitempty"`
	Messages         []application.Message `json:"messages,omitempty"`
	PendingTool      *pendingTool          `json:"pendingTool,omitempty"`
	InputLanguage    string                `json:"inputLanguage,omitempty"`
	ResponseText     string                `json:"responseText,omitempty"`
	TTSArtifactID    string                `json:"ttsArtifactId,omitempty"`
	ToolSteps        int                   `json:"toolSteps,omitempty"`
	LastRemoteTurnID string                `json:"lastRemoteTurnId,omitempty"`
}

func decodeState(raw json.RawMessage) (persistedState, error) {
	if len(raw) == 0 || string(raw) == "null" || string(raw) == "{}" {
		return persistedState{Version: stateVersion}, nil
	}
	var state persistedState
	if err := json.Unmarshal(raw, &state); err != nil {
		return persistedState{}, fmt.Errorf("decode provider state: %w", err)
	}
	if state.Version != stateVersion {
		return persistedState{}, fmt.Errorf("unsupported provider state version %d", state.Version)
	}
	switch state.Phase {
	case "", phaseInput, phaseLLM, phaseTool, phaseTTS, phaseComplete:
	default:
		return persistedState{}, errors.New("provider state contains an invalid phase")
	}
	return state, nil
}

func (worker *Worker) beginTurn(state persistedState, turn application.Turn) persistedState {
	if state.TurnID == turn.ID {
		return state
	}
	state.Messages = trimConversation(state.Messages, worker.config.MaxConversationMessages-1)
	state.TurnID = turn.ID
	state.Phase = phaseInput
	state.PendingTool = nil
	state.InputLanguage = ""
	state.ResponseText = ""
	state.TTSArtifactID = ""
	state.ToolSteps = 0
	state.LastRemoteTurnID = ""
	return state
}

func trimConversation(messages []application.Message, maximum int) []application.Message {
	if maximum <= 0 {
		return nil
	}
	if len(messages) <= maximum {
		return append([]application.Message(nil), messages...)
	}
	start := len(messages) - maximum
	// A retained suffix must begin at a user boundary. Starting with a tool
	// result would produce an invalid stateless provider transcript.
	for start < len(messages) && messages[start].Role != "user" {
		start++
	}
	if start >= len(messages) {
		return nil
	}
	return append([]application.Message(nil), messages[start:]...)
}
