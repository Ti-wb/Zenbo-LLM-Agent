import { createPinia, setActivePinia } from 'pinia';
import { createRenderer, defineComponent, nextTick, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CONNECTION_STATES,
  Emotion,
  RuntimeEventType,
  TURN_STATES,
  useRuntimeStore,
} from '../stores/runtime';
import { gatewayErrorState, useRuntimeController } from './useRuntimeController';
import { RuntimeTransport } from '../services/runtimeTransport';

afterEach(() => vi.unstubAllGlobals());

function testRenderer() {
  return createRenderer({
    patchProp() {},
    insert(child, parent) {
      if (!parent.children) parent.children = [];
      parent.children.push(child);
    },
    remove() {},
    createElement: () => ({}),
    createText: (text) => ({ text }),
    createComment: (text) => ({ text }),
    setText(node, text) {
      node.text = text;
    },
    setElementText(node, text) {
      node.text = text;
    },
    parentNode: () => null,
    nextSibling: () => null,
  });
}

function fakeTransport({ status, conversation }) {
  const listeners = new Map();
  return {
    bootstrapped: true,
    on: vi.fn((type, handler) => {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(handler);
      return () => listeners.get(type)?.delete(handler);
    }),
    emit: async (type, detail) => {
      for (const handler of listeners.get(type) || []) await handler(detail);
    },
    bootstrap: vi.fn().mockResolvedValue({}),
    getRuntimeSettings: vi.fn().mockResolvedValue({ pinConfigured: true }),
    getRuntimeStatus: vi.fn().mockResolvedValue(status),
    setMotionEnabled: vi.fn(),
    setDeviceAttention: vi.fn().mockResolvedValue({}),
    submitTextTurn: vi.fn().mockResolvedValue({ accepted: true }),
    startNewSession: vi.fn(),
    getConversation: vi.fn().mockResolvedValue(conversation),
    resetCursor: vi.fn(),
    connectEvents: vi.fn(),
    acknowledge: vi.fn(),
    failProtocol: vi.fn(),
    close: vi.fn(),
    cancelTurn: vi.fn().mockResolvedValue(null),
    sendPlayback: vi.fn().mockResolvedValue(null),
    sendToolResult: vi.fn().mockResolvedValue(null),
    uploadVoiceTurn: vi.fn(),
    resolveAudio: vi.fn(),
  };
}

async function mountController({ transport, conversation }) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const playback = {
    isPlaying: ref(false),
    mouthLevel: ref(0),
    play: vi.fn(),
    stop: vi.fn().mockResolvedValue(undefined),
  };
  const vad = {
    isRunning: ref(false),
    start: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn().mockResolvedValue(undefined),
    destroy: vi.fn().mockResolvedValue(undefined),
  };
  const timers = {
    clearAll: vi.fn(),
    clearTurnRetry: vi.fn(),
    scheduleTurnRetry: vi.fn(),
    clearInactivity: vi.fn(),
    clearResume: vi.fn(),
    scheduleInactivity: vi.fn(),
    scheduleResume: vi.fn(),
  };
  let controller;
  let vadCallbacks;
  const app = testRenderer().createApp(
    defineComponent({
      setup() {
        controller = useRuntimeController({
          bootstrapToken: 'bootstrap-token',
          playback,
          timers,
          transport,
          createVAD: (callbacks) => { vadCallbacks = callbacks; return vad; },
        });
        return () => null;
      },
    }),
  );
  app.use(pinia);
  app.mount({});
  await vi.waitFor(() => {
    expect(transport.connectEvents).toHaveBeenCalledOnce();
  });
  const runtime = useRuntimeStore();
  expect(runtime.lastSequence).toBe(conversation.lastSequence);
  return { app, controller, playback, runtime, timers, vad, vadCallbacks };
}

function localEvent(type, sequence, data) {
  return {
    protocolVersion: '2.0', type, sequence, data,
    eventId: `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`,
    timestamp: '2026-09-21T00:00:00.000Z',
  };
}

async function manualListeningHarness(conversation = {}) {
  const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3, ...conversation };
  const transport = fakeTransport({ status: { gatewayState: 'READY', turnBusy: false }, conversation: initial });
  const harness = await mountController({ transport, conversation: initial });
  harness.vad.start.mockImplementation(async () => { harness.vad.isRunning.value = true; });
  harness.vad.pause.mockImplementation(async () => { harness.vad.isRunning.value = false; });
  harness.vad.destroy.mockImplementation(async () => { harness.vad.isRunning.value = false; });
  return { ...harness, transport, initial };
}

describe('manual listening intent', () => {
  it('signals attention only for actual speech and audio playback, then returns idle', async () => {
    const { app, controller, transport, vadCallbacks, runtime, playback } = await manualListeningHarness();
    await controller.toggleListening();
    expect(transport.setDeviceAttention).not.toHaveBeenCalled();
    await vadCallbacks.onSpeechStart();
    expect(transport.setDeviceAttention).toHaveBeenLastCalledWith('listening');
    const turnId = runtime.activeTurnId;
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['voice'], { type: 'audio/wav' }) });
    expect(transport.setDeviceAttention).toHaveBeenLastCalledWith('idle');
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId,
      data: { artifacts: [{ artifactId: 'audio' }] } });
    const callbacks = playback.play.mock.calls[0][1];
    callbacks.onStarted(); await nextTick();
    expect(transport.setDeviceAttention).toHaveBeenLastCalledWith('speaking');
    await callbacks.onEnded();
    expect(transport.setDeviceAttention).toHaveBeenLastCalledWith('idle');
    app.unmount();
  });

  it('orders idle behind an in-flight listening phase without replaying a stale phase', async () => {
    const { app, controller, transport, vadCallbacks } = await manualListeningHarness();
    let resolveAttention;
    transport.setDeviceAttention.mockImplementationOnce(() => new Promise((resolve) => { resolveAttention = resolve; }));
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    await controller.toggleListening();
    expect(transport.setDeviceAttention).toHaveBeenCalledTimes(1);
    resolveAttention({}); await nextTick();
    expect(transport.setDeviceAttention.mock.calls).toEqual([['listening'], ['idle']]);
    app.unmount();
  });

  it('asks Hermes for a camera capture through a text turn without granting microphone intent', async () => {
    const { app, controller, transport, vad, runtime, playback, timers } = await manualListeningHarness();
    expect(await controller.requestCameraView()).toBe(true);
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    expect(transport.submitTextTurn.mock.calls[0][0]).toMatchObject({ text: '請拍下眼前畫面，並告訴我你看到了什麼。', language: 'zh-TW' });
    expect(vad.start).not.toHaveBeenCalled();
    expect(controller.canAskCamera.value).toBe(false);
    const turnId = runtime.activeTurnId;
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_ACCEPTED, sequence: 4, turnId, data: {} });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.AGENT_THINKING, sequence: 5, turnId, data: {} });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TOOL_CALL, sequence: 6, turnId,
      data: { toolName: 'capture_camera', callId: 'native-camera-call', arguments: {} } });
    expect(transport.sendToolResult).not.toHaveBeenCalled();
    const metadata = { artifactId: 'local-image', mimeType: 'image/jpeg', byteLength: 44, sha256: 'a'.repeat(64), width: 640, height: 480, capturedAt: '2026-09-22T10:00:00Z' };
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.CAMERA_CAPTURED, sequence: 7, turnId, data: { ...metadata, imageBase64: 'never-in-store' } });
    expect(runtime.cameraCaptures).toEqual([metadata]);
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.AGENT_TEXT_FINAL, sequence: 8, turnId,
      data: { text: '桌上有一個杯子。', final: true } });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 9, turnId,
      data: { artifacts: [{ artifactId: 'camera-answer' }] } });
    await vi.waitFor(() => expect(playback.play).toHaveBeenCalledOnce());
    const callbacks = playback.play.mock.calls[0][1];
    callbacks.onStarted();
    await callbacks.onEnded();
    expect(transport.sendPlayback.mock.calls).toEqual([
      ['started', turnId, { artifactId: 'camera-answer' }],
      ['completed', turnId, { artifactId: 'camera-answer' }],
    ]);
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_COMPLETED, sequence: 10, turnId, data: {} });
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.assistantText).toBe('桌上有一個杯子。');
    expect(runtime.lastSequence).toBe(10);
    expect(controller.canAskCamera.value).toBe(true);
    expect(vad.start).not.toHaveBeenCalled();
    expect(timers.scheduleResume).not.toHaveBeenCalled();
    expect(transport.uploadVoiceTurn).not.toHaveBeenCalled();
    app.unmount();
  });

  it('does not retry a rejected camera text turn', async () => {
    const { app, controller, transport, runtime } = await manualListeningHarness();
    transport.submitTextTurn.mockRejectedValueOnce(Object.assign(new Error('busy'), { code: 'TURN_BUSY' }));
    expect(await controller.requestCameraView()).toBe(false);
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    expect(runtime.activeTurnId).toBe('');
    expect(controller.cameraRequestMessage.value).toContain('尚未結束');
    app.unmount();
  });
  it.each([TURN_STATES.IDLE, TURN_STATES.LISTENING])('starts awake with the microphone off for a %s snapshot', async (turnState) => {
    const { app, controller, transport, runtime, vad, vadCallbacks } = await manualListeningHarness({ turnState });
    expect(runtime.sleeping).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    await transport.emit('event', localEvent('local.gateway.state', 4, { state: 'READY' }));
    await vadCallbacks.onSpeechStart();
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['not authorized']) });
    expect(vad.start).not.toHaveBeenCalled();
    expect(transport.uploadVoiceTurn).not.toHaveBeenCalled();
    await transport.emit('event', localEvent('local.interaction', 5, { kind: 'HEAD_PRESS' }));
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(vad.isRunning.value).toBe(true);
    await controller.toggleListening();
    expect(runtime.sleeping).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.isRunning.value).toBe(false);
    app.unmount();
  });

  it.each(['OFFLINE', 'CONNECTING', 'AUTH_ERROR'])('does not save a click made while %s for a later READY', async (state) => {
    const { app, controller, transport, runtime, vad } = await manualListeningHarness();
    await transport.emit('connection', { state });
    await controller.toggleListening();
    await controller.wakeUp();
    await transport.emit('connection', { state: 'READY' });
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.start).not.toHaveBeenCalled();
    app.unmount();
  });

  it('continues listening after the last ordered answer artifact, then returns to awake idle on silence', async () => {
    const { app, controller, transport, runtime, vad, vadCallbacks, playback, timers } = await manualListeningHarness();
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    const turnId = runtime.activeTurnId;
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['voice'], { type: 'audio/wav' }) });
    expect(vad.isRunning.value).toBe(false);
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId,
      data: { artifacts: [{ artifactId: 'one' }, { artifactId: 'two' }] },
    });
    const first = playback.play.mock.calls[0][1];
    first.onStarted();
    expect(runtime.effectiveEmotion).toBe(Emotion.CURIOUS);
    await first.onEnded();
    expect(timers.scheduleResume).not.toHaveBeenCalled();
    const second = playback.play.mock.calls[1][1];
    second.onStarted();
    await second.onEnded();
    expect(runtime.effectiveEmotion).toBe(Emotion.NEUTRAL);
    expect(timers.scheduleResume).toHaveBeenCalledOnce();
    timers.scheduleResume.mock.calls[0][0]();
    await vi.waitFor(() => expect(runtime.turnState).toBe(TURN_STATES.LISTENING));
    expect(vad.start).toHaveBeenCalledTimes(2);
    timers.scheduleInactivity.mock.calls.at(-1)[0]();
    await vi.waitFor(() => expect(vad.isRunning.value).toBe(false));
    expect(runtime.sleeping).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    app.unmount();
  });

  it('ignores an old inactivity callback after a new manual listening session starts', async () => {
    const { app, controller, runtime, vad, timers } = await manualListeningHarness();
    await controller.toggleListening();
    const oldTimeout = timers.scheduleInactivity.mock.calls[0][0];
    await controller.toggleListening();
    await controller.toggleListening();
    oldTimeout();
    await nextTick();
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(vad.isRunning.value).toBe(true);
    app.unmount();
  });

  it('invalidates a scheduled follow-up across disconnect and a later manual activation', async () => {
    const { app, controller, transport, runtime, vad, vadCallbacks, timers } = await manualListeningHarness();
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    const turnId = runtime.activeTurnId;
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['voice'], { type: 'audio/wav' }) });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_COMPLETED, sequence: 4, turnId, data: {},
    });
    const oldResume = timers.scheduleResume.mock.calls[0][0];
    await transport.emit('connection', { state: 'OFFLINE' });
    await transport.emit('connection', { state: 'READY' });
    oldResume();
    await nextTick();
    expect(vad.start).toHaveBeenCalledOnce();
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    await controller.toggleListening();
    oldResume();
    await nextTick();
    expect(vad.start).toHaveBeenCalledTimes(2);
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    app.unmount();
  });

  it('clears a manual conversation after a recoverable turn error even without an OFFLINE control', async () => {
    const { app, controller, transport, runtime, vad, vadCallbacks } = await manualListeningHarness();
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    const turnId = runtime.activeTurnId;
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['voice'], { type: 'audio/wav' }) });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_ERROR, sequence: 4, turnId,
      data: { error: { code: 'GATEWAY_OFFLINE', retryable: true } },
    });
    await transport.emit('connection', { state: 'READY' });
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.isRunning.value).toBe(false);
    expect(vad.start).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('keeps a paused turn revoked after recovery and late tool/audio events', async () => {
    const { app, controller, transport, runtime, vad, vadCallbacks, initial } = await manualListeningHarness();
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    const turnId = runtime.activeTurnId;
    await controller.toggleListening();
    transport.getConversation.mockResolvedValue({ ...initial, activeTurnId: turnId, turnState: TURN_STATES.THINKING, lastSequence: 5 });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.SESSION_SNAPSHOT, sequence: 5, data: { lastSequence: 5 },
    });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TOOL_CALL, sequence: 6, turnId,
      data: { callId: 'late-call', toolName: 'show_emotion' },
    });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 7, turnId,
      data: { artifacts: [{ artifactId: 'late-audio' }] },
    });
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.sleeping).toBe(false);
    expect(vad.isRunning.value).toBe(false);
    expect(runtime.lastSequence).toBe(7);
    expect(transport.resolveAudio).not.toHaveBeenCalled();
    expect(transport.sendToolResult).toHaveBeenCalledWith(expect.objectContaining({ status: 'rejected' }));
    app.unmount();
  });

  it.each([RuntimeEventType.SESSION_CLOSED, RuntimeEventType.SESSION_EXPIRED])('revokes listening after %s even if READY arrives directly', async (type) => {
    const { app, controller, transport, runtime, vad, timers } = await manualListeningHarness();
    await controller.toggleListening();
    const oldTimeout = timers.scheduleInactivity.mock.calls[0][0];
    await transport.emit('event', { protocolVersion: '2.0', type, sequence: 4, data: {} });
    await transport.emit('connection', { state: 'READY' });
    oldTimeout();
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.isRunning.value).toBe(false);
    expect(vad.start).toHaveBeenCalledOnce();
    app.unmount();
  });

  it.each(['snapshot', 'sequence-gap'])('does not reuse manual intent after authoritative %s recovery', async (kind) => {
    const { app, controller, transport, runtime, vad, initial } = await manualListeningHarness();
    await controller.toggleListening();
    transport.getConversation.mockResolvedValue({ ...initial, turnState: TURN_STATES.LISTENING, lastSequence: 8 });
    await transport.emit('event', kind === 'snapshot'
      ? { protocolVersion: '2.0', type: RuntimeEventType.SESSION_SNAPSHOT, sequence: 8, data: { lastSequence: 8 } }
      : localEvent('local.gateway.state', 8, { state: 'READY' }));
    await transport.emit('connection', { state: 'READY' });
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.isRunning.value).toBe(false);
    expect(vad.start).toHaveBeenCalledOnce();
    app.unmount();
  });

  it.each([false, true])('keeps the mic off after settings save (fails=%s)', async (fails) => {
    const { app, controller, transport, runtime, vad } = await manualListeningHarness();
    transport.unlockRuntimeSettings = vi.fn().mockResolvedValue({});
    transport.putRuntimeSettings = fails ? vi.fn().mockRejectedValue(new Error('save failed')) : vi.fn().mockResolvedValue({});
    await controller.toggleListening();
    await controller.saveSettings({ ...runtime.settings, unlockPin: '123456' });
    await transport.emit('connection', { state: 'READY' });
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.sleeping).toBe(false);
    expect(vad.isRunning.value).toBe(false);
    expect(vad.start).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('returns screen-off sleep to awake idle but never grants a hidden click', async () => {
    let visibilityChanged;
    const document = {
      hidden: false,
      addEventListener: vi.fn((_event, callback) => { visibilityChanged = callback; }),
      removeEventListener: vi.fn(),
    };
    vi.stubGlobal('document', document);
    const { app, controller, transport, runtime, vad } = await manualListeningHarness();
    await controller.toggleListening();
    document.hidden = true;
    await visibilityChanged();
    await controller.toggleListening();
    await controller.wakeUp();
    expect(runtime.sleeping).toBe(true);
    document.hidden = false;
    await visibilityChanged();
    expect(runtime.sleeping).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    await transport.emit('event', localEvent('local.screen.state', 4, { state: 'OFF' }));
    await controller.toggleListening();
    await transport.emit('event', localEvent('local.screen.state', 5, { state: 'ON' }));
    await transport.emit('connection', { state: 'READY' });
    expect(runtime.sleeping).toBe(false);
    expect(vad.isRunning.value).toBe(false);
    expect(vad.start).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('keeps explicit tool sleep through screen OFF/ON until a manual wake', async () => {
    const { app, controller, transport, runtime, vad } = await manualListeningHarness({ activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING });
    await transport.emit('event', sleepToolEvent());
    await transport.emit('event', localEvent('local.screen.state', 5, { state: 'OFF' }));
    await transport.emit('event', localEvent('local.screen.state', 6, { state: 'ON' }));
    expect(runtime.sleeping).toBe(true);
    expect(vad.start).not.toHaveBeenCalled();
    await controller.toggleListening();
    expect(runtime.sleeping).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(vad.start).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('does not restore a cancelled listening request after unmount', async () => {
    const { app, controller, transport, vad } = await manualListeningHarness({ activeTurnId: 'turn-1', turnState: TURN_STATES.SPEAKING });
    let finishCancel;
    transport.cancelTurn.mockImplementation(() => new Promise((resolve) => { finishCancel = resolve; }));
    const interaction = controller.toggleListening();
    app.unmount();
    finishCancel();
    await interaction;
    await controller.toggleListening();
    await controller.wakeUp();
    expect(vad.start).not.toHaveBeenCalled();
    expect(vad.isRunning.value).toBe(false);
  });
});

describe('camera turn request races', () => {
  it.each([false, true])('keeps Native acceptance after a lost text POST response (audio already started=%s)', async (audioStarted) => {
    const { app, controller, transport, runtime, playback, vad } = await manualListeningHarness();
    let rejectPost;
    const fetchImpl = vi.fn(() => new Promise((_resolve, reject) => { rejectPost = reject; }));
    const wireTransport = new RuntimeTransport({ fetchImpl });
    transport.submitTextTurn.mockImplementation(wireTransport.submitTextTurn.bind(wireTransport));
    const camera = controller.requestCameraView();
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledOnce());
    const turnId = runtime.activeTurnId;
    const [url, request] = fetchImpl.mock.calls[0];
    expect(new URL(url).pathname).toBe('/api/v2/conversation/turns');
    expect(request.headers['Idempotency-Key']).toBe(turnId);
    expect(JSON.parse(request.body)).toMatchObject({ clientTurnId: turnId, language: 'zh-TW' });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_ACCEPTED, sequence: 4, turnId, data: {} });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.AGENT_THINKING, sequence: 5, turnId, data: {} });
    const audio = { protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 6, turnId,
      data: { artifacts: [{ artifactId: 'camera-answer' }] } };
    if (audioStarted) {
      await transport.emit('event', audio);
      await vi.waitFor(() => expect(playback.play).toHaveBeenCalledOnce());
      playback.play.mock.calls[0][1].onStarted();
    }
    rejectPost(new TypeError('NetworkError when attempting to fetch resource'));
    expect(await camera).toBe(true);
    expect(runtime.activeTurnId).toBe(turnId);
    expect(controller.canAskCamera.value).toBe(false);
    expect(transport.getRuntimeStatus).toHaveBeenCalledOnce(); // Startup only; Native events already confirmed acceptance.
    expect(transport.cancelTurn).not.toHaveBeenCalled();
    if (!audioStarted) {
      await transport.emit('event', audio);
      await vi.waitFor(() => expect(playback.play).toHaveBeenCalledOnce());
      playback.play.mock.calls[0][1].onStarted();
    }
    await playback.play.mock.calls[0][1].onEnded();
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_COMPLETED, sequence: 7, turnId, data: {} });
    expect(transport.sendPlayback).toHaveBeenLastCalledWith('completed', turnId, { artifactId: 'camera-answer' });
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    expect(vad.start).not.toHaveBeenCalled();
    app.unmount();
  });

  it('confirms an ambiguous text POST from Native status without skipping retained events', async () => {
    const { app, controller, transport, runtime, playback, initial } = await manualListeningHarness();
    transport.submitTextTurn.mockRejectedValueOnce(new TypeError('NetworkError'));
    transport.getRuntimeStatus.mockImplementationOnce(async () => ({
      gatewayState: 'READY', activeSessionId: initial.sessionId, activeTurnId: runtime.activeTurnId,
      turnBusy: true, lastSequence: 8,
    }));
    expect(await controller.requestCameraView()).toBe(true);
    const turnId = runtime.activeTurnId;
    expect(runtime.lastSequence).toBe(3);
    expect(transport.getConversation).toHaveBeenCalledOnce(); // Startup only.
    expect(transport.resetCursor).toHaveBeenCalledOnce();
    expect(transport.cancelTurn).not.toHaveBeenCalled();
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_ACCEPTED, sequence: 4, turnId, data: {} });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.AGENT_THINKING, sequence: 5, turnId, data: {} });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TOOL_CALL, sequence: 6, turnId,
      data: { toolName: 'capture_camera', callId: 'native-camera-call', arguments: {} } });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 7, turnId,
      data: { artifacts: [{ artifactId: 'camera-answer' }] } });
    await vi.waitFor(() => expect(playback.play).toHaveBeenCalledOnce());
    playback.play.mock.calls[0][1].onStarted();
    await playback.play.mock.calls[0][1].onEnded();
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_COMPLETED, sequence: 8, turnId, data: {} });
    expect(transport.sendToolResult).not.toHaveBeenCalled();
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    expect(transport.failProtocol).not.toHaveBeenCalled();
    expect(runtime.lastSequence).toBe(8);
    app.unmount();
  });

  it.each([false, true])('safely cancels an unconfirmed camera request without replay (status unavailable=%s)', async (statusUnavailable) => {
    const { app, controller, transport, runtime, timers } = await manualListeningHarness();
    let turnId;
    transport.submitTextTurn.mockImplementationOnce(async (request) => {
      turnId = request.turnId;
      throw new TypeError('NetworkError');
    });
    if (statusUnavailable) transport.getRuntimeStatus.mockRejectedValueOnce(new TypeError('NetworkError'));
    transport.cancelTurn.mockRejectedValueOnce(new TypeError('Cancel response also lost'));
    expect(await controller.requestCameraView()).toBe(false);
    expect(transport.cancelTurn).toHaveBeenCalledExactlyOnceWith(turnId, 'user_interaction');
    expect(runtime.activeTurnId).toBe('');
    expect(controller.cameraRequestMessage.value).toContain('已要求停止');
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_ACCEPTED, sequence: 4, turnId, data: {} });
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 5, turnId,
      data: { artifacts: [{ artifactId: 'cancelled-audio' }] } });
    expect(transport.resolveAudio).not.toHaveBeenCalled();
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    expect(timers.scheduleTurnRetry).not.toHaveBeenCalled();
    app.unmount();
  });

  it('preserves a newer acceptance that arrives while the status read is pending', async () => {
    const { app, controller, transport, runtime } = await manualListeningHarness();
    let finishStatus;
    transport.submitTextTurn.mockRejectedValueOnce(new TypeError('NetworkError'));
    transport.getRuntimeStatus.mockImplementationOnce(() => new Promise((resolve) => { finishStatus = resolve; }));
    const camera = controller.requestCameraView();
    await vi.waitFor(() => expect(transport.getRuntimeStatus).toHaveBeenCalledTimes(2));
    const turnId = runtime.activeTurnId;
    await transport.emit('event', { protocolVersion: '2.0', type: RuntimeEventType.TURN_ACCEPTED, sequence: 4, turnId, data: {} });
    finishStatus({ gatewayState: 'READY', activeTurnId: null });
    expect(await camera).toBe(true);
    expect(runtime.activeTurnId).toBe(turnId);
    expect(runtime.lastSequence).toBe(4);
    expect(transport.cancelTurn).not.toHaveBeenCalled();
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('does not restore a cancelled camera turn from a delayed status read', async () => {
    const { app, controller, transport, runtime, initial } = await manualListeningHarness();
    let finishStatus;
    transport.submitTextTurn.mockRejectedValueOnce(new TypeError('NetworkError'));
    transport.getRuntimeStatus.mockImplementationOnce(() => new Promise((resolve) => { finishStatus = resolve; }));
    const camera = controller.requestCameraView();
    await vi.waitFor(() => expect(transport.getRuntimeStatus).toHaveBeenCalledTimes(2));
    const turnId = runtime.activeTurnId;
    await controller.toggleListening();
    finishStatus({ gatewayState: 'READY', activeSessionId: initial.sessionId, activeTurnId: turnId });
    expect(await camera).toBe(false);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(transport.cancelTurn).toHaveBeenCalledExactlyOnceWith(turnId, 'user_interaction');
    expect(transport.submitTextTurn).toHaveBeenCalledOnce();
    app.unmount();
  });

  it.each(['button', 'head-press', 'new-session', 'screen-off', 'unmount'])(
    'does not submit a camera request invalidated by %s during VAD pause', async (interruption) => {
      const { app, controller, transport, runtime, vad, initial } = await manualListeningHarness();
      await controller.toggleListening();
      let finishPause;
      vad.pause.mockImplementationOnce(() => new Promise((resolve) => { finishPause = resolve; }));
      const camera = controller.requestCameraView();
      expect(controller.cameraRequestPending.value).toBe(true);
      if (interruption === 'button') await controller.toggleListening();
      if (interruption === 'head-press') await transport.emit('event', localEvent('local.interaction', 4, { kind: 'HEAD_PRESS' }));
      if (interruption === 'screen-off') await transport.emit('event', localEvent('local.screen.state', 4, { state: 'OFF' }));
      if (interruption === 'new-session') {
        transport.startNewSession.mockResolvedValueOnce({ ...initial, sessionId: 'session-2', lastSequence: 4 });
        expect(await controller.startNewSession()).toBe(true);
      }
      if (interruption === 'unmount') app.unmount();
      finishPause();
      expect(await camera).toBe(false);
      expect(transport.submitTextTurn).not.toHaveBeenCalled();
      expect(runtime.activeTurnId).toBe('');
      expect(controller.cameraRequestPending.value).toBe(false);
      if (interruption !== 'unmount') app.unmount();
    },
  );
});

function sleepToolEvent(argumentsValue = {}) {
  return {
    protocolVersion: '2.0', type: RuntimeEventType.TOOL_CALL, sequence: 4, turnId: 'turn-1',
    data: {
      callId: '00000000-0000-4000-8000-000000000004', toolName: 'go_to_sleep',
      toolVersion: '1.0.0', arguments: argumentsValue, timeoutMs: 5000,
      deadlineAt: '2999-01-01T00:00:00.000Z',
    },
  };
}

function sleepApiHarness({ failAccepted = false, failTerminal = false, failCancel = false, holdTerminal = false } = {}) {
  const requests = [];
  let cancelled = false;
  let terminalApplied = false;
  let acknowledgeTerminal;
  const response = (ok, data = null, errorCode = 'GATEWAY_OFFLINE') => new Response(JSON.stringify({
    ok, requestId: '00000000-0000-4000-8000-000000000099', data,
    error: ok ? null : { code: errorCode, message: 'Tool acknowledgement unavailable.', retryable: true },
  }), { status: ok ? 200 : 502, headers: { 'Content-Type': 'application/json' } });
  const wireTransport = new RuntimeTransport({
    fetchImpl: async (url, request) => {
      const body = JSON.parse(request.body);
      requests.push({ path: new URL(url).pathname, method: request.method, body });
      if (url.includes('/tool-calls/')) {
        if (body.status === 'accepted') return response(!failAccepted, { status: 'accepted' });
        if (cancelled || terminalApplied) return response(false);
        terminalApplied = true;
        if (holdTerminal) {
          return new Promise((resolve) => { acknowledgeTerminal = () => resolve(response(true, { status: body.status })); });
        }
        return response(!failTerminal, failTerminal ? null : { status: body.status });
      }
      if (url.endsWith('/conversation/cancel')) {
        cancelled = true;
        return response(!failCancel, failCancel ? null : { cancelled: true }, 'TURN_BUSY');
      }
      throw new Error(`Unexpected request: ${url}`);
    },
  });
  return {
    requests,
    sendToolResult: wireTransport.sendToolResult.bind(wireTransport),
    cancelTurn: wireTransport.cancelTurn.bind(wireTransport),
    acknowledgeTerminal: () => acknowledgeTerminal(),
  };
}

describe('go_to_sleep result and cancellation ordering', () => {
  it('stops locally, waits for the succeeded HTTP ACK, and then cancels exactly once', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const api = sleepApiHarness({ holdTerminal: true });
    const transport = Object.assign(fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial }), {
      sendToolResult: api.sendToolResult, cancelTurn: api.cancelTurn,
    });
    const { app, runtime, vad, playback } = await mountController({ transport, conversation: initial });
    const pendingEvent = transport.emit('event', sleepToolEvent());
    await vi.waitFor(() => expect(api.requests).toHaveLength(2));
    expect(runtime.sleeping).toBe(true);
    expect(runtime.micEnabled).toBe(false);
    expect(vad.pause).toHaveBeenCalledOnce();
    expect(playback.stop).toHaveBeenCalledWith('sleep');
    expect(api.requests.map(({ body }) => body.status)).toEqual(['accepted', 'succeeded']);
    expect(api.requests[1].body.output).toEqual({ ok: true, sleeping: true });
    api.acknowledgeTerminal();
    await pendingEvent;
    expect(api.requests.map(({ path, method }) => [method, path])).toEqual([
      ['PUT', '/api/v2/conversation/tool-calls/00000000-0000-4000-8000-000000000004'],
      ['PUT', '/api/v2/conversation/tool-calls/00000000-0000-4000-8000-000000000004'],
      ['POST', '/api/v2/conversation/cancel'],
    ]);
    expect(api.requests[2].body).toEqual({ turnId: 'turn-1', reason: 'sleep' });
    expect(runtime.sleeping).toBe(true);
    expect(runtime.error).toBe('');
    await transport.emit('event', sleepToolEvent());
    expect(api.requests).toHaveLength(3);
    app.unmount();
  });

  it.each([
    ['terminal ACK is lost after applying success', { failTerminal: true }, ['accepted', 'succeeded']],
    ['accepted ACK fails before the handler runs', { failAccepted: true }, ['accepted']],
  ])('sleeps and cancels safely without sending a second terminal when %s', async (_, options, statuses) => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const api = sleepApiHarness(options);
    const transport = Object.assign(fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial }), {
      sendToolResult: api.sendToolResult, cancelTurn: api.cancelTurn,
    });
    const { app, runtime, vad, playback } = await mountController({ transport, conversation: initial });
    await transport.emit('event', sleepToolEvent());
    expect(api.requests.filter(({ method }) => method === 'PUT').map(({ body }) => body.status)).toEqual(statuses);
    expect(api.requests.filter(({ method }) => method === 'POST')).toHaveLength(1);
    expect(api.requests.at(-1).body).toEqual({ turnId: 'turn-1', reason: 'sleep' });
    expect(runtime.sleeping).toBe(true);
    expect(runtime.micEnabled).toBe(false);
    expect(runtime.lastSequence).toBe(4);
    expect(runtime.error).toContain('休眠工具結果回報失敗');
    expect(vad.pause).toHaveBeenCalled();
    expect(playback.stop).toHaveBeenCalledWith('sleep');
    expect(transport.failProtocol).not.toHaveBeenCalled();
    app.unmount();
  });

  it('rejects an invalid sleep request without sleeping or cancelling the turn', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const api = sleepApiHarness();
    const transport = Object.assign(fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial }), {
      sendToolResult: api.sendToolResult, cancelTurn: api.cancelTurn,
    });
    const { app, runtime } = await mountController({ transport, conversation: initial });
    await transport.emit('event', sleepToolEvent({ extra: true }));
    expect(api.requests).toHaveLength(1);
    expect(api.requests[0].body.status).toBe('rejected');
    expect(runtime.sleeping).toBe(false);
    app.unmount();
  });

  it('stays asleep when cancellation returns TURN_BUSY and Native later recovers', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const api = sleepApiHarness({ failCancel: true });
    const transport = Object.assign(fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial }), {
      sendToolResult: api.sendToolResult, cancelTurn: api.cancelTurn,
    });
    const { app, runtime, vad } = await mountController({ transport, conversation: initial });
    await transport.emit('event', sleepToolEvent());
    transport.getConversation.mockResolvedValueOnce({ ...initial, activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 8 });
    await transport.emit('event', {
      protocolVersion: '2.0', eventId: '00000000-0000-4000-8000-000000000008',
      sequence: 8, type: 'local.gateway.state', timestamp: '2026-09-17T00:00:00.000Z', data: { state: 'READY' },
    });
    expect(runtime.sleeping).toBe(true);
    expect(runtime.statusLabel).toBe('休眠中');
    expect(vad.start).not.toHaveBeenCalled();
    expect(api.requests.filter(({ method }) => method === 'POST')).toHaveLength(1);
    app.unmount();
  });
});

describe('Native motion preference', () => {
  it('waits for Native confirmation, ignores repeat clicks and keeps failed updates separate from speech', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.SPEAKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY', motionEnabled: false }, conversation: initial });
    const { app, controller, runtime, playback, vad } = await mountController({ transport, conversation: initial });
    expect(runtime.motionEnabled).toBe(false);
    let confirm;
    transport.setMotionEnabled.mockImplementationOnce(() => new Promise((resolve) => { confirm = resolve; }));
    const pending = controller.setMotionEnabled(true);
    expect(controller.motionUpdating.value).toBe(true);
    expect(runtime.motionEnabled).toBe(false);
    await controller.setMotionEnabled(true);
    expect(transport.setMotionEnabled).toHaveBeenCalledOnce();
    confirm({ motionEnabled: true, moving: false });
    expect(await pending).toBe(true);
    expect(runtime.motionEnabled).toBe(true);
    expect(controller.motionUpdating.value).toBe(false);
    transport.setMotionEnabled.mockRejectedValueOnce(new Error('Native unavailable'));
    expect(await controller.setMotionEnabled(false)).toBe(false);
    expect(runtime.motionEnabled).toBe(true);
    expect(controller.motionError.value).toContain('Native unavailable');
    expect(runtime.error).toBe('');
    expect(runtime.turnState).toBe(TURN_STATES.SPEAKING);
    expect(vad.pause).not.toHaveBeenCalled();
    expect(playback.stop).not.toHaveBeenCalled();
    expect(transport.cancelTurn).not.toHaveBeenCalled();
    app.unmount();
  });

  it('reads the persisted value at startup and synchronizes Native controls and reconnect status', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY', motionEnabled: true, battery: { percentage: 82, charging: true } }, conversation: initial });
    const { app, controller, runtime } = await mountController({ transport, conversation: initial });
    expect(runtime.motionEnabled).toBe(true);
    expect(runtime.batteryLabel).toBe('82%');
    expect(runtime.battery.charging).toBe(true);
    let confirm;
    transport.setMotionEnabled.mockImplementationOnce(() => new Promise((resolve) => { confirm = resolve; }));
    const pending = controller.setMotionEnabled(true);
    await transport.emit('event', {
      protocolVersion: '2.0', eventId: '00000000-0000-4000-8000-000000000004',
      sequence: 4, type: 'local.robot.state', timestamp: '2026-09-17T00:00:00.000Z',
      data: { ready: true, moving: false, motionEnabled: false, battery: { percentage: null, charging: null } },
    });
    expect(runtime.motionEnabled).toBe(false);
    expect(runtime.batteryLabel).toBe('--%');
    expect(runtime.robotReady).toBe(true);
    confirm({ motionEnabled: true, moving: true });
    await pending;
    expect(runtime.motionEnabled).toBe(false);
    expect(runtime.robotMoving).toBe(false);
    runtime.setConnection(CONNECTION_STATES.DEGRADED);
    transport.getRuntimeStatus.mockResolvedValueOnce({ gatewayState: 'READY', motionEnabled: true });
    await transport.emit('runtimeConnected', { connected: true });
    expect(runtime.motionEnabled).toBe(true);
    expect(runtime.lastSequence).toBe(4);
    app.unmount();
  });
});

describe('listening startup cancellation', () => {
  it('does not let a late start revive sleep and allows a subsequent wake', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'OFFLINE' }, conversation: initial });
    const { app, controller, runtime, timers, vad } = await mountController({ transport, conversation: initial });
    let finishOldStart;
    vad.start
      .mockImplementationOnce(() => new Promise((resolve) => { finishOldStart = resolve; }))
      .mockImplementationOnce(async () => { vad.isRunning.value = true; });
    vad.pause.mockImplementation(async () => { vad.isRunning.value = false; });
    await transport.emit('connection', { state: CONNECTION_STATES.READY });
    expect(vad.start).not.toHaveBeenCalled();
    const oldListening = controller.toggleListening();
    await vi.waitFor(() => expect(finishOldStart).toBeTypeOf('function'));
    await transport.emit('event', {
      protocolVersion: '2.0', sequence: 4, type: 'local.screen.state',
      eventId: '00000000-0000-4000-8000-000000000004', timestamp: '2026-09-17T00:00:00.000Z',
      data: { state: 'OFF' },
    });
    expect(runtime.sleeping).toBe(true);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    vad.isRunning.value = true;
    finishOldStart();
    await oldListening;
    expect(runtime.sleeping).toBe(true);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(timers.scheduleInactivity).not.toHaveBeenCalled();
    await transport.emit('event', {
      protocolVersion: '2.0', sequence: 5, type: 'local.screen.state',
      eventId: '00000000-0000-4000-8000-000000000005', timestamp: '2026-09-17T00:00:00.000Z',
      data: { state: 'ON' },
    });
    await controller.wakeUp();
    expect(timers.scheduleInactivity).toHaveBeenCalledOnce();
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    app.unmount();
  });
});

describe('new conversation', () => {
  const initial = {
    sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3,
    transcript: '原本的問題', assistantText: '原本的回答',
  };
  const ready = { gatewayState: 'READY', turnBusy: false, lastSequence: 3 };
  const empty = (lastSequence) => ({ ...initial, lastSequence, transcript: '', assistantText: '' });
  const gateway = (sequence, state) => ({
    protocolVersion: '2.0', eventId: `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`,
    type: 'local.gateway.state', sequence, timestamp: '2026-09-17T00:00:00.000Z', data: { state },
  });
  const snapshot = (sequence) => ({
    protocolVersion: '2.0', type: RuntimeEventType.SESSION_SNAPSHOT, sequence, data: empty(sequence),
  });

  it('gates capture and duplicate taps until the accepted cursor has a newer READY, even before HTTP completion', async () => {
    const transport = fakeTransport({ status: ready, conversation: initial });
    const { app, controller, runtime, vad, vadCallbacks } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    await controller.wakeUp();
    await vadCallbacks.onSpeechStart();
    let finishOldPause;
    vad.pause.mockImplementationOnce(() => new Promise((resolve) => { finishOldPause = resolve; }));
    const oldSpeech = vadCallbacks.onSpeechEnd({ blob: new Blob(['old'], { type: 'audio/wav' }) });
    let accept;
    transport.startNewSession.mockImplementation(() => new Promise((resolve) => { accept = resolve; }));
    const change = controller.startNewSession();
    expect(controller.newSessionPending.value).toBe(true);
    expect(await controller.startNewSession()).toBe(false);
    await vi.waitFor(() => expect(accept).toBeTypeOf('function'));
    vad.start.mockClear();
    await controller.wakeUp();
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['during reset'], { type: 'audio/wav' }) });
    await transport.emit('event', gateway(4, 'READY')); // Old binding is not completion.
    expect(controller.newSessionPending.value).toBe(true);
    await transport.emit('event', gateway(5, 'CONNECTING'));
    transport.getConversation.mockResolvedValueOnce(empty(7));
    transport.getRuntimeStatus.mockResolvedValueOnce({ ...ready, lastSequence: 7 });
    await transport.emit('event', snapshot(6)); // The snapshot read already includes READY at 7.
    expect(vad.start).not.toHaveBeenCalled();
    expect(controller.newSessionPending.value).toBe(true);
    accept(empty(6));
    expect(await change).toBe(true);
    finishOldPause();
    await oldSpeech;
    expect(controller.newSessionPending.value).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.lastSequence).toBe(7);
    expect(vad.start).not.toHaveBeenCalled();
    expect(transport.startNewSession).toHaveBeenCalledOnce();
    expect(transport.uploadVoiceTurn).not.toHaveBeenCalled();
    app.unmount();
  });

  it('clears old conversation only after acceptance and preserves sleep until the new binding is READY', async () => {
    const transport = fakeTransport({ status: ready, conversation: initial });
    const { app, controller, runtime, vad } = await mountController({ transport, conversation: initial });
    runtime.goToSleep();
    runtime.queueEmotion(Emotion.HAPPY);
    const settingsBefore = { ...runtime.settings };
    let accept;
    transport.startNewSession.mockImplementation(() => new Promise((resolve) => { accept = resolve; }));
    const change = controller.startNewSession();
    await vi.waitFor(() => expect(accept).toBeTypeOf('function'));
    expect(runtime.assistantText).toBe('原本的回答');
    expect(runtime.pendingEmotion).toBe(Emotion.HAPPY);
    accept(empty(5));
    await change;
    expect(runtime.transcript).toBe('');
    expect(runtime.assistantText).toBe('');
    expect(runtime.pendingEmotion).toBe('');
    expect(controller.newSessionPending.value).toBe(true);
    await transport.emit('event', gateway(4, 'CONNECTING'));
    transport.getConversation.mockResolvedValueOnce(empty(5));
    transport.getRuntimeStatus.mockResolvedValueOnce({ ...ready, gatewayState: 'CONNECTING', turnBusy: true, lastSequence: 5 });
    await transport.emit('event', snapshot(5));
    expect(controller.newSessionPending.value).toBe(true);
    await transport.emit('event', gateway(6, 'READY'));
    expect(controller.newSessionPending.value).toBe(false);
    expect(runtime.sleeping).toBe(true);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.settings).toEqual(settingsBefore);
    expect(vad.start).not.toHaveBeenCalled();
    app.unmount();
  });

  it('keeps busy/error feedback inline, preserves captions, and rechecks Native on the next tap', async () => {
    const transport = fakeTransport({ status: ready, conversation: initial });
    const { app, controller, runtime, vad } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    transport.getRuntimeStatus.mockResolvedValueOnce({ ...ready, turnBusy: true });
    expect(await controller.startNewSession()).toBe(false);
    expect(transport.startNewSession).not.toHaveBeenCalled();
    expect(controller.newSessionMessage.value).toBe('上一輪還在結束，請稍後再試');
    transport.startNewSession.mockRejectedValueOnce(Object.assign(new Error('Busy'), { code: 'TURN_BUSY' }));
    expect(await controller.startNewSession()).toBe(false);
    expect(transport.startNewSession).toHaveBeenCalledOnce();
    expect(transport.getRuntimeStatus).toHaveBeenCalledTimes(3);
    expect(runtime.transcript).toBe('原本的問題');
    expect(runtime.assistantText).toBe('原本的回答');
    expect(runtime.error).toBe('');
    expect(runtime.settingsOpen).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.start).not.toHaveBeenCalled();
    for (const state of [TURN_STATES.UPLOADING, TURN_STATES.THINKING, TURN_STATES.SPEAKING]) {
      runtime.turnState = state;
      runtime.activeTurnId = 'active-turn';
      expect(controller.canStartNewSession.value).toBe(false);
      expect(await controller.startNewSession()).toBe(false);
    }
    expect(transport.startNewSession).toHaveBeenCalledOnce();
    app.unmount();
  });
});

describe('gateway error recovery state', () => {
  it('prioritizes canonical Local Runtime error codes over message heuristics', () => {
    expect(gatewayErrorState({ code: 'GATEWAY_TLS', message: 'network offline' })).toBe(
      CONNECTION_STATES.TLS_ERROR,
    );
    expect(gatewayErrorState({ code: 'GATEWAY_AUTH', message: 'TLS handshake failed' })).toBe(
      CONNECTION_STATES.AUTH_ERROR,
    );
    expect(gatewayErrorState({ code: 'GATEWAY_INCOMPATIBLE', message: 'offline' })).toBe(
      CONNECTION_STATES.INCOMPATIBLE,
    );
    expect(gatewayErrorState({ code: 'SETUP_REQUIRED', message: 'offline' })).toBe(
      CONNECTION_STATES.UNCONFIGURED,
    );
  });

  it('uses a conservative fallback only when no canonical code is present', () => {
    expect(gatewayErrorState(new Error('certificate pin mismatch'))).toBe(
      CONNECTION_STATES.TLS_ERROR,
    );
    expect(gatewayErrorState(new Error('connection refused'))).toBe(CONNECTION_STATES.OFFLINE);
  });
});

describe('automatic gateway reconnect', () => {
  function gatewayState(sequence, state) {
    return {
      protocolVersion: '2.0', eventId: `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`,
      type: 'local.gateway.state', sequence, timestamp: '2026-09-17T00:00:00.000Z',
      data: { state, detail: state === 'READY' ? '' : 'Gateway is reconnecting.' },
    };
  }

  it('discards capture across disconnect and waits for a fresh manual activation after READY', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, runtime, vad, vadCallbacks, timers } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    vad.pause.mockImplementation(async () => { vad.isRunning.value = false; });
    await controller.wakeUp();
    await vadCallbacks.onSpeechStart();
    let finishPause;
    vad.pause.mockImplementationOnce(() => new Promise((resolve) => { finishPause = resolve; }));
    const oldSpeech = vadCallbacks.onSpeechEnd({ blob: new Blob(['old voice'], { type: 'audio/wav' }) });
    await transport.emit('event', gatewayState(4, 'OFFLINE'));
    expect(runtime.micEnabled).toBe(false);
    expect(timers.clearAll).toHaveBeenCalled();
    expect(runtime.error).toBe('');
    expect(runtime.settingsOpen).toBe(false);
    await vadCallbacks.onSpeechStart();
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['offline voice'], { type: 'audio/wav' }) });
    await transport.emit('event', gatewayState(5, 'CONNECTING'));
    expect(runtime.statusLabel).toBe('正在連線');
    await transport.emit('event', gatewayState(6, 'READY'));
    finishPause();
    await oldSpeech;
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(transport.uploadVoiceTurn).not.toHaveBeenCalled();
    expect(vad.start).toHaveBeenCalledOnce();
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    const fresh = new Blob(['fresh voice'], { type: 'audio/wav' });
    await vadCallbacks.onSpeechEnd({ blob: fresh });
    expect(transport.uploadVoiceTurn).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ audio: fresh }));
    app.unmount();
  });

  it.each([false, true])('waits for an explicit recoverable terminal, including when it arrives after READY: %s', async (terminalAfterReady) => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.TRANSCRIBING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, runtime, vad } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    await transport.emit('event', gatewayState(4, 'OFFLINE'));
    await transport.emit('event', gatewayState(5, 'CONNECTING'));
    if (terminalAfterReady) {
      await transport.emit('event', gatewayState(6, 'READY'));
      expect(runtime.activeTurnId).toBe('turn-1');
      expect(vad.start).not.toHaveBeenCalled();
    }
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_ERROR, sequence: terminalAfterReady ? 7 : 6,
      turnId: 'turn-1', data: { error: { code: 'GATEWAY_OFFLINE', retryable: true, message: '連線中斷，正在重新連線。' } },
    });
    if (!terminalAfterReady) {
      expect(runtime.turnState).toBe(TURN_STATES.IDLE);
      expect(vad.start).not.toHaveBeenCalled();
      await transport.emit('event', gatewayState(7, 'READY'));
    }
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.error).toBe('');
    expect(vad.start).not.toHaveBeenCalled();
    expect(transport.uploadVoiceTurn).not.toHaveBeenCalled();
    app.unmount();
  });

  it.each([false, true])('settles an upload rejected before Native acceptance without replay, even after READY: %s', async (rejectionAfterReady) => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    let rejectUpload;
    const wireTransport = new RuntimeTransport({
      fetchImpl: () => new Promise((resolve) => { rejectUpload = resolve; }),
    });
    transport.uploadVoiceTurn.mockImplementation(wireTransport.uploadVoiceTurn.bind(wireTransport));
    const { app, controller, runtime, vad, vadCallbacks } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    await controller.toggleListening();
    await vadCallbacks.onSpeechStart();
    const upload = vadCallbacks.onSpeechEnd({ blob: new Blob([new Uint8Array(44)], { type: 'audio/wav' }) });
    await vi.waitFor(() => expect(rejectUpload).toBeTypeOf('function'));
    await transport.emit('event', gatewayState(4, 'OFFLINE'));
    if (rejectionAfterReady) await transport.emit('event', gatewayState(5, 'READY'));
    expect(runtime.turnState).toBe(TURN_STATES.UPLOADING);
    rejectUpload(new Response(JSON.stringify({
      ok: false, data: null, requestId: 'request-1',
      error: { code: 'GATEWAY_OFFLINE', retryable: true, message: '連線中斷，正在重新連線。' },
    }), { status: 503, headers: { 'Content-Type': 'application/json' } }));
    await upload;
    if (!rejectionAfterReady) {
      expect(runtime.turnState).toBe(TURN_STATES.IDLE);
      await transport.emit('event', gatewayState(5, 'READY'));
    }
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.error).toBe('');
    expect(vad.start).toHaveBeenCalledOnce(); // Explicit activation before disconnect only.
    expect(transport.uploadVoiceTurn).toHaveBeenCalledOnce();
    app.unmount();
  });

  it('preserves manual sleep while reconnecting and ignores the old turn terminal after READY', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.TRANSCRIBING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, runtime, vad } = await mountController({ transport, conversation: initial });
    await transport.emit('event', gatewayState(4, 'OFFLINE'));
    await transport.emit('event', {
      protocolVersion: '2.0', type: 'local.screen.state', sequence: 5,
      eventId: '00000000-0000-4000-8000-000000000005', timestamp: '2026-09-17T00:00:00.000Z', data: { state: 'OFF' },
    });
    await transport.emit('event', gatewayState(6, 'CONNECTING'));
    await transport.emit('event', gatewayState(7, 'READY'));
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_ERROR, sequence: 8, turnId: 'turn-1',
      data: { error: { code: 'GATEWAY_OFFLINE', retryable: true, message: '連線中斷，正在重新連線。' } },
    });
    expect(runtime.sleeping).toBe(true);
    expect(runtime.micEnabled).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.error).toBe('');
    expect(vad.start).not.toHaveBeenCalled();
    app.unmount();
  });
});

describe('runtime authoritative recovery', () => {
  it.each([RuntimeEventType.TURN_CANCELLED, RuntimeEventType.TURN_ERROR, RuntimeEventType.TURN_COMPLETED])(
    'keeps resumed listening intact when a cancelled turn later emits %s', async (type) => {
      const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.SPEAKING, lastSequence: 3 };
      const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
      const { app, controller, playback, runtime, vad } = await mountController({ transport, conversation: initial });
      vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
      await controller.toggleListening();
      expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
      expect(runtime.activeTurnId).toBe('');
      expect(playback.stop).toHaveBeenCalledOnce();
      await transport.emit('event', {
        protocolVersion: '2.0', type, sequence: 4, turnId: 'turn-1',
        data: { message: 'Late old-turn failure' },
      });
      expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
      expect(runtime.error).toBe('');
      expect(vad.isRunning.value).toBe(true);
      expect(playback.stop).toHaveBeenCalledOnce();
      expect(vad.pause).toHaveBeenCalledOnce();
      expect(runtime.lastSequence).toBe(4);
      app.unmount();
    },
  );

  it.each([false, true])('stops local audio and revokes late tools before a slow cancel ACK (moving=%s)', async (moving) => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, playback, runtime, vad } = await mountController({ transport, conversation: initial });
    runtime.robotMoving = moving;
    let finishCancel;
    transport.cancelTurn.mockImplementation(() => new Promise((resolve) => { finishCancel = resolve; }));
    const cancellation = controller.toggleListening();
    expect(controller.toggleListening()).toBe(cancellation);
    expect(playback.stop).toHaveBeenCalledWith('client-cancelled');
    expect(vad.pause).toHaveBeenCalledOnce();
    expect(runtime.activeTurnId).toBe('');
    expect(vad.start).not.toHaveBeenCalled();
    await transport.emit('connection', { state: CONNECTION_STATES.READY });
    expect(vad.start).not.toHaveBeenCalled();
    expect(controller.canStartNewSession.value).toBe(false);
    // A recovery snapshot may still describe the remotely cancelling turn.
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.SESSION_SNAPSHOT, sequence: 3,
      data: { ...initial },
    });
    expect(runtime.activeTurnId).toBe('turn-1');
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TOOL_CALL, sequence: 4, turnId: 'turn-1',
      data: {
        callId: '00000000-0000-4000-8000-000000000004', toolName: 'show_emotion',
        toolVersion: '1.0.0', arguments: { emotion: 'EXCITED' }, timeoutMs: 5000,
        deadlineAt: '2999-01-01T00:00:00.000Z',
      },
    });
    expect(transport.sendToolResult).toHaveBeenCalledWith(expect.objectContaining({
      status: 'rejected', error: expect.objectContaining({ code: 'STALE_TURN' }),
    }));
    expect(runtime.pendingEmotion).toBe('');
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 5, turnId: 'turn-1',
      data: { artifacts: [{ artifactId: 'late-audio' }] },
    });
    expect(transport.resolveAudio).not.toHaveBeenCalled();
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_CANCELLED, sequence: 6, turnId: 'turn-1', data: {},
    });
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.lastSequence).toBe(6);
    await transport.emit('connection', { state: CONNECTION_STATES.READY });
    expect(vad.start).not.toHaveBeenCalled();
    finishCancel();
    await cancellation;
    expect(transport.cancelTurn).toHaveBeenCalledExactlyOnceWith(moving ? '' : 'turn-1', 'user_interaction');
    expect(vad.start).not.toHaveBeenCalled(); // The intervening recovery revoked the manual request.
    app.unmount();
  });

  it('preserves screen-off sleep when an older interaction cancellation ACK arrives', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.SPEAKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, runtime, vad } = await mountController({ transport, conversation: initial });
    let finishCancel;
    transport.cancelTurn.mockImplementationOnce(() => new Promise((resolve) => { finishCancel = resolve; }));
    const cancellation = controller.toggleListening();
    await transport.emit('event', {
      protocolVersion: '2.0', eventId: '00000000-0000-4000-8000-000000000004',
      type: 'local.screen.state', sequence: 4, timestamp: '2026-09-19T00:00:00.000Z',
      data: { state: 'OFF' },
    });
    expect(runtime.sleeping).toBe(true);
    finishCancel();
    await cancellation;
    expect(runtime.sleeping).toBe(true);
    expect(runtime.activeTurnId).toBe('');
    expect(vad.start).not.toHaveBeenCalled();
    app.unmount();
  });

  it.each([false, true])('converges a rejected cancellation ACK without duplicate cancellation (moving=%s)', async (moving) => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.SPEAKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, playback, runtime, vad } = await mountController({ transport, conversation: initial });
    runtime.robotMoving = moving;
    let rejectCancel;
    transport.cancelTurn.mockImplementation(() => new Promise((_resolve, reject) => { rejectCancel = reject; }));
    const cancellation = controller.toggleListening();
    expect(controller.toggleListening()).toBe(cancellation);
    expect(playback.stop).toHaveBeenCalledWith('client-cancelled');
    expect(vad.start).not.toHaveBeenCalled();
    rejectCancel(new Error('Cancellation ACK lost'));
    await expect(cancellation).resolves.toBeUndefined();
    expect(transport.cancelTurn).toHaveBeenCalledOnce();
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(vad.start).toHaveBeenCalledOnce();
    expect(controller.canStartNewSession.value).toBe(true);
    transport.getConversation.mockResolvedValue({ ...initial, turnState: TURN_STATES.THINKING });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.SESSION_SNAPSHOT, sequence: 3, data: { ...initial },
    });
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId: 'turn-1',
      data: { artifacts: [{ artifactId: 'late-after-ack' }] },
    });
    expect(transport.resolveAudio).not.toHaveBeenCalled();
    expect(runtime.lastSequence).toBe(4);
    app.unmount();
  });

  it('returns to awake idle while the moving robot cancellation ACK is pending', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.LISTENING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, playback, runtime, vad } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    await controller.toggleListening();
    vad.start.mockClear();
    runtime.robotMoving = true;
    let finishCancel;
    transport.cancelTurn.mockImplementation(() => new Promise((resolve) => { finishCancel = resolve; }));
    const cancellation = controller.toggleListening();
    expect(runtime.sleeping).toBe(false);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(playback.stop).toHaveBeenCalledWith('client-cancelled');
    expect(vad.pause).toHaveBeenCalledOnce();
    finishCancel();
    await cancellation;
    expect(transport.cancelTurn).toHaveBeenCalledOnce();
    expect(vad.start).not.toHaveBeenCalled();
    app.unmount();
  });

  it('handles cancellation without waiting for the first audio artifact download', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime } = await mountController({ transport, conversation: initial });
    let finishDownload;
    transport.resolveAudio.mockImplementation(() => new Promise((resolve) => { finishDownload = resolve; }));
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId: 'turn-1',
      data: { artifacts: [{ artifactId: 'part-1' }] },
    });
    expect(runtime.lastSequence).toBe(4);
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_CANCELLED, sequence: 5, turnId: 'turn-1', data: {},
    });
    finishDownload(new Blob(['late']));
    await nextTick();
    expect(runtime.lastSequence).toBe(5);
    expect(playback.play).not.toHaveBeenCalled();
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    app.unmount();
  });

  it('commits normal controls without jumping over later conversation events', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, runtime } = await mountController({ transport, conversation: initial });
    await transport.emit('event', {
      protocolVersion: '2.0', eventId: '00000000-0000-4000-8000-000000000004',
      type: 'local.gateway.state', sequence: 4, timestamp: '2026-09-17T00:00:00.000Z', data: { state: 'READY' },
    });
    expect(transport.getConversation).toHaveBeenCalledOnce();
    expect(runtime.lastSequence).toBe(4);
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.AGENT_TEXT_FINAL, sequence: 5, turnId: 'turn-1', data: { text: '原本的回答' },
    });
    expect(runtime.assistantText).toBe('原本的回答');
    expect(runtime.lastSequence).toBe(5);
    app.unmount();
  });

  it('plays every artifact in order and holds the emotion until the final segment ends', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime, timers } = await mountController({ transport, conversation: initial });
    runtime.queueEmotion(Emotion.HAPPY, 0);
    const activate = vi.spyOn(runtime, 'activatePendingEmotion');
    const artifacts = [{ artifactId: 'part-1' }, { artifactId: 'part-2' }];
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId: 'turn-1', data: { artifacts },
    });
    expect(transport.resolveAudio).toHaveBeenCalledExactlyOnceWith(artifacts[0]);
    const first = playback.play.mock.calls[0][1];
    first.onStarted();
    await first.onEnded();
    expect(playback.play).toHaveBeenCalledTimes(2);
    expect(transport.resolveAudio).toHaveBeenLastCalledWith(artifacts[1]);
    expect(runtime.effectiveEmotion).toBe(Emotion.HAPPY);
    expect(runtime.turnState).toBe(TURN_STATES.SPEAKING);
    expect(timers.scheduleResume).not.toHaveBeenCalled();
    const second = playback.play.mock.calls[1][1];
    second.onStarted();
    expect(activate).toHaveBeenCalledOnce();
    await second.onEnded();
    expect(runtime.effectiveEmotion).toBe(Emotion.NEUTRAL);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(timers.scheduleResume).not.toHaveBeenCalled(); // Recovered playback has no manual listening intent.
    expect(transport.sendPlayback.mock.calls.map(([status, , metadata]) => [status, metadata.artifactId])).toEqual([
      ['started', 'part-1'], ['completed', 'part-1'], ['started', 'part-2'], ['completed', 'part-2'],
    ]);
    app.unmount();
  });

  it('cancels the whole playlist while the next artifact is still downloading', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime, timers } = await mountController({ transport, conversation: initial });
    let finishDownload;
    transport.resolveAudio.mockResolvedValueOnce(new Blob(['one'])).mockImplementationOnce(() => new Promise((resolve) => { finishDownload = resolve; }));
    runtime.queueEmotion(Emotion.EXCITED);
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId: 'turn-1',
      data: { artifacts: [{ artifactId: 'part-1' }, { artifactId: 'part-2' }] },
    });
    const first = playback.play.mock.calls[0][1];
    first.onStarted();
    const pendingEnd = first.onEnded();
    await vi.waitFor(() => expect(transport.resolveAudio).toHaveBeenCalledTimes(2));
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TURN_CANCELLED, sequence: 5, turnId: 'turn-1', data: {},
    });
    finishDownload(new Blob(['two']));
    await pendingEnd;
    expect(playback.play).toHaveBeenCalledOnce();
    expect(runtime.effectiveEmotion).toBe(Emotion.NEUTRAL);
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(timers.scheduleResume).not.toHaveBeenCalled();
    expect(transport.sendPlayback).toHaveBeenLastCalledWith('interrupted', 'turn-1', {
      artifactId: 'part-2', reason: 'client_cancelled',
    });
    app.unmount();
  });

  it('stops the playlist on a failed artifact instead of skipping to later speech', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: 'turn-1', turnState: TURN_STATES.THINKING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime, timers } = await mountController({ transport, conversation: initial });
    transport.resolveAudio.mockResolvedValueOnce(new Blob(['one'])).mockRejectedValueOnce(new Error('Audio artifact digest does not match metadata.'));
    await transport.emit('event', {
      protocolVersion: '2.0', type: RuntimeEventType.TTS_READY, sequence: 4, turnId: 'turn-1',
      data: { artifacts: [{ artifactId: 'part-1' }, { artifactId: 'part-2' }, { artifactId: 'part-3' }] },
    });
    const first = playback.play.mock.calls[0][1];
    first.onStarted();
    await first.onEnded();
    expect(playback.play).toHaveBeenCalledOnce();
    expect(transport.resolveAudio).toHaveBeenCalledTimes(2);
    expect(runtime.turnState).toBe(TURN_STATES.ERROR);
    expect(runtime.error).toContain('digest');
    expect(timers.scheduleResume).not.toHaveBeenCalled();
    app.unmount();
  });

  it('clears captions only on actual speech and preserves later events when the upload ACK arrives late', async () => {
    const initial = {
      sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3,
      transcript: '上一句語音', assistantText: '上一輪回答',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, runtime, vad, vadCallbacks } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
    await controller.wakeUp();
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(runtime.assistantText).toBe('上一輪回答');
    await vadCallbacks.onSpeechStart();
    expect(runtime.transcript).toBe('');
    expect(runtime.assistantText).toBe('');
    const turnId = runtime.activeTurnId;
    let acceptUpload;
    transport.uploadVoiceTurn.mockImplementation(() => new Promise((resolve) => { acceptUpload = resolve; }));
    const upload = vadCallbacks.onSpeechEnd({ blob: new Blob(['voice'], { type: 'audio/wav' }) });
    await vi.waitFor(() => expect(acceptUpload).toBeTypeOf('function'));
    await transport.emit('event', {
      protocolVersion: '2.0', sequence: 4, type: RuntimeEventType.TURN_ACCEPTED, turnId, data: {},
    });
    expect(runtime.turnState).toBe(TURN_STATES.TRANSCRIBING);
    await transport.emit('event', {
      protocolVersion: '2.0', sequence: 5, type: RuntimeEventType.STT_FINAL, turnId, data: { text: '新的語音' },
    });
    expect(runtime.turnState).toBe(TURN_STATES.THINKING);
    expect(runtime.transcript).toBe('新的語音');
    await transport.emit('event', {
      protocolVersion: '2.0', sequence: 6, type: RuntimeEventType.AGENT_TEXT_FINAL, turnId, data: { text: '新的回答' },
    });
    acceptUpload({ state: 'accepted', turnId, clientTurnId: turnId });
    await upload;
    expect(runtime.turnState).toBe(TURN_STATES.SYNTHESIZING);
    expect(runtime.assistantText).toBe('新的回答');
    expect(runtime.lastSequence).toBe(6);
    app.unmount();
  });

  it('waits for TURN_BUSY with the same recorded audio and turn UUID, then accepts the retry', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, runtime, timers, vadCallbacks } = await mountController({ transport, conversation: initial });
    await controller.toggleListening();
    transport.uploadVoiceTurn.mockRejectedValueOnce(Object.assign(new Error('Previous run is still cancelling.'), { code: 'TURN_BUSY' })).mockResolvedValueOnce({ accepted: true });
    await vadCallbacks.onSpeechStart();
    const blob = new Blob(['voice'], { type: 'audio/wav' });
    await vadCallbacks.onSpeechEnd({ blob });
    expect(runtime.waitingForPreviousTurn).toBe(true);
    expect(runtime.turnState).toBe(TURN_STATES.UPLOADING);
    expect(runtime.error).toBe('');
    expect(runtime.statusLabel).toBe('等待前一個回合結束');
    timers.scheduleTurnRetry.mock.calls[0][0]();
    await vi.waitFor(() => expect(runtime.waitingForPreviousTurn).toBe(false));
    expect(transport.uploadVoiceTurn).toHaveBeenCalledTimes(2);
    expect(transport.uploadVoiceTurn.mock.calls[1][0]).toEqual(transport.uploadVoiceTurn.mock.calls[0][0]);
    expect(runtime.turnState).toBe(TURN_STATES.TRANSCRIBING);
    app.unmount();
  });

  it('does not retry a busy upload after the user cancels it', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.IDLE, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, runtime, timers, vadCallbacks } = await mountController({ transport, conversation: initial });
    await controller.toggleListening();
    transport.uploadVoiceTurn.mockRejectedValue(Object.assign(new Error('Busy'), { code: 'TURN_BUSY' }));
    await vadCallbacks.onSpeechStart();
    await vadCallbacks.onSpeechEnd({ blob: new Blob(['voice'], { type: 'audio/wav' }) });
    const retry = timers.scheduleTurnRetry.mock.calls[0][0];
    await controller.toggleListening();
    retry();
    expect(runtime.waitingForPreviousTurn).toBe(false);
    expect(transport.uploadVoiceTurn).toHaveBeenCalledOnce();
    expect(timers.clearTurnRetry).toHaveBeenCalled();
    app.unmount();
  });

  it('synchronizes playback mouth level into runtime state and returns it to zero', async () => {
    const initial = {
      sessionId: 'session-1',
      activeTurnId: 'turn-1',
      turnState: TURN_STATES.SPEAKING,
      lastSequence: 3,
      transcript: '',
      assistantText: '',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime } = await mountController({ transport, conversation: initial });

    playback.mouthLevel.value = 0.64;
    await nextTick();
    expect(runtime.mouthLevel).toBe(0.64);

    playback.mouthLevel.value = 0;
    await nextTick();
    expect(runtime.mouthLevel).toBe(0);
    app.unmount();
  });

  it('activates a pending backend emotion only when TTS playback starts and clears it when playback ends', async () => {
    const initial = {
      sessionId: 'session-1',
      activeTurnId: 'turn-1',
      turnState: TURN_STATES.THINKING,
      lastSequence: 3,
      transcript: '',
      assistantText: '',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime } = await mountController({ transport, conversation: initial });

    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.TOOL_CALL,
      sequence: 4,
      turnId: 'turn-1',
      data: {
        callId: '00000000-0000-4000-8000-000000000004',
        toolName: 'show_emotion',
        toolVersion: '1.0.0',
        arguments: { emotion: Emotion.HAPPY, durationMs: 2000 },
        timeoutMs: 5000,
        deadlineAt: '2999-01-01T00:00:00.000Z',
      },
    });

    expect(runtime.pendingEmotion).toBe(Emotion.HAPPY);
    expect(runtime.explicitEmotion).toBe(Emotion.NEUTRAL);
    expect(runtime.effectiveEmotion).toBe(Emotion.CURIOUS);
    expect(transport.sendToolResult).toHaveBeenLastCalledWith(
      expect.objectContaining({
        status: 'success',
        result: { ok: true, emotion: Emotion.HAPPY, durationMs: 2000 },
      }),
    );

    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.AGENT_TEXT_FINAL,
      sequence: 5,
      turnId: 'turn-1',
      data: { text: '很高興見到你！' },
    });
    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.TTS_READY,
      sequence: 6,
      turnId: 'turn-1',
      data: { artifacts: [{ artifactId: '00000000-0000-4000-8000-000000000006' }] },
    });

    expect(playback.play).toHaveBeenCalledOnce();
    expect(runtime.explicitEmotion).toBe(Emotion.NEUTRAL);
    const playbackCallbacks = playback.play.mock.calls[0][1];
    const playbackStartedAt = Date.now();
    playbackCallbacks.onStarted();
    expect(runtime.turnState).toBe(TURN_STATES.SPEAKING);
    expect(runtime.effectiveEmotion).toBe(Emotion.HAPPY);
    expect(runtime.emotionExpiresAt).toBeGreaterThanOrEqual(playbackStartedAt + 2000);

    await playbackCallbacks.onEnded();
    expect(runtime.turnState).toBe(TURN_STATES.IDLE);
    expect(runtime.effectiveEmotion).toBe(Emotion.NEUTRAL);
    app.unmount();
  });

  it('re-reads the local conversation for a Native snapshot before later turn events', async () => {
    const initial = {
      sessionId: 'session-1',
      activeTurnId: 'turn-1',
      turnState: TURN_STATES.THINKING,
      lastSequence: 5,
      transcript: '保留這段話',
      assistantText: '',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, runtime } = await mountController({ transport, conversation: initial });
    transport.getConversation.mockResolvedValueOnce({
      ...initial,
      turnState: TURN_STATES.AWAITING_TOOL,
      lastSequence: 10,
    });

    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.SESSION_SNAPSHOT,
      sequence: 10,
      data: { state: 'active', lastSequence: 10, activeTurnId: 'turn-1' },
    });

    expect(transport.getConversation).toHaveBeenCalledTimes(2);
    expect(runtime.turnState).toBe(TURN_STATES.AWAITING_TOOL);
    expect(runtime.activeTurnId).toBe('turn-1');
    expect(runtime.transcript).toBe('保留這段話');

    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.AGENT_THINKING,
      sequence: 11,
      turnId: 'turn-1',
      data: {},
    });
    expect(runtime.lastSequence).toBe(11);
    expect(runtime.turnState).toBe(TURN_STATES.THINKING);
    expect(transport.failProtocol).not.toHaveBeenCalled();
    app.unmount();
  });

  it('refreshes connection state without skipping retained Native events after reconnect', async () => {
    const initial = {
      sessionId: 'session-1',
      activeTurnId: 'turn-1',
      turnState: TURN_STATES.THINKING,
      lastSequence: 3,
      transcript: '',
      assistantText: '',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, runtime } = await mountController({ transport, conversation: initial });
    await transport.emit('connection', { state: CONNECTION_STATES.DEGRADED });
    transport.getConversation.mockResolvedValueOnce({
      ...initial,
      turnState: TURN_STATES.AWAITING_TOOL,
      lastSequence: 4,
    });

    await transport.emit('runtimeConnected', { connected: true });

    expect(transport.getRuntimeStatus).toHaveBeenCalledTimes(2);
    expect(transport.getConversation).toHaveBeenCalledOnce();
    expect(runtime.connectionState).toBe(CONNECTION_STATES.READY);
    expect(runtime.turnState).toBe(TURN_STATES.THINKING);
    expect(runtime.lastSequence).toBe(3);

    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.SESSION_READY,
      sequence: 4,
      data: { resumedAfter: 3 },
    });
    expect(transport.failProtocol).not.toHaveBeenCalled();
    expect(runtime.lastSequence).toBe(4);
    app.unmount();
  });

  it('converges an authoritative upload failure and cleans local activity', async () => {
    const initial = {
      sessionId: 'session-1',
      activeTurnId: 'turn-1',
      turnState: TURN_STATES.UPLOADING,
      lastSequence: 3,
      transcript: '剛送出的內容',
      assistantText: '',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime, timers, vad } = await mountController({
      transport,
      conversation: initial,
    });
    transport.getConversation.mockResolvedValueOnce({
      ...initial,
      activeTurnId: null,
      turnState: TURN_STATES.ERROR,
      lastSequence: 7,
    });

    await transport.emit('event', {
      protocolVersion: '2.0',
      eventId: '00000000-0000-4000-8000-000000000001',
      type: 'local.gateway.state',
      sequence: 7,
      timestamp: '2026-07-18T00:00:00.000Z',
      data: {
        state: 'READY',
        detail: 'Gateway upload failed.',
        errorCode: 'GATEWAY_OFFLINE',
      },
    });

    expect(runtime.turnState).toBe(TURN_STATES.ERROR);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.error).toBe('Gateway upload failed.');
    expect(timers.clearAll).toHaveBeenCalled();
    expect(vad.pause).toHaveBeenCalled();
    expect(playback.stop).toHaveBeenCalledWith('turn-error');
    app.unmount();
  });

  it('cleans timers, VAD, and playback for a Native turn.error', async () => {
    const initial = {
      sessionId: 'session-1',
      activeTurnId: 'turn-1',
      turnState: TURN_STATES.THINKING,
      lastSequence: 3,
      transcript: '',
      assistantText: '',
    };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, playback, runtime, timers, vad } = await mountController({
      transport,
      conversation: initial,
    });

    await transport.emit('event', {
      protocolVersion: '2.0',
      type: RuntimeEventType.TURN_ERROR,
      sequence: 4,
      turnId: 'turn-1',
      data: { error: { message: 'Provider timed out.' } },
    });

    expect(runtime.turnState).toBe(TURN_STATES.ERROR);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.error).toBe('Provider timed out.');
    expect(timers.clearAll).toHaveBeenCalled();
    expect(vad.pause).toHaveBeenCalled();
    expect(playback.stop).toHaveBeenCalledWith('turn-error');
    expect(runtime.lastSequence).toBe(4);
    app.unmount();
  });
});
