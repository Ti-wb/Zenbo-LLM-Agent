<script setup>
import { computed, nextTick, onBeforeUnmount, ref, toRef, watch } from 'vue';
import { useRobotControls } from '../composables/useRobotControls';
import ConversationCameraPreview from './ConversationCameraPreview.vue';
import LanControlQr from './LanControlQr.vue';

const props = defineProps({
  open: Boolean,
  canAskCamera: Boolean,
  cameraRequestPending: Boolean,
  cameraRequestMessage: { type: String, default: '' },
  latestCapture: { type: Object, default: null },
  faceVariant: { type: String, default: 'eyes' },
});
const emit = defineEmits(['close', 'ask-camera', 'face-variant-change']);
const { status, error, pending, online, canMove, canFollow, previewEnabled, imageUrl, imageLabel,
  imageError, action, settings, remote, capture } = useRobotControls(toRef(props, 'open'));
const dialog = ref(null);
const closeButton = ref(null);
let previousFocus;
const attentionLabel = computed(() => {
  if (!status.value?.attentionEnabled) return '已關閉';
  if (status.value?.motionBlockedReason) return '接線時暫停看人，拔除後可恢復';
  const state = String(status.value?.attentionState || '').toLowerCase();
  if (state.includes('unavailable') || state.includes('unsupported')) return '目前無法取得說話方向';
  if (state === 'looking_at_speaker') return '看向聲音來源';
  if (state === 'tracking_face') return '目前追蹤人臉';
  if (state === 'paused_for_camera') return '使用相機時暫停';
  if (state === 'waiting_for_direction') return '等待聲音方向或人臉';
  return '已啟用，等待互動';
});
const motionHint = computed(() => status.value?.motionBlockedReason
  ? (status.value.motionBlockedReason === 'POWER_CONNECTED' ? 'Zenbo 接著充電線，拔除後才可移動與跟隨。' : 'Zenbo 接著 USB，拔除後才可移動與跟隨。')
  : status.value?.motionEnabled ? '每次最多前後移動 15 公分，或轉向 15°。' : '請先在主畫面開啟「動作」。');
const cameraReleasing = computed(() => status.value?.cameraState === 'releasing');
const cameraReleaseFailed = computed(() => status.value?.cameraError === 'CAMERA_RELEASE_FAILED');
const cameraBlocksFollowing = computed(() => status.value?.cameraEnabled || cameraReleasing.value || cameraReleaseFailed.value);
const cameraLabel = computed(() => {
  if (cameraReleasing.value) return '相機正在關閉，請稍候';
  if (cameraReleaseFailed.value) return '相機尚未完成關閉，請重試';
  if (!status.value?.cameraEnabled) return '相機已關閉';
  const state = String(status.value?.cameraState || '').toLowerCase();
  const permission = String(status.value?.cameraPermission || '').toLowerCase();
  if (permission.includes('denied') || permission === 'false') return '需要允許相機權限';
  if (state.includes('busy')) return '相機使用中，請先停止跟隨';
  if (state.includes('error') || state.includes('unavailable')) return '相機暫時無法使用';
  return '相機已啟用';
});
const pairingExpiry = computed(() => {
  const value = status.value?.remote?.pairingExpiresAt;
  if (!value) return '';
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? '' : time.toLocaleTimeString('zh-TW', { hour: '2-digit', minute: '2-digit' });
});

function releaseFocus() {
  document.removeEventListener('keydown', onKeydown, true);
  document.removeEventListener('focusin', onFocusIn, true);
  if (previousFocus?.isConnected) previousFocus.focus();
  previousFocus = null;
}

function onFocusIn(event) {
  const scope = dialog.value?.querySelector('#lan-qr-panel') || dialog.value;
  if (scope && !scope.contains(event.target)) (scope.querySelector('button') || closeButton.value)?.focus();
}

function onKeydown(event) {
  if (!dialog.value || event.isComposing) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    const qrClose = dialog.value.querySelector('[data-qr-close]');
    if (qrClose) qrClose.click();
    else emit('close');
  } else if (event.key === 'Tab') {
    const scope = dialog.value.querySelector('#lan-qr-panel') || dialog.value;
    const elements = [...scope.querySelectorAll('button,input,select,a[href]')]
      .filter((element) => !element.disabled && element.tabIndex >= 0 && element.getClientRects().length);
    const index = elements.indexOf(document.activeElement);
    if (index < 0 || (event.shiftKey ? index === 0 : index === elements.length - 1)) {
      event.preventDefault();
      (elements[event.shiftKey ? elements.length - 1 : 0] || dialog.value).focus();
    }
  }
}

watch(() => props.open, async (visible, _, onCleanup) => {
  if (!visible) return releaseFocus();
  previousFocus = document.activeElement;
  let cancelled = false;
  onCleanup(() => { cancelled = true; });
  await nextTick();
  if (cancelled) return;
  closeButton.value?.focus();
  document.addEventListener('keydown', onKeydown, true);
  document.addEventListener('focusin', onFocusIn, true);
}, { immediate: true, flush: 'post' });
onBeforeUnmount(releaseFocus);
</script>

<template>
  <div v-if="open" ref="dialog" class="robot-overlay" role="dialog" aria-modal="true" aria-labelledby="robot-title" tabindex="-1">
    <section class="robot-card">
      <header class="panel-heading">
        <div><p class="eyebrow">ZENBO · DEVICE</p><h1 id="robot-title">機器控制</h1></div>
        <span class="connection" :class="{ online }">{{ online ? '已連接機器' : '等待機器連線' }}</span>
        <button ref="closeButton" type="button" class="close-button" aria-label="關閉機器控制" @click="emit('close')">×</button>
      </header>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <section class="face-style-section" aria-labelledby="face-style-title">
        <h2 id="face-style-title">臉部風格</h2>
        <div class="face-style-options" role="group" aria-labelledby="face-style-title">
          <button type="button" :aria-pressed="faceVariant === 'eyes'" @click="emit('face-variant-change', 'eyes')">雙眼像素臉</button>
          <button type="button" :aria-pressed="faceVariant === 'classic'" @click="emit('face-variant-change', 'classic')">原版 PixelFace</button>
        </div>
        <p class="hint">立即套用並保存在本機。</p>
      </section>
      <div class="controls-layout">
        <section class="control-section movement-section" aria-labelledby="movement-title">
          <div class="section-heading"><h2 id="movement-title">移動與跟隨</h2><span>{{ status?.motionEnabled ? '動作 開' : '動作 關' }}</span></div>
          <p class="hint">{{ motionHint }}</p>
          <div class="direction-pad">
            <button class="forward" type="button" :disabled="!canMove" @click="action('forward')"><span aria-hidden="true">↑</span>前進</button>
            <button class="left" type="button" :disabled="!canMove" @click="action('left')"><span aria-hidden="true">↶</span>左轉</button>
            <button class="stop" type="button" @click="action('stop')"><span aria-hidden="true">■</span>停止</button>
            <button class="right" type="button" :disabled="!canMove" @click="action('right')"><span aria-hidden="true">↷</span>右轉</button>
            <button class="backward" type="button" :disabled="!canMove" @click="action('backward')"><span aria-hidden="true">↓</span>後退</button>
          </div>
          <button class="wide-button follow-button" type="button" :aria-pressed="status?.following === true" :disabled="!status?.following && !canFollow" @click="action(status?.following ? 'stop' : 'follow')">
            {{ status?.following ? '停止跟隨' : '開始跟隨我' }}
          </button>
          <p v-if="cameraBlocksFollowing && !status?.following" class="hint">{{ cameraReleasing ? '相機正在關閉，完成後即可跟隨。' : cameraReleaseFailed ? '相機尚未完成關閉，請在右側重試；跟隨暫停。' : '跟隨需先關閉右側的相機；方向按鈕仍可使用。' }}</p>
          <label class="toggle-row">
            <span><strong>互動時看人</strong><small>{{ attentionLabel }}</small></span>
            <input type="checkbox" :checked="status?.attentionEnabled === true" :disabled="!online || Boolean(pending)" @change="settings({ attentionEnabled: $event.target.checked })" />
          </label>
          <p class="hint">優先使用聲源方向；沒有方向時追蹤人臉，多人時不保證鎖定說話者。回答使用 Hermes 語音。</p>
        </section>

        <section class="control-section camera-section" aria-labelledby="camera-title">
          <div class="section-heading"><h2 id="camera-title">Zenbo 眼前畫面</h2><label class="camera-switch"><span>相機</span><input type="checkbox" :checked="status?.cameraEnabled === true" :disabled="!online || Boolean(pending)" @change="settings({ cameraEnabled: $event.target.checked })" /></label></div>
          <button v-if="cameraReleaseFailed" class="wide-button" type="button" :disabled="!online || Boolean(pending)" @click="settings({ cameraEnabled: false })">重試關閉相機</button>
          <div class="camera-view">
            <img v-if="imageUrl" :src="imageUrl" alt="Zenbo 相機畫面" />
            <div v-else class="camera-placeholder"><span aria-hidden="true">◉</span><p>{{ imageError || cameraLabel }}</p></div>
          </div>
          <p class="frame-caption" role="status">{{ imageLabel || '即時預覽約每秒 2 張；關閉後不保留畫面' }}</p>
          <div class="camera-actions">
            <button type="button" :disabled="!online || !status?.cameraEnabled || Boolean(pending)" :aria-pressed="previewEnabled" @click="previewEnabled = !previewEnabled">{{ previewEnabled ? '停止預覽' : '即時預覽' }}</button>
            <button type="button" :disabled="!online || !status?.cameraEnabled || Boolean(pending)" @click="capture">{{ pending === 'capture' ? '拍照中…' : '本機拍照' }}</button>
          </div>
          <button class="wide-button ask-button" type="button" :disabled="!canAskCamera || !status?.cameraEnabled || cameraRequestPending" @click="emit('ask-camera')">{{ cameraRequestPending ? '正在請 Zenbo 看看…' : '拍照給 Hermes 看' }}</button>
          <p class="hint" role="status">{{ cameraRequestMessage || '在目前對話拍照並請 Hermes 描述畫面。本機拍照只在此預覽。' }}</p>
          <ConversationCameraPreview v-if="latestCapture && status?.cameraEnabled" :capture="latestCapture" />
        </section>
      </div>

      <section class="remote-section" aria-labelledby="remote-title">
        <div class="section-heading"><h2 id="remote-title">同一個 Wi-Fi 遙控</h2><span :class="{ connected: status?.remote?.connected }">{{ status?.remote?.connected ? '遙控已連線' : status?.remote?.enabled ? '等待配對' : '已關閉' }}</span></div>
        <div v-if="status?.remote?.enabled" class="pairing-details">
          <div class="remote-address"><span>也可手動開啟</span><a v-for="url in status.remote.urls || []" :key="url" :href="url" target="_blank" rel="noopener noreferrer">{{ url }}</a><p v-if="!status.remote.urls?.length">尚未取得區網位址，請確認 Wi-Fi。</p></div>
          <div v-if="status.remote.pairingCode" class="pairing-code"><span>手動配對備用碼</span><strong>{{ status.remote.pairingCode }}</strong><small v-if="pairingExpiry">有效至 {{ pairingExpiry }}</small></div>
        </div>
        <LanControlQr :active="open" :enabled="status?.remote?.enabled === true" :connected="status?.remote?.connected === true" :pairing-code="status?.remote?.pairingCode || ''" :pairing-expires-at="status?.remote?.pairingExpiresAt || ''" :urls="status?.remote?.urls || []" @stop="action('stop')" />
        <p v-if="status?.remote?.enabled" class="hint">重新產生配對 QR 會停止動作，並中斷目前的遙控連線。</p>
        <div class="remote-actions">
          <button type="button" :disabled="!online || Boolean(pending)" @click="remote(true)">{{ pending === 'remote' ? '準備配對 QR…' : status?.remote?.enabled ? '重新產生配對 QR' : '啟用並顯示配對 QR' }}</button>
          <button v-if="status?.remote?.enabled" type="button" :disabled="pending === 'remote-disable'" @click="remote(false)">{{ pending === 'remote-disable' ? '關閉中…' : '關閉區網遙控' }}</button>
        </div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.robot-overlay { position: fixed; inset: 0; z-index: 11; overflow: auto; padding: 24px; background: rgb(2 10 16 / 94%); }
.robot-card { width: min(900px, 100%); margin: 0 auto; padding: 24px; border: 1px solid #2c444c; border-radius: 20px; background: #0b1c25; }
.panel-heading, .section-heading { display: flex; align-items: center; gap: 16px; }
.panel-heading { margin-bottom: 22px; }
.eyebrow { margin: 0 0 5px; font-size: 10px; letter-spacing: .16em; color: #83bdb7; }
h1, h2, p { margin-top: 0; }
h1 { margin-bottom: 0; font-size: 25px; }
h2 { margin-bottom: 0; font-size: 16px; }
.connection { margin-left: auto; color: #a9bbc1; font-size: 12px; }
.connection.online, .connected { color: #9edcc4; }
button { min-height: 44px; border: 1px solid #34505a; border-radius: 10px; background: #132c36; color: #e6f9f6; cursor: pointer; touch-action: manipulation; }
button:disabled { opacity: .42; cursor: default; }
button:focus-visible, input:focus-visible, a:focus-visible { outline: 2px solid #93dfca; outline-offset: 3px; }
.close-button { flex-shrink: 0; width: 44px; font-size: 26px; background: transparent; }
.face-style-section { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 14px; margin-bottom: 22px; padding: 12px 14px; border: 1px solid #29414a; border-radius: 10px; background: #08161e; }
.face-style-section h2 { margin: 0; font-size: 13px; }
.face-style-options { display: flex; flex-wrap: wrap; gap: 8px; }
.face-style-options button { padding: 0 14px; font-size: 13px; }
.face-style-options button[aria-pressed="true"] { border-color: #94d8bf; background: #254236; color: #e5faed; }
.face-style-section .hint { margin: 0 0 0 auto; }
.controls-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
.section-heading { justify-content: space-between; margin-bottom: 12px; }
.section-heading > span { font-size: 12px; color: #a7c0c5; }
.hint { margin: 9px 0 0; color: #9bb5be; font-size: 12px; line-height: 1.6; }
.direction-pad { display: grid; grid-template: repeat(3, 62px) / repeat(3, 1fr); gap: 7px; margin: 17px auto; max-width: 295px; }
.direction-pad button { display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 2px; font-size: 12px; }
.direction-pad button span { font-size: 22px; line-height: 1.1; }
.forward { grid-area: 1 / 2; }.left { grid-area: 2 / 1; }.stop { grid-area: 2 / 2; }.right { grid-area: 2 / 3; }.backward { grid-area: 3 / 2; }
.direction-pad .stop { border-color: #bb7978; background: #522d32; color: #fff0eb; }
.wide-button { width: 100%; padding: 9px 12px; }
.follow-button[aria-pressed="true"] { background: #254236; border-color: #63977f; }
.toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-top: 18px; }
.toggle-row strong { font-size: 13px; font-weight: 500; }.toggle-row small { display: block; margin-top: 4px; color: #91b7be; font-size: 11px; }
input[type="checkbox"] { width: 22px; height: 22px; accent-color: #94d8bf; flex-shrink: 0; }
.camera-switch { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #b9d4d6; }
.camera-view { display: grid; place-items: center; aspect-ratio: 4 / 3; background: #041016; border: 1px solid #28414b; border-radius: 12px; overflow: hidden; }
.camera-view img { width: 100%; height: 100%; object-fit: contain; }
.camera-placeholder { text-align: center; color: #9db6bd; padding: 20px; }.camera-placeholder > span { font-size: 32px; color: #4b7c84; }.camera-placeholder p { margin: 12px 0 0; font-size: 13px; }
.frame-caption { min-height: 16px; margin: 8px 0 12px; color: #8fb5be; font-size: 11px; }
.camera-actions { display: flex; gap: 8px; }.camera-actions button { flex: 1; font-size: 13px; }
.ask-button { margin-top: 9px; background: #94d8bf; color: #082419; border-color: transparent; font-weight: 600; font-size: 13px; }
.remote-section { margin-top: 24px; padding-top: 20px; border-top: 1px solid #29414a; }
.pairing-details { display: flex; align-items: flex-start; gap: 24px; padding: 15px; border-radius: 10px; background: #08161e; margin-bottom: 14px; }
.remote-address { flex: 1; min-width: 0; }.remote-address > span, .pairing-code > span { color: #94b4bd; font-size: 11px; }.remote-address a { display: block; margin-top: 8px; font-size: 14px; color: #b0e8d7; overflow-wrap: anywhere; }.remote-address p { font-size: 12px; margin: 8px 0 0; }
.pairing-code strong { display: block; color: #e5faed; font-size: 25px; letter-spacing: .14em; margin-top: 3px; font-variant-numeric: tabular-nums; }.pairing-code small { color: #95acb7; font-size: 10px; }
.remote-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }.remote-actions button { padding: 0 18px; font-size: 13px; }

.notice { padding: 12px; border-radius: 8px; font-size: 13px; line-height: 1.5; }.error { color: #ffd2c4; background: #3c2426; }
@media (max-width: 620px) { .robot-overlay { padding: 12px; }.robot-card { padding: 18px; }.controls-layout { grid-template-columns: 1fr; gap: 26px; }.connection { font-size: 10px; }.panel-heading { gap: 9px; }.pairing-details { flex-direction: column; gap: 15px; }.camera-view { max-height: 280px; }}
</style>
