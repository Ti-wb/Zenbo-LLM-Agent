// One quiet, local cue; microphone capture remains owned by useVAD.
export function createListeningCue({
  AudioContextImpl = globalThis.AudioContext || globalThis.webkitAudioContext,
} = {}) {
  let context;
  let activeTone;
  let pendingResume;
  let generation = 0;
  let destroyed = false;

  function releaseTone(tone, stop = false) {
    if (!tone) return;
    tone.oscillator.onended = null;
    if (stop) {
      try { tone.oscillator.stop(); } catch { /* Already stopped. */ }
    }
    try { tone.oscillator.disconnect(); } catch { /* Best-effort cleanup. */ }
    try { tone.gain.disconnect(); } catch { /* Best-effort cleanup. */ }
    if (activeTone === tone) activeTone = undefined;
  }

  function cancel() {
    generation += 1;
    pendingResume?.();
    pendingResume = undefined;
    releaseTone(activeTone, true);
  }

  async function play() {
    cancel();
    if (destroyed || !AudioContextImpl) return false;
    const ownGeneration = generation;
    try {
      if (!context || context.state === 'closed') context = new AudioContextImpl();
      if (context.state !== 'running') {
        // Autoplay rejection or a suspended browser must never queue a late cue.
        const resumed = await new Promise((resolve) => {
          let settled = false;
          const finish = (success) => {
            if (settled) return;
            settled = true;
            clearTimeout(timeout);
            if (pendingResume === abort) pendingResume = undefined;
            resolve(success);
          };
          const abort = () => finish(false);
          const timeout = setTimeout(abort, 250);
          pendingResume = abort;
          Promise.resolve().then(() => context.resume()).then(() => finish(true), abort);
        });
        if (!resumed) return false;
      }
      if (destroyed || ownGeneration !== generation || context.state !== 'running') return false;

      const oscillator = context.createOscillator();
      const gain = context.createGain();
      const tone = { oscillator, gain };
      activeTone = tone;
      oscillator.type = 'sine';
      oscillator.frequency.setValueAtTime(660, context.currentTime);
      gain.gain.setValueAtTime(0, context.currentTime);
      gain.gain.linearRampToValueAtTime(0.025, context.currentTime + 0.008);
      gain.gain.linearRampToValueAtTime(0, context.currentTime + 0.09);
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.onended = () => releaseTone(tone);
      oscillator.start(context.currentTime);
      oscillator.stop(context.currentTime + 0.1);
      return true;
    } catch {
      if (ownGeneration === generation) releaseTone(activeTone, true);
      return false;
    }
  }

  function destroy() {
    destroyed = true;
    cancel();
    try { Promise.resolve(context?.close()).catch(() => {}); } catch { /* Optional audio. */ }
    context = undefined;
  }

  return { play, cancel, destroy };
}
