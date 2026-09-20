import { createPinia, setActivePinia } from 'pinia';
import { createRenderer, defineComponent, nextTick, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import {
  CONNECTION_STATES,
  Emotion,
  RuntimeEventType,
  TURN_STATES,
  useRuntimeStore,
} from '../stores/runtime';
import { gatewayErrorState, useRuntimeController } from './useRuntimeController';
import { RuntimeTransport } from '../services/runtimeTransport';

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
    const oldListening = transport.emit('connection', { state: CONNECTION_STATES.READY });
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
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(runtime.lastSequence).toBe(7);
    expect(vad.start).toHaveBeenCalledOnce();
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
    expect(vad.start).toHaveBeenCalledOnce(); // Initial boot only.
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
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
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

  it('discards capture across a disconnect and listens for fresh speech after READY without a settings error', async () => {
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
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(transport.uploadVoiceTurn).not.toHaveBeenCalled();
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
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.error).toBe('');
    expect(vad.start).toHaveBeenCalledOnce();
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
    const { app, runtime, vad, vadCallbacks } = await mountController({ transport, conversation: initial });
    vad.start.mockImplementation(async () => { vad.isRunning.value = true; });
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
    expect(runtime.turnState).toBe(TURN_STATES.LISTENING);
    expect(runtime.activeTurnId).toBe('');
    expect(runtime.error).toBe('');
    expect(vad.start).toHaveBeenCalledTimes(2); // Initial boot and recovered listening.
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
    expect(vad.start).toHaveBeenCalledOnce();
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

  it('sleeps and pauses local capture while the moving robot cancellation ACK is pending', async () => {
    const initial = { sessionId: 'session-1', activeTurnId: '', turnState: TURN_STATES.LISTENING, lastSequence: 3 };
    const transport = fakeTransport({ status: { gatewayState: 'READY' }, conversation: initial });
    const { app, controller, playback, runtime, vad } = await mountController({ transport, conversation: initial });
    runtime.robotMoving = true;
    let finishCancel;
    transport.cancelTurn.mockImplementation(() => new Promise((resolve) => { finishCancel = resolve; }));
    const cancellation = controller.toggleListening();
    expect(runtime.sleeping).toBe(true);
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
    expect(timers.scheduleResume).toHaveBeenCalledOnce();
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
    const { app, runtime, timers, vadCallbacks } = await mountController({ transport, conversation: initial });
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
