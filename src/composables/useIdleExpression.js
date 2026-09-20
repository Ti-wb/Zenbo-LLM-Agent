import { computed, onBeforeUnmount, onMounted, ref, unref, watch } from 'vue';
import { CONNECTION_STATES, Emotion, TURN_STATES } from '../stores/runtime';

const idleEmotions = [Emotion.HAPPY, Emotion.CURIOUS, Emotion.EXCITED];

export function useIdleExpression(runtime, {
  enabled = true,
  random = Math.random,
  setTimeoutImpl = globalThis.setTimeout,
  clearTimeoutImpl = globalThis.clearTimeout,
  visibilityDocument = globalThis.document,
} = {}) {
  const mounted = ref(false);
  const visible = ref(!visibilityDocument?.hidden);
  const idleEmotion = ref(Emotion.NEUTRAL);
  let timer;

  const eligible = computed(() => mounted.value && visible.value && unref(enabled)
    && runtime.connectionState === CONNECTION_STATES.READY
    && runtime.turnState === TURN_STATES.IDLE
    && !runtime.sleeping && !runtime.micEnabled && !runtime.turnBusy
    && !runtime.activeTurnId && !runtime.waitingForPreviousTurn
    && !runtime.error && !runtime.settingsOpen
    && !runtime.pendingEmotion && runtime.explicitEmotion === Emotion.NEUTRAL);

  function clearTimer() {
    if (timer !== undefined) clearTimeoutImpl(timer);
    timer = undefined;
  }

  function scheduleExpression() {
    timer = setTimeoutImpl(() => {
      if (!eligible.value) return;
      idleEmotion.value = idleEmotions[Math.floor(random() * idleEmotions.length)];
      timer = setTimeoutImpl(() => {
        idleEmotion.value = Emotion.NEUTRAL;
        if (eligible.value) scheduleExpression();
      }, 3000 + Math.floor(random() * 2001));
    }, 20000 + Math.floor(random() * 20001));
  }

  watch(eligible, (canAnimate) => {
    clearTimer();
    idleEmotion.value = Emotion.NEUTRAL;
    if (canAnimate) scheduleExpression();
  }, { flush: 'sync' });

  function updateVisibility() {
    visible.value = !visibilityDocument?.hidden;
  }

  onMounted(() => {
    updateVisibility();
    visibilityDocument?.addEventListener('visibilitychange', updateVisibility);
    mounted.value = true;
  });

  onBeforeUnmount(() => {
    mounted.value = false;
    clearTimer();
    visibilityDocument?.removeEventListener('visibilitychange', updateVisibility);
  });

  // Keep ambient expressions separate from tool-owned, playback-staged emotion.
  return computed(() => eligible.value ? idleEmotion.value : runtime.effectiveEmotion);
}
