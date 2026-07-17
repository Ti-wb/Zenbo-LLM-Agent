import { ref } from 'vue';

export function createAudioPlayback(dependencies = {}) {
  const AudioImpl = dependencies.AudioImpl || globalThis.Audio;
  const AudioContextImpl =
    dependencies.AudioContextImpl || globalThis.AudioContext || globalThis.webkitAudioContext;
  const createObjectURL = dependencies.createObjectURL || globalThis.URL?.createObjectURL?.bind(globalThis.URL);
  const revokeObjectURL = dependencies.revokeObjectURL || globalThis.URL?.revokeObjectURL?.bind(globalThis.URL);
  const requestFrame = dependencies.requestAnimationFrame || globalThis.requestAnimationFrame?.bind(globalThis);
  const cancelFrame = dependencies.cancelAnimationFrame || globalThis.cancelAnimationFrame?.bind(globalThis);

  const isPlaying = ref(false);
  const mouthLevel = ref(0);
  const error = ref(null);

  let audio = null;
  let objectUrl = '';
  let audioContext = null;
  let sourceNode = null;
  let analyser = null;
  let analyserData = null;
  let frameId = 0;
  let generation = 0;
  let callbacks = {};

  const stopAnalyser = () => {
    if (frameId) cancelFrame?.(frameId);
    frameId = 0;
    mouthLevel.value = 0;
  };

  const closeGraph = async () => {
    stopAnalyser();
    try {
      sourceNode?.disconnect();
      analyser?.disconnect();
    } catch {
      // Already disconnected.
    }
    sourceNode = null;
    analyser = null;
    analyserData = null;
    if (audioContext && audioContext.state !== 'closed') {
      try {
        await audioContext.close();
      } catch {
        // Some Android audio contexts reject close during teardown.
      }
    }
    audioContext = null;
  };

  const releaseMedia = async () => {
    if (audio) {
      audio.onended = null;
      audio.onerror = null;
      audio.pause();
      audio.removeAttribute?.('src');
      audio.load?.();
      audio = null;
    }
    if (objectUrl) revokeObjectURL?.(objectUrl);
    objectUrl = '';
    await closeGraph();
  };

  const updateMouth = () => {
    if (!analyser || !analyserData || !isPlaying.value) return;
    analyser.getByteFrequencyData(analyserData);
    let sum = 0;
    const upper = Math.min(36, analyserData.length);
    for (let index = 2; index < upper; index += 1) sum += analyserData[index];
    const average = sum / Math.max(1, upper - 2);
    const target = Math.max(0, Math.min(1, (average - 12) / 120));
    mouthLevel.value = mouthLevel.value * 0.58 + target * 0.42;
    frameId = requestFrame?.(updateMouth) || 0;
  };

  const stop = async (reason = 'interrupted') => {
    const wasPlaying = isPlaying.value;
    generation += 1;
    isPlaying.value = false;
    const stoppedCallbacks = callbacks;
    callbacks = {};
    await releaseMedia();
    if (wasPlaying) stoppedCallbacks.onInterrupted?.(reason);
  };

  const play = async (blob, options = {}) => {
    if (!blob || !AudioImpl || !createObjectURL) {
      throw new Error('Audio playback is not available.');
    }

    await stop('replaced');
    const playGeneration = generation;
    callbacks = options;
    error.value = null;
    objectUrl = createObjectURL(blob);
    audio = new AudioImpl(objectUrl);
    audio.preload = 'auto';

    if (AudioContextImpl) {
      try {
        audioContext = new AudioContextImpl();
        sourceNode = audioContext.createMediaElementSource(audio);
        analyser = audioContext.createAnalyser();
        analyser.fftSize = 256;
        analyser.smoothingTimeConstant = 0.68;
        analyserData = new Uint8Array(analyser.frequencyBinCount);
        sourceNode.connect(analyser);
        analyser.connect(audioContext.destination);
        await audioContext.resume?.();
      } catch (cause) {
        console.warn('Audio analyser unavailable; continuing playback without mouth tracking.', cause);
        await closeGraph();
      }
    }

    audio.onended = async () => {
      if (playGeneration !== generation) return;
      isPlaying.value = false;
      const endedCallbacks = callbacks;
      callbacks = {};
      await releaseMedia();
      endedCallbacks.onEnded?.();
    };

    audio.onerror = async () => {
      if (playGeneration !== generation) return;
      const playbackError = new Error('Audio playback failed.');
      error.value = playbackError;
      isPlaying.value = false;
      const failedCallbacks = callbacks;
      callbacks = {};
      await releaseMedia();
      failedCallbacks.onError?.(playbackError);
    };

    try {
      await audio.play();
      isPlaying.value = true;
      callbacks.onStarted?.();
      updateMouth();
    } catch (cause) {
      error.value = cause;
      isPlaying.value = false;
      const failedCallbacks = callbacks;
      callbacks = {};
      await releaseMedia();
      failedCallbacks.onError?.(cause);
      throw cause;
    }
  };

  return { error, isPlaying, mouthLevel, play, stop };
}

const singleton = createAudioPlayback();

export function useAudioPlayback() {
  return singleton;
}
