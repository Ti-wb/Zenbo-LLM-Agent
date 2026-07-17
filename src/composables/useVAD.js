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
  const isSpeechActive = ref(false);
  const isInitializing = ref(false);
  const error = ref(null);

  let instance = null;
  let destroyed = false;

  const reportError = (cause) => {
    error.value = cause instanceof Error ? cause : new Error(String(cause));
    onError?.(error.value);
  };

  const init = async () => {
    if (instance || isInitializing.value || destroyed) return instance;
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      reportError(new Error('此裝置無法使用麥克風。'));
      return null;
    }

    isInitializing.value = true;
    error.value = null;

    try {
      instance = await MicVAD.new({
        model: 'v5',
        startOnLoad: false,
        baseAssetPath: localAssetUrl('./vad/'),
        onnxWASMBasePath: localAssetUrl('./vad/ort/'),
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
          isSpeechActive.value = true;
          onSpeechStart?.();
        },
        onSpeechEnd: async (samples) => {
          isSpeechActive.value = false;
          const blob = encodePcm16Wav(samples, sampleRate);
          await onSpeechEnd?.({ blob, samples, sampleRate });
        },
        onVADMisfire: () => {
          isSpeechActive.value = false;
        },
      });
      isReady.value = true;
      return instance;
    } catch (cause) {
      reportError(cause);
      instance = null;
      return null;
    } finally {
      isInitializing.value = false;
    }
  };

  const start = async () => {
    if (destroyed || isRunning.value) return;
    const vad = instance || (await init());
    if (!vad) return;
    try {
      await vad.start();
      isRunning.value = true;
    } catch (cause) {
      reportError(cause);
    }
  };

  const pause = async () => {
    if (!instance || !isRunning.value) return;
    try {
      await instance.pause();
    } catch (cause) {
      reportError(cause);
    } finally {
      isRunning.value = false;
      isSpeechActive.value = false;
    }
  };

  const destroy = async () => {
    destroyed = true;
    if (!instance) return;
    try {
      await instance.destroy();
    } catch (cause) {
      reportError(cause);
    } finally {
      instance = null;
      isReady.value = false;
      isRunning.value = false;
      isSpeechActive.value = false;
    }
  };

  onBeforeUnmount(destroy);

  return {
    error,
    isInitializing,
    isReady,
    isRunning,
    isSpeechActive,
    init,
    start,
    pause,
    destroy,
  };
}
