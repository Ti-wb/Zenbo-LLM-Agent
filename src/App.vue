<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import PixelFace from './components/PixelFace.vue';
import SettingsOverlay from './components/SettingsOverlay.vue';
import RobotControlsOverlay from './components/RobotControlsOverlay.vue';
import { useIdleExpression } from './composables/useIdleExpression';
import { useRuntimeController } from './composables/useRuntimeController';
import { createListeningCue } from './services/listeningCue';
import { useRuntimeStore } from './stores/runtime';

const runtime = useRuntimeStore();
const {
  isListening,
  isAudioReady = ref(false),
  inputLevel = ref(0),
  motionUpdating = ref(false),
  motionError = ref(''),
  canStartNewSession = ref(false),
  newSessionPending = ref(false),
  newSessionMessage = ref(''),
  savingSettings,
  settingsTestResult,
  testingSettings,
  saveSettings,
  setMotionEnabled,
  startNewSession,
  canAskCamera = ref(false),
  cameraRequestPending = ref(false),
  cameraRequestMessage = ref(''),
  requestCameraView,
  testSettings,
  toggleListening,
} = useRuntimeController();
const displayedEmotion = useIdleExpression(runtime, {
  enabled: computed(() => !isListening.value && !newSessionPending.value),
});
const listeningCue = createListeningCue();
const now = ref(new Date());
const settingsHolding = ref(false);
const errorVisible = ref(false);
const robotControlsOpen = ref(false);
const cameraNotice = ref(false);
const latestCapture = computed(() => runtime.cameraCaptures[runtime.cameraCaptures.length - 1] || null);
let clockTimer;
let settingsHoldTimer;
let cameraNoticeTimer;

watch(() => latestCapture.value?.artifactId, (artifactId, previous) => {
  window.clearTimeout(cameraNoticeTimer);
  cameraNotice.value = Boolean(artifactId && artifactId !== previous);
  if (cameraNotice.value) cameraNoticeTimer = window.setTimeout(() => { cameraNotice.value = false; }, 8000);
});

const canIndicateListening = computed(() =>
  !runtime.sleeping && !runtime.error && !runtime.waitingForPreviousTurn
  && !newSessionPending.value
  && runtime.connectionState === 'READY'
  && ['IDLE', 'LISTENING'].includes(runtime.turnState),
);
const listeningReady = computed(() => canIndicateListening.value && isAudioReady.value);
const microphoneLevel = computed(() => {
  const level = Number(inputLevel.value);
  return listeningReady.value && Number.isFinite(level) ? Math.max(0, Math.min(1, level)) : 0;
});
const listeningStyle = computed(() => ({
  '--listening-frame': listeningReady.value ? 0.3 + microphoneLevel.value * 0.65 : 0,
}));
const statusLabel = computed(() => {
  if (newSessionPending.value && !runtime.sleeping) return '正在建立新對話';
  if (!canIndicateListening.value) return runtime.statusLabel;
  if (listeningReady.value) return '我在聽';
  if (isListening.value || runtime.turnState === 'LISTENING') return '麥克風準備中';
  if (runtime.turnState === 'IDLE' && !runtime.micEnabled && !runtime.turnBusy
      && !runtime.activeTurnId && !runtime.recoveryNotice) return '點一下開始聊天';
  return runtime.statusLabel;
});
const batteryDescription = computed(() => {
  const charge = runtime.battery.charging === true ? '，充電中' : '';
  return runtime.battery.percentage === null ? `電量未知${charge}` : `電量 ${runtime.batteryLabel}${charge}`;
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
  window.clearTimeout(cameraNoticeTimer);
  cancelSettingsHold();
  listeningCue.destroy();
});
</script>

<template>
  <main class="face-shell" :class="{ 'listening-ready': listeningReady }" :style="listeningStyle">
    <header class="top-bar">
      <div class="device-indicators">
        <span class="clock">{{ clock }}</span>
        <span class="battery-readout" :class="{ charging: runtime.battery.charging === true }" role="img" :aria-label="batteryDescription">
          <span class="battery-icon" aria-hidden="true">
            <i :style="{ width: `${runtime.battery.percentage ?? 0}%` }" />
          </span>
          <span aria-hidden="true">{{ runtime.batteryLabel }}</span>
          <span v-if="runtime.battery.charging === true" class="charging-mark" aria-hidden="true">ϟ</span>
        </span>
        <span class="connection-dot" :data-state="runtime.connectionState" aria-hidden="true" />
      </div>
      <button
        class="icon-button"
        type="button"
        :aria-label="isListening ? '暫停聆聽' : '開始聆聽'"
        @click="toggleListening"
      >
        {{ isListening ? '●' : '○' }}
      </button>
      <div class="session-control">
        <button
          class="new-session-button"
          type="button"
          :disabled="!canStartNewSession || !startNewSession"
          :aria-busy="newSessionPending"
          :title="newSessionPending ? '正在建立新對話' : canStartNewSession ? '保留設定，開始一段新對話' : '連線就緒並結束目前回合後可建立新對話'"
          @click="startNewSession?.()"
        >
          {{ newSessionPending ? '建立中…' : '新對話' }}
        </button>
        <p v-if="newSessionMessage" class="session-message" role="status">{{ newSessionMessage }}</p>
      </div>
      <div class="motion-control">
        <button
          class="motion-toggle"
          :class="{ enabled: runtime.motionEnabled === true }"
          type="button"
          :aria-label="motionUpdating ? '正在更新動作設定' : runtime.motionEnabled ? '動作已開啟，點一下關閉' : '動作已關閉，點一下開啟'"
          :aria-pressed="runtime.motionEnabled === true"
          :aria-busy="motionUpdating"
          :disabled="motionUpdating || !setMotionEnabled"
          @click="setMotionEnabled?.(!runtime.motionEnabled)"
        >
          {{ motionUpdating ? '設定中' : runtime.motionEnabled ? '動作 開' : '動作 關' }}
        </button>
        <p v-if="motionError" class="motion-error" role="status">{{ motionError }}</p>
      </div>
      <button
        class="new-session-button robot-controls-button"
        type="button"
        :disabled="!runtime.settings.onboardingComplete"
        @click="robotControlsOpen = true"
      >機器</button>
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
        @click="toggleListening"
      >
        <PixelFace
          :emotion="displayedEmotion"
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
      <button v-if="cameraNotice" type="button" class="camera-notice" @click="robotControlsOpen = true; cameraNotice = false">已拍攝眼前畫面 · 開啟照片</button>
      <button v-if="runtime.sleeping" class="wake-button" type="button" @click="toggleListening">
        喚醒 Zenbo
      </button>
    </section>

    <div v-if="errorVisible && runtime.error && !runtime.settingsOpen && !robotControlsOpen" class="error-overlay" role="alertdialog" aria-modal="true">
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
    <RobotControlsOverlay
      :open="robotControlsOpen"
      :can-ask-camera="canAskCamera"
      :camera-request-pending="cameraRequestPending"
      :camera-request-message="cameraRequestMessage"
      :latest-capture="latestCapture"
      @close="robotControlsOpen = false"
      @ask-camera="requestCameraView?.()"
    />
  </main>
</template>

<style scoped>
.device-indicators {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-right: auto;
  white-space: nowrap;
}

.device-indicators .clock { margin-right: 0; }
.top-bar > .icon-button { flex-shrink: 0; }

.battery-readout {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 66px;
  color: #a8bfc4;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.battery-readout.charging { color: #9edcc4; }
.charging-mark { font-size: 17px; line-height: 1; }

.battery-icon {
  position: relative;
  display: block;
  width: 22px;
  height: 12px;
  padding: 2px;
  border: 2px solid currentColor;
}

.battery-icon::after {
  position: absolute;
  content: '';
  width: 2px;
  height: 6px;
  right: -4px;
  top: 1px;
  background: currentColor;
}

.battery-icon i { display: block; height: 100%; background: currentColor; }

.motion-control,
.session-control {
  position: relative;
  flex-shrink: 0;
}

.motion-toggle,
.new-session-button {
  min-width: 86px;
  height: 48px;
  flex-shrink: 0;
  padding: 0 14px;
  border: 1px solid #34474b;
  border-radius: 24px;
  background: #0b1b22;
  color: #abc2c7;
  font-size: 13px;
  letter-spacing: 0.04em;
  white-space: nowrap;
  cursor: pointer;
  touch-action: manipulation;
}

.motion-toggle.enabled {
  border-color: #5aa88e;
  background: #102922;
  color: #a3e8cf;
}
.robot-controls-button { min-width: 60px; }
.camera-notice { margin-top: 8px; min-height: 36px; padding: 0 14px; border: 1px solid #3b6059; border-radius: 18px; background: #0e2824; color: #b4e9d4; font-size: 12px; cursor: pointer; }

.motion-toggle:focus-visible,
.new-session-button:focus-visible {
  outline: 2px solid #80dcc0;
  outline-offset: 3px;
}

.motion-toggle:disabled,
.new-session-button:disabled {
  opacity: 0.55;
  cursor: default;
}

.motion-error,
.session-message {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  width: 220px;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid #5b5140;
  border-radius: 6px;
  background: #172027;
  color: #efd7a7;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.session-message {
  width: 196px;
  border-color: #34474b;
  color: #b4d4d2;
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

@media (max-width: 480px) {
  .face-shell { --face-top-bar-height: calc(var(--face-safe-top) + 84px); }
  .top-bar { gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
  .device-indicators { width: 100%; }
  .device-indicators .connection-dot { margin-left: auto; }
  .motion-toggle,
  .new-session-button { min-width: 74px; padding: 0 10px; font-size: 12px; }
  .session-message { left: 0; right: auto; }
}

@media (prefers-reduced-motion: reduce) {
  .listening-frame {
    transition: none;
  }
}
</style>
