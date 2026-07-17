import { ref, onMounted, onUnmounted } from 'vue';
import { MicVAD } from '@ricky0123/vad-web';

import { useRobotAPI } from './useRobotAPI';

function float32ArrayToWavBlob(float32Array, sampleRate) {
  const int16Array = new Int16Array(float32Array.length);
  for (let i = 0; i < float32Array.length; i++) {
    const sample = Math.max(-1, Math.min(1, float32Array[i]));
    int16Array[i] = sample * 0x7fff;
  }

  const length = int16Array.length;
  const buffer = new ArrayBuffer(44 + length * 2);
  const view = new DataView(buffer);

  const writeString = (offset, string) => {
    for (let i = 0; i < string.length; i++) {
      view.setUint8(offset + i, string.charCodeAt(i));
    }
  };

  writeString(0, 'RIFF');
  view.setUint32(4, 36 + length * 2, true);
  writeString(8, 'WAVE');
  writeString(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeString(36, 'data');
  view.setUint32(40, length * 2, true);

  const offset = 44;
  for (let i = 0; i < length; i++) {
    view.setInt16(offset + i * 2, int16Array[i], true);
  }

  return new Blob([buffer], { type: 'audio/wav' });
}

export function useVAD(options = {}) {
  const {
    sampleRate = 16000,
    autoStart = false,
    onSpeechEnd, // legacy callback; prefer event registration below
    onSpeechStart, // legacy callback; prefer event registration below
  } = options;

  const isListening = ref(false);
  const isReady = ref(false);
  const isInitializing = ref(false);
  const error = ref(null);
  const isMuted = ref(false);

  let vadInstance = null;
  let mediaStream = null;
  const speechStartHandlers = new Set();
  const speechEndHandlers = new Set();

  const init = async () => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices) {
      error.value = new Error('Media devices are not available in this environment.');
      return;
    }

    if (vadInstance || isInitializing.value) return;

    isInitializing.value = true;
    error.value = null;

    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
        },
      });

      const vad = await MicVAD.new({
        stream: mediaStream,
        onSpeechEnd: async (audio) => {
          isListening.value = false;

          
          useRobotAPI().setExpression('QUESTIONING');

          const blob = float32ArrayToWavBlob(audio, sampleRate);
          const payload = { audio, blob };

          if (typeof onSpeechEnd === 'function') {
            await onSpeechEnd(payload);
          }

          for (const handler of speechEndHandlers) {
            try {
              await handler(payload);
            } catch (e) {
              console.error('useVAD onSpeechEnd handler error:', e);
            }
          }
        },
        onSpeechRealStart: () => {
          useRobotAPI().unlockExpression();
          useRobotAPI().setExpression('EXPECTING');
          
          isListening.value = true;

          if (typeof onSpeechStart === 'function') {
            onSpeechStart();
          }

          for (const handler of speechStartHandlers) {
            try {
              handler();
            } catch (e) {
              console.error('useVAD onSpeechStart listener error:', e);
            }
          }
        },
        onnxWASMBasePath:
          'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.23.2/dist/',
        baseAssetPath:
          'https://cdn.jsdelivr.net/npm/@ricky0123/vad-web@0.0.30/dist/',
      });

      vadInstance = vad;
      isReady.value = true;
    } catch (e) {
      console.error(e);
      error.value = e;
    } finally {
      isInitializing.value = false;
    }
  };

  const start = async () => {
    if (!vadInstance) {
      await init();
    }
    if (!vadInstance) return;

    try {
      await vadInstance.start();
    } catch (e) {
      console.error(e);
      error.value = e;
    }
  };

  const stop = async () => {
    if (!vadInstance) return;

    try {
      if (typeof vadInstance.pause === 'function') {
        await vadInstance.pause();
      } else if (typeof vadInstance.stop === 'function') {
        await vadInstance.stop();
      }
    } catch (e) {
      console.error(e);
      error.value = e;
    } finally {
      isListening.value = false;
    }
  };

  const destroy = async () => {
    await stop();

    if (vadInstance && typeof vadInstance.destroy === 'function') {
      try {
        await vadInstance.destroy();
      } catch (e) {
        console.error(e);
      }
    }

    vadInstance = null;
    isReady.value = false;

    if (mediaStream) {
      mediaStream.getTracks().forEach((track) => track.stop());
      mediaStream = null;
    }

    speechStartHandlers.clear();
    speechEndHandlers.clear();
  };

  onMounted(async () => {
    if (autoStart) {
      await start();
    } else {
      await init();
    }
  });

  onUnmounted(async () => {
    await destroy();
  });

  const muteMic = () => {
    if (mediaStream) {
      console.log('Muting mic');
      mediaStream.getAudioTracks().forEach((track) => {
        track.enabled = false;
      });
    }
    isMuted.value = true;
    isListening.value = false;
    vadInstance.pause();
  };

  const unmuteMic = () => {
    if (mediaStream) {
      console.log('Unmuting mic');
      mediaStream.getAudioTracks().forEach((track) => {
        track.enabled = true;
      });
    }
    isMuted.value = false;
    vadInstance.start();
  };

  const onSpeechStartEvent = (handler) => {
    if (typeof handler !== 'function') return () => {};
    speechStartHandlers.add(handler);
    return () => speechStartHandlers.delete(handler);
  };

  const onSpeechEndEvent = (handler) => {
    if (typeof handler !== 'function') return () => {};
    speechEndHandlers.add(handler);
    return () => speechEndHandlers.delete(handler);
  };

  return {
    isListening,
    isMuted,
    isReady,
    isInitializing,
    error,
    start,
    stop,
    destroy,
    muteMic,
    unmuteMic,
    onSpeechStart: onSpeechStartEvent,
    onSpeechEnd: onSpeechEndEvent,
  };
}
