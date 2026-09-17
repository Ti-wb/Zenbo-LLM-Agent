import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import {
  CONNECTION_STATES,
  Emotion,
  GatewayState,
  TURN_STATES,
  nextTurnState,
  useRuntimeStore,
} from './runtime';

describe('runtime store', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('exposes only the approved Gateway, Turn, and Emotion enum values', () => {
    expect(Object.values(GatewayState)).toEqual([
      'UNCONFIGURED',
      'CONNECTING',
      'READY',
      'DEGRADED',
      'AUTH_ERROR',
      'TLS_ERROR',
      'INCOMPATIBLE',
      'OFFLINE',
    ]);
    expect(Object.values(TURN_STATES)).toEqual([
      'IDLE',
      'LISTENING',
      'UPLOADING',
      'TRANSCRIBING',
      'THINKING',
      'AWAITING_TOOL',
      'SYNTHESIZING',
      'SPEAKING',
      'ERROR',
    ]);
    expect(Object.values(Emotion)).toEqual([
      'NEUTRAL',
      'HAPPY',
      'CURIOUS',
      'CONCERNED',
      'EXCITED',
    ]);
  });

  it('maps renderer lifecycle events to deterministic turn states', () => {
    expect(nextTurnState(TURN_STATES.IDLE, 'speech_started')).toBe(TURN_STATES.LISTENING);
    expect(nextTurnState(TURN_STATES.LISTENING, 'upload_started')).toBe(TURN_STATES.UPLOADING);
    expect(nextTurnState(TURN_STATES.THINKING, 'tool_started')).toBe(TURN_STATES.AWAITING_TOOL);
    expect(nextTurnState(TURN_STATES.SPEAKING, 'wake')).toBe(TURN_STATES.IDLE);
    expect(nextTurnState(TURN_STATES.IDLE, 'unknown')).toBe(TURN_STATES.IDLE);
  });

  it('atomically applies an authoritative snapshot even when its cursor jumps', () => {
    const store = useRuntimeStore();
    store.lastSequence = 2;
    const snapshot = {
      protocolVersion: '2.0',
      type: 'session.snapshot',
      sequence: 12,
      data: {
        sessionId: 'session-12',
        activeTurnId: 'turn-12',
        turnState: TURN_STATES.AWAITING_TOOL,
        lastSequence: 12,
        transcript: '幫我查天氣',
        assistantText: '我正在查詢。',
      },
    };

    expect(store.applyEnvelope(snapshot)).toBe(true);
    store.applyConversationSnapshot(snapshot.data);
    expect(store.sessionId).toBe('session-12');
    expect(store.lastSequence).toBe(12);
    expect(store.activeTurnId).toBe('turn-12');
    expect(store.turnState).toBe(TURN_STATES.AWAITING_TOOL);
    expect(store.transcript).toBe('幫我查天氣');
    expect(store.assistantText).toBe('我正在查詢。');
  });

  it('rejects a snapshot whose envelope cursor disagrees with its authoritative cursor', () => {
    const store = useRuntimeStore();

    expect(
      store.applyEnvelope({
        protocolVersion: '2.0',
      type: 'session.snapshot',
        sequence: 9,
        data: { lastSequence: 12 },
      }),
    ).toBe(false);
    expect(store.error).toContain('authoritative cursor');
  });

  it('advances the Native cursor for session events and local controls', () => {
    const store = useRuntimeStore();
    const ready = { protocolVersion: '2.0', type: 'session.ready', sequence: 1, data: { resumedAfter: 0 } };
    expect(store.applyEnvelope(ready)).toBe(true);
    store.commitEnvelope(ready);
    expect(store.lastSequence).toBe(1);
    const robot = { protocolVersion: '2.0', type: 'local.robot.state', sequence: 2, data: { ready: true, moving: false } };
    expect(store.applyEnvelope(robot)).toBe(true);
    store.commitEnvelope(robot);
    expect(store.lastSequence).toBe(2);
    expect(store.applyEnvelope(robot)).toBe(false);
    expect(store.applyEnvelope({ protocolVersion: '2.0', type: 'agent.thinking', sequence: 4 })).toBe(false);
    expect(store.error).toContain('預期 3');
  });

  it('rejects legacy and unversioned event envelopes', () => {
    const store = useRuntimeStore();
    for (const protocolVersion of [undefined, '1.0']) {
      expect(store.applyEnvelope({ protocolVersion, type: 'stt.final', sequence: 1 })).toBe(false);
    }
    expect(store.lastSequence).toBe(0);
  });

  it('never admits device or session tokens into Pinia state', () => {
    const store = useRuntimeStore();
    store.setSession({ sessionId: 'local-session', sessionToken: 'local-token' });
    store.patchSettings({ apiKey: 'device-secret', robotName: 'Kira' });

    expect(store.sessionId).toBe('local-session');
    expect(store).not.toHaveProperty('sessionToken');
    expect(store.settings).not.toHaveProperty('apiKey');
    expect(store.settings.robotName).toBe('Kira');
  });

  it('keeps a backend emotion pending until playback and expires it from playback start', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.queueEmotion(Emotion.HAPPY, 1500);
    expect(store.pendingEmotion).toBe(Emotion.HAPPY);
    expect(store.explicitEmotion).toBe(Emotion.NEUTRAL);

    store.transition('thinking_started');
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);

    store.activatePendingEmotion(1000);
    store.transition('playback_started');
    expect(store.pendingEmotion).toBe('');
    expect(store.effectiveEmotion).toBe(Emotion.HAPPY);
    expect(store.emotionExpiresAt).toBe(2500);

    store.clearExpiredEmotion(2499);
    expect(store.effectiveEmotion).toBe(Emotion.HAPPY);
    store.clearExpiredEmotion(2500);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
  });

  it('keeps duration zero through playback but clears emotions on terminal and safety resets', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.queueEmotion(Emotion.EXCITED, 0);
    store.activatePendingEmotion(1000);
    store.transition('playback_started');

    expect(store.effectiveEmotion).toBe(Emotion.EXCITED);
    expect(store.emotionExpiresAt).toBe(0);

    store.transition('reset');
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);

    store.queueEmotion(Emotion.HAPPY, 0);
    store.transition('speech_started');
    expect(store.pendingEmotion).toBe('');
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);

    store.setEmotion(Emotion.HAPPY);
    store.setConnection(CONNECTION_STATES.DEGRADED);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);

    store.setConnection(CONNECTION_STATES.READY);
    store.setEmotion(Emotion.HAPPY);
    store.goToSleep();
    expect(store.sleeping).toBe(true);
    expect(store.turnState).toBe(TURN_STATES.IDLE);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
  });
});
