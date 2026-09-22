import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import {
  CONNECTION_STATES,
  Emotion,
  TURN_STATES,
  useRuntimeStore,
} from './runtime';

describe('runtime store', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('keeps unknown battery readings distinct from zero percent', () => {
    const store = useRuntimeStore();
    expect(store.batteryLabel).toBe('--%');
    store.setBattery({ percentage: 0, charging: true });
    expect(store.batteryLabel).toBe('0%');
    expect(store.battery.charging).toBe(true);
    store.setBattery({ percentage: 73, charging: false });
    expect(store.batteryLabel).toBe('73%');
    store.setBattery({ percentage: null, charging: null });
    expect(store.batteryLabel).toBe('--%');
  });



  it('explains why the device is not ready instead of inviting speech while disconnected', () => {
    const store = useRuntimeStore();
    store.recoveryNotice = '連線已恢復';
    const cases = [
      [CONNECTION_STATES.TLS_ERROR, '連線憑證需要處理'],
      [CONNECTION_STATES.AUTH_ERROR, '連線驗證失敗'],
      [CONNECTION_STATES.OFFLINE, '尚未連線'],
      [CONNECTION_STATES.CONNECTING, '正在連線'],
      [CONNECTION_STATES.DEGRADED, '正在恢復連線'],
      [CONNECTION_STATES.UNCONFIGURED, '請先完成設定'],
      [CONNECTION_STATES.INCOMPATIBLE, '服務版本不相容'],
    ];
    for (const [connection, label] of cases) {
      store.setConnection(connection);
      expect(store.statusLabel).toBe(label);
      expect(store.turnState).toBe(TURN_STATES.IDLE);
    }
  });

  it('keeps sleep and pending cancellation ahead of connection status', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.TLS_ERROR);
    store.waitingForPreviousTurn = true;
    expect(store.statusLabel).toBe('等待前一個回合結束');
    store.sleeping = true;
    expect(store.statusLabel).toBe('休眠中');
  });

  it('preserves ready conversation and recovery labels without changing the active turn', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    expect(store.statusLabel).toBe('準備好了');
    store.transition('speech_started', { turnId: 'active-turn' });
    expect(store.statusLabel).toBe('我在聽');
    store.transition('playback_started');
    expect(store.statusLabel).toBe('說話中');
    expect(store.activeTurnId).toBe('active-turn');
    store.transition('reset');
    store.recoveryNotice = '連線中斷，請再說一次';
    expect(store.statusLabel).toBe('連線中斷，請再說一次');
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
    const robot = { protocolVersion: '2.0', type: 'local.robot.state', sequence: 2, data: { ready: true, moving: false, motionEnabled: false, battery: { percentage: null, charging: null } } };
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
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);
    expect(store.explicitEmotion).toBe(Emotion.NEUTRAL);
    expect(store.emotionExpiresAt).toBe(0);
  });

  it('adds an attentive reply expression only after local playback starts', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.applyConversationSnapshot({ turnState: TURN_STATES.SPEAKING });
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);

    store.transition('reset');
    store.activatePendingEmotion(1000);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
    store.transition('playback_started');
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);
    expect(store.explicitEmotion).toBe(Emotion.NEUTRAL);
    store.clearExpiredEmotion(100000);
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);

    store.transition('reset');
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
    expect(store.pendingEmotion).toBe('');
    expect(store.explicitEmotion).toBe(Emotion.NEUTRAL);
  });

  it.each([Emotion.NEUTRAL, Emotion.EXCITED])('preserves explicit %s through every audio segment', (emotion) => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.queueEmotion(emotion, 0);
    store.activatePendingEmotion(1000);
    store.transition('playback_started');
    expect(store.effectiveEmotion).toBe(emotion);

    store.clearExpiredEmotion(100000);
    store.transition('playback_started');
    expect(store.effectiveEmotion).toBe(emotion);
    expect(store.emotionExpiresAt).toBe(0);
    expect(store.replyEmotionFallback).toBe(false);
  });

  it('respects the duration of explicit neutral before returning to the reply fallback', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.queueEmotion(Emotion.NEUTRAL, 1500);
    store.activatePendingEmotion(1000);
    store.transition('playback_started');
    store.clearExpiredEmotion(2499);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
    store.clearExpiredEmotion(2500);
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);
  });

  it('keeps queued emotion staged while allowing an explicit override of the reply fallback', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.activatePendingEmotion(1000);
    store.transition('playback_started');
    store.queueEmotion(Emotion.EXCITED);
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);

    store.setEmotion(Emotion.NEUTRAL);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
    store.setEmotion(Emotion.HAPPY);
    expect(store.effectiveEmotion).toBe(Emotion.HAPPY);
    expect(store.pendingEmotion).toBe(Emotion.EXCITED);
  });

  it('returns an expired non-playback expression to ordinary standby', () => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.setEmotion(Emotion.HAPPY, 1500);
    store.clearExpiredEmotion(store.emotionExpiresAt);
    expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
    expect(store.replyEmotionFallback).toBe(false);
  });

  it.each([
    ['terminal', (store) => store.transition('reset')],
    ['new speech', (store) => store.transition('speech_started')],
    ['failure', (store) => store.transition('failed')],
    ['wake', (store) => store.wakeUp()],
    ['sleep', (store) => store.goToSleep()],
    ['snapshot', (store) => store.applyConversationSnapshot({ turnState: TURN_STATES.SPEAKING })],
    ['connection loss', (store) => store.setConnection(CONNECTION_STATES.DEGRADED)],
    ['interruption', (store) => store.resetEmotion()],
  ])('clears reply-only expression state after %s', (_reason, reset) => {
    const store = useRuntimeStore();
    store.setConnection(CONNECTION_STATES.READY);
    store.activatePendingEmotion(1000);
    store.transition('playback_started');
    expect(store.effectiveEmotion).toBe(Emotion.CURIOUS);

    store.queueEmotion(Emotion.EXCITED, 0);
    reset(store);
    expect(store.replyEmotionFallback).toBe(false);
    expect(store.pendingEmotion).toBe('');
    expect(store.explicitEmotion).toBe(Emotion.NEUTRAL);
    expect(store.emotionExpiresAt).toBe(0);
  });

  it('clears indefinite explicit emotion after a turn ends, connection fails, or the screen sleeps', () => {
    const store = useRuntimeStore();
    for (const reset of [() => store.transition('reset'),
      () => store.setConnection(CONNECTION_STATES.DEGRADED), () => store.goToSleep()]) {
      store.setConnection(CONNECTION_STATES.READY);
      store.setEmotion(Emotion.HAPPY, 0);
      expect(store.effectiveEmotion).toBe(Emotion.HAPPY);
      reset();
      expect(store.explicitEmotion).toBe(Emotion.NEUTRAL);
      expect(store.effectiveEmotion).toBe(Emotion.NEUTRAL);
    }
    expect(store.sleeping).toBe(true);
    expect(store.turnState).toBe(TURN_STATES.IDLE);
  });

});
