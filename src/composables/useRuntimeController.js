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
  GatewayEventType,
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
  const started = ref(false);
  const disposers = [];
  const timers = options.timers || createRuntimeTimers(options.timerOptions);
  let speechTurnId = '';

  const vad = options.vad || useVAD({
    onSpeechStart: async () => {
      if (runtime.sleeping) return;
      timers.clearInactivity();
      if (playback.isPlaying.value) {
        await playback.stop('barge-in');
      }
      if (runtime.activeTurnId) await transport.cancelTurn(runtime.activeTurnId, 'barge_in');
      speechTurnId = createId();
      runtime.transition('speech_started', { turnId: speechTurnId, error: '' });
    },
    onSpeechEnd: async ({ blob }) => {
      if (runtime.sleeping || !speechTurnId) return;
      timers.clearInactivity();
      const turnId = speechTurnId;
      speechTurnId = '';
      await vad.pause();
      runtime.transition('upload_started', { turnId });
      try {
        const response = await transport.uploadVoiceTurn({
          turnId,
          audio: blob,
          language: runtime.settings.language,
        });
        if (response?.type) await handleRuntimeEvent(response);
        else if (
          runtime.activeTurnId === turnId &&
          runtime.turnState === TURN_STATES.UPLOADING
        ) {
          runtime.transition('transcription_started', { turnId });
        }
      } catch (error) {
        if (
          runtime.activeTurnId === turnId &&
          [TURN_STATES.UPLOADING, TURN_STATES.TRANSCRIBING].includes(runtime.turnState)
        ) {
          runtime.transition('failed', { turnId: '', error: error.message });
        } else {
          runtime.error = error.message;
        }
      }
    },
    onError: (error) => runtime.transition('failed', { error: error.message }),
  });

  tools.register({
    name: 'show_emotion',
    owner: 'web',
    version: '1.0.0',
    description: 'Show an allow-listed emotion on the local Zenbo face.',
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

  async function convergeTurnError(message = 'Agent turn failed.') {
    timers.clearAll();
    speechTurnId = '';
    await Promise.allSettled([vad.pause(), playback.stop('turn-error')]);
    runtime.transition('failed', {
      turnId: '',
      error: message || 'Agent turn failed.',
    });
  }

  async function applyAuthoritativeConversation(conversation = {}, errorMessage = '') {
    runtime.applyConversationSnapshot(conversation);
    transport.resetCursor(runtime.lastSequence);
    if (runtime.turnState === TURN_STATES.ERROR) {
      await convergeTurnError(errorMessage || 'Agent turn failed.');
    }
  }

  async function refreshAuthoritativeRuntime(errorMessage = '') {
    const status = await transport.getRuntimeStatus();
    const conversation = await transport.getConversation();
    await applyAuthoritativeConversation(conversation, errorMessage);
    const gateway = status?.gateway || status || {};
    const state = normalizedGatewayState(gateway.state || status?.gatewayState);
    runtime.setConnection(
      state,
      state === CONNECTION_STATES.READY && runtime.turnState !== TURN_STATES.ERROR
        ? ''
        : runtime.error || errorMessage,
    );
    if (
      state === CONNECTION_STATES.READY &&
      !runtime.sleeping &&
      runtime.turnState === TURN_STATES.IDLE &&
      !runtime.activeTurnId
    ) {
      await enterListening();
    }
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
      await enterSleep('sleep');
      return { ok: true, sleeping: true };
    },
  });

  async function playResponse(envelope) {
    const payload = eventPayload(envelope);
    const audioPayload = payload;
    const turnId = envelope.turnId || payload.turnId || runtime.activeTurnId;
    const playbackMetadata = {
      artifactId: audioPayload.artifactId,
    };
    const reportPlayback = (status, extra = {}) =>
      transport.sendPlayback(status, turnId, { ...playbackMetadata, ...extra }).catch((error) => {
        runtime.error = `播放狀態回報失敗：${error.message}`;
      });
    let startedReport = Promise.resolve();
    let terminalReport = null;
    let failureHandled = false;
    const reportTerminal = (status, extra = {}) => {
      if (!terminalReport) {
        terminalReport = startedReport.then(() => reportPlayback(status, extra));
      }
      return terminalReport;
    };
    const failPlayback = async (error) => {
      if (failureHandled) return;
      failureHandled = true;
      await reportTerminal('interrupted', { reason: 'playback_error' });
      runtime.transition('failed', { turnId: '', error: error.message });
      if (!runtime.sleeping) scheduleListeningResume();
    };
    runtime.transition('synthesis_started', { turnId });
    timers.clearAll();
    try {
      const blob = await transport.resolveAudio(audioPayload);
      await vad.pause();
      await playback.play(blob, {
        onStarted: () => {
          runtime.activatePendingEmotion();
          runtime.transition('playback_started', { turnId });
          startedReport = reportPlayback('started');
        },
        onEnded: async () => {
          await reportTerminal('completed');
          runtime.transition('reset', { turnId: '' });
          if (!runtime.sleeping) scheduleListeningResume();
        },
        onInterrupted: (reason) => {
          runtime.resetEmotion();
          const reasons = {
            'barge-in': 'barge_in',
            sleep: 'screen_off',
            'screen-off': 'screen_off',
          };
          void reportTerminal('interrupted', {
            reason: reasons[reason] || 'client_cancelled',
          });
        },
        onError: (error) => void failPlayback(error),
      });
    } catch (error) {
      await failPlayback(error);
    }
  }

  async function handleRuntimeEvent(envelope) {
    if (!envelope) return;
    const localControl = decodeLocalControl(envelope);
    if (localControl) {
      switch (localControl.kind) {
        case 'gateway': {
          await refreshAuthoritativeRuntime(localControl.detail);
          break;
        }
        case 'robot':
          runtime.robotReady = localControl.ready;
          runtime.robotMoving = localControl.moving;
          break;
        case 'screen':
          if (localControl.state === 'OFF') await enterSleep('screen-off');
          break;
        case 'interaction':
          await toggleListening();
          break;
        case 'invalid':
          transport.failProtocol(new Error(localControl.message));
          break;
        default:
          break;
      }
      return;
    }
    const payload = eventPayload(envelope);
    const type = envelope.type || '';
    const sequence = Number(envelope.sequence || 0);
    const supersededSessionReady =
      type === GatewayEventType.SESSION_READY &&
      Number.isInteger(sequence) &&
      sequence >= 0 &&
      sequence === payload.resumedAfter &&
      sequence < runtime.lastSequence;
    if (supersededSessionReady) return;
    if (!runtime.applyEnvelope(envelope)) {
      const isRetainedDuplicate =
        Object.values(GatewayEventType).includes(envelope.type) &&
        envelope.type !== GatewayEventType.SESSION_READY &&
        envelope.type !== GatewayEventType.SESSION_SNAPSHOT &&
        Number.isInteger(sequence) &&
        sequence > 0 &&
        sequence <= runtime.lastSequence;
      if (!isRetainedDuplicate) {
        transport.failProtocol(
          new Error(runtime.error || '收到不合法的 Agent Gateway 事件。'),
        );
      }
      return;
    }
    const turnId = envelope.turnId || payload.turnId || '';
    const toolCallCanRun =
      runtime.turnState === TURN_STATES.THINKING ||
      runtime.turnState === TURN_STATES.AWAITING_TOOL;
    const isCurrentToolCall =
      type === GatewayEventType.TOOL_CALL &&
      Boolean(turnId) &&
      Boolean(runtime.activeTurnId) &&
      turnId === runtime.activeTurnId &&
      toolCallCanRun;
    const owner = type === GatewayEventType.TOOL_CALL ? toolOwner(payload.toolName) : null;

    if (type === GatewayEventType.TOOL_CALL && owner === ToolOwner.NATIVE) {
      if (isCurrentToolCall) runtime.transition('tool_started', { turnId });
      runtime.commitEnvelope(envelope);
      transport.acknowledge(envelope.sequence);
      return;
    }

    if (
      type === GatewayEventType.TOOL_CALL &&
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

    const canBindAcceptedTurn =
      type === GatewayEventType.TURN_ACCEPTED &&
      Boolean(runtime.activeTurnId) &&
      [TURN_STATES.UPLOADING, TURN_STATES.TRANSCRIBING].includes(runtime.turnState);
    const currentTurnIsTerminal = [TURN_STATES.IDLE, TURN_STATES.ERROR].includes(
      runtime.turnState,
    );
    if (
      turnId &&
      !canBindAcceptedTurn &&
      (currentTurnIsTerminal || turnId !== runtime.activeTurnId)
    ) {
      runtime.commitEnvelope(envelope);
      transport.acknowledge(envelope.sequence);
      return;
    }

    switch (type) {
      case GatewayEventType.SESSION_READY:
        runtime.setConnection(CONNECTION_STATES.READY);
        if (
          !runtime.sleeping &&
          runtime.turnState === TURN_STATES.IDLE &&
          !runtime.activeTurnId
        ) {
          await enterListening();
        }
        break;
      case GatewayEventType.SESSION_SNAPSHOT:
        await applyAuthoritativeConversation(await transport.getConversation());
        return;
      case GatewayEventType.TURN_ACCEPTED:
        runtime.transition('transcription_started', { turnId });
        break;
      case GatewayEventType.STT_FINAL:
        runtime.transition('thinking_started', {
          turnId,
          transcript: payload.text || payload.transcript || '',
        });
        break;
      case GatewayEventType.AGENT_THINKING:
        runtime.transition('thinking_started', { turnId });
        break;
      case GatewayEventType.AGENT_TEXT_FINAL:
        runtime.transition(payload.final === false ? 'thinking_started' : 'synthesis_started', {
          turnId,
          assistantText: payload.text || payload.output || '',
        });
        break;
      case GatewayEventType.TOOL_CALL: {
        runtime.transition('tool_started', { turnId });
        const result = await tools.execute(envelope, {
          onAccepted: (accepted) => transport.sendToolResult(accepted),
        });
        await transport.sendToolResult(result);
        break;
      }
      case GatewayEventType.TTS_READY:
        await playResponse(envelope);
        break;
      case GatewayEventType.TURN_COMPLETED:
        runtime.activeTurnId = '';
        if (!playback.isPlaying.value) {
          runtime.transition('reset', { turnId: '' });
          if (!runtime.sleeping) scheduleListeningResume();
        }
        break;
      case GatewayEventType.TURN_ERROR:
        await convergeTurnError(
          payload.message || payload.error?.message || payload.error || 'Agent turn failed.',
        );
        break;
      case GatewayEventType.TURN_CANCELLED:
        runtime.transition('reset', { turnId: '' });
        break;
      case GatewayEventType.SESSION_CLOSED:
      case GatewayEventType.SESSION_EXPIRED:
        runtime.setConnection(
          payload.reason === 'credential_revoked'
            ? CONNECTION_STATES.AUTH_ERROR
            : CONNECTION_STATES.OFFLINE,
          'Agent session 已結束，請檢查設定後重新連線。',
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
      const deviceToken = String(settings.deviceToken || '').trim();
      settingsTestResult.value = await transport.testRuntimeSettings({
        gatewayUrl: String(settings.gatewayUrl || '').trim(),
        trustMode,
        agentProfile: String(settings.agentProfile || 'default').trim(),
        ...(trustMode === 'SYSTEM_TRUST' && deviceToken ? { deviceToken } : {}),
      });
    } catch (error) {
      runtime.error = `Gateway 測試失敗：${error.message}`;
    } finally {
      settings.unlockPin = '';
      settings.deviceToken = '';
      testingSettings.value = false;
    }
  }

  async function saveSettings(settings) {
    savingSettings.value = true;
    runtime.error = '';
    const safeSettings = publicSettings(settings);
    const deviceToken = String(settings.deviceToken || '').trim();
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
      const settingsBody = runtimeSettingsBody(safeSettings, deviceToken, confirmedFingerprint);
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
      runtime.settingsOpen = false;
      settingsTestResult.value = null;
    } catch (error) {
      runtime.error = `本機 Runtime 尚未儲存設定：${error.message}`;
      runtime.settingsOpen = true;
      return;
    } finally {
      settings.deviceToken = '';
      settings.pin = '';
      settings.confirmPin = '';
      settings.unlockPin = '';
      savingSettings.value = false;
    }

    transport.close();
    await playback.stop('settings changed');
    await vad.pause();
    await connect();
  }

  async function wakeUp() {
    runtime.wakeUp();
    if (runtime.connectionState === CONNECTION_STATES.READY) {
      await enterListening();
    }
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
    speechTurnId = '';
    await vad.start();
    if (!vad.isRunning.value) return;
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
    if (!options.skipCancel) {
      await transport.cancelTurn(turnId, cancelReasons[reason] || 'user_interaction');
    }
    const localShutdown = Promise.allSettled([vad.pause(), playback.stop(reason)]);
    runtime.goToSleep();
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
      await Promise.allSettled([playback.stop('client-cancelled'), vad.pause()]);
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
        runtime.setConnection(state);
        if (state === CONNECTION_STATES.READY) {
          if (
            !runtime.sleeping &&
            runtime.turnState === TURN_STATES.IDLE &&
            !runtime.activeTurnId
          ) {
            await enterListening();
          }
        }
      }),
      transport.on('runtimeConnected', async () => {
        if (runtime.connectionState !== CONNECTION_STATES.DEGRADED) return;
        try {
          await refreshAuthoritativeRuntime();
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
    timers.clearAll();
    disposers.forEach((dispose) => dispose());
    transport.close();
    await playback.stop('unmount');
    await vad.destroy();
  });

  return {
    isListening: computed(() => vad.isRunning.value),
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
