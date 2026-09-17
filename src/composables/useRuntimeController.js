import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useAudioPlayback } from './useAudioPlayback';
import { useVAD } from './useVAD';
import { DeviceToolRegistry } from '../services/deviceToolRegistry';
import { consumeBootstrapToken, RuntimeTransport } from '../services/runtimeTransport';
import { createRuntimeTimers } from '../services/runtimeTimers';
import { decodeLocalControl } from '../services/localControl';
import { headPressAction, InteractionAction } from '../services/interactionPolicy';
import { publicSettings, runtimeSettingsBody } from '../services/runtimeSettings';
import { ToolOwner, toolOwner } from '../services/toolOwnership';
import {
  CONNECTION_STATES,
  RuntimeEventType,
  SUPPORTED_EMOTIONS,
  TURN_STATES,
  useRuntimeStore,
} from '../stores/runtime';

function createId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

function eventPayload(envelope) {
  return envelope?.data || {};
}

function isRecoverableDisconnect(error) {
  return error?.code === 'GATEWAY_OFFLINE' && error.retryable === true;
}

export function gatewayErrorState(error) {
  const codeStates = {
    GATEWAY_UNCONFIGURED: CONNECTION_STATES.UNCONFIGURED,
    SETUP_REQUIRED: CONNECTION_STATES.UNCONFIGURED,
    GATEWAY_AUTH: CONNECTION_STATES.AUTH_ERROR,
    UNAUTHORIZED: CONNECTION_STATES.AUTH_ERROR,
    FORBIDDEN_ORIGIN: CONNECTION_STATES.AUTH_ERROR,
    INVALID_BOOTSTRAP_TOKEN: CONNECTION_STATES.AUTH_ERROR,
    SESSION_EXPIRED: CONNECTION_STATES.AUTH_ERROR,
    GATEWAY_TLS: CONNECTION_STATES.TLS_ERROR,
    GATEWAY_INCOMPATIBLE: CONNECTION_STATES.INCOMPATIBLE,
    GATEWAY_OFFLINE: CONNECTION_STATES.OFFLINE,
    TIMEOUT: CONNECTION_STATES.OFFLINE,
    RATE_LIMITED: CONNECTION_STATES.DEGRADED,
    INTERNAL_ERROR: CONNECTION_STATES.DEGRADED,
  };
  const canonicalState = codeStates[String(error?.code || '').toUpperCase()];
  if (canonicalState) return canonicalState;
  const message = String(error?.message || error || '').toLowerCase();
  if (/\b(401|403|auth|token|credential|unauthor)/.test(message)) {
    return CONNECTION_STATES.AUTH_ERROR;
  }
  if (/tls|certificate|spki|pin mismatch/.test(message)) return CONNECTION_STATES.TLS_ERROR;
  if (/protocol|version|incompatible/.test(message)) return CONNECTION_STATES.INCOMPATIBLE;
  return CONNECTION_STATES.OFFLINE;
}

function normalizedGatewayState(value) {
  const state = String(value || '').toUpperCase();
  if (Object.values(CONNECTION_STATES).includes(state)) return state;
  if (state === 'RECONNECTING') return CONNECTION_STATES.DEGRADED;
  if (state === 'CONNECTED') return CONNECTION_STATES.READY;
  if (state === 'DISABLED' || state === 'STOPPED') return CONNECTION_STATES.UNCONFIGURED;
  return CONNECTION_STATES.OFFLINE;
}

export function useRuntimeController(options = {}) {
  const runtime = useRuntimeStore();
  const transport = options.transport || new RuntimeTransport(options.transportOptions);
  const tools = options.tools || new DeviceToolRegistry();
  const playback = options.playback || useAudioPlayback();
  const savingSettings = ref(false);
  const testingSettings = ref(false);
  const settingsTestResult = ref(null);
  const motionUpdating = ref(false);
  const motionError = ref('');
  const started = ref(false);
  const disposers = [];
  const timers = options.timers || createRuntimeTimers(options.timerOptions);
  let listeningGeneration = 0;
  let robotStateRevision = 0;
  let speechTurnId = '';
  let activePlaylist = null;
  let pendingVoiceTurn = null;

  function captureIsCurrent(generation = listeningGeneration) {
    return generation === listeningGeneration &&
      !runtime.sleeping && runtime.connectionState === CONNECTION_STATES.READY;
  }

  async function applyGatewayState(state, detail = '') {
    const reconnecting = [
      CONNECTION_STATES.OFFLINE, CONNECTION_STATES.CONNECTING, CONNECTION_STATES.DEGRADED,
    ].includes(state);
    runtime.setConnection(state, runtime.turnState === TURN_STATES.ERROR
      ? runtime.error
      : reconnecting || state === CONNECTION_STATES.READY ? '' : detail);
    if (reconnecting) {
      timers.clearAll();
      // LISTENING and TURN_BUSY audio have not been accepted by Native. An
      // uploaded/remote turn keeps its identity until Native sends a terminal.
      const localOnly = runtime.turnState === TURN_STATES.LISTENING || pendingVoiceTurn?.retryPending;
      speechTurnId = '';
      pendingVoiceTurn = null;
      runtime.waitingForPreviousTurn = false;
      if (localOnly) runtime.transition('reset', { turnId: '' });
      await pauseListening();
    } else if (state === CONNECTION_STATES.READY) {
      await enterListening();
    }
  }

  function applyNativeRobotStatus(status) {
    robotStateRevision += 1;
    runtime.motionEnabled = status?.motionEnabled === true;
    if (typeof status?.robotReady === 'boolean') runtime.robotReady = status.robotReady;
  }

  async function setMotionEnabled(enabled) {
    if (motionUpdating.value || typeof enabled !== 'boolean') return false;
    motionUpdating.value = true;
    motionError.value = '';
    const revision = robotStateRevision;
    try {
      const result = await transport.setMotionEnabled(enabled);
      if (typeof result?.motionEnabled !== 'boolean' || typeof result?.moving !== 'boolean') {
        throw new Error('本機 Runtime 回覆的動作狀態無效。');
      }
      // A newer Native robot event may arrive before this HTTP response.
      if (revision === robotStateRevision) {
        runtime.motionEnabled = result.motionEnabled;
        runtime.robotMoving = result.moving;
      }
      return true;
    } catch (error) {
      motionError.value = `未收到動作設定確認：${error.message || '請稍後再試。'}`;
      return false;
    } finally {
      motionUpdating.value = false;
    }
  }

  async function submitPendingVoiceTurn(pending) {
    if (pendingVoiceTurn !== pending || !captureIsCurrent()) return;
    pending.retryPending = false;
    try {
      const response = await transport.uploadVoiceTurn({
        turnId: pending.turnId,
        audio: pending.blob,
        language: runtime.settings.language,
      });
      if (pendingVoiceTurn !== pending || runtime.sleeping) return;
      pendingVoiceTurn = null;
      runtime.waitingForPreviousTurn = false;
      if (response?.type) await handleRuntimeEvent(response);
      else if (runtime.activeTurnId === pending.turnId && runtime.turnState === TURN_STATES.UPLOADING) {
        runtime.transition('transcription_started', { turnId: pending.turnId });
      }
    } catch (error) {
      // Native may reject the multipart request before creating a turn, so no
      // terminal event exists. A turn.accepted event moves us past UPLOADING.
      if (isRecoverableDisconnect(error) && runtime.activeTurnId === pending.turnId &&
          runtime.turnState === TURN_STATES.UPLOADING) {
        await convergeTurnError('', true);
        return;
      }
      if (pendingVoiceTurn !== pending || runtime.sleeping) return;
      if (error.code === 'TURN_BUSY') {
        pending.retryPending = true;
        runtime.waitingForPreviousTurn = true;
        timers.scheduleTurnRetry(() => { void submitPendingVoiceTurn(pending); });
        return;
      }
      pendingVoiceTurn = null;
      runtime.waitingForPreviousTurn = false;
      if (runtime.activeTurnId === pending.turnId &&
          [TURN_STATES.UPLOADING, TURN_STATES.TRANSCRIBING].includes(runtime.turnState)) {
        runtime.transition('failed', { turnId: '', error: error.message });
      } else {
        runtime.error = error.message;
      }
    }
  }

  const vad = options.vad || (options.createVAD || useVAD)({
    onSpeechStart: async () => {
      const generation = listeningGeneration;
      if (!captureIsCurrent(generation)) return;
      timers.clearInactivity();
      if (playback.isPlaying.value) await stopResponse('barge-in');
      if (!captureIsCurrent(generation)) return;
      if (runtime.activeTurnId) await transport.cancelTurn(runtime.activeTurnId, 'barge_in');
      if (!captureIsCurrent(generation)) return;
      speechTurnId = createId();
      runtime.transition('speech_started', {
        turnId: speechTurnId, transcript: '', assistantText: '', error: '',
      });
    },
    onSpeechEnd: async ({ blob }) => {
      if (!captureIsCurrent() || !speechTurnId) return;
      timers.clearInactivity();
      const turnId = speechTurnId;
      speechTurnId = '';
      const paused = pauseListening();
      const generation = listeningGeneration;
      await paused;
      if (!captureIsCurrent(generation) || runtime.activeTurnId !== turnId) return;
      runtime.transition('upload_started', { turnId });
      pendingVoiceTurn = { turnId, blob };
      await submitPendingVoiceTurn(pendingVoiceTurn);
    },
    onError: (error) => runtime.transition('failed', { error: error.message }),
  });

  tools.register({
    name: 'show_emotion',
    owner: 'web',
    version: '1.0.0',
    description: 'Queue an allow-listed emotion for the next local speech playlist.',
    inputSchema: {
      type: 'object',
      required: ['emotion'],
      additionalProperties: false,
      properties: {
        emotion: { type: 'string', enum: [...SUPPORTED_EMOTIONS] },
        durationMs: { type: 'integer', minimum: 0, maximum: 30000 },
      },
    },
    resultSchema: {
      type: 'object',
      required: ['ok', 'emotion', 'durationMs'],
      additionalProperties: false,
      properties: {
        ok: { type: 'boolean' },
        emotion: { type: 'string', enum: [...SUPPORTED_EMOTIONS] },
        durationMs: { type: 'integer', minimum: 0, maximum: 30000 },
      },
    },
    sideEffect: 'ui',
    idempotent: true,
    requiresConfirmation: false,
    handler: ({ emotion, durationMs = 0 }) => {
      runtime.queueEmotion(emotion, durationMs);
      return { ok: true, emotion, durationMs };
    },
  });

  async function convergeTurnError(message = 'Agent turn failed.', reconnectable = false) {
    timers.clearAll();
    speechTurnId = '';
    await Promise.allSettled([pauseListening(), stopResponse('turn-error')]);
    runtime.transition(reconnectable ? 'reset' : 'failed', {
      turnId: '',
      error: reconnectable ? '' : message || 'Agent turn failed.',
    });
    if (reconnectable) await enterListening();
  }

  async function applyAuthoritativeConversation(conversation = {}, errorMessage = '') {
    if (activePlaylist || pendingVoiceTurn) {
      await Promise.allSettled([stopResponse('runtime-recovery'), pauseListening()]);
    }
    runtime.applyConversationSnapshot(conversation);
    transport.resetCursor(runtime.lastSequence);
    if (runtime.turnState === TURN_STATES.ERROR) {
      await convergeTurnError(errorMessage || 'Agent turn failed.');
    }
  }

  async function refreshAuthoritativeRuntime(errorMessage = '') {
    const status = await transport.getRuntimeStatus();
    applyNativeRobotStatus(status);
    const conversation = await transport.getConversation();
    await applyAuthoritativeConversation(conversation, errorMessage);
    const gateway = status?.gateway || status || {};
    const state = normalizedGatewayState(gateway.state || status?.gatewayState);
    await applyGatewayState(state, runtime.error || errorMessage);
  }

  tools.register({
    name: 'go_to_sleep',
    owner: 'web',
    version: '1.0.0',
    description: 'Pause local listening and show the sleeping face until the user wakes Zenbo.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    resultSchema: {
      type: 'object',
      required: ['ok', 'sleeping'],
      additionalProperties: false,
      properties: { ok: { type: 'boolean' }, sleeping: { type: 'boolean' } },
    },
    sideEffect: 'ui',
    idempotent: true,
    requiresConfirmation: false,
    handler: async () => {
      // The tool result must be acknowledged before cancelling its parent turn.
      // Native stops robot motion when it accepts this tool.
      await enterSleep('sleep', { skipCancel: true });
      return { ok: true, sleeping: true };
    },
  });

  async function stopResponse(reason = 'client-cancelled') {
    pendingVoiceTurn = null;
    timers.clearTurnRetry();
    runtime.waitingForPreviousTurn = false;
    const playlist = activePlaylist;
    activePlaylist = null;
    if (playlist) playlist.cancelled = true;
    await playback.stop(reason);
    if (playlist?.current) await playlist.current.interrupt(reason);
    runtime.mouthLevel = 0;
    runtime.resetEmotion();
  }

  async function playResponse(envelope) {
    const payload = eventPayload(envelope);
    const turnId = envelope.turnId || runtime.activeTurnId;
    const artifacts = payload.artifacts;
    if (!Array.isArray(artifacts) || !artifacts.length ||
        artifacts.some((artifact) => !artifact?.artifactId)) {
      await convergeTurnError('Native 未提供有效的語音播放清單。');
      return;
    }
    if (activePlaylist) {
      await convergeTurnError('Native 重複送出同一回合的語音播放清單。');
      return;
    }
    const playlist = { turnId, artifacts, cancelled: false, started: false, current: null };
    activePlaylist = playlist;
    const isCurrent = () => activePlaylist === playlist && !playlist.cancelled;
    runtime.transition('synthesis_started', { turnId });
    timers.clearAll();

    async function playSegment(index) {
      if (!isCurrent()) return;
      const artifact = artifacts[index];
      let startedReport = Promise.resolve();
      let terminalReport = null;
      let segmentTerminal = false;
      const reportPlayback = (status, extra = {}) =>
        transport.sendPlayback(status, turnId, { artifactId: artifact.artifactId, ...extra })
          .catch((error) => {
            if (isCurrent()) runtime.error = `播放狀態回報失敗：${error.message}`;
          });
      const reportTerminal = (status, extra = {}) => {
        if (!terminalReport) {
          terminalReport = startedReport.then(() => reportPlayback(status, extra));
        }
        return terminalReport;
      };
      const interrupt = async (reason) => {
        segmentTerminal = true;
        const reasons = {
          'barge-in': 'barge_in',
          sleep: 'screen_off',
          'screen-off': 'screen_off',
        };
        await reportTerminal('interrupted', {
          reason: reasons[reason] || 'client_cancelled',
        });
      };
      playlist.current = { interrupt };
      const failPlayback = async (error) => {
        if (!isCurrent() || segmentTerminal) return;
        segmentTerminal = true;
        await reportTerminal('interrupted', { reason: 'playback_error' });
        if (!isCurrent()) return;
        activePlaylist = null;
        playlist.cancelled = true;
        await playback.stop('playback-error');
        runtime.transition('failed', { turnId: '', error: error.message });
      };
      try {
        const blob = await transport.resolveAudio(artifact);
        if (!isCurrent()) return;
        await pauseListening();
        if (!isCurrent()) return;
        await playback.play(blob, {
          onStarted: () => {
            if (!isCurrent() || segmentTerminal) return;
            if (!playlist.started) {
              playlist.started = true;
              runtime.activatePendingEmotion();
            }
            runtime.transition('playback_started', { turnId });
            startedReport = reportPlayback('started');
          },
          onEnded: async () => {
            if (!isCurrent() || segmentTerminal) return;
            segmentTerminal = true;
            await reportTerminal('completed');
            if (!isCurrent()) return;
            if (index + 1 < artifacts.length) {
              await playSegment(index + 1);
              return;
            }
            activePlaylist = null;
            runtime.transition('reset', { turnId: '' });
            if (!runtime.sleeping) scheduleListeningResume();
          },
          onInterrupted: (reason) => {
            void interrupt(reason);
            if (isCurrent()) {
              activePlaylist = null;
              playlist.cancelled = true;
              runtime.resetEmotion();
            }
          },
          onError: (error) => void failPlayback(error),
        });
      } catch (error) {
        await failPlayback(error);
      }
    }
    await playSegment(0);
  }

  async function handleRuntimeEvent(envelope) {
    if (!envelope) return;
    const localControl = decodeLocalControl(envelope);
    if (localControl) {
      if (localControl.kind === 'invalid') {
        transport.failProtocol(new Error(localControl.message));
        return;
      }
      if (envelope.sequence <= runtime.lastSequence) return;
      // Native recovery controls may jump over evicted history. Refresh the
      // authoritative conversation instead of applying an incomplete sequence.
      if (localControl.kind === 'gateway' && envelope.sequence !== runtime.lastSequence + 1) {
        await refreshAuthoritativeRuntime(localControl.detail);
        if (runtime.connectionState === CONNECTION_STATES.READY) {
          runtime.recoveryNotice = '連線已恢復，請重新說一次';
        }
        return;
      }
      if (!runtime.applyEnvelope(envelope)) {
        transport.failProtocol(new Error(runtime.error));
        return;
      }
      switch (localControl.kind) {
        case 'gateway':
          await applyGatewayState(localControl.state, localControl.detail);
          break;
        case 'robot':
          robotStateRevision += 1;
          runtime.robotReady = localControl.ready;
          runtime.robotMoving = localControl.moving;
          runtime.motionEnabled = localControl.motionEnabled;
          break;
        case 'screen':
          if (localControl.state === 'OFF') await enterSleep('screen-off');
          break;
        case 'interaction':
          await toggleListening();
          break;
        default:
          break;
      }
      runtime.commitEnvelope(envelope);
      transport.acknowledge(envelope.sequence);
      return;
    }
    const payload = eventPayload(envelope);
    const type = envelope.type || '';
    const sequence = Number(envelope.sequence || 0);
    if (!runtime.applyEnvelope(envelope)) {
      const isRetainedDuplicate =
        Object.values(RuntimeEventType).includes(envelope.type) &&
        envelope.type !== RuntimeEventType.SESSION_SNAPSHOT &&
        Number.isInteger(sequence) &&
        sequence > 0 &&
        sequence <= runtime.lastSequence;
      if (!isRetainedDuplicate) {
        transport.failProtocol(
          new Error(runtime.error || '收到不合法的 Native 事件。'),
        );
      }
      return;
    }
    const turnId = envelope.turnId || '';
    const toolCallCanRun =
      runtime.turnState === TURN_STATES.THINKING ||
      runtime.turnState === TURN_STATES.AWAITING_TOOL;
    const isCurrentToolCall =
      type === RuntimeEventType.TOOL_CALL &&
      Boolean(turnId) &&
      Boolean(runtime.activeTurnId) &&
      turnId === runtime.activeTurnId &&
      toolCallCanRun;
    const owner = type === RuntimeEventType.TOOL_CALL ? toolOwner(payload.toolName) : null;

    if (type === RuntimeEventType.TOOL_CALL && owner === ToolOwner.NATIVE) {
      if (isCurrentToolCall) runtime.transition('tool_started', { turnId });
      runtime.commitEnvelope(envelope);
      transport.acknowledge(envelope.sequence);
      return;
    }

    if (
      type === RuntimeEventType.TOOL_CALL &&
      !isCurrentToolCall
    ) {
      await transport.sendToolResult({
        callId: payload.callId,
        name: payload.toolName,
        status: 'rejected',
        error: {
          code: 'STALE_TURN',
          message: 'Tool call does not belong to the active user turn.',
          retryable: false,
        },
      });
      runtime.commitEnvelope(envelope);
      transport.acknowledge(envelope.sequence);
      return;
    }

    const currentTurnIsTerminal = [TURN_STATES.IDLE, TURN_STATES.ERROR].includes(
      runtime.turnState,
    );
    if (
      turnId &&
      (currentTurnIsTerminal || turnId !== runtime.activeTurnId)
    ) {
      runtime.commitEnvelope(envelope);
      transport.acknowledge(envelope.sequence);
      return;
    }

    switch (type) {
      case RuntimeEventType.SESSION_READY:
        await applyGatewayState(CONNECTION_STATES.READY);
        break;
      case RuntimeEventType.SESSION_SNAPSHOT:
        await applyAuthoritativeConversation(await transport.getConversation());
        return;
      case RuntimeEventType.TURN_ACCEPTED:
        runtime.transition('transcription_started', { turnId });
        break;
      case RuntimeEventType.STT_FINAL:
        runtime.transition('thinking_started', {
          turnId,
          transcript: payload.text || '',
        });
        break;
      case RuntimeEventType.AGENT_THINKING:
        runtime.transition('thinking_started', { turnId });
        break;
      case RuntimeEventType.AGENT_TEXT_FINAL:
        runtime.transition(payload.final === false ? 'thinking_started' : 'synthesis_started', {
          turnId,
          assistantText: payload.text || '',
        });
        break;
      case RuntimeEventType.TOOL_CALL: {
        runtime.transition('tool_started', { turnId });
        let sleepAccepted = false;
        try {
          const result = await tools.execute(envelope, {
            onAccepted: (accepted) => {
              sleepAccepted = payload.toolName === 'go_to_sleep';
              return transport.sendToolResult(accepted);
            },
          });
          await transport.sendToolResult(result);
        } catch (error) {
          if (!sleepAccepted) throw error;
          // An ACK can be lost after Native has committed the result. Do not
          // send another terminal result; preserve sleep and request safe stop.
          runtime.error = '已進入休眠，但休眠工具結果回報失敗；已要求停止目前回合。';
        } finally {
          if (sleepAccepted) {
            const localSleep = runtime.sleeping
              ? Promise.resolve()
              : enterSleep('sleep', { skipCancel: true });
            await transport.cancelTurn(turnId, 'sleep');
            await localSleep;
          }
        }
        break;
      }
      case RuntimeEventType.TTS_READY:
        // Artifact downloads and autoplay promises must not block later Native
        // cancellation, head-press, or screen-off events in the event queue.
        void playResponse(envelope).catch((error) => convergeTurnError(error.message));
        break;
      case RuntimeEventType.TURN_COMPLETED:
        runtime.activeTurnId = '';
        if (!activePlaylist && !playback.isPlaying.value) {
          runtime.transition('reset', { turnId: '' });
          if (!runtime.sleeping) scheduleListeningResume();
        }
        break;
      case RuntimeEventType.TURN_ERROR:
        await convergeTurnError(
          payload.message || payload.error?.message || payload.error || 'Agent turn failed.',
          isRecoverableDisconnect(payload.error),
        );
        break;
      case RuntimeEventType.TURN_CANCELLED:
        await stopResponse('client-cancelled');
        runtime.transition('reset', { turnId: '' });
        break;
      case RuntimeEventType.SESSION_CLOSED:
      case RuntimeEventType.SESSION_EXPIRED:
        await stopResponse('session-closed');
        await pauseListening();
        runtime.setConnection(
          payload.reason === 'credential_revoked'
            ? CONNECTION_STATES.AUTH_ERROR
            : CONNECTION_STATES.OFFLINE,
          'Native session 已結束，請檢查設定後重新連線。',
        );
        break;
      default:
        break;
    }

    runtime.commitEnvelope(envelope);
    transport.acknowledge(envelope.sequence);
  }

  async function connect() {
    runtime.setConnection(CONNECTION_STATES.CONNECTING);
    runtime.lastSequence = 0;
    try {
      if (!transport.bootstrapped) throw new Error('本機 Renderer session 尚未完成 bootstrap。');
      await refreshAuthoritativeRuntime();
      transport.connectEvents({ after: runtime.lastSequence });
    } catch (error) {
      runtime.setConnection(gatewayErrorState(error), error.message);
      if (!runtime.settings.onboardingComplete) runtime.settingsOpen = true;
    }
  }

  async function loadSettings() {
    try {
      const response = await transport.getRuntimeSettings();
      const nativeSettings = response.settings || response.data || response;
      runtime.patchSettings({
        ...publicSettings(nativeSettings),
        ...(nativeSettings.pinConfigured !== undefined
          ? { onboardingComplete: Boolean(nativeSettings.pinConfigured) }
          : {}),
      });
    } catch {
      // Native remains authoritative; defaults are only an in-memory startup fallback.
    }
    runtime.settingsOpen = !runtime.settings.onboardingComplete;
    if (transport.bootstrapped && !runtime.settings.onboardingComplete) {
      runtime.setConnection(CONNECTION_STATES.UNCONFIGURED);
    }
  }

  async function unlockSettings(settings) {
    const pin = String(settings.unlockPin || '');
    if (!/^[0-9]{6,12}$/.test(pin)) throw new Error('Unlock PIN 必須是 6–12 位數字。');
    return transport.unlockRuntimeSettings({ pin });
  }

  async function testSettings(settings) {
    testingSettings.value = true;
    settingsTestResult.value = null;
    runtime.error = '';
    try {
      if (runtime.settings.onboardingComplete) await unlockSettings(settings);
      const trustMode = settings.trustMode || 'SYSTEM_TRUST';
      const apiKey = String(settings.apiKey || '').trim();
      settingsTestResult.value = await transport.testRuntimeSettings({
        gatewayUrl: String(settings.gatewayUrl || '').trim(),
        trustMode,
        ...(trustMode === 'SYSTEM_TRUST' && apiKey ? { apiKey } : {}),
      });
    } catch (error) {
      runtime.error = `Hermes 測試失敗：${error.message}`;
    } finally {
      settings.unlockPin = '';
      settings.apiKey = '';
      testingSettings.value = false;
    }
  }

  async function saveSettings(settings) {
    savingSettings.value = true;
    runtime.error = '';
    const safeSettings = publicSettings(settings);
    const apiKey = String(settings.apiKey || '').trim();
    try {
      const confirmedFingerprint =
        safeSettings.trustMode === 'CONFIRMED_SPKI_PIN'
          ? String(settings.confirmedFingerprint || '')
          : '';
      if (
        safeSettings.trustMode === 'CONFIRMED_SPKI_PIN' &&
        (!safeSettings.certificatePin || confirmedFingerprint !== safeSettings.certificatePin)
      ) {
        throw new Error('必須明確確認本次 TLS 測試取得的 SPKI fingerprint。');
      }
      const settingsBody = runtimeSettingsBody(safeSettings, apiKey, confirmedFingerprint);
      if (runtime.settings.onboardingComplete) {
        await unlockSettings(settings);
        await transport.putRuntimeSettings(settingsBody);
      } else {
        const setupPin = String(settings.pin || '');
        const confirmPin = String(settings.confirmPin || '');
        if (!/^[0-9]{6,12}$/.test(setupPin) || setupPin !== confirmPin) {
          throw new Error('Setup PIN 與確認 PIN 不一致。');
        }
        await transport.setupRuntimeSettings({ ...settingsBody, pin: setupPin, confirmPin });
      }
      runtime.patchSettings(safeSettings);
      await loadSettings();
      runtime.settingsOpen = false;
      settingsTestResult.value = null;
    } catch (error) {
      runtime.error = `本機 Runtime 尚未儲存設定：${error.message}`;
      runtime.settingsOpen = true;
      return;
    } finally {
      settings.apiKey = '';
      settings.pin = '';
      settings.confirmPin = '';
      settings.unlockPin = '';
      savingSettings.value = false;
    }

    transport.close();
    await stopResponse('settings changed');
    await pauseListening();
    await connect();
  }

  async function wakeUp() {
    runtime.wakeUp();
    if (runtime.connectionState === CONNECTION_STATES.READY) {
      await enterListening();
    }
  }

  function pauseListening() {
    listeningGeneration += 1;
    return vad.pause();
  }

  async function enterListening() {
    timers.clearResume();
    if (
      runtime.connectionState !== CONNECTION_STATES.READY ||
      runtime.sleeping ||
      runtime.turnState !== TURN_STATES.IDLE ||
      runtime.activeTurnId
    ) {
      return;
    }
    const request = ++listeningGeneration;
    speechTurnId = '';
    await vad.start();
    if (request !== listeningGeneration || !vad.isRunning.value) return;
    if (runtime.sleeping || runtime.connectionState !== CONNECTION_STATES.READY) {
      await pauseListening();
      return;
    }
    if (runtime.turnState !== TURN_STATES.IDLE || runtime.activeTurnId) return;
    runtime.transition('speech_started', { turnId: '', error: '' });
    timers.scheduleInactivity(() => {
      void enterSleep('inactivity');
    });
  }

  function scheduleListeningResume() {
    timers.scheduleResume(() => {
      void enterListening();
    });
  }

  async function enterSleep(reason = 'client-cancelled', options = {}) {
    timers.clearAll();
    const turnId = runtime.activeTurnId;
    speechTurnId = '';
    const cancelReasons = {
      'screen-off': 'screen_off',
      inactivity: 'sleep',
      sleep: 'sleep',
      'client-cancelled': 'user_interaction',
    };
    const localShutdown = Promise.allSettled([pauseListening(), stopResponse(reason)]);
    runtime.goToSleep();
    if (!options.skipCancel) {
      await transport.cancelTurn(turnId, cancelReasons[reason] || 'user_interaction');
    }
    await localShutdown;
  }

  async function toggleListening() {
    let safetyCancelled = false;
    if (runtime.robotMoving) {
      await transport.cancelTurn('', 'user_interaction');
      safetyCancelled = true;
    }
    if (!runtime.settings.onboardingComplete) {
      runtime.settingsOpen = true;
      return;
    }
    const action = headPressAction(runtime);
    if (action === InteractionAction.WAKE) return wakeUp();
    if (action === InteractionAction.SLEEP) {
      return enterSleep('client-cancelled', { skipCancel: safetyCancelled });
    }

    if (action === InteractionAction.CANCEL_AND_LISTEN) {
      const turnId = runtime.activeTurnId;
      if (!safetyCancelled) await transport.cancelTurn(turnId, 'user_interaction');
      await Promise.allSettled([stopResponse('client-cancelled'), pauseListening()]);
      runtime.transition('reset', { turnId: '' });
    }
    await enterListening();
  }

  async function handleVisibilityChange() {
    if (globalThis.document?.hidden) await enterSleep('screen-off');
  }

  onMounted(async () => {
    disposers.push(
      transport.on('connection', async ({ state }) => {
        await applyGatewayState(state);
      }),
      transport.on('runtimeConnected', async () => {
        if (runtime.connectionState !== CONNECTION_STATES.DEGRADED) return;
        try {
          // Preserve the committed cursor so retained tool/audio events replay.
          // A Native recovery control requests a snapshot if history was evicted.
          const status = await transport.getRuntimeStatus();
          applyNativeRobotStatus(status);
          const gateway = status?.gateway || status || {};
          await applyGatewayState(normalizedGatewayState(gateway.state || status?.gatewayState));
        } catch (error) {
          runtime.setConnection(gatewayErrorState(error), error.message);
          throw error;
        }
      }),
      transport.on('event', handleRuntimeEvent),
      transport.on('error', (error) => {
        runtime.resetEmotion();
        if (runtime.connectionState !== CONNECTION_STATES.DEGRADED) runtime.error = error.message;
      }),
    );
    globalThis.document?.addEventListener('visibilitychange', handleVisibilityChange);
    disposers.push(() =>
      globalThis.document?.removeEventListener('visibilitychange', handleVisibilityChange),
    );
    try {
      const bootstrapToken = options.bootstrapToken ?? consumeBootstrapToken();
      await transport.bootstrap(bootstrapToken);
    } catch (error) {
      runtime.setConnection(gatewayErrorState(error), error.message);
    }
    await loadSettings();
    started.value = true;
    if (runtime.settings.onboardingComplete) await connect();
  });

  watch(playback.mouthLevel, (level) => {
    runtime.mouthLevel = level;
  });

  watch(vad.isRunning, (isRunning) => {
    runtime.micEnabled = isRunning;
  });

  onBeforeUnmount(async () => {
    listeningGeneration += 1;
    timers.clearAll();
    disposers.forEach((dispose) => dispose());
    transport.close();
    await stopResponse('unmount');
    await vad.destroy();
  });

  return {
    isListening: computed(() => vad.isRunning.value),
    isAudioReady: computed(() => Boolean(vad.isAudioReady?.value)),
    inputLevel: computed(() => vad.inputLevel?.value || 0),
    motionUpdating,
    motionError,
    setMotionEnabled,
    savingSettings,
    settingsTestResult,
    testingSettings,
    started,
    saveSettings,
    testSettings,
    toggleListening,
    wakeUp,
  };
}
