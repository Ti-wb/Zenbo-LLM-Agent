import { afterEach, describe, expect, it, vi } from 'vitest';
import { createListeningCue } from './listeningCue';

function audioHarness(state = 'running') {
  const oscillators = [];
  const gains = [];
  const contexts = [];
  class AudioContextImpl {
    constructor() {
      this.state = state;
      this.currentTime = 10;
      this.destination = {};
      this.resume = vi.fn(async () => { this.state = 'running'; });
      this.close = vi.fn(async () => { this.state = 'closed'; });
      contexts.push(this);
    }

    createOscillator() {
      const oscillator = {
        frequency: { setValueAtTime: vi.fn() },
        connect: vi.fn(), disconnect: vi.fn(), start: vi.fn(), stop: vi.fn(),
      };
      oscillators.push(oscillator);
      return oscillator;
    }

    createGain() {
      const gain = {
        gain: { setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn() },
        connect: vi.fn(), disconnect: vi.fn(),
      };
      gains.push(gain);
      return gain;
    }
  }
  return { AudioContextImpl, contexts, oscillators, gains };
}

afterEach(() => vi.useRealTimers());

describe('listening cue', () => {
  it('plays one brief, quiet cue when audio is available and safely skips unavailable audio', async () => {
    const audio = audioHarness();
    const cue = createListeningCue(audio);
    expect(audio.contexts).toHaveLength(0);
    expect(await cue.play()).toBe(true);
    expect(audio.oscillators).toHaveLength(1);
    const oscillator = audio.oscillators[0];
    const duration = oscillator.stop.mock.calls[0][0] - oscillator.start.mock.calls[0][0];
    expect(duration).toBeGreaterThan(0);
    expect(duration).toBeLessThanOrEqual(0.2);
    const levels = audio.gains[0].gain.linearRampToValueAtTime.mock.calls.map(([level]) => level);
    expect(Math.max(...levels)).toBeGreaterThan(0);
    expect(Math.max(...levels)).toBeLessThanOrEqual(0.05);
    expect(levels.at(-1)).toBe(0);
    oscillator.onended();
    expect(oscillator.disconnect).toHaveBeenCalledOnce();
    expect(audio.gains[0].disconnect).toHaveBeenCalledOnce();
    cue.destroy();
    const missing = createListeningCue({ AudioContextImpl: null });
    expect(await missing.play()).toBe(false);
    const denied = createListeningCue({ AudioContextImpl: class { constructor() { throw new Error('Unavailable'); } } });
    expect(await denied.play()).toBe(false);
    const suspended = audioHarness('suspended');
    const rejected = createListeningCue(suspended);
    const playing = rejected.play();
    suspended.contexts[0].resume = vi.fn().mockRejectedValue(new Error('Not allowed'));
    expect(await playing).toBe(false);
    expect(suspended.oscillators).toHaveLength(0);
    rejected.destroy();
  });

  it('cancels pending or active audio when readiness is lost or the component is destroyed', async () => {
    const audio = audioHarness('suspended');
    let resume;
    const cue = createListeningCue(audio);
    const playing = cue.play();
    audio.contexts[0].resume = vi.fn(() => new Promise((resolve) => { resume = resolve; }));
    await Promise.resolve();
    cue.cancel();
    expect(await playing).toBe(false);
    audio.contexts[0].state = 'running';
    resume();
    await Promise.resolve();
    expect(audio.oscillators).toHaveLength(0);
    expect(await cue.play()).toBe(true);
    cue.destroy();
    expect(audio.oscillators[0].stop).toHaveBeenLastCalledWith();
    expect(audio.oscillators[0].disconnect).toHaveBeenCalledOnce();
    expect(audio.contexts[0].close).toHaveBeenCalledOnce();
    expect(await cue.play()).toBe(false);
    expect(audio.contexts).toHaveLength(1);
  });

  it('expires a blocked autoplay resume instead of replaying on a later user gesture', async () => {
    vi.useFakeTimers();
    const audio = audioHarness('suspended');
    const cue = createListeningCue(audio);
    const playing = cue.play();
    let resume;
    audio.contexts[0].resume = vi.fn(() => new Promise((resolve) => { resume = resolve; }));
    await vi.advanceTimersByTimeAsync(250);
    expect(await playing).toBe(false);
    audio.contexts[0].state = 'running';
    resume();
    await Promise.resolve();
    expect(audio.oscillators).toHaveLength(0);
    cue.destroy();
  });

});
