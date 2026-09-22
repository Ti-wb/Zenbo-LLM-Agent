<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { createLanControlQr, isLanControlUrl } from '../services/lanControlQr';

const props = defineProps({
  active: Boolean,
  enabled: Boolean,
  connected: Boolean,
  pairingCode: { type: String, default: '' },
  pairingExpiresAt: { type: String, default: '' },
  urls: { type: Array, default: () => [] },
});
const emit = defineEmits(['stop']);
const expanded = ref(false);
const selectedUrl = ref('');
const closeButton = ref(null);
const toggleButton = ref(null);
const now = ref(Date.now());
let clock;
const urls = computed(() => [...new Set(props.urls.filter(isLanControlUrl))]);
const remaining = computed(() => Math.max(0, Math.ceil((Date.parse(props.pairingExpiresAt) - now.value) / 1000)) || 0);
const available = computed(() => props.active && props.enabled && !props.connected && urls.value.length > 0
  && /^[0-9]{8}$/.test(props.pairingCode) && remaining.value > 0);
const qr = computed(() => expanded.value && available.value && urls.value.includes(selectedUrl.value)
  ? createLanControlQr(selectedUrl.value, props.pairingCode) : null);
const hint = computed(() => props.connected ? '手機已配對。要換另一支手機，請重新產生配對 QR。'
  : !urls.value.length ? '尚未取得 Wi-Fi 區網位址，請確認 Zenbo 的 Wi-Fi。'
    : !available.value ? '配對 QR 已過期或已使用，請重新產生配對 QR。'
      : '手機連上同一個 Wi-Fi，掃描即可配對，不必輸入配對碼。');

// Compare values rather than each Native poll's new object. Only a new authority
// or address opens the QR; identical polls preserve the user's collapsed state.
watch(() => JSON.stringify([props.active, props.enabled, props.connected, urls.value, props.pairingCode, remaining.value > 0]), () => {
  now.value = Date.now();
  selectedUrl.value = urls.value[0] || '';
  expanded.value = available.value;
}, { immediate: true });
watch(() => props.active && props.enabled, (active) => {
  clearInterval(clock);
  if (active) clock = setInterval(() => { now.value = Date.now(); }, 1000);
}, { immediate: true });
watch(() => Boolean(qr.value), async (visible) => {
  await nextTick();
  if (visible && qr.value) closeButton.value?.focus?.();
  else if (available.value) toggleButton.value?.focus?.();
});
onBeforeUnmount(() => clearInterval(clock));
</script>

<template>
  <div v-if="active && enabled" class="lan-qr">
    <button ref="toggleButton" class="qr-toggle" type="button" :disabled="!available" :aria-expanded="Boolean(qr)" aria-controls="lan-qr-panel" @click="expanded = !expanded">{{ qr ? '收起配對 QR' : '顯示配對 QR' }}</button>
    <p class="qr-hint" role="status">{{ hint }}</p>
    <div v-if="qr" class="qr-scrim">
      <section id="lan-qr-panel" class="qr-panel" aria-label="掃碼直接配對">
        <svg class="qr-image" :viewBox="`0 0 ${qr.size} ${qr.size}`" width="256" height="256" role="img" aria-label="掃碼直接配對 Zenbo 相機與遙控網頁" shape-rendering="crispEdges">
          <rect width="100%" height="100%" fill="#fff" />
          <path :d="qr.path" fill="#000" />
        </svg>
        <div class="qr-instructions">
          <h3>掃描即可配對</h3>
          <p>手機和 Zenbo 連上同一個 Wi-Fi，用手機相機掃描，不必再輸入配對碼。</p>
          <label v-if="urls.length > 1" class="qr-address-choice"><span>選擇區網位址</span><select v-model="selectedUrl"><option v-for="url in urls" :key="url" :value="url">{{ url }}</option></select></label>
          <p class="qr-url">{{ selectedUrl }}</p>
          <p class="qr-expiry" role="status">一次有效 · 剩餘 {{ remaining }} 秒</p>
          <div class="qr-actions">
            <button class="qr-stop" type="button" @click="emit('stop')">■ 停止移動</button>
            <button ref="closeButton" data-qr-close type="button" @click="expanded = false">收起配對 QR</button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.lan-qr { margin-bottom: 14px; }
button { min-height: 44px; padding: 9px 16px; border: 1px solid #34505a; border-radius: 10px; background: #132c36; color: #e6f9f6; cursor: pointer; touch-action: manipulation; font-size: 13px; }
button:disabled { opacity: .42; cursor: default; }
button:focus-visible, select:focus-visible { outline: 2px solid #93dfca; outline-offset: 3px; }
.qr-scrim { position: fixed; inset: 0; z-index: 1; display: flex; align-items: center; justify-content: center; padding: 16px; background: #020a10ed; }
.qr-panel { display: flex; align-items: center; gap: 24px; width: 740px; max-width: 100%; max-height: 100%; overflow: auto; padding: 20px; border: 1px solid #34505a; border-radius: 12px; background: #08161e; box-sizing: border-box; }
.qr-image { display: block; width: 256px; height: 256px; max-width: 100%; flex-shrink: 0; background: #fff; }
.qr-instructions { flex: 1; min-width: 0; }
h3 { margin: 0 0 10px; color: #e6f9f6; font-size: 20px; }
p { margin: 0 0 10px; color: #afc8cd; font-size: 13px; line-height: 1.6; }
.qr-url { color: #b0e8d7; overflow-wrap: anywhere; }
.qr-hint { margin: 8px 0 0; font-size: 12px; }
.qr-address-choice { display: block; margin-bottom: 10px; }
.qr-address-choice span { display: block; margin-bottom: 5px; color: #afc8cd; font-size: 12px; }
select { width: 100%; min-height: 44px; padding: 8px; border: 1px solid #34505a; border-radius: 8px; background: #132c36; color: #e6f9f6; font-size: 13px; }
.qr-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.qr-actions button { flex: 1; white-space: nowrap; }
.qr-stop { border-color: #bb7978; background: #522d32; color: #fff0eb; }
@media (max-width: 620px) and (min-height: 501px) { .qr-panel { flex-direction: column; padding: 16px; gap: 16px; width: 420px; }.qr-instructions { width: 100%; }.qr-image { width: min(256px, 100%); height: auto; } }
@media (max-height: 500px) { .qr-panel { gap: 20px; padding: 16px; }.qr-image { width: 228px; height: 228px; }.qr-instructions p { margin-bottom: 7px; }.qr-address-choice { margin-bottom: 7px; } }
</style>
