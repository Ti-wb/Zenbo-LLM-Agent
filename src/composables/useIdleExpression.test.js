import { createPinia, setActivePinia } from 'pinia';
import { createRenderer, defineComponent, ref } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CONNECTION_STATES, Emotion, TURN_STATES, useRuntimeStore } from '../stores/runtime';
import { useIdleExpression } from './useIdleExpression';

const renderer = createRenderer({
  patchProp() {},
  insert() {},
  remove() {},
  createElement: () => ({}),
  createText: () => ({}),
  createComment: () => ({}),
  setText() {},
  setElementText() {},
  parentNode: () => null,
  nextSibling: () => null,
});

describe('idle expressions', () => {
  const apps = [];

  beforeEach(() => {
    vi.useFakeTimers();
    setActivePinia(createPinia());
  });

  afterEach(() => {
    for (const app of apps.splice(0)) app.unmount();
    vi.useRealTimers();
  });

  function mount({ patch = {}, random = vi.fn(() => 0), hidden = false } = {}) {
    const runtime = useRuntimeStore();
    runtime.setConnection(CONNECTION_STATES.READY);
    runtime.$patch(patch);
    const enabled = ref(true);
    const visibilityDocument = new EventTarget();
    visibilityDocument.hidden = hidden;
    let emotion;
    const app = renderer.createApp(defineComponent({
      setup() {
        emotion = useIdleExpression(runtime, { enabled, random, visibilityDocument });
        return () => null;
      },
    }));
    app.mount({});
    apps.push(app);
    return { runtime, emotion, enabled, visibilityDocument, random, app };
  }

  it('mostly stays neutral, briefly emotes, then starts a fresh idle wait', () => {
    const { emotion } = mount();
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(19999);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.HAPPY);
    vi.advanceTimersByTime(2999);
    expect(emotion.value).toBe(Emotion.HAPPY);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    vi.advanceTimersByTime(19999);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.HAPPY);
    expect(vi.getTimerCount()).toBe(1);
  });

  it('can select the curious expression between happy and excited', () => {
    const random = vi.fn().mockReturnValueOnce(0).mockReturnValueOnce(0.5).mockReturnValue(0);
    const { emotion } = mount({ random });
    vi.advanceTimersByTime(20000);
    expect(emotion.value).toBe(Emotion.CURIOUS);
  });

  it('bounds the longest idle wait and expression duration at 40 and 5 seconds', () => {
    const { emotion } = mount({ random: () => 0.99999 });
    vi.advanceTimersByTime(39999);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.EXCITED);
    vi.advanceTimersByTime(4999);
    expect(emotion.value).toBe(Emotion.EXCITED);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
  });

  it.each([
    ['disconnected', { connectionState: CONNECTION_STATES.DEGRADED }],
    ['sleeping', { sleeping: true }],
    ['microphone enabled', { micEnabled: true }],
    ['busy', { turnBusy: true }],
    ['active turn', { activeTurnId: 'local-turn' }],
    ['waiting for cancellation', { waitingForPreviousTurn: true }],
    ['error', { error: '需要處理' }],
    ['settings open', { settingsOpen: true }],
  ])('stops and resets an ambient expression when %s', (_label, patch) => {
    const { runtime, emotion } = mount();
    vi.advanceTimersByTime(20000);
    expect(emotion.value).toBe(Emotion.HAPPY);
    runtime.$patch(patch);
    expect(emotion.value).toBe(runtime.effectiveEmotion);
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(120000);
    expect(emotion.value).toBe(runtime.effectiveEmotion);
  });

  it('does not schedule until ready and restarts the full wait after listening', () => {
    const { runtime, emotion } = mount({ patch: { connectionState: CONNECTION_STATES.CONNECTING } });
    expect(vi.getTimerCount()).toBe(0);
    runtime.setConnection(CONNECTION_STATES.READY);
    vi.advanceTimersByTime(19000);
    runtime.transition('speech_started');
    expect(emotion.value).toBe(Emotion.CURIOUS);
    expect(vi.getTimerCount()).toBe(0);
    runtime.transition('reset');
    vi.advanceTimersByTime(19999);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.HAPPY);
  });

  it('leaves staged and explicit reply emotion untouched during ordered playback', () => {
    const { runtime, emotion } = mount();
    vi.advanceTimersByTime(20000);
    runtime.queueEmotion(Emotion.EXCITED, 0);
    expect(vi.getTimerCount()).toBe(0);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    expect(runtime.pendingEmotion).toBe(Emotion.EXCITED);
    expect(runtime.explicitEmotion).toBe(Emotion.NEUTRAL);
    runtime.activatePendingEmotion();
    runtime.transition('playback_started');
    vi.advanceTimersByTime(120000);
    expect(emotion.value).toBe(Emotion.EXCITED);
    expect(runtime.pendingEmotion).toBe('');
    expect(runtime.explicitEmotion).toBe(Emotion.EXCITED);
    expect(runtime.emotionExpiresAt).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    runtime.transition('reset');
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    expect(vi.getTimerCount()).toBe(1);
  });

  it('preserves an explicit neutral tool expression until playback starts', () => {
    const { runtime, emotion } = mount();
    runtime.queueEmotion(Emotion.NEUTRAL, 0);
    vi.advanceTimersByTime(120000);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    expect(runtime.pendingEmotion).toBe(Emotion.NEUTRAL);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('stops while hidden and starts a fresh idle wait when visible', () => {
    const { emotion, visibilityDocument } = mount({ hidden: true });
    expect(vi.getTimerCount()).toBe(0);
    visibilityDocument.hidden = false;
    visibilityDocument.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(20000);
    expect(emotion.value).toBe(Emotion.HAPPY);
    visibilityDocument.hidden = true;
    visibilityDocument.dispatchEvent(new Event('visibilitychange'));
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    expect(vi.getTimerCount()).toBe(0);
    visibilityDocument.hidden = false;
    visibilityDocument.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(19999);
    expect(emotion.value).toBe(Emotion.NEUTRAL);
    vi.advanceTimersByTime(1);
    expect(emotion.value).toBe(Emotion.HAPPY);
  });

  it('keeps at most one timer while external eligibility repeatedly changes', () => {
    const { emotion, enabled } = mount();
    for (let index = 0; index < 20; index += 1) {
      vi.advanceTimersByTime(20000);
      expect(emotion.value).toBe(Emotion.HAPPY);
      enabled.value = false;
      expect(emotion.value).toBe(Emotion.NEUTRAL);
      expect(vi.getTimerCount()).toBe(0);
      enabled.value = true;
      expect(vi.getTimerCount()).toBe(1);
    }
  });

  it('removes its timer and visibility listener on unmount', () => {
    const { app, visibilityDocument, random } = mount();
    const removeListener = vi.spyOn(visibilityDocument, 'removeEventListener');
    vi.advanceTimersByTime(20000);
    const callsBeforeUnmount = random.mock.calls.length;
    app.unmount();
    apps.pop();
    expect(removeListener).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
    visibilityDocument.hidden = true;
    visibilityDocument.dispatchEvent(new Event('visibilitychange'));
    visibilityDocument.hidden = false;
    visibilityDocument.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(120000);
    expect(random).toHaveBeenCalledTimes(callsBeforeUnmount);
    expect(vi.getTimerCount()).toBe(0);
  });
});
