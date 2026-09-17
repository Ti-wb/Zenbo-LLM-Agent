import { onBeforeUnmount, ref } from 'vue';
import { MicVAD } from '@ricky0123/vad-web';
import { encodePcm16Wav } from '../utils/audio';

function localAssetUrl(relativePath) {
  if (typeof document === 'undefined') return relativePath;
  return new URL(relativePath, document.baseURI).href;
}

export function useVAD(options = {}) {
  const {
    sampleRate = 16000,
    onSpeechStart,
    onSpeechEnd,
    onError,
  } = options;

  const isReady = ref(false);
  const isRunning = ref(false);
  const isAudioReady = ref(false);
  const inputLevel = ref(0);
  const isSpeechActive = ref(false);
  const isInitializing = ref(false);
  const error = ref(null);

  let instance = null;
  let destroyed = false;
  let initPromise = null;
  let startPromise = null;
  let destroyPromise = null;
  let operation = Promise.resolve();
  let generation = 0;
  let wantsRunning = false;
  let processingGeneration = -1;
  let firstFrameGeneration = -1;

  const enqueue = (task) => {
    operation = operation.then(task, task);
    return operation;
  };

  const reportError = (cause) => {
    error.value = cause instanceof Error ? cause : new Error(String(cause));
    onError?.(error.value);
  };

  const init = () => {
    if (destroyed) return Promise.resolve(null);
    if (instance) return Promise.resolve(instance);
    if (initPromise) return initPromise;
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      reportError(new Error('此裝置無法使用麥克風。'));
      return Promise.resolve(null);
    }

    isInitializing.value = true;
    error.value = null;

    initPromise = (async () => {
      try {
        instance = await MicVAD.new({
          model: 'v5',
          startOnLoad: false,
          baseAssetPath: localAssetUrl('./vad/'),
          onnxWASMBasePath: localAssetUrl('./vad/ort/'),
          onFrameProcessed: (_probabilities, samples) => {
            if (destroyed || !wantsRunning || processingGeneration !== generation) return;
            firstFrameGeneration = generation;
            let squareSum = 0;
            for (let index = 0; index < samples.length; index += 1) {
              squareSum += samples[index] * samples[index];
            }
            // Visual input feedback only; speech detection keeps the model's defaults.
            const level = Math.min(1, Math.sqrt(squareSum / Math.max(1, samples.length)) * 12);
            inputLevel.value = inputLevel.value * 0.5 + level * 0.5;
            isAudioReady.value = isRunning.value;
          },
          getStream: () =>
            navigator.mediaDevices.getUserMedia({
              audio: {
                channelCount: 1,
                echoCancellation: true,
                autoGainControl: true,
                noiseSuppression: true,
              },
            }),
          onSpeechRealStart: () => {
            if (!isRunning.value || destroyed) return;
            isSpeechActive.value = true;
            onSpeechStart?.();
          },
          onSpeechEnd: async (samples) => {
            isSpeechActive.value = false;
            if (!isRunning.value || destroyed) return;
            const blob = encodePcm16Wav(samples, sampleRate);
            await onSpeechEnd?.({ blob, samples, sampleRate });
          },
          onVADMisfire: () => {
            isSpeechActive.value = false;
          },
        });
        isReady.value = !destroyed;
        return instance;
      } catch (cause) {
        if (!destroyed) reportError(cause);
        instance = null;
        return null;
      } finally {
        isInitializing.value = false;
        initPromise = null;
      }
    })();
    return initPromise;
  };

  const start = () => {
    if (destroyed) return Promise.resolve();
    if (wantsRunning) return startPromise || Promise.resolve();
    wantsRunning = true;
    const request = ++generation;
    const pending = enqueue(async () => {
      const vad = instance || (await init());
      if (destroyed || request !== generation) return;
      if (!vad) {
        wantsRunning = false;
        return;
      }
      try {
        processingGeneration = request;
        await vad.start();
        if (!destroyed && request === generation) {
          isRunning.value = true;
          isAudioReady.value = firstFrameGeneration === request;
        }
      } catch (cause) {
        if (!destroyed && request === generation) {
          wantsRunning = false;
          reportError(cause);
        }
      }
    });
    startPromise = pending;
    void pending.finally(() => {
      if (startPromise === pending) startPromise = null;
    });
    return pending;
  };

  const pause = () => {
    generation += 1;
    wantsRunning = false;
    isRunning.value = false;
    isAudioReady.value = false;
    inputLevel.value = 0;
    isSpeechActive.value = false;
    // Stop even if start() is still obtaining a stream. A later start waits its turn.
    return enqueue(async () => {
      try {
        await instance?.pause();
      } catch (cause) {
        if (!destroyed) reportError(cause);
      }
    });
  };

  const destroy = () => {
    if (destroyPromise) return destroyPromise;
    destroyed = true;
    generation += 1;
    wantsRunning = false;
    isReady.value = false;
    isRunning.value = false;
    isAudioReady.value = false;
    inputLevel.value = 0;
    isSpeechActive.value = false;
    destroyPromise = enqueue(async () => {
      await initPromise;
      try {
        await instance?.destroy();
      } catch (cause) {
        reportError(cause);
      } finally {
        instance = null;
      }
    });
    return destroyPromise;
  };

  onBeforeUnmount(destroy);

  return {
    error,
    isInitializing,
    isReady,
    isRunning,
    isAudioReady,
    inputLevel,
    isSpeechActive,
    init,
    start,
    pause,
    destroy,
  };
}
