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

function fakeAudioContext() {
  const source = { connect: vi.fn(), disconnect: vi.fn() };
  const analyser = {
    connect: vi.fn(),
    disconnect: vi.fn(),
    frequencyBinCount: 128,
    getByteFrequencyData: vi.fn((data) => data.fill(80)),
  };
  return class FakeAudioContext {
    constructor() {
      this.destination = {};
      this.state = 'running';
      this.createMediaElementSource = vi.fn(() => source);
      this.createAnalyser = vi.fn(() => analyser);
      this.resume = vi.fn().mockResolvedValue(undefined);
      this.close = vi.fn().mockResolvedValue(undefined);
    }
  };
}

describe('createAudioPlayback', () => {
  it('owns one audio element at a time and revokes its object URL on stop', async () => {
    FakeAudio.instances = [];
    const revokeObjectURL = vi.fn();
    const onInterrupted = vi.fn();
    const playback = createAudioPlayback({
      AudioImpl: FakeAudio,
      AudioContextImpl: fakeAudioContext(),
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
});
