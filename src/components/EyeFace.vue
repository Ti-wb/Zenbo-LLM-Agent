<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { renderEyeFace } from './eyeFaceModel.js';
import {
  THINKING_TURN_STATES,
  normalizeEmotion,
  resolveEqualizerBand,
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
  const emotion = props.sleeping ? 'sleeping'
    : turnState === 'LISTENING' ? 'curious' : normalizeEmotion(props.emotion);
  const state = props.sleeping ? '休眠'
    : THINKING_TURN_STATES.includes(turnState) ? '思考'
      : TURN_STATE_LABELS[turnState] || '待機';
  return `Zenbo 雙眼像素臉：${EMOTION_LABELS[emotion]}；狀態：${state}`;
});

let animationFrame = 0;
let startedAt = 0;
let lastDrawAt = 0;
let lastFrameKey = '';
let previousMouthBand;
let motionQuery = null;

function draw(time) {
  animationFrame = requestAnimationFrame(draw);
  if (lastDrawAt && time - lastDrawAt < 1000 / 30) return;
  lastDrawAt = time;

  const context = canvas.value?.getContext('2d', { alpha: false });
  if (!context) return;
  const turnState = String(props.turnState || 'IDLE').toUpperCase();
  const emotion = props.sleeping ? 'SLEEPING'
    : turnState === 'LISTENING' ? 'CURIOUS' : normalizeEmotion(props.emotion).toUpperCase();
  const speaking = !props.sleeping && turnState === 'SPEAKING';
  const mouthBand = speaking ? resolveEqualizerBand(props.mouthLevel, previousMouthBand) : 0;
  previousMouthBand = speaking ? mouthBand : undefined;
  const elapsed = time - startedAt;
  const blink = !reducedMotion.value && !speaking && !props.sleeping
    && elapsed > 5000 && elapsed % 5200 < 135;
  const frameKey = `${emotion}:${mouthBand}:${blink}`;
  if (frameKey === lastFrameKey) return;
  lastFrameKey = frameKey;
  renderEyeFace(context, { emotion, mouthBand, blink });
}

function updateReducedMotion(event) {
  reducedMotion.value = Boolean(event.matches);
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
    class="eye-face"
    width="160"
    height="100"
    role="img"
    :aria-label="ariaLabel"
  />
</template>

<style scoped>
.eye-face {
  display: block;
  width: min(92vw, calc(92vh * 1.6));
  height: auto;
  aspect-ratio: 8 / 5;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
}
</style>
