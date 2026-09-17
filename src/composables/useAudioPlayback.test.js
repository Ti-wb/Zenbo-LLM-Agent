import { describe, expect, it, vi } from 'vitest';
import { createAudioPlayback } from './useAudioPlayback';

class FakeAudio {
  static instances = [];

  constructor(src) {
    this.src = src;
    this.play = vi.fn().mockResolvedValue(undefined);
    this.pause = vi.fn();
    this.removeAttribute = vi.fn();
    this.load = vi.fn();
    FakeAudio.instances.push(this);
  }
}

function audioContextHarness(initialFrequency = 80) {
  let frequency = initialFrequency;
  const source = { connect: vi.fn(), disconnect: vi.fn() };
  const analyser = {
    connect: vi.fn(),
    disconnect: vi.fn(),
    frequencyBinCount: 128,
    getByteFrequencyData: vi.fn((data) => data.fill(frequency)),
  };
  class FakeAudioContext {
    constructor() {
      this.destination = {};
      this.state = 'running';
      this.createMediaElementSource = vi.fn(() => source);
      this.createAnalyser = vi.fn(() => analyser);
      this.resume = vi.fn().mockResolvedValue(undefined);
      this.close = vi.fn().mockResolvedValue(undefined);
    }
  }
  return {
    analyser,
    AudioContextImpl: FakeAudioContext,
    setFrequency(value) {
      frequency = value;
    },
  };
}

function animationFrameHarness() {
  let nextId = 1;
  const callbacks = new Map();
  return {
    request: vi.fn((callback) => {
      const id = nextId;
      nextId += 1;
      callbacks.set(id, callback);
      return id;
    }),
    cancel: vi.fn((id) => callbacks.delete(id)),
    step() {
      const entry = callbacks.entries().next().value;
      if (!entry) throw new Error('No animation frame is pending.');
      const [id, callback] = entry;
      callbacks.delete(id);
      callback();
    },
  };
}

describe('createAudioPlayback', () => {
  it('does not restart audio when a cancelled play promise resolves late', async () => {
    FakeAudio.instances = [];
    let finishPlay;
    class PendingAudio extends FakeAudio {
      constructor(src) {
        super(src);
        this.play = vi.fn(() => new Promise((resolve) => { finishPlay = resolve; }));
      }
    }
    const onStarted = vi.fn();
    const playback = createAudioPlayback({
      AudioImpl: PendingAudio,
      AudioContextImpl: audioContextHarness().AudioContextImpl,
      createObjectURL: () => 'blob:pending',
      revokeObjectURL: vi.fn(),
    });
    const pending = playback.play(new Blob(['one']), { onStarted });
    await vi.waitFor(() => expect(finishPlay).toBeTypeOf('function'));
    await playback.stop('client-cancelled');
    finishPlay();
    await pending;
    expect(onStarted).not.toHaveBeenCalled();
    expect(playback.isPlaying.value).toBe(false);
    expect(playback.mouthLevel.value).toBe(0);
  });

  it('owns one audio element at a time and revokes its object URL on stop', async () => {
    FakeAudio.instances = [];
    const revokeObjectURL = vi.fn();
    const onInterrupted = vi.fn();
    const playback = createAudioPlayback({
      AudioImpl: FakeAudio,
      AudioContextImpl: audioContextHarness().AudioContextImpl,
      createObjectURL: vi.fn(() => 'blob:test'),
      revokeObjectURL,
      requestAnimationFrame: vi.fn(() => 1),
      cancelAnimationFrame: vi.fn(),
    });

    await playback.play(new Blob(['audio']), { onInterrupted });
    expect(playback.isPlaying.value).toBe(true);
    expect(FakeAudio.instances).toHaveLength(1);

    await playback.stop('barge-in');
    expect(playback.isPlaying.value).toBe(false);
    expect(playback.mouthLevel.value).toBe(0);
    expect(FakeAudio.instances[0].pause).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:test');
    expect(onInterrupted).toHaveBeenCalledWith('barge-in');
  });

  it('smooths silence, normal, and loud analyser input while clamping the target range', async () => {
    FakeAudio.instances = [];
    const audioContext = audioContextHarness(0);
    const animationFrames = animationFrameHarness();
    const playback = createAudioPlayback({
      AudioImpl: FakeAudio,
      AudioContextImpl: audioContext.AudioContextImpl,
      createObjectURL: vi.fn(() => 'blob:levels'),
      revokeObjectURL: vi.fn(),
      requestAnimationFrame: animationFrames.request,
      cancelAnimationFrame: animationFrames.cancel,
    });

    await playback.play(new Blob(['audio']));
    expect(playback.mouthLevel.value).toBe(0);

    audioContext.setFrequency(72);
    animationFrames.step();
    expect(playback.mouthLevel.value).toBeCloseTo(0.21, 5);

    audioContext.setFrequency(255);
    animationFrames.step();
    expect(playback.mouthLevel.value).toBeCloseTo(0.5418, 5);
    expect(playback.mouthLevel.value).toBeGreaterThan(0);
    expect(playback.mouthLevel.value).toBeLessThanOrEqual(1);
    expect(audioContext.analyser.getByteFrequencyData).toHaveBeenCalledTimes(3);

    await playback.stop();
  });

  it('resets mouth level immediately when playback ends or reports an error', async () => {
    FakeAudio.instances = [];
    const onEnded = vi.fn();
    const onError = vi.fn();
    const playback = createAudioPlayback({
      AudioImpl: FakeAudio,
      AudioContextImpl: audioContextHarness(132).AudioContextImpl,
      createObjectURL: vi
        .fn()
        .mockReturnValueOnce('blob:ended')
        .mockReturnValueOnce('blob:error'),
      revokeObjectURL: vi.fn(),
      requestAnimationFrame: vi.fn(() => 1),
      cancelAnimationFrame: vi.fn(),
    });

    await playback.play(new Blob(['ended']), { onEnded });
    expect(playback.mouthLevel.value).toBeCloseTo(0.42, 5);
    await FakeAudio.instances[0].onended();
    expect(playback.isPlaying.value).toBe(false);
    expect(playback.mouthLevel.value).toBe(0);
    expect(onEnded).toHaveBeenCalledOnce();

    await playback.play(new Blob(['error']), { onError });
    expect(playback.mouthLevel.value).toBeCloseTo(0.42, 5);
    await FakeAudio.instances[1].onerror();
    expect(playback.isPlaying.value).toBe(false);
    expect(playback.mouthLevel.value).toBe(0);
    expect(playback.error.value).toEqual(new Error('Audio playback failed.'));
    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'Audio playback failed.',
      }),
    );
  });
});
