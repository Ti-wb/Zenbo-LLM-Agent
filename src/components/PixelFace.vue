<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

const props = defineProps({
  emotion: { type: String, default: 'NEUTRAL' },
  mouthLevel: { type: Number, default: 0 },
  sleeping: { type: Boolean, default: false },
  turnState: { type: String, default: 'IDLE' },
});

const canvas = ref(null);
const reducedMotion = ref(false);
const emotionKey = computed(() => String(props.emotion || 'NEUTRAL').toLowerCase());
const isThinking = computed(() =>
  ['UPLOADING', 'TRANSCRIBING', 'THINKING', 'AWAITING_TOOL', 'SYNTHESIZING'].includes(
    props.turnState,
  ),
);
const palette = computed(() => ({
  happy: '#68ffd1',
  curious: '#6ce8ff',
  concerned: '#ff7f91',
  excited: '#ffdf6c',
}[emotionKey.value] || '#76f4ff'));

const motion = {
  gazeX: 0,
  gazeY: 0,
  eyeOpen: 1,
  eyeCurve: 0,
  mouthOpen: 0,
  mouthCurve: 0,
};

let animationFrame = 0;
let startedAt = 0;
let lastDrawAt = 0;
const FRAME_INTERVAL = 1000 / 30;

function approach(current, target, amount = 0.22) {
  return current + (target - current) * amount;
}

function pixelRect(context, x, y, width, height, color) {
  context.fillStyle = color;
  context.fillRect(Math.round(x), Math.round(y), Math.round(width), Math.round(height));
}

function targetMotion(elapsed) {
  const emotionTargets = {
    neutral: { gazeX: 0, gazeY: 0, eyeCurve: 0, mouthCurve: 0 },
    happy: { gazeX: 0, gazeY: -0.1, eyeCurve: 0.8, mouthCurve: 1 },
    curious: { gazeX: 0.55, gazeY: -0.15, eyeCurve: 0.1, mouthCurve: 0.15 },
    concerned: { gazeX: -0.3, gazeY: 0.2, eyeCurve: -0.75, mouthCurve: -1 },
    excited: { gazeX: 0, gazeY: -0.2, eyeCurve: 0.25, mouthCurve: 0.75 },
  };
  const target = emotionTargets[emotionKey.value] || emotionTargets.neutral;
  const thinkingDrift = isThinking.value && !reducedMotion.value ? Math.sin(elapsed / 850) * 0.2 : 0;
  const blinkPhase = elapsed % 5200;
  const blinking = !props.sleeping && blinkPhase > 5050;
  const level = Math.max(0, Math.min(1, props.mouthLevel));

  return {
    gazeX: props.sleeping ? 0 : target.gazeX + thinkingDrift,
    gazeY: props.sleeping ? 0 : target.gazeY,
    eyeOpen: props.sleeping || blinking ? 0.05 : emotionKey.value === 'excited' ? 1 : 0.78,
    eyeCurve: props.sleeping ? 1 : target.eyeCurve,
    mouthOpen: props.sleeping ? 0 : Math.max(level, emotionKey.value === 'excited' ? 0.42 : 0),
    mouthCurve: props.sleeping ? 0 : target.mouthCurve,
  };
}

function updateMotion(target) {
  motion.gazeX = approach(motion.gazeX, target.gazeX, 0.18);
  motion.gazeY = approach(motion.gazeY, target.gazeY, 0.18);
  motion.eyeOpen = approach(motion.eyeOpen, target.eyeOpen, 0.36);
  motion.eyeCurve = approach(motion.eyeCurve, target.eyeCurve, 0.2);
  motion.mouthOpen = approach(motion.mouthOpen, target.mouthOpen, 0.34);
  motion.mouthCurve = approach(motion.mouthCurve, target.mouthCurve, 0.2);
}

function drawEye(context, centerX, centerY, color) {
  const width = 25;
  const height = Math.max(2, Math.round(3 + motion.eyeOpen * 19));
  const curve = Math.round(motion.eyeCurve * 4);
  const pupilX = Math.round(motion.gazeX * 4);
  const pupilY = Math.round(motion.gazeY * 3);

  pixelRect(context, centerX - width / 2, centerY - height / 2 + curve, 6, height - 2, color);
  pixelRect(context, centerX - width / 2 + 6, centerY - height / 2, width - 12, height, color);
  pixelRect(context, centerX + width / 2 - 6, centerY - height / 2 + curve, 6, height - 2, color);

  if (height > 8) {
    pixelRect(context, centerX - 3 + pupilX, centerY - 3 + pupilY, 7, 7, '#07141d');
    pixelRect(context, centerX - 2 + pupilX, centerY - 2 + pupilY, 2, 2, '#efffff');
  }
}

function drawMouth(context, centerX, centerY, color) {
  const width = 29;
  const opening = Math.round(motion.mouthOpen * 14);
  const curve = Math.round(motion.mouthCurve * 5);

  if (opening > 2) {
    pixelRect(context, centerX - width / 2, centerY - opening / 2, width, opening + 3, color);
    pixelRect(context, centerX - width / 2 + 5, centerY - opening / 2 + 4, width - 10, Math.max(2, opening - 5), '#07141d');
    return;
  }

  pixelRect(context, centerX - width / 2, centerY - curve, 6, 3, color);
  pixelRect(context, centerX - width / 2 + 6, centerY, width - 12, 3, color);
  pixelRect(context, centerX + width / 2 - 6, centerY - curve, 6, 3, color);
}

function draw(time) {
  animationFrame = requestAnimationFrame(draw);
  if (lastDrawAt && time - lastDrawAt < FRAME_INTERVAL) return;
  lastDrawAt = time;

  const context = canvas.value?.getContext('2d');
  if (!context) return;
  const elapsed = time - startedAt;
  updateMotion(targetMotion(elapsed));

  context.imageSmoothingEnabled = false;
  context.clearRect(0, 0, 160, 100);
  context.fillStyle = '#061018';
  context.fillRect(0, 0, 160, 100);

  drawEye(context, 48, 43, palette.value);
  drawEye(context, 112, 43, palette.value);
  drawMouth(context, 80, 72, palette.value);
}

onMounted(() => {
  reducedMotion.value = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  startedAt = performance.now();
  animationFrame = requestAnimationFrame(draw);
});

onBeforeUnmount(() => cancelAnimationFrame(animationFrame));
</script>

<template>
  <canvas
    ref="canvas"
    class="pixel-face"
    width="160"
    height="100"
    role="img"
    :aria-label="`Zenbo 表情：${emotion}；視線會依目前狀態移動`"
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
  filter: drop-shadow(0 0 28px rgb(69 227 255 / 18%));
}
</style>
