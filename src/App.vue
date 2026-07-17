<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import PixelFace from './components/PixelFace.vue';
import SettingsOverlay from './components/SettingsOverlay.vue';
import { useRuntimeController } from './composables/useRuntimeController';
import { useRuntimeStore } from './stores/runtime';

const runtime = useRuntimeStore();
const {
  isListening,
  savingSettings,
  settingsTestResult,
  testingSettings,
  saveSettings,
  testSettings,
  toggleListening,
  wakeUp,
} = useRuntimeController();
const now = ref(new Date());
const settingsHolding = ref(false);
const errorVisible = ref(false);
let clockTimer;
let settingsHoldTimer;

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
});
</script>

<template>
  <main class="face-shell">
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
      </button>
      <p class="status-label">{{ runtime.statusLabel }}</p>
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
