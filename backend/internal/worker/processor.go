package worker

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/toolvalidation"
)

func equivalentJSON(left, right json.RawMessage) bool {
	var leftValue, rightValue any
	leftDecoder := json.NewDecoder(strings.NewReader(string(left)))
	leftDecoder.UseNumber()
	rightDecoder := json.NewDecoder(strings.NewReader(string(right)))
	rightDecoder.UseNumber()
	if leftDecoder.Decode(&leftValue) != nil || rightDecoder.Decode(&rightValue) != nil {
		return false
	}
	return reflect.DeepEqual(leftValue, rightValue)
}

func (worker *Worker) processTurn(ctx context.Context, job application.Job) error {
	if job.SessionID == "" || job.TurnID == "" {
		return permanent("INVALID_JOB", "The process-turn job is missing its resource identity", nil)
	}
	session, err := worker.repository.GetSessionForWorker(ctx, job.SessionID)
	if err != nil {
		return err
	}
	if session.State != application.SessionActive {
		return errTurnStopped
	}
	turn, err := worker.repository.GetTurn(ctx, session.ID, job.TurnID)
	if err != nil {
		return err
	}
	if turn.State.Terminal() {
		return errTurnStopped
	}

	tools, err := toolvalidation.Compile(session.ToolManifest)
	if err != nil {
		return permanent(
			"INVALID_TOOL_MANIFEST",
			"The session tool manifest is invalid",
			err,
		)
	}
	providers, err := worker.providers.ForSession(ctx, session)
	if err != nil {
		return permanent(
			"PROVIDER_PROFILE_UNAVAILABLE",
			"The session provider profile is unavailable",
			err,
		)
	}
	if providers.LLM == nil || providers.Transcriber == nil || providers.Synthesizer == nil {
		return permanent(
			"PROVIDER_PROFILE_INCOMPLETE",
			"The session provider profile does not provide LLM, STT, and TTS",
			nil,
		)
	}

	providerState, err := worker.repository.GetProviderState(ctx, session.ID)
	if err != nil {
		return err
	}
	if providerState.ProviderKind != session.ProviderKind ||
		providerState.ProviderProfile != session.ProviderProfile {
		return permanent(
			"PROVIDER_STATE_MISMATCH",
			"The durable provider state does not match the pinned session profile",
			nil,
		)
	}
	state, err := decodeState(providerState.OpaqueState)
	if err != nil {
		return permanent(
			"PROVIDER_STATE_INVALID",
			"The durable provider state is invalid",
			err,
		)
	}
	state = worker.beginTurn(state, turn)

	processCtx, cancel := context.WithCancelCause(ctx)
	watcherDone := make(chan struct{})
	go worker.watchTurn(processCtx, cancel, session.ID, turn.ID, watcherDone)
	defer func() {
		cancel(nil)
		<-watcherDone
	}()

	if turn.State != application.TurnProcessing && turn.State != application.TurnWaitingForTool {
		turn, err = worker.repository.SetTurnState(
			processCtx, session.ID, turn.ID, application.TurnProcessing, worker.now(),
		)
		if err != nil {
			return err
		}
	}

	if err := worker.prepareInput(
		processCtx, session, &turn, providers, &providerState, &state,
	); err != nil {
		return err
	}
	if err := worker.ensureTurnEvent(
		processCtx, session, turn.ID, "agent.thinking", json.RawMessage(`{}`),
	); err != nil {
		return err
	}

	for {
		if err := worker.requireActive(processCtx, session.ID, turn.ID); err != nil {
			return err
		}
		switch state.Phase {
		case phaseComplete:
			return nil
		case phaseTTS:
			return worker.synthesizeAndComplete(
				processCtx, session, turn, providers, &providerState, &state,
			)
		case phaseTool:
			if state.PendingTool == nil {
				return permanent(
					"PROVIDER_STATE_INVALID",
					"The durable provider state lost its pending tool",
					nil,
				)
			}
			if err := worker.finishPendingTool(
				processCtx, session, turn, tools, &providerState, &state,
			); err != nil {
				return err
			}
			continue
		case phaseLLM:
		default:
			return permanent(
				"PROVIDER_STATE_INVALID",
				"The durable provider state is not ready for model execution",
				nil,
			)
		}

		if err := worker.ensureProviderThread(
			processCtx,
			session,
			turn,
			providers,
			tools,
			&providerState,
			state,
		); err != nil {
			return err
		}
		response, err := worker.stepLLM(
			processCtx, session, turn, providers, tools, providerState, state,
		)
		if err != nil {
			return err
		}
		if response.ThreadID != "" {
			if providerState.RemoteThreadID != "" &&
				providerState.RemoteThreadID != response.ThreadID {
				return permanent(
					"PROVIDER_THREAD_MISMATCH",
					"The provider returned a different conversation thread",
					nil,
				)
			}
			providerState.RemoteThreadID = response.ThreadID
		}
		state.LastRemoteTurnID = response.ResponseID

		if len(response.ToolCalls) > 0 {
			if len(response.ToolCalls) != 1 {
				return permanent(
					"PROVIDER_MALFORMED_RESPONSE",
					"The provider returned more than one device tool call in a step",
					nil,
				)
			}
			if state.ToolSteps >= worker.config.MaxToolSteps {
				return permanent(
					"TOOL_LOOP_LIMIT",
					"The model exceeded the device tool-call limit",
					nil,
				)
			}
			call := response.ToolCalls[0]
			if call.ID == "" || len(call.ID) > 256 {
				return permanent(
					"PROVIDER_MALFORMED_RESPONSE",
					"The provider returned an invalid tool correlation ID",
					nil,
				)
			}
			if err := tools.ValidateArguments(call.Name, call.Arguments); err != nil {
				return permanent(
					"INVALID_TOOL_ARGUMENTS",
					"The model returned device tool arguments that do not match the registered schema",
					err,
				)
			}
			state.Messages = append(state.Messages, application.Message{
				Role:       "assistant",
				Content:    response.Text,
				ToolCallID: call.ID,
				ToolName:   call.Name,
				ToolOutput: append(json.RawMessage(nil), call.Arguments...),
			})
			state.PendingTool = &pendingTool{
				GatewayCallID:  newUUID(),
				ProviderCallID: call.ID,
				Name:           call.Name,
				Arguments:      append(json.RawMessage(nil), call.Arguments...),
			}
			state.ToolSteps++
			state.Phase = phaseTool
			if err := worker.putState(processCtx, session, providerState, state); err != nil {
				return err
			}
			continue
		}

		text := strings.TrimSpace(response.Text)
		if text == "" || !utf8.ValidString(text) ||
			utf8.RuneCountInString(text) > 16_000 {
			return permanent(
				"PROVIDER_MALFORMED_RESPONSE",
				"The provider returned an empty or oversized final response",
				nil,
			)
		}
		state.Messages = append(state.Messages, application.Message{
			Role:    "assistant",
			Content: text,
		})
		state.ResponseText = text
		state.Phase = phaseTTS
		if err := worker.putState(processCtx, session, providerState, state); err != nil {
			return err
		}
		turn, err = worker.repository.SetTurnResponse(
			processCtx, session.ID, turn.ID, text, worker.now(),
		)
		if err != nil {
			return err
		}
		data, _ := json.Marshal(contract.AgentTextFinalData{Text: text})
		if err := worker.ensureTurnEvent(
			processCtx, session, turn.ID, "agent.text.final", data,
		); err != nil {
			return err
		}
	}
}

func (worker *Worker) prepareInput(
	ctx context.Context,
	session application.Session,
	turn *application.Turn,
	providers application.ProviderSet,
	providerState *application.ProviderState,
	state *persistedState,
) error {
	var input string
	switch turn.InputKind {
	case "audio":
		if strings.TrimSpace(turn.Transcript) == "" {
			artifact, err := worker.repository.GetArtifact(
				ctx, session.DeviceID, session.ID, turn.InputArtifactID, worker.now(),
			)
			if err != nil {
				return err
			}
			audio, err := worker.readArtifact(ctx, artifact, domain.MaxInputAudioBytes)
			if err != nil {
				return err
			}
			callCtx, cancel := context.WithTimeout(ctx, worker.config.ProviderTimeout)
			transcription, err := providers.Transcriber.Transcribe(
				callCtx,
				application.TranscriptionRequest{
					Audio:    audio,
					Filename: "turn.wav",
					MIMEType: "audio/wav",
					Model:    providers.STTModel,
					Language: turn.Language,
				},
			)
			cancel()
			if err != nil {
				return err
			}
			text := strings.TrimSpace(transcription.Text)
			if text == "" || !utf8.ValidString(text) ||
				utf8.RuneCountInString(text) > 16_000 {
				return permanent(
					"PROVIDER_MALFORMED_RESPONSE",
					"The transcription provider returned empty or oversized text",
					nil,
				)
			}
			language := validLanguage(transcription.Language)
			if language == "" {
				language = worker.turnLanguage(session, *turn)
			}
			updated, err := worker.repository.SetTurnTranscript(
				ctx, session.ID, turn.ID, text, worker.now(),
			)
			if err != nil {
				return err
			}
			*turn = updated
			turn.Language = language
			state.InputLanguage = language
		}
		input = strings.TrimSpace(turn.Transcript)
		if input == "" {
			return permanent(
				"TRANSCRIPTION_MISSING",
				"The audio turn has no durable transcription",
				nil,
			)
		}
		if err := worker.deleteInputArtifact(ctx, turn.InputArtifactID); err != nil {
			return err
		}
		language := validLanguage(state.InputLanguage)
		if language == "" {
			language = worker.turnLanguage(session, *turn)
		}
		data, _ := json.Marshal(contract.STTFinalData{Text: input, Language: language})
		if err := worker.ensureTurnEvent(
			ctx, session, turn.ID, "stt.final", data,
		); err != nil {
			return err
		}
	case "text":
		input = strings.TrimSpace(turn.InputText)
		if input == "" || !utf8.ValidString(input) ||
			utf8.RuneCountInString(input) > 16_000 {
			return permanent(
				"INVALID_TURN_INPUT",
				"The text turn contains no text",
				nil,
			)
		}
		data, _ := json.Marshal(contract.STTFinalData{
			Text:     input,
			Language: worker.turnLanguage(session, *turn),
		})
		if err := worker.ensureTurnEvent(
			ctx, session, turn.ID, "stt.final", data,
		); err != nil {
			return err
		}
	default:
		return permanent(
			"INVALID_TURN_INPUT",
			"The turn input type is unsupported",
			nil,
		)
	}
	if state.Phase == phaseInput || state.Phase == "" {
		state.Messages = append(state.Messages, application.Message{
			Role:    "user",
			Content: input,
		})
		state.Phase = phaseLLM
		if err := worker.putState(ctx, session, *providerState, *state); err != nil {
			return err
		}
	}
	return nil
}

func (worker *Worker) stepLLM(
	ctx context.Context,
	session application.Session,
	turn application.Turn,
	providers application.ProviderSet,
	tools *toolvalidation.Registry,
	providerState application.ProviderState,
	state persistedState,
) (application.StepResponse, error) {
	request := worker.buildStepRequest(
		session, turn, providers, tools, providerState, state,
	)
	callCtx, cancel := context.WithTimeout(ctx, worker.config.ProviderTimeout)
	defer cancel()
	return providers.LLM.Step(callCtx, request)
}

func (worker *Worker) buildStepRequest(
	session application.Session,
	turn application.Turn,
	providers application.ProviderSet,
	tools *toolvalidation.Registry,
	providerState application.ProviderState,
	state persistedState,
) application.StepRequest {
	definitions := make([]application.ToolDefinition, 0, len(tools.Names()))
	for _, name := range tools.Names() {
		definition, _ := tools.Definition(name)
		definitions = append(definitions, application.ToolDefinition{
			Name:        definition.Name,
			Description: definition.Description,
			InputSchema: definition.InputSchema,
		})
	}
	return application.StepRequest{
		SessionID:    session.ID,
		TurnID:       turn.ID,
		ThreadID:     providerState.RemoteThreadID,
		Model:        providers.Model,
		SystemPrompt: systemPrompt(session),
		Messages:     append([]application.Message(nil), state.Messages...),
		Tools:        definitions,
	}
}

func (worker *Worker) ensureProviderThread(
	ctx context.Context,
	session application.Session,
	turn application.Turn,
	providers application.ProviderSet,
	tools *toolvalidation.Registry,
	providerState *application.ProviderState,
	state persistedState,
) error {
	if providerState.RemoteThreadID != "" || providers.ThreadEnsurer == nil {
		return nil
	}
	request := worker.buildStepRequest(
		session, turn, providers, tools, *providerState, state,
	)
	callCtx, cancel := context.WithTimeout(ctx, worker.config.ProviderTimeout)
	threadID, err := providers.ThreadEnsurer.EnsureThread(callCtx, request)
	cancel()
	if err != nil {
		return err
	}
	if strings.TrimSpace(threadID) == "" || len(threadID) > 256 {
		return permanent(
			"PROVIDER_MALFORMED_RESPONSE",
			"The provider returned an invalid conversation thread ID",
			nil,
		)
	}
	providerState.RemoteThreadID = threadID
	if err := worker.putState(ctx, session, *providerState, state); err != nil {
		if providers.ThreadDeleter != nil {
			deleteContext, deleteCancel := context.WithTimeout(
				context.WithoutCancel(ctx), worker.config.ProviderTimeout,
			)
			deleteErr := providers.ThreadDeleter.DeleteThread(deleteContext, threadID)
			deleteCancel()
			if deleteErr != nil {
				worker.config.Logger.Error(
					"gateway unpersisted provider thread cleanup failed",
					"error", safeError(deleteErr),
				)
			}
		}
		return err
	}
	return nil
}

func (worker *Worker) finishPendingTool(
	ctx context.Context,
	session application.Session,
	turn application.Turn,
	tools *toolvalidation.Registry,
	providerState *application.ProviderState,
	state *persistedState,
) error {
	pending := state.PendingTool
	definition, exists := tools.Definition(pending.Name)
	if !exists {
		return permanent(
			"INVALID_TOOL_CALL",
			"The pending device tool is not registered for this session",
			nil,
		)
	}
	call, err := worker.repository.GetToolCall(ctx, session.ID, pending.GatewayCallID)
	if errors.Is(err, application.ErrNotFound) {
		timeout := time.Duration(definition.TimeoutMS) * time.Millisecond
		if timeout <= 0 || timeout > worker.config.MaxToolTimeout {
			timeout = worker.config.MaxToolTimeout
		}
		now := worker.now()
		deadline := now.Add(timeout)
		eventID := newUUID()
		eventData, _ := json.Marshal(contract.ToolCallData{
			CallID:      pending.GatewayCallID,
			ToolName:    definition.Name,
			ToolVersion: definition.Version,
			Arguments:   pending.Arguments,
			TimeoutMS:   int(timeout / time.Millisecond),
			DeadlineAt:  domain.NewTimestamp(deadline),
		})
		if err := validateOutgoingEvent(
			session.ID,
			turn.ID,
			eventID,
			"tool.call",
			now,
			eventData,
		); err != nil {
			return permanent(
				"INVALID_GATEWAY_EVENT",
				"The worker generated an invalid tool-call event",
				err,
			)
		}
		call, _, err = worker.repository.CreateToolCall(
			ctx,
			application.CreateToolCallParams{
				ToolCall: application.ToolCall{
					ID:         pending.GatewayCallID,
					SessionID:  session.ID,
					TurnID:     turn.ID,
					Name:       definition.Name,
					Version:    definition.Version,
					Owner:      definition.Owner,
					SideEffect: definition.SideEffect,
					Arguments:  pending.Arguments,
					DeadlineAt: deadline,
					TimeoutMS:  int(timeout / time.Millisecond),
					CreatedAt:  now,
					UpdatedAt:  now,
				},
				EventID:   eventID,
				EventData: eventData,
			},
		)
	}
	if err != nil {
		return err
	}
	if call.TurnID != turn.ID || call.Name != pending.Name ||
		!equivalentJSON(call.Arguments, pending.Arguments) {
		return permanent(
			"TOOL_STATE_MISMATCH",
			"The durable tool call does not match provider state",
			nil,
		)
	}
	call, err = worker.waitForTool(ctx, call)
	if err != nil {
		return err
	}
	if call.Status == application.ToolSucceeded {
		if err := tools.ValidateResult(call.Name, call.Output); err != nil {
			return permanent(
				"INVALID_TOOL_RESULT",
				"The device returned a tool result that does not match the registered schema",
				err,
			)
		}
	}
	content, err := toolResultContent(call)
	if err != nil {
		return permanent(
			"INVALID_TOOL_RESULT",
			"The durable device tool result is malformed",
			err,
		)
	}
	state.Messages = append(state.Messages, application.Message{
		Role:       "tool",
		Content:    content,
		ToolCallID: pending.ProviderCallID,
		ToolName:   pending.Name,
	})
	state.PendingTool = nil
	state.Phase = phaseLLM
	if err := worker.putState(ctx, session, *providerState, *state); err != nil {
		return err
	}
	_, err = worker.repository.SetTurnState(
		ctx, session.ID, turn.ID, application.TurnProcessing, worker.now(),
	)
	return err
}

func (worker *Worker) waitForTool(
	ctx context.Context,
	call application.ToolCall,
) (application.ToolCall, error) {
	for {
		if call.Status.Terminal() {
			return call, nil
		}
		now := worker.now()
		if !call.DeadlineAt.After(now) {
			errorData, _ := json.Marshal(domain.ErrorDetail{
				Code:      "TIMEOUT",
				Message:   "Tool result deadline elapsed",
				Retryable: false,
			})
			updated, _, err := worker.repository.UpdateToolCall(
				ctx,
				call.SessionID,
				call.ID,
				application.ToolFailed,
				nil,
				errorData,
				now,
			)
			if errors.Is(err, application.ErrToolTerminal) {
				return worker.repository.GetToolCall(ctx, call.SessionID, call.ID)
			}
			return updated, err
		}
		wait := worker.config.ToolPoll
		if remaining := call.DeadlineAt.Sub(now); wait > remaining {
			wait = remaining
		}
		if err := waitContext(ctx, wait); err != nil {
			return application.ToolCall{}, err
		}
		updated, err := worker.repository.GetToolCall(ctx, call.SessionID, call.ID)
		if err != nil {
			return application.ToolCall{}, err
		}
		call = updated
	}
}

func toolResultContent(call application.ToolCall) (string, error) {
	payload := struct {
		Status string          `json:"status"`
		Output json.RawMessage `json:"output,omitempty"`
		Error  json.RawMessage `json:"error,omitempty"`
	}{
		Status: string(call.Status),
	}
	if call.Status == application.ToolSucceeded {
		if len(call.Output) == 0 || !json.Valid(call.Output) {
			return "", errors.New("successful tool call has no valid output")
		}
		payload.Output = call.Output
	} else {
		if len(call.Error) == 0 || !json.Valid(call.Error) {
			payload.Error = json.RawMessage(
				`{"code":"TOOL_FAILED","message":"Device tool failed","retryable":false}`,
			)
		} else {
			payload.Error = call.Error
		}
	}
	encoded, err := json.Marshal(payload)
	return string(encoded), err
}
