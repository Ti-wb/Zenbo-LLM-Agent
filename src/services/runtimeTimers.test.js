import { describe, expect, it, vi } from 'vitest';
import { createRuntimeTimers } from './runtimeTimers';

describe('createRuntimeTimers', () => {
  it('retries a busy turn after 500 ms and cancels pending retries with all activity', () => {
    vi.useFakeTimers();
    const timers = createRuntimeTimers();
    const retried = vi.fn();
    timers.scheduleTurnRetry(retried);
    vi.advanceTimersByTime(499);
    expect(retried).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(retried).toHaveBeenCalledOnce();
    timers.scheduleTurnRetry(retried);
    timers.clearAll();
    vi.advanceTimersByTime(500);
    expect(retried).toHaveBeenCalledOnce();
    vi.useRealTimers();
  });
  it('replaces timers and releases every pending callback during teardown', () => {
    vi.useFakeTimers();
    const timers = createRuntimeTimers();
    const resumed = vi.fn();
    const slept = vi.fn();

    timers.scheduleResume(resumed);
    timers.scheduleResume(resumed);
    timers.scheduleInactivity(slept);
    expect(vi.getTimerCount()).toBe(2);

    vi.advanceTimersByTime(500);
    expect(resumed).toHaveBeenCalledOnce();
    expect(slept).not.toHaveBeenCalled();

    timers.clearAll();
    expect(vi.getTimerCount()).toBe(0);
    vi.advanceTimersByTime(20000);
    expect(slept).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('fires the inactivity callback at twenty seconds', () => {
    vi.useFakeTimers();
    const timers = createRuntimeTimers();
    const slept = vi.fn();
    timers.scheduleInactivity(slept);

    vi.advanceTimersByTime(19999);
    expect(slept).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(slept).toHaveBeenCalledOnce();
    vi.useRealTimers();
  });
});
