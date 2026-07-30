import { createPinia, setActivePinia } from 'pinia';
import { createRenderer, defineComponent, nextTick, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import {
  CONNECTION_STATES,
  Emotion,
  GatewayEventType,
  TURN_STATES,
  useRuntimeStore,
} from '../stores/runtime';
import { gatewayErrorState, useRuntimeController } from './useRuntimeController';

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
    clearInactivity: vi.fn(),
    clearResume: vi.fn(),
    scheduleInactivity: vi.fn(),
    scheduleResume: vi.fn(),
  };
  let controller;
  const app = testRenderer().createApp(
    defineComponent({
      setup() {
        controller = useRuntimeController({
          bootstrapToken: 'bootstrap-token',
          playback,
          timers,
          transport,
          vad,
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
  return { app, playback, runtime, timers, vad };
}

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

describe('runtime authoritative recovery', () => {
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
      type: GatewayEventType.TOOL_CALL,
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
      type: GatewayEventType.AGENT_TEXT_FINAL,
      sequence: 5,
      turnId: 'turn-1',
      data: { text: '很高興見到你！' },
    });
    await transport.emit('event', {
      type: GatewayEventType.TTS_READY,
      sequence: 6,
      turnId: 'turn-1',
      data: { artifactId: '00000000-0000-4000-8000-000000000006' },
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

  it('re-reads the local conversation for a remote snapshot before later turn events', async () => {
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
      type: GatewayEventType.SESSION_SNAPSHOT,
      sequence: 10,
      data: { state: 'active', lastSequence: 10, activeTurnId: 'turn-1' },
    });

    expect(transport.getConversation).toHaveBeenCalledTimes(2);
    expect(runtime.turnState).toBe(TURN_STATES.AWAITING_TOOL);
    expect(runtime.activeTurnId).toBe('turn-1');
    expect(runtime.transcript).toBe('保留這段話');

    await transport.emit('event', {
      type: GatewayEventType.AGENT_THINKING,
      sequence: 11,
      turnId: 'turn-1',
      data: {},
    });
    expect(runtime.lastSequence).toBe(11);
    expect(runtime.turnState).toBe(TURN_STATES.THINKING);
    expect(transport.failProtocol).not.toHaveBeenCalled();
    app.unmount();
  });

  it('refreshes Gateway and turn state after the local event stream reconnects', async () => {
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
    expect(transport.getConversation).toHaveBeenCalledTimes(2);
    expect(runtime.connectionState).toBe(CONNECTION_STATES.READY);
    expect(runtime.turnState).toBe(TURN_STATES.AWAITING_TOOL);
    expect(runtime.lastSequence).toBe(4);

    await transport.emit('event', {
      type: GatewayEventType.SESSION_READY,
      sequence: 3,
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
    });

    await transport.emit('event', {
      protocolVersion: '1.0',
      eventId: '00000000-0000-4000-8000-000000000001',
      type: 'local.gateway.state',
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

  it('cleans timers, VAD, and playback for a remote turn.error', async () => {
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
      type: GatewayEventType.TURN_ERROR,
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
