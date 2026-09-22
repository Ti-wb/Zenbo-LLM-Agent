import { createRenderer, defineComponent, nextTick, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRobotControls } from './useRobotControls';

const apps = [];
afterEach(() => { apps.splice(0).forEach((app) => app.unmount()); vi.useRealTimers(); });

async function mount(options = {}) {
  const currentStatus = { robotReady: true, motionEnabled: true, cameraEnabled: true, following: false,
    remote: { enabled: false, pairingCode: '123456' } };
  const transport = {
    getDeviceStatus: vi.fn().mockImplementation(async () => ({ ...currentStatus })),
    getCameraImage: vi.fn().mockResolvedValue(new Blob(['jpeg'], { type: 'image/jpeg' })),
    sendDeviceAction: vi.fn().mockResolvedValue({ accepted: true }),
    putDeviceSettings: vi.fn().mockResolvedValue({}),
    unlockRuntimeSettings: vi.fn().mockResolvedValue({}),
    setRemoteEnabled: vi.fn().mockResolvedValue({}),
    captureCamera: vi.fn().mockResolvedValue({ artifactId: 'photo', capturedAt: '2026-09-22T10:00:00Z' }),
    ...options.transport,
  };
  const imageUrls = { createObjectURL: vi.fn().mockReturnValue('blob:frame'), revokeObjectURL: vi.fn() };
  const renderer = createRenderer({ insert() {}, remove() {}, patchProp() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null });
  const open = ref(true);
  let controls;
  const app = renderer.createApp(defineComponent({ setup() {
    controls = useRobotControls(open, { transport, imageUrls }); return () => null;
  } }));
  app.mount({}); apps.push(app);
  await nextTick();
  return { app, controls, open, transport, imageUrls, currentStatus };
}

describe('robot controls lifecycle', () => {
  it.each([
    { cameraEnabled: true, cameraState: 'ready', cameraError: '' },
    { cameraEnabled: false, cameraState: 'releasing', cameraError: '' },
    { cameraEnabled: false, cameraState: 'error', cameraError: 'CAMERA_RELEASE_FAILED' },
  ])('blocks following until camera ownership is released: %j', async (camera) => {
    const { controls, transport, currentStatus } = await mount();
    Object.assign(currentStatus, camera);
    await controls.refresh();
    expect(controls.canMove.value).toBe(true);
    expect(controls.canFollow.value).toBe(false);
    expect(await controls.action('follow')).toBe(false);
    expect(transport.sendDeviceAction).not.toHaveBeenCalled();
    expect(await controls.action('stop')).toBe(true);
    Object.assign(currentStatus, { cameraEnabled: false, cameraState: 'disabled', cameraError: '' });
    await controls.refresh();
    expect(controls.canFollow.value).toBe(true);
    expect(await controls.action('follow')).toBe(true);
    expect(transport.sendDeviceAction.mock.calls.map(([action]) => action)).toEqual(['stop', 'follow']);
  });

  it('allows bounded manual actions while the camera is enabled, without retrying a failure', async () => {
    const { controls, transport } = await mount();
    transport.sendDeviceAction.mockRejectedValueOnce(Object.assign(new Error('vendor detail'), { code: 'ROBOT_BUSY' }));
    expect(controls.canMove.value).toBe(true);
    expect(await controls.action('forward')).toBe(false);
    expect(transport.sendDeviceAction).toHaveBeenCalledExactlyOnceWith('forward');
    expect(controls.error.value).toContain('請先停止');
    expect(controls.error.value).not.toContain('vendor');
  });

  it('keeps stop available during an unresolved movement request', async () => {
    const { controls, transport } = await mount();
    let finish;
    transport.sendDeviceAction.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    const move = controls.action('forward');
    expect(controls.canMove.value).toBe(false);
    await controls.action('stop');
    expect(transport.sendDeviceAction.mock.calls.map(([action]) => action)).toEqual(['forward', 'stop']);
    finish({ accepted: true }); await move;
  });

  it('requires an unlock only when enabling LAN control', async () => {
    const { controls, transport } = await mount();
    await controls.remote(true, '123456');
    expect(transport.unlockRuntimeSettings).toHaveBeenCalledExactlyOnceWith({ pin: '123456' });
    await controls.remote(false, '');
    expect(transport.unlockRuntimeSettings).toHaveBeenCalledTimes(1);
    expect(transport.setRemoteEnabled.mock.calls).toEqual([[true], [false]]);
  });

  it('can revoke LAN access while a capture is pending', async () => {
    const { controls, transport, currentStatus } = await mount();
    let finish;
    currentStatus.remote = { enabled: true, connected: true, pairingCode: '123456' };
    transport.captureCamera.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    const capture = controls.capture();
    transport.setRemoteEnabled.mockImplementationOnce(async () => { currentStatus.remote = { enabled: false, connected: false }; });
    expect(await controls.remote(false, '')).toBe(true);
    expect(transport.unlockRuntimeSettings).not.toHaveBeenCalled();
    expect(controls.status.value.remote.pairingCode).toBeUndefined();
    finish({ artifactId: 'photo', capturedAt: '2026-09-22T10:00:00Z' }); await capture;
  });

  it('clears a retained snapshot when the camera is disabled without active preview', async () => {
    const { controls, currentStatus, imageUrls } = await mount();
    await controls.capture();
    expect(controls.imageUrl.value).toBe('blob:frame');
    expect(controls.previewEnabled.value).toBe(false);
    currentStatus.cameraEnabled = false;
    await controls.refresh();
    expect(controls.imageUrl.value).toBe('');
    expect(imageUrls.revokeObjectURL).toHaveBeenCalledWith('blob:frame');
  });

  it('does not display a frame that finishes after closing the panel', async () => {
    const { controls, transport, open, imageUrls } = await mount();
    let finish;
    transport.getCameraImage.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
    controls.previewEnabled.value = true; await nextTick();
    expect(transport.getCameraImage).toHaveBeenCalledOnce();
    const signal = transport.getCameraImage.mock.calls[0][1].signal;
    open.value = false; await nextTick();
    expect(signal.aborted).toBe(true);
    finish(new Blob(['late'], { type: 'image/jpeg' })); await nextTick();
    expect(imageUrls.createObjectURL).not.toHaveBeenCalled();
    expect(controls.status.value).toBeNull();
  });

  it('keeps at most one preview request in flight and clears images on timeout', async () => {
    vi.useFakeTimers();
    const { controls, transport, imageUrls } = await mount();
    controls.previewEnabled.value = true; await nextTick(); await nextTick();
    expect(controls.imageUrl.value).toBe('blob:frame');
    transport.getCameraImage.mockImplementation((_id, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(Object.assign(new Error(), { name: 'AbortError' })));
    }));
    await vi.advanceTimersByTimeAsync(1500);
    expect(transport.getCameraImage).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1000);
    expect(controls.imageUrl.value).toBe('');
    expect(imageUrls.revokeObjectURL).toHaveBeenCalledWith('blob:frame');
    expect(controls.imageError.value).toContain('影像逾時');
  });
});
