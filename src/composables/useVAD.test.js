import { createRenderer, defineComponent, watch } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import { MicVAD } from '@ricky0123/vad-web';
import { useVAD } from './useVAD';

vi.mock('@ricky0123/vad-web', () => ({ MicVAD: { new: vi.fn() } }));

afterEach(() => vi.unstubAllGlobals());

it('shares initialization, cancels a pending start and serializes a fresh start after cleanup', async () => {
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn() } });
  let finishInit;
  let finishStart;
  const order = [];
  const instance = {
    start: vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => {
        order.push('old-start');
        finishStart = resolve;
      }))
      .mockImplementationOnce(async () => { order.push('new-start'); }),
    pause: vi.fn(async () => { order.push('pause'); }),
    destroy: vi.fn().mockResolvedValue(undefined),
  };
  MicVAD.new.mockImplementationOnce(() => new Promise((resolve) => { finishInit = resolve; }));
  let vad;
  const renderer = createRenderer({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
  });
  const app = renderer.createApp(defineComponent({ setup() { vad = useVAD(); return () => null; } }));
  app.mount({});
  const initial = vad.init();
  expect(vad.init()).toBe(initial);
  const first = vad.start();
  expect(vad.start()).toBe(first);
  finishInit(instance);
  await vi.waitFor(() => expect(instance.start).toHaveBeenCalledOnce());
  const callbacks = MicVAD.new.mock.calls[0][0];
  callbacks.onFrameProcessed({ isSpeech: 0 }, new Float32Array(512));
  expect(vad.isAudioReady.value).toBe(false);
  const runningChanges = [];
  const stopWatch = watch(vad.isRunning, (value) => runningChanges.push(value), { flush: 'sync' });
  const pause = vad.pause();
  expect(vad.isRunning.value).toBe(false);
  expect(vad.isAudioReady.value).toBe(false);
  const second = vad.start();
  expect(instance.start).toHaveBeenCalledOnce();
  finishStart();
  await Promise.all([first, pause, second]);
  expect(order).toEqual(['old-start', 'pause', 'new-start']);
  expect(runningChanges).toEqual([true]);
  expect(MicVAD.new).toHaveBeenCalledOnce();
  expect(vad.isRunning.value).toBe(true);
  expect(vad.isAudioReady.value).toBe(false);
  callbacks.onFrameProcessed({ isSpeech: 0 }, new Float32Array(512));
  expect(vad.isAudioReady.value).toBe(true);
  expect(vad.inputLevel.value).toBe(0);
  callbacks.onFrameProcessed({ isSpeech: 0.8 }, new Float32Array(512).fill(0.05));
  expect(vad.inputLevel.value).toBeGreaterThan(0);
  expect(vad.inputLevel.value).toBeLessThanOrEqual(1);
  await vad.pause();
  expect(vad.isAudioReady.value).toBe(false);
  expect(vad.inputLevel.value).toBe(0);
  instance.start.mockImplementationOnce(async () => {
    callbacks.onFrameProcessed({ isSpeech: 0 }, new Float32Array(512));
    expect(vad.isAudioReady.value).toBe(false);
  });
  await vad.start();
  expect(vad.isAudioReady.value).toBe(true);
  await vad.destroy();
  expect(vad.isRunning.value).toBe(false);
  expect(vad.isAudioReady.value).toBe(false);
  expect(vad.inputLevel.value).toBe(0);
  stopWatch();
  app.unmount();
});
