<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import PixelFace from './components/PixelFace.vue';
import SettingsOverlay from './components/SettingsOverlay.vue';
import { useRuntimeController } from './composables/useRuntimeController';
import { createListeningCue } from './services/listeningCue';
import { useRuntimeStore } from './stores/runtime';

const runtime = useRuntimeStore();
const {
  isListening,
  isAudioReady = ref(false),
  inputLevel = ref(0),
  savingSettings,
  settingsTestResult,
  testingSettings,
  saveSettings,
  testSettings,
  toggleListening,
  wakeUp,
} = useRuntimeController();
const listeningCue = createListeningCue();
const now = ref(new Date());
const settingsHolding = ref(false);
const errorVisible = ref(false);
let clockTimer;
let settingsHoldTimer;

const canIndicateListening = computed(() =>
  !runtime.sleeping && !runtime.error && !runtime.waitingForPreviousTurn
  && runtime.connectionState === 'READY'
  && ['IDLE', 'LISTENING'].includes(runtime.turnState),
);
const listeningReady = computed(() => canIndicateListening.value && isAudioReady.value);
const microphoneLevel = computed(() => {
  const level = Number(inputLevel.value);
  return listeningReady.value && Number.isFinite(level) ? Math.max(0, Math.min(1, level)) : 0;
});
const listeningStyle = computed(() => ({
  '--listening-glow': listeningReady.value ? 0.12 + microphoneLevel.value * 0.5 : 0,
  '--listening-frame': listeningReady.value ? 0.3 + microphoneLevel.value * 0.65 : 0,
}));
const statusLabel = computed(() => {
  if (!canIndicateListening.value) return runtime.statusLabel;
  if (listeningReady.value) return '我在聽';
  if (isListening.value || runtime.turnState === 'LISTENING') return '麥克風準備中';
  return runtime.statusLabel;
});

watch([isAudioReady, canIndicateListening], ([ready, eligible], [previousReady] = []) => {
  if (!ready || !eligible) listeningCue.cancel();
  else if (!previousReady) void listeningCue.play();
}, { immediate: true, flush: 'sync' });

const clock = computed(() =>
  new Intl.DateTimeFormat(runtime.settings.language || 'zh-TW', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(now.value),
);

watch(
  () => runtime.error,
  (error) => {
    if (error) errorVisible.value = true;
  },
);

onMounted(() => {
  clockTimer = window.setInterval(() => {
    now.value = new Date();
    runtime.clearExpiredEmotion();
  }, 1000);
});

function cancelSettingsHold() {
  window.clearTimeout(settingsHoldTimer);
  settingsHoldTimer = undefined;
  settingsHolding.value = false;
}

function beginSettingsHold(event) {
  if (event.type === 'pointerdown' && (event.button !== 0 || event.isPrimary === false)) return;
  if (event.repeat || settingsHoldTimer) return;
  settingsHolding.value = true;
  settingsHoldTimer = window.setTimeout(() => {
    settingsHoldTimer = undefined;
    settingsHolding.value = false;
    runtime.settingsOpen = true;
  }, 2000);
}

function handleSettingsKeyDown(event) {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  event.preventDefault();
  beginSettingsHold(event);
}

function handleSettingsKeyUp(event) {
  if (event.key === 'Enter' || event.key === ' ') cancelSettingsHold();
}

onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  cancelSettingsHold();
  listeningCue.destroy();
});
</script>

<template>
  <main class="face-shell" :class="{ 'listening-ready': listeningReady }" :style="listeningStyle">
    <div class="listening-ambient" aria-hidden="true" />
    <header class="top-bar">
      <span class="clock">{{ clock }}</span>
      <span class="connection-dot" :data-state="runtime.connectionState" aria-hidden="true" />
      <button
        class="icon-button"
        type="button"
        :aria-label="isListening ? '暫停聆聽' : '開始聆聽'"
        @click="toggleListening"
      >
        {{ isListening ? '●' : '○' }}
      </button>
      <button
        class="icon-button settings-hold"
        :class="{ holding: settingsHolding }"
        type="button"
        aria-label="長按兩秒開啟設定"
        title="長按兩秒開啟設定"
        @click.prevent
        @contextmenu.prevent
        @pointerdown="beginSettingsHold"
        @pointerup="cancelSettingsHold"
        @pointercancel="cancelSettingsHold"
        @pointerleave="cancelSettingsHold"
        @keydown="handleSettingsKeyDown"
        @keyup="handleSettingsKeyUp"
        @blur="cancelSettingsHold"
      >
        <span aria-hidden="true">⚙</span>
        <span class="hold-progress" aria-hidden="true" />
      </button>
    </header>

    <section class="face-stage" aria-live="polite">
      <button
        class="face-control"
        type="button"
        :aria-label="runtime.sleeping ? '點一下喚醒 Zenbo' : isListening ? '點一下暫停聆聽' : '點一下開始聆聽'"
        :aria-pressed="isListening"
        @click="runtime.sleeping ? wakeUp() : toggleListening()"
      >
        <PixelFace
          :emotion="runtime.effectiveEmotion"
          :mouth-level="runtime.mouthLevel"
          :sleeping="runtime.sleeping"
          :turn-state="runtime.turnState"
        />
        <span class="listening-frame" aria-hidden="true">
          <i class="listening-corner top-left" />
          <i class="listening-corner top-right" />
          <i class="listening-corner bottom-left" />
          <i class="listening-corner bottom-right" />
        </span>
      </button>
      <p class="status-label" :class="{ 'listening-label': listeningReady }">{{ statusLabel }}</p>
      <p v-if="runtime.transcript || runtime.assistantText" class="caption">
        {{ runtime.assistantText || runtime.transcript }}
      </p>
      <button v-if="runtime.sleeping" class="wake-button" type="button" @click="wakeUp">
        喚醒 Zenbo
      </button>
    </section>

    <div v-if="errorVisible && runtime.error && !runtime.settingsOpen" class="error-overlay" role="alertdialog" aria-modal="true">
      <section class="error-card" aria-labelledby="runtime-error-title">
        <p class="error-kicker">LOCAL RUNTIME</p>
        <h2 id="runtime-error-title">連線需要處理</h2>
        <p>{{ runtime.error }}</p>
        <div class="error-actions">
          <button type="button" @click="errorVisible = false">稍後</button>
          <button type="button" @click="runtime.settingsOpen = true">開啟設定</button>
        </div>
      </section>
    </div>

    <SettingsOverlay
      :open="runtime.settingsOpen"
      :settings="runtime.settings"
      :connection-state="runtime.connectionState"
      :error="runtime.error"
      :saving="savingSettings"
      :testing="testingSettings"
      :test-result="settingsTestResult"
      @close="runtime.settingsOpen = false"
      @save="saveSettings"
      @test="testSettings"
    />
  </main>
</template>

<style scoped>
.listening-ambient {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(ellipse at 50% 48%, transparent 22%, #163e35 68%, #0c2a27 100%);
  opacity: var(--listening-glow, 0);
  transition: opacity 80ms linear;
}

.face-stage {
  position: relative;
}

.face-control {
  position: relative;
}

.listening-frame {
  position: absolute;
  inset: 0;
  pointer-events: none;
  color: #80dcc0;
  opacity: var(--listening-frame, 0);
  transition: opacity 80ms linear;
}

.listening-corner {
  position: absolute;
  width: 24px;
  height: 20px;
  border-style: solid;
  border-color: currentColor;
  border-width: 0;
}

.top-left { top: 4%; left: 3%; border-top-width: 4px; border-left-width: 4px; }
.top-right { top: 4%; right: 3%; border-top-width: 4px; border-right-width: 4px; }
.bottom-left { bottom: 4%; left: 3%; border-bottom-width: 4px; border-left-width: 4px; }
.bottom-right { bottom: 4%; right: 3%; border-bottom-width: 4px; border-right-width: 4px; }

.listening-label {
  color: #9bd8c6;
}

@media (prefers-reduced-motion: reduce) {
  .listening-ambient,
  .listening-frame {
    transition: none;
  }
}
</style>
