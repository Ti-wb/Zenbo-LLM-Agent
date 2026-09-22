<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import { RuntimeTransport } from '../services/runtimeTransport';
const props = defineProps({ capture: { type: Object, required: true } });
const transport = new RuntimeTransport();
const image = ref('');
const error = ref('');
let generation = 0;
function clear() { if (image.value) URL.revokeObjectURL(image.value); image.value = ''; }
watch(() => props.capture.artifactId, async (artifactId, _, onCleanup) => {
  const current = ++generation;
  clear(); error.value = '';
  const abort = new AbortController();
  onCleanup(() => abort.abort());
  try {
    const blob = await transport.getCameraImage(artifactId, { signal: abort.signal });
    if (current === generation && !abort.signal.aborted) image.value = URL.createObjectURL(blob);
  } catch (cause) {
    if (current === generation && !abort.signal.aborted) error.value = '照片已失效，請重新拍照。';
  }
}, { immediate: true });
onBeforeUnmount(() => { generation += 1; clear(); });
</script>

<template>
  <figure class="conversation-photo">
    <img v-if="image" :src="image" alt="本對話最近拍攝並提供給 Hermes 的照片" />
    <figcaption><strong>本對話最近照片</strong><span>{{ error || new Date(capture.capturedAt).toLocaleTimeString('zh-TW') }}</span></figcaption>
  </figure>
</template>

<style scoped>
.conversation-photo { display: flex; align-items: center; gap: 12px; margin: 14px 0 0; padding: 11px; background: #07151c; border: 1px solid #2b4650; border-radius: 9px; }
img { display: block; width: 100px; height: 75px; object-fit: contain; background: #020a0f; border-radius: 5px; }
figcaption { display: grid; gap: 6px; font-size: 12px; color: #c4dad8; }strong { font-weight: 500; }span { color: #93b1bd; font-size: 11px; }
</style>
