<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { createLanControlQr, isLanControlUrl } from '../services/lanControlQr';

const props = defineProps({
  active: Boolean,
  enabled: Boolean,
  urls: { type: Array, default: () => [] },
});
const emit = defineEmits(['stop']);
const expanded = ref(false);
const selectedUrl = ref('');
const panel = ref(null);
const urls = computed(() => [...new Set(props.urls.filter(isLanControlUrl))]);
const available = computed(() => props.active && props.enabled && urls.value.length > 0);
const qr = computed(() => expanded.value && available.value && urls.value.includes(selectedUrl.value)
  ? createLanControlQr(selectedUrl.value) : null);

// Compare address content: Native refreshes the status object on every poll.
watch(() => JSON.stringify([props.active, props.enabled, urls.value]), () => {
  expanded.value = false;
  selectedUrl.value = available.value ? urls.value[0] : '';
}, { immediate: true, flush: 'sync' });

async function toggleQr() {
  expanded.value = available.value && !expanded.value;
  if (expanded.value) {
    await nextTick();
    panel.value?.scrollIntoView?.({ block: 'nearest' });
  }
}
</script>

<template>
  <div v-if="active && enabled" class="lan-qr">
    <button class="qr-toggle" type="button" :disabled="!available" :aria-expanded="Boolean(qr)" aria-controls="lan-qr-panel" @click="toggleQr">{{ qr ? '收起 QR code' : '顯示 QR code' }}</button>
    <p v-if="!available" class="qr-hint" role="status">取得 Wi-Fi 區網位址後，就能顯示 QR code。</p>
    <div v-if="qr" id="lan-qr-panel" ref="panel" class="qr-panel">
      <svg class="qr-image" :viewBox="`0 0 ${qr.size} ${qr.size}`" width="256" height="256" role="img" aria-label="掃碼開啟 Zenbo 相機與遙控網頁" shape-rendering="crispEdges">
        <rect width="100%" height="100%" fill="#fff" />
        <path :d="qr.path" fill="#000" />
      </svg>
      <div class="qr-instructions">
        <h3>用手機相機掃描</h3>
        <p>手機和 Zenbo 連上同一個 Wi-Fi，掃碼後輸入上方的一次性配對碼，即可觀看相機與遙控。</p>
        <label v-if="urls.length > 1" class="qr-address-choice"><span>選擇區網位址</span><select v-model="selectedUrl"><option v-for="url in urls" :key="url" :value="url">{{ url }}</option></select></label>
        <p class="qr-url">{{ selectedUrl }}</p>
        <button class="qr-stop" type="button" @click="emit('stop')">■ 停止移動</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lan-qr { margin-bottom: 14px; }
button { min-height: 44px; padding: 9px 16px; border: 1px solid #34505a; border-radius: 10px; background: #132c36; color: #e6f9f6; cursor: pointer; touch-action: manipulation; font-size: 13px; }
button:disabled { opacity: .42; cursor: default; }
button:focus-visible, select:focus-visible { outline: 2px solid #93dfca; outline-offset: 3px; }
.qr-panel { display: flex; align-items: center; gap: 24px; margin-top: 12px; padding: 20px; border: 1px solid #34505a; border-radius: 12px; background: #08161e; }
.qr-image { display: block; width: 256px; height: 256px; max-width: 100%; flex-shrink: 0; background: #fff; }
.qr-instructions { flex: 1; min-width: 0; }
h3 { margin: 0 0 10px; color: #e6f9f6; font-size: 17px; }
p { margin: 0 0 12px; color: #afc8cd; font-size: 13px; line-height: 1.6; }
.qr-url { color: #b0e8d7; overflow-wrap: anywhere; }
.qr-hint { margin: 8px 0 0; font-size: 12px; }
.qr-address-choice { display: block; margin-bottom: 10px; }
.qr-address-choice span { display: block; margin-bottom: 5px; color: #afc8cd; font-size: 12px; }
select { width: 100%; min-height: 44px; padding: 8px; border: 1px solid #34505a; border-radius: 8px; background: #132c36; color: #e6f9f6; font-size: 13px; }
.qr-stop { width: 100%; border-color: #bb7978; background: #522d32; color: #fff0eb; }
@media (max-width: 620px) { .qr-panel { flex-direction: column; padding: 16px; gap: 16px; }.qr-instructions { width: 100%; }.qr-image { width: min(256px, 100%); height: auto; } }
</style>
