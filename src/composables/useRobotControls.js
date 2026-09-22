import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { RuntimeTransport } from '../services/runtimeTransport';
import { deviceMessage } from '../services/deviceMessages';

export function useRobotControls(open, options = {}) {
  const transport = options.transport || new RuntimeTransport();
  const imageUrls = options.imageUrls || globalThis.URL;
  const status = ref(null);
  const actionError = ref('');
  const statusError = ref('');
  const error = computed(() => actionError.value || statusError.value);
  const pending = ref('');
  const online = ref(false);
  const previewEnabled = ref(false);
  const imageUrl = ref('');
  const imageLabel = ref('');
  const imageError = ref('');
  let generation = 0;
  let statusRevision = 0;
  let operationId = 0;
  let remoteRevision = 0;
  let statusTimer;
  let frameTimer;
  let frameAbort;
  let framePending = false;
  let disposed = false;

  const canMove = computed(() => online.value && status.value?.robotReady === true
    && status.value?.motionEnabled === true && !status.value?.motionBlockedReason && !pending.value);
  const canFollow = computed(() => canMove.value && !status.value?.cameraEnabled
    && status.value?.cameraState !== 'releasing' && status.value?.cameraError !== 'CAMERA_RELEASE_FAILED');

  function clearImage() {
    if (imageUrl.value) imageUrls.revokeObjectURL(imageUrl.value);
    imageUrl.value = '';
    imageLabel.value = '';
  }

  function replaceImage(blob, label) {
    clearImage();
    imageUrl.value = imageUrls.createObjectURL(blob);
    imageLabel.value = label;
  }

  async function refresh() {
    const current = generation;
    const revision = statusRevision;
    try {
      const result = await transport.getDeviceStatus();
      if (disposed || !open.value || current !== generation || revision !== statusRevision) return;
      status.value = result;
      online.value = true;
      statusError.value = '';
      if (!result.cameraEnabled) {
        previewEnabled.value = false;
        stopPreview();
      }
    } catch (cause) {
      if (disposed || !open.value || current !== generation || revision !== statusRevision) return;
      online.value = false;
      statusError.value = deviceMessage(cause);
      clearImage();
    }
  }

  async function pollStatus() {
    const current = generation;
    await refresh();
    if (!disposed && open.value && current === generation) statusTimer = setTimeout(pollStatus, 1000);
  }

  function stopPreview() {
    clearTimeout(frameTimer);
    frameAbort?.abort();
    frameAbort = null;
    clearImage();
  }

  async function pollFrame() {
    if (framePending || disposed || !open.value || !previewEnabled.value || !online.value) return;
    const current = generation;
    framePending = true;
    const abort = new AbortController();
    frameAbort = abort;
    const timeout = setTimeout(() => { abort.abort(); clearImage(); }, 2000);
    try {
      const blob = await transport.getCameraImage('', { signal: abort.signal });
      if (disposed || !open.value || current !== generation || !previewEnabled.value || abort.signal.aborted) return;
      replaceImage(blob, `畫面更新 · ${new Date().toLocaleTimeString('zh-TW')}`);
      imageError.value = '';
    } catch (cause) {
      if (!disposed && current === generation && previewEnabled.value) {
        clearImage();
        imageError.value = cause.name === 'AbortError' ? '影像逾時，等待新畫面' : deviceMessage(cause);
      }
    } finally {
      clearTimeout(timeout);
      framePending = false;
      if (frameAbort === abort) frameAbort = null;
      if (!disposed && open.value && previewEnabled.value) frameTimer = setTimeout(pollFrame, 500);
    }
  }

  async function perform(name, operation) {
    if (pending.value && !['stop', 'remote-disable'].includes(name)) return false;
    const current = generation;
    const operationToken = ++operationId;
    statusRevision += 1;
    pending.value = name;
    actionError.value = '';
    try {
      const completed = await operation();
      if (completed === false || disposed || current !== generation || operationToken !== operationId) return false;
      statusRevision += 1;
      await refresh();
      return true;
    } catch (cause) {
      if (!disposed && current === generation && operationToken === operationId) actionError.value = deviceMessage(cause);
      return false;
    } finally {
      if (operationToken === operationId) pending.value = '';
    }
  }

  function action(name) {
    if (name === 'follow' && !canFollow.value) return Promise.resolve(false);
    if (name !== 'stop' && !canMove.value) return Promise.resolve(false);
    return perform(name, () => transport.sendDeviceAction(name));
  }

  function settings(value) {
    return perform('settings', () => transport.putDeviceSettings(value));
  }

  function remote(enabled, pin) {
    if (enabled && pending.value) return Promise.resolve(false);
    const revision = ++remoteRevision;
    const current = generation;
    return perform(enabled ? 'remote' : 'remote-disable', async () => {
      if (enabled) {
        await transport.unlockRuntimeSettings({ pin });
        // Disable and closing the panel revoke an enable still waiting on PIN.
        // A successful old unlock must never dispatch a new LAN authority.
        if (disposed || !open.value || current !== generation || revision !== remoteRevision) return false;
      }
      await transport.setRemoteEnabled(enabled);
      if (!enabled && current === generation && revision === remoteRevision && status.value) status.value = { ...status.value, remote: { enabled: false, connected: false, urls: [] } };
    });
  }

  async function capture() {
    previewEnabled.value = false;
    stopPreview();
    const current = generation;
    return perform('capture', async () => {
      const metadata = await transport.captureCamera();
      const blob = await transport.getCameraImage(metadata.artifactId);
      if (!disposed && open.value && current === generation) {
        replaceImage(blob, `本機照片 · ${new Date(metadata.capturedAt).toLocaleTimeString('zh-TW')}`);
        imageError.value = '';
      }
    });
  }

  watch([open, previewEnabled, online], ([visible, preview, connected]) => {
    stopPreview();
    if (visible && preview && connected) void pollFrame();
  });

  watch(open, (visible) => {
    generation += 1;
    operationId += 1;
    pending.value = '';
    clearTimeout(statusTimer);
    if (visible) {
      actionError.value = '';
      statusError.value = '';
      void pollStatus();
    } else {
      previewEnabled.value = false;
      online.value = false;
      status.value = null; // Pairing codes never survive closing the controls.
      stopPreview();
    }
  }, { immediate: true });

  onBeforeUnmount(() => {
    disposed = true;
    generation += 1;
    operationId += 1;
    pending.value = '';
    clearTimeout(statusTimer);
    stopPreview();
  });

  return { status, error, pending, online, canMove, canFollow, previewEnabled, imageUrl, imageLabel,
    imageError, refresh, action, settings, remote, capture };
}
