import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Synthetic browser/HTTP fixtures exercise the shipped inline script without a device.
const pageScript = readFileSync(new URL('../../public/remote-control.html', import.meta.url), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1];

function jsonResponse(data, status = 200) {
  return { ok: status === 200, status, json: async () => status === 200
    ? { ok: true, data } : { ok: false, error: { code: 'SESSION_EXPIRED' } } };
}

function delayedResponse(response, delayMs, signal, ignoreAbort = false) {
  return new Promise((resolve, reject) => {
    let timer;
    const abort = () => { clearTimeout(timer); reject(new DOMException('Timed out', 'AbortError')); };
    if (!ignoreAbort) signal?.addEventListener('abort', abort, { once: true });
    if (Number.isFinite(delayMs)) timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve(response);
    }, delayMs);
  });
}

function createPage({ status: suppliedStatus, route } = {}) {
  const status = { robotReady: true, motionEnabled: true, cameraEnabled: false, following: false, ...suppliedStatus };
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) {
      const listeners = new Map();
      elements.set(id, {
        hidden: false, textContent: '', disabled: false, value: '', dataset: {}, src: '',
        addEventListener: (type, handler) => listeners.set(type, handler),
        dispatch: (type, event = {}) => listeners.get(type)?.(event),
        removeAttribute(name) { delete this[name]; },
        setAttribute(name, value) { this[name] = value; },
        focus() {}, classList: { add() {}, remove() {} },
      });
    }
    return elements.get(id);
  };
  const buttons = ['forward', 'backward', 'left', 'right', 'stop'].map((action) => {
    const button = element(action); button.dataset.action = action; return button;
  });
  const fetch = vi.fn((path, options) => {
    const customized = route?.(path, options);
    if (customized !== undefined) return customized;
    if (path === '/remote/pair') return Promise.resolve(jsonResponse({ csrfToken: 'synthetic-csrf', status }));
    if (path === '/remote/status') return Promise.resolve(jsonResponse(status));
    if (path === '/remote/frame') return Promise.resolve({ ok: true, blob: async () => new Blob(['jpeg'], { type: 'image/jpeg' }) });
    return Promise.resolve(jsonResponse({ accepted: true }));
  });
  const imageUrls = { createObjectURL: vi.fn(() => 'blob:synthetic-frame'), revokeObjectURL: vi.fn() };
  vm.runInNewContext(pageScript, {
    document: { getElementById: element, querySelectorAll: (selector) => selector.includes(':not')
      ? buttons.filter((button) => button.dataset.action !== 'stop') : buttons,
    addEventListener() {}, hidden: false },
    window: { addEventListener() {} }, fetch, AbortController, URL: imageUrls, Date, Blob,
    setTimeout, clearTimeout, setInterval,
  });
  return {
    element, fetch, imageUrls, status,
    async pair() {
      element('code').value = '12345678';
      await element('pair-form').dispatch('submit', { preventDefault() {} });
      await vi.advanceTimersByTimeAsync(0);
    },
    async action(action) {
      element(action === 'follow' ? 'follow' : action).dispatch('click');
      await vi.advanceTimersByTimeAsync(0);
    },
    requests(path) { return fetch.mock.calls.filter(([url]) => url === path).map(([, request]) => request); },
    actions() { return this.requests('/remote/action').map((request) => JSON.parse(request.body).action); },
  };
}

describe('LAN remote control request deadlines', () => {
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); });

  it.each([['follow', 6500], ['forward', 5500], ['stop', 2000]])(
    'keeps heartbeat active while %s completes within the full Native budget', async (action, nativeBudgetMs) => {
      vi.useFakeTimers();
      const page = createPage({ route: (path, options) => path === '/remote/action'
        ? delayedResponse(jsonResponse({ accepted: true }), nativeBudgetMs, options.signal) : undefined });
      await page.pair();
      await page.action(action);
      await vi.advanceTimersByTimeAsync(nativeBudgetMs);
      expect(page.actions()).toEqual([action]);
      expect(page.requests('/remote/action')[0].signal.aborted).toBe(false);
      expect(page.requests('/remote/heartbeat')).toHaveLength(1 + nativeBudgetMs / 500);
      expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
      expect(page.element('action-status').textContent).toBe(action === 'stop' ? '已送出停止' : '指令已送出');
    },
  );

  it.each([['follow', 7500], ['forward', 6500], ['stop', 3000]])(
    'stops and disconnects when %s actually exceeds its deadline without replay', async (action, deadlineMs) => {
      vi.useFakeTimers();
      const page = createPage({ route: (path, options) => path === '/remote/action' && !options.keepalive
        ? delayedResponse(null, Infinity, options.signal) : undefined });
      await page.pair();
      await page.action(action);
      await vi.advanceTimersByTimeAsync(deadlineMs - 1);
      expect(page.actions()).toEqual([action]);
      expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
      await vi.advanceTimersByTimeAsync(1);
      expect(page.requests('/remote/action')[0].signal.aborted).toBe(true);
      expect(page.actions()).toEqual([action, 'stop']);
      expect(page.element('connection-status').textContent).toBe('連線已暫停');
      expect(page.element('frame').hidden).toBe(true);
      expect(page.element('reconnect').hidden).toBe(false);
      const heartbeatCount = page.requests('/remote/heartbeat').length;
      await vi.advanceTimersByTimeAsync(10000);
      expect(page.requests('/remote/heartbeat')).toHaveLength(heartbeatCount);
      expect(page.actions()).toEqual([action, 'stop']);
    },
  );

  it('sends stop immediately while follow is pending and allows its 2 second Native confirmation', async () => {
    vi.useFakeTimers();
    const page = createPage({ route: (path, options) => {
      if (path !== '/remote/action') return undefined;
      const action = JSON.parse(options.body).action;
      return delayedResponse(jsonResponse({ accepted: true }), action === 'follow' ? 6500 : 2000, options.signal);
    } });
    await page.pair();
    await page.action('follow');
    await vi.advanceTimersByTimeAsync(500);
    await page.action('stop');
    expect(page.actions()).toEqual(['follow', 'stop']);
    await vi.advanceTimersByTimeAsync(2000);
    expect(page.element('action-status').textContent).toBe('已送出停止');
    expect(page.requests('/remote/action')[1].signal.aborted).toBe(false);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
  });

  it('retains the short heartbeat deadline and aborts the pending action if the lease connection stalls', async () => {
    vi.useFakeTimers();
    let heartbeatCount = 0;
    const page = createPage({ route: (path, options) => {
      if (path === '/remote/action' && !options.keepalive) return delayedResponse(null, Infinity, options.signal);
      if (path === '/remote/heartbeat' && ++heartbeatCount > 1) return delayedResponse(null, Infinity, options.signal);
      return undefined;
    } });
    await page.pair();
    await page.action('follow');
    await vi.advanceTimersByTimeAsync(2299);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
    await vi.advanceTimersByTimeAsync(1);
    expect(page.requests('/remote/heartbeat')[1].signal.aborted).toBe(true);
    expect(page.requests('/remote/action')[0].signal.aborted).toBe(true);
    expect(page.actions()).toEqual(['follow', 'stop']);
    expect(page.element('connection-status').textContent).toBe('連線已暫停');
  });

  it('retains the short frame deadline while a move continues with fresh heartbeats', async () => {
    vi.useFakeTimers();
    const page = createPage({ status: { cameraEnabled: true }, route: (path, options) => {
      if (path === '/remote/frame') return delayedResponse(null, Infinity, options.signal);
      if (path === '/remote/action') return delayedResponse(jsonResponse({ accepted: true }), 5500, options.signal);
      return undefined;
    } });
    await page.pair();
    await page.action('forward');
    await vi.advanceTimersByTimeAsync(1800);
    expect(page.requests('/remote/frame')[0].signal.aborted).toBe(true);
    expect(page.requests('/remote/action')[0].signal.aborted).toBe(false);
    expect(page.element('camera-message').textContent).toBe('影像逾時，等待新畫面');
    expect(page.element('frame').hidden).toBe(true);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
  });

  it('ignores late action and image responses after the controller session is revoked', async () => {
    vi.useFakeTimers();
    let heartbeatCount = 0;
    const page = createPage({ status: { cameraEnabled: true }, route: (path, options) => {
      if (path === '/remote/heartbeat' && ++heartbeatCount > 1) return Promise.resolve(jsonResponse(null, 401));
      if (path === '/remote/action' && !options.keepalive) return delayedResponse(jsonResponse({ accepted: true }), 2000, options.signal, true);
      if (path === '/remote/frame') return delayedResponse({ ok: true, blob: async () => new Blob(['jpeg'], { type: 'image/jpeg' }) }, 1000, options.signal, true);
      return undefined;
    } });
    await page.pair();
    await page.action('forward');
    await vi.advanceTimersByTimeAsync(2000);
    expect(page.element('connection-status').textContent).toBe('連線已暫停');
    expect(page.element('error').textContent).toContain('配對已失效');
    expect(page.element('frame').hidden).toBe(true);
    expect(page.imageUrls.createObjectURL).not.toHaveBeenCalled();
    expect(page.element('action-status').textContent).toBe('');
    expect(page.actions()).toEqual(['forward', 'stop']);
  });

  it.each(['network', 'invalid JSON', 'invalid receipt'])(
    'stops once and ends heartbeat after an uncertain action %s failure, ignoring late imagery', async (failure) => {
      vi.useFakeTimers();
      const page = createPage({ status: { cameraEnabled: true }, route: (path, options) => {
        if (path === '/remote/frame') return delayedResponse({ ok: true,
          blob: async () => new Blob(['jpeg'], { type: 'image/jpeg' }) }, 1500, options.signal, true);
        if (path !== '/remote/action' || options.keepalive) return undefined;
        if (failure === 'network') return new Promise((_resolve, reject) => {
          setTimeout(() => reject(new TypeError('Connection reset after dispatch')), 1000);
        });
        const response = failure === 'invalid JSON' ? { ok: true, json: async () => {
          throw new SyntaxError('Invalid response JSON');
        } } : jsonResponse({});
        return delayedResponse(response, 1000, options.signal);
      } });
      await page.pair();
      await page.action('forward');
      await vi.advanceTimersByTimeAsync(1000);
      expect(page.actions()).toEqual(['forward', 'stop']);
      expect(page.element('connection-status').textContent).toBe('連線已暫停');
      expect(page.element('reconnect').hidden).toBe(false);
      const heartbeatCount = page.requests('/remote/heartbeat').length;
      await vi.advanceTimersByTimeAsync(10000);
      expect(page.actions()).toEqual(['forward', 'stop']);
      expect(page.requests('/remote/heartbeat')).toHaveLength(heartbeatCount);
      expect(page.element('frame').hidden).toBe(true);
      expect(page.imageUrls.createObjectURL).not.toHaveBeenCalled();
    },
  );

  it('preserves an authoritative SDK rejection without disconnecting or sending a fallback stop', async () => {
    vi.useFakeTimers();
    const page = createPage({ route: (path) => path === '/remote/action' ? Promise.resolve({
      ok: false, status: 409, json: async () => ({ ok: false, error: { code: 'ROBOT_BUSY', message: 'Robot is busy' } }),
    }) : undefined });
    await page.pair();
    await page.action('forward');
    await vi.advanceTimersByTimeAsync(2000);
    expect(page.actions()).toEqual(['forward']);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
    expect(page.element('action-status').textContent).toContain('機器正在執行其他動作');
    expect(page.requests('/remote/heartbeat')).toHaveLength(5);
  });

  it.each([{ cameraState: 'releasing' }, { cameraState: 'error', cameraError: 'CAMERA_RELEASE_FAILED' }])(
    'withholds follow until the camera resource is released: %j', async (cameraStatus) => {
      vi.useFakeTimers();
      const page = createPage({ status: cameraStatus });
      await page.pair();
      expect(page.element('follow').disabled).toBe(true);
      expect(page.element('follow-hint').hidden).toBe(false);
      expect(page.element('follow-hint').textContent).toContain('相機尚未釋放');
      page.status.cameraState = 'disabled';
      page.status.cameraError = '';
      await vi.advanceTimersByTimeAsync(1000);
      expect(page.element('follow').disabled).toBe(false);
      expect(page.element('follow-hint').hidden).toBe(true);
    },
  );
});
