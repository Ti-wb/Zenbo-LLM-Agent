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

function createPage({ status: suppliedStatus, route, hash = '', hidden = false, pathname = '/' } = {}) {
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
  const events = [];
  const location = { hash, pathname, search: '' };
  const history = { replaceState: vi.fn((_state, _title, url) => { events.push('clear-fragment'); location.hash = ''; expect(url).toBe(location.pathname + location.search); }) };
  const fetch = vi.fn((path, options) => {
    events.push(path);
    const customized = route?.(path, options);
    if (customized !== undefined) return customized;
    if (path === '/remote/pair') return Promise.resolve(jsonResponse({ csrfToken: 'synthetic-csrf', status }));
    if (path === '/remote/status') return Promise.resolve(jsonResponse(status));
    if (path === '/remote/frame') return Promise.resolve({ ok: true, blob: async () => new Blob(['jpeg'], { type: 'image/jpeg' }) });
    return Promise.resolve(jsonResponse({ accepted: true }));
  });
  const imageUrls = { createObjectURL: vi.fn(() => 'blob:synthetic-frame'), revokeObjectURL: vi.fn() };
  const windowEvents = new Map(), documentEvents = new Map();
  const document = { getElementById: element, querySelectorAll: (selector) => selector.includes(':not')
    ? buttons.filter((button) => button.dataset.action !== 'stop') : buttons,
    addEventListener: (name, handler) => documentEvents.set(name, handler), hidden };
  vm.runInNewContext(pageScript, {
    document, window: { addEventListener: (name, handler) => windowEvents.set(name, handler), location, history },
    fetch, AbortController, URL: imageUrls, Date, Blob, setTimeout, clearTimeout, setInterval,
  });
  return {
    element, fetch, imageUrls, status, events, location, history,
    event(name) { return windowEvents.get(name)?.(); },
    scan(code) { location.hash = `#pair=${code}`; windowEvents.get('hashchange')?.(); },
    visible(value) { document.hidden = !value; documentEvents.get('visibilitychange')?.(); },
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

  it('consumes a scanned fragment before its single pairing POST, without manual input or physical actions', async () => {
    vi.useFakeTimers();
    const page = createPage({ hash: '#pair=12345678' });
    expect(page.events.slice(0, 2)).toEqual(['clear-fragment', '/remote/pair']);
    expect(page.element('pair-progress').hidden).toBe(false);
    await page.element('pair-form').dispatch('submit', { preventDefault() {} });
    await vi.advanceTimersByTimeAsync(0);
    expect(page.location.hash).toBe('');
    expect(page.requests('/remote/pair')).toHaveLength(1);
    expect(JSON.parse(page.requests('/remote/pair')[0].body)).toEqual({ code: '12345678' });
    expect(page.element('code').value).toBe('');
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
    expect(page.element('pair-section').hidden).toBe(true);
    expect(page.actions()).toEqual([]);
    const reload = createPage({ hash: page.location.hash });
    expect(reload.requests('/remote/pair')).toHaveLength(0);
  });

  it('clears malformed and rejected QR codes and requires a new scan without retrying', async () => {
    vi.useFakeTimers();
    const missing = createPage({ pathname: '/remote-control.html' });
    expect(missing.element('error').textContent).toContain('這個連結沒有配對資訊');
    expect(missing.requests('/remote/pair')).toHaveLength(0);
    const invalid = createPage({ hash: '#pair=12345678&pin=123456' });
    expect(invalid.location.hash).toBe('');
    expect(invalid.requests('/remote/pair')).toHaveLength(0);
    expect(invalid.element('error').textContent).toContain('重新產生配對 QR');
    const rejected = createPage({ hash: '#pair=12345678', route: (path) => path === '/remote/pair'
      ? Promise.resolve({ ok: false, status: 429, json: async () => ({ ok: false,
        error: { code: 'PAIRING_REJECTED', message: 'Pairing rejected' } }) }) : undefined });
    await vi.advanceTimersByTimeAsync(10000);
    expect(rejected.location.hash).toBe('');
    expect(rejected.requests('/remote/pair')).toHaveLength(1);
    expect(rejected.element('error').textContent).toContain('已過期、已使用');
    expect(rejected.element('error').textContent).toContain('重新產生配對 QR');
    expect(rejected.element('pair-button').disabled).toBe(false);
    expect(rejected.actions()).toEqual([]);
  });

  it('pairs a new same-document scan after rejection or connection and consumes each fragment before one POST', async () => {
    vi.useFakeTimers();
    let attempt = 0;
    const page = createPage({ route: (path) => path === '/remote/pair' && ++attempt === 1
      ? Promise.resolve({ ok: false, status: 429, json: async () => ({ ok: false,
        error: { code: 'PAIRING_REJECTED', message: 'expired' } }) }) : undefined });
    for (const code of ['11111111', '22222222', '33333333']) {
      const start = page.events.length;
      page.scan(code);
      expect(page.location.hash).toBe('');
      expect(page.events[start]).toBe('clear-fragment');
      await vi.advanceTimersByTimeAsync(0);
      page.event('hashchange'); page.event('pageshow');
    }
    expect(page.requests('/remote/pair').map((request) => JSON.parse(request.body).code)).toEqual(['11111111', '22222222', '33333333']);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
    expect(page.actions()).toEqual(['stop']);
    expect(page.requests('/remote/logout')).toHaveLength(0);
  });

  it('serializes the latest scan behind a pending pair, and never revives a hidden generation or replays it on pageshow', async () => {
    vi.useFakeTimers();
    let resolveOld, resolveLogout;
    const page = createPage({ route: (path, options) => {
      if (path === '/remote/pair' && JSON.parse(options.body).code === '11111111') return new Promise((resolve) => { resolveOld = resolve; });
      if (path === '/remote/logout') return new Promise((resolve) => { resolveLogout = resolve; });
      return undefined;
    } });
    page.scan('11111111');
    page.scan('11111111'); // Duplicate in-flight navigation must not consume the code twice.
    page.scan('22222222'); page.scan('33333333');
    expect(page.requests('/remote/pair')).toHaveLength(1);
    resolveOld(jsonResponse({ csrfToken: 'obsolete', status: page.status }));
    await vi.advanceTimersByTimeAsync(0);
    expect(page.requests('/remote/pair').map((request) => JSON.parse(request.body).code)).toEqual(['11111111', '33333333']);
    expect(page.requests('/remote/heartbeat')).toHaveLength(1);
    expect(page.actions()).toEqual([]);
    page.event('pagehide'); page.event('pageshow');
    expect(page.requests('/remote/pair')).toHaveLength(2);
    expect(page.element('connection-status').textContent).toBe('連線已暫停');
    page.event('pagehide'); page.scan('44444444');
    expect(page.location.hash).toBe('');
    expect(page.requests('/remote/pair')).toHaveLength(2);
    page.event('pageshow'); await vi.advanceTimersByTimeAsync(0);
    expect(page.requests('/remote/pair')).toHaveLength(3);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');

    const logout = page.element('logout').dispatch('click');
    page.scan('88888888');
    expect(page.requests('/remote/pair')).toHaveLength(3);
    resolveLogout(jsonResponse({ accepted: true }));
    await logout; await vi.advanceTimersByTimeAsync(0);
    expect(page.requests('/remote/pair')).toHaveLength(4);
    expect(page.element('connection-status').textContent).toBe('已連接 Zenbo');
    expect(page.requests('/remote/heartbeat').at(-1).headers['X-CSRF-Token']).toBe('synthetic-csrf');

    const background = createPage({ hash: '#pair=77777777', hidden: true });
    expect(background.location.hash).toBe('');
    background.event('pageshow'); background.visible(false);
    expect(background.requests('/remote/pair')).toHaveLength(0);
    background.visible(true); await vi.advanceTimersByTimeAsync(0);
    expect(background.requests('/remote/pair')).toHaveLength(1);
    expect(background.element('connection-status').textContent).toBe('已連接 Zenbo');

    let resolveHidden;
    const hidden = createPage({ hash: '#pair=55555555', route: (path) => path === '/remote/pair'
      ? new Promise((resolve) => { resolveHidden = resolve; }) : undefined });
    hidden.scan('66666666'); hidden.event('pagehide');
    resolveHidden(jsonResponse({ csrfToken: 'late', status: hidden.status }));
    await vi.advanceTimersByTimeAsync(0); hidden.event('pageshow');
    expect(hidden.requests('/remote/pair')).toHaveLength(1);
    expect(hidden.requests('/remote/heartbeat')).toHaveLength(0);
    expect(hidden.element('connection-status').textContent).toBe('連線已暫停');
  });

  it('blocks movement while power or USB is connected but retains stop and restores controls after disconnect', async () => {
    vi.useFakeTimers();
    const page = createPage({ status: { motionBlockedReason: 'POWER_CONNECTED' } });
    await page.pair();
    expect(page.element('forward').disabled).toBe(true);
    expect(page.element('follow').disabled).toBe(true);
    expect(page.element('stop').disabled).toBe(false);
    expect(page.element('motion-hint').textContent).toContain('充電線');
    page.status.motionBlockedReason = 'USB_CONNECTED';
    await vi.advanceTimersByTimeAsync(1000);
    expect(page.element('motion-hint').textContent).toContain('USB');
    page.status.motionBlockedReason = '';
    await vi.advanceTimersByTimeAsync(1000);
    expect(page.element('forward').disabled).toBe(false);
    expect(page.element('follow').disabled).toBe(false);
  });

  it.each([['follow', 11500], ['forward', 5500], ['stop', 2000]])(
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

  it.each([['follow', 12500], ['forward', 6500], ['stop', 3000]])(
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
      return delayedResponse(jsonResponse({ accepted: true }), action === 'follow' ? 11500 : 2000, options.signal);
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
