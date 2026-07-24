package worker

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"strings"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contract"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

func (worker *Worker) synthesizeAndComplete(
	ctx context.Context,
	session application.Session,
	turn application.Turn,
	providers application.ProviderSet,
	providerState *application.ProviderState,
	state *persistedState,
) error {
	text := strings.TrimSpace(state.ResponseText)
	if text == "" {
		text = strings.TrimSpace(turn.ResponseText)
	}
	if text == "" {
		return permanent(
			"PROVIDER_STATE_INVALID",
			"The final response text was not persisted before speech synthesis",
			nil,
		)
	}
	if err := worker.ensureFinalText(
		ctx, session, &turn, text,
	); err != nil {
		return err
	}
	if state.TTSArtifactID == "" {
		state.TTSArtifactID = newUUID()
		if err := worker.putState(ctx, session, *providerState, *state); err != nil {
			return err
		}
	}

	artifact, err := worker.repository.GetArtifact(
		ctx, session.DeviceID, session.ID, state.TTSArtifactID, worker.now(),
	)
	if errors.Is(err, application.ErrArtifactGone) {
		state.TTSArtifactID = newUUID()
		if stateErr := worker.putState(ctx, session, *providerState, *state); stateErr != nil {
			return stateErr
		}
		artifact, err = worker.createSpeechArtifact(
			ctx, session, turn, providers, state.TTSArtifactID, text,
		)
	} else if errors.Is(err, application.ErrNotFound) {
		artifact, err = worker.createSpeechArtifact(
			ctx, session, turn, providers, state.TTSArtifactID, text,
		)
	}
	if err != nil {
		return err
	}
	data, _ := json.Marshal(contract.TTSReadyData{
		ArtifactID: artifact.ID,
		MIMEType:   artifact.MIMEType,
		ByteLength: int(artifact.ByteLength),
		SHA256:     hex.EncodeToString(artifact.SHA256),
		ExpiresAt:  domain.NewTimestamp(artifact.ExpiresAt),
	})
	if err := worker.ensureTurnEvent(
		ctx, session, turn.ID, "tts.ready", data,
	); err != nil {
		return err
	}
	_, changed, err := worker.repository.FinishTurn(
		ctx,
		session.ID,
		turn.ID,
		application.TurnCompleted,
		text,
		nil,
		"turn.completed",
		json.RawMessage(`{}`),
		worker.now(),
	)
	if err != nil {
		return err
	}
	if !changed {
		current, getErr := worker.repository.GetTurn(ctx, session.ID, turn.ID)
		if getErr != nil {
			return getErr
		}
		if current.State != application.TurnCompleted {
			return errTurnStopped
		}
	}
	state.Phase = phaseComplete
	return worker.putState(ctx, session, *providerState, *state)
}

// ensureFinalText closes both crash windows after phaseTTS is persisted:
// before the turn response update and before agent.text.final is appended.
// The durable turn field is written first and the event helper deduplicates by
// turn/type, so every recovery observes the required event order without
// emitting a second final response.
func (worker *Worker) ensureFinalText(
	ctx context.Context,
	session application.Session,
	turn *application.Turn,
	text string,
) error {
	durableText := strings.TrimSpace(turn.ResponseText)
	if durableText == "" {
		updated, err := worker.repository.SetTurnResponse(
			ctx, session.ID, turn.ID, text, worker.now(),
		)
		if err != nil {
			return err
		}
		*turn = updated
	} else if durableText != text {
		return permanent(
			"PROVIDER_STATE_MISMATCH",
			"The durable turn response does not match the provider state",
			nil,
		)
	}
	data, _ := json.Marshal(contract.AgentTextFinalData{Text: text})
	return worker.ensureTurnEvent(
		ctx, session, turn.ID, "agent.text.final", data,
	)
}

func (worker *Worker) createSpeechArtifact(
	ctx context.Context,
	session application.Session,
	turn application.Turn,
	providers application.ProviderSet,
	artifactID, text string,
) (application.Artifact, error) {
	callCtx, cancel := context.WithTimeout(ctx, worker.config.ProviderTimeout)
	speech, err := providers.Synthesizer.Synthesize(callCtx, application.SpeechRequest{
		Text:     text,
		Model:    providers.TTSModel,
		Voice:    providers.Voice,
		Format:   "mp3",
		Language: worker.turnLanguage(session, turn),
		Speed:    1,
	})
	cancel()
	if err != nil {
		return application.Artifact{}, err
	}
	if len(speech.Audio) == 0 || len(speech.Audio) > domain.MaxOutputAudioBytes {
		return application.Artifact{}, permanent(
			"PROVIDER_MALFORMED_RESPONSE",
			"The speech provider returned empty or oversized audio",
			nil,
		)
	}
	mimeType, extension, err := normalizeSpeechFormat(speech.MIMEType, speech.Format)
	if err != nil {
		return application.Artifact{}, err
	}
	key, err := blob.Key(session.ID, artifactID, extension)
	if err != nil {
		return application.Artifact{}, err
	}
	object, err := worker.blobs.Put(
		ctx,
		key,
		bytes.NewReader(speech.Audio),
		domain.MaxOutputAudioBytes,
	)
	if err != nil {
		return application.Artifact{}, err
	}
	now := worker.now()
	pending := application.Artifact{
		ID:         artifactID,
		SessionID:  session.ID,
		TurnID:     turn.ID,
		Kind:       "tts_audio",
		BlobKey:    object.Key,
		MIMEType:   mimeType,
		ByteLength: object.Size,
		SHA256:     object.SHA256[:],
		CreatedAt:  now,
		ExpiresAt:  now.Add(worker.config.TTSLifetime),
	}
	artifact, createErr := worker.repository.CreateArtifact(ctx, pending)
	if createErr == nil {
		return artifact, nil
	}
	// A crash may have committed metadata before job completion. Reuse that
	// exact artifact rather than synthesizing a second externally visible ID.
	existing, getErr := worker.repository.GetArtifact(
		ctx, session.DeviceID, session.ID, artifactID, now,
	)
	if getErr == nil {
		return existing, nil
	}
	_ = worker.blobs.Delete(context.WithoutCancel(ctx), key)
	return application.Artifact{}, createErr
}

func normalizeSpeechFormat(mimeType, format string) (string, string, error) {
	mimeType = strings.TrimSpace(strings.Split(mimeType, ";")[0])
	format = strings.ToLower(strings.TrimSpace(format))
	switch {
	case mimeType == "audio/mpeg", mimeType == "" && (format == "mp3" || format == "mpeg"):
		return "audio/mpeg", "mp3", nil
	case mimeType == "audio/wav", mimeType == "audio/x-wav",
		mimeType == "" && (format == "wav" || format == "wave"):
		return "audio/wav", "wav", nil
	default:
		return "", "", permanent(
			"PROVIDER_MALFORMED_RESPONSE",
			"The speech provider returned an unsupported audio format",
			nil,
		)
	}
}

func (worker *Worker) readArtifact(
	ctx context.Context,
	artifact application.Artifact,
	maximum int64,
) ([]byte, error) {
	reader, object, err := worker.blobs.Open(ctx, artifact.BlobKey)
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	if object.Size != artifact.ByteLength || object.Size <= 0 || object.Size > maximum {
		return nil, permanent(
			"INVALID_AUDIO_ARTIFACT",
			"The input audio metadata does not match the stored object",
			nil,
		)
	}
	value, err := io.ReadAll(io.LimitReader(reader, maximum+1))
	if err != nil {
		return nil, err
	}
	if int64(len(value)) != artifact.ByteLength {
		return nil, permanent(
			"INVALID_AUDIO_ARTIFACT",
			"The input audio length changed while it was read",
			nil,
		)
	}
	digest := sha256.Sum256(value)
	if len(artifact.SHA256) != sha256.Size ||
		subtle.ConstantTimeCompare(digest[:], artifact.SHA256) != 1 {
		return nil, permanent(
			"INVALID_AUDIO_ARTIFACT",
			"The input audio digest does not match durable metadata",
			nil,
		)
	}
	return value, nil
}

func (worker *Worker) deleteInputArtifact(ctx context.Context, artifactID string) error {
	if artifactID == "" {
		return permanent(
			"INVALID_AUDIO_ARTIFACT",
			"The audio turn has no input artifact",
			nil,
		)
	}
	lease, err := worker.repository.LeaseArtifactForDeletion(
		ctx,
		artifactID,
		worker.config.WorkerID,
		worker.now(),
		worker.config.ArtifactDeletionLease,
	)
	if errors.Is(err, application.ErrNotFound) || errors.Is(err, application.ErrLeaseLost) {
		// Another sweeper has already claimed or deleted it.
		return nil
	}
	if err != nil {
		return err
	}
	return worker.deleteArtifactLease(ctx, lease)
}
