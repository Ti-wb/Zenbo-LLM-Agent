<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import {
  THINKING_TURN_STATES,
  interpolateFaceFrames,
  interpolateSleepFrames,
  normalizeEmotion,
  renderFace,
  resolveFaceFrame,
  resolveSpeechTransitionMouth,
} from './pixelFaceModel.js';

const props = defineProps({
  emotion: { type: String, default: 'NEUTRAL' },
  mouthLevel: { type: Number, default: 0 },
  sleeping: { type: Boolean, default: false },
  turnState: { type: String, default: 'IDLE' },
});

const EMOTION_LABELS = Object.freeze({
  neutral: '中性',
  happy: '開心',
  curious: '好奇',
  concerned: '擔心',
  excited: '興奮',
  sleeping: '休眠',
});

const TURN_STATE_LABELS = Object.freeze({
  IDLE: '待機',
  LISTENING: '聆聽',
  SPEAKING: '說話',
  ERROR: '需要幫忙',
});

const canvas = ref(null);
const reducedMotion = ref(false);
const ariaLabel = computed(() => {
  const turnState = String(props.turnState || 'IDLE').toUpperCase();
  const emotion = props.sleeping
    ? 'sleeping'
    : turnState === 'LISTENING'
      ? 'curious'
      : normalizeEmotion(props.emotion);
  const state = props.sleeping
    ? '休眠'
    : THINKING_TURN_STATES.includes(turnState)
      ? '思考'
      : TURN_STATE_LABELS[turnState] || '待機';
  return `Zenbo 像素臉：${EMOTION_LABELS[emotion]}；狀態：${state}`;
});

const FRAME_INTERVAL = 1000 / 30;
const EMOTION_TRANSITION_MS = 200;
const SPEECH_ENTRY_MS = 100;
const SPEECH_EXIT_MS = 180;

let animationFrame = 0;
let startedAt = 0;
let lastDrawAt = 0;
let motionQuery = null;
let displayedFrame = null;
let resolvedEmotion = null;
let previousSleeping = false;
let emotionTransition = null;
let speechTransition = null;
let wasSpeaking = false;
let previousEqualizerBand;

function withMouth(frame, mouth) {
  return { ...frame, mouth };
}

function transitionEmotion(frame, time) {
  const sleepingChanged = props.sleeping !== previousSleeping;
  if (frame.emotion !== resolvedEmotion) {
    if (
      resolvedEmotion !== null &&
      displayedFrame &&
      !reducedMotion.value
    ) {
      emotionTransition = {
        startedAt: time,
        from: displayedFrame,
        phase: sleepingChanged
          ? props.sleeping
            ? 'sleeping'
            : 'waking'
          : 'emotion',
      };
    } else {
      emotionTransition = null;
    }
    resolvedEmotion = frame.emotion;
  }
  previousSleeping = props.sleeping;

  if (reducedMotion.value || !emotionTransition) {
    emotionTransition = null;
    return frame;
  }

  const progress = (time - emotionTransition.startedAt) / EMOTION_TRANSITION_MS;
  if (progress >= 1) {
    emotionTransition = null;
    return frame;
  }
  if (emotionTransition.phase !== 'emotion') {
    return interpolateSleepFrames(
      emotionTransition.from,
      frame,
      progress,
      emotionTransition.phase,
    );
  }
  return interpolateFaceFrames(emotionTransition.from, frame, progress);
}

function transitionSpeech(frame, time) {
  if (frame.speaking !== wasSpeaking) {
    speechTransition = {
      phase: frame.speaking ? 'entering' : 'leaving',
      startedAt: time,
      fromMouth: displayedFrame?.mouth || frame.speechClosedMouth,
    };
    wasSpeaking = frame.speaking;
  }

  if (reducedMotion.value || props.sleeping || !speechTransition) {
    speechTransition = null;
    return frame;
  }

  const elapsed = time - speechTransition.startedAt;
  const transitionFinished =
    (speechTransition.phase === 'entering' && elapsed >= SPEECH_ENTRY_MS) ||
    (speechTransition.phase === 'leaving' && elapsed >= SPEECH_EXIT_MS);
  if (transitionFinished) {
    speechTransition = null;
    return frame;
  }
  return withMouth(
    frame,
    resolveSpeechTransitionMouth({
      ...speechTransition,
      elapsedMs: elapsed,
      closedMouth: frame.speechClosedMouth,
      targetMouth: frame.mouth,
    }),
  );
}

function draw(time) {
  animationFrame = requestAnimationFrame(draw);
  if (lastDrawAt && time - lastDrawAt < FRAME_INTERVAL) return;
  lastDrawAt = time;

  const context = canvas.value?.getContext('2d');
  if (!context) return;

  let frame = resolveFaceFrame({
    emotion: props.emotion,
    mouthLevel: props.mouthLevel,
    sleeping: props.sleeping,
    turnState: props.turnState,
    elapsedMs: time - startedAt,
    reducedMotion: reducedMotion.value,
    previousEqualizerBand,
  });

  previousEqualizerBand = frame.speaking ? frame.equalizerBand : undefined;
  frame = transitionEmotion(frame, time);
  frame = transitionSpeech(frame, time);
  displayedFrame = frame;
  renderFace(context, frame);
}

function updateReducedMotion(event) {
  reducedMotion.value = Boolean(event.matches);
  if (reducedMotion.value) {
    emotionTransition = null;
    speechTransition = null;
  }
}

onMounted(() => {
  motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)') || null;
  if (motionQuery) {
    reducedMotion.value = motionQuery.matches;
    if (typeof motionQuery.addEventListener === 'function') {
      motionQuery.addEventListener('change', updateReducedMotion);
    } else {
      motionQuery.addListener?.(updateReducedMotion);
    }
  }

  startedAt = performance.now();
  previousSleeping = props.sleeping;
  animationFrame = requestAnimationFrame(draw);
});

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame);
  if (typeof motionQuery?.removeEventListener === 'function') {
    motionQuery.removeEventListener('change', updateReducedMotion);
  } else {
    motionQuery?.removeListener?.(updateReducedMotion);
  }
});
</script>

<template>
  <canvas
    ref="canvas"
    class="pixel-face"
    width="160"
    height="100"
    role="img"
    :aria-label="ariaLabel"
  />
</template>

<style scoped>
.pixel-face {
  display: block;
  width: min(92vw, calc(92vh * 1.6));
  height: auto;
  aspect-ratio: 8 / 5;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
}
</style>
