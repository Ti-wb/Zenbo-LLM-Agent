export const DEFAULT_RUNTIME_ORIGIN = 'http://127.0.0.1:8787';
export const LOCAL_API_PREFIX = '/api/v2';
const CLIENT_VERSION = '0.1.0';
const MAX_AUDIO_BYTES = 10 * 1024 * 1024;
const MAX_VOICE_BYTES = 2 * 1024 * 1024;
const MAX_VOICE_DURATION_MS = 30000;
// Native may stop attention (2 s), enable avoidance (1.5 s), then acquire
// follow (5 s initialization + 3 s search) or complete a move (2 s).
// Leave 1 s for scheduling/HTTP.
const DEVICE_ACTION_TIMEOUT_MS = Object.freeze({
  follow: 12500,
  forward: 6500,
  backward: 6500,
  left: 6500,
  right: 6500,
  stop: 3000,
});

function trimSlash(value) {
  return String(value || '').replace(/\/+$/, '');
}

function asWsOrigin(origin) {
  return trimSlash(origin).replace(/^http:/, 'ws:').replace(/^https:/, 'wss:');
}

function payloadMessage(payload, fallback) {
  return payload?.message || payload?.error?.message || payload?.error || fallback;
}

export function consumeBootstrapToken(
  locationImpl = globalThis.location,
  historyImpl = globalThis.history,
) {
  const hash = String(locationImpl?.hash || '').replace(/^#/, '');
  const token = new URLSearchParams(hash).get('bootstrapToken') || '';
  if (token && historyImpl?.replaceState) {
    historyImpl.replaceState(null, '', `${locationImpl.pathname || '/'}${locationImpl.search || ''}`);
  }
  return token;
}

export function wavDurationMs(blobSize, sampleRate = 16000) {
  return Math.max(1, Math.round((Math.max(0, blobSize - 44) / (sampleRate * 2)) * 1000));
}

async function sha256Hex(blob) {
  if (!globalThis.crypto?.subtle) return '';
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function fallbackUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

export class RuntimeTransport {
  constructor(options = {}) {
    this.origin = trimSlash(options.origin || DEFAULT_RUNTIME_ORIGIN);
    this.fetchImpl = options.fetchImpl || globalThis.fetch?.bind(globalThis);
    this.WebSocketImpl = options.WebSocketImpl || globalThis.WebSocket;
    this.setTimeoutImpl = options.setTimeoutImpl || globalThis.setTimeout?.bind(globalThis);
    this.clearTimeoutImpl = options.clearTimeoutImpl || globalThis.clearTimeout?.bind(globalThis);
    this.reconnectDelays = options.reconnectDelays || [500, 1000, 2000, 5000, 10000, 15000];
    this.reconnectJitter = options.reconnectJitter ?? 0.2;
    this.randomImpl = options.randomImpl || Math.random;
    this.randomUuidImpl =
      options.randomUuidImpl || globalThis.crypto?.randomUUID?.bind(globalThis.crypto) || fallbackUuid;
    this.nowImpl = options.nowImpl || Date.now;
    this.sessionId = '';
    this.bootstrapped = false;
    this.cursor = 0;
    this.socket = null;
    this.socketGeneration = 0;
    this.eventQueueTail = Promise.resolve();
    this.reconnectTimer = null;
    this.reconnectAttempt = 0;
    this.closedByClient = false;
    this.idempotencyKeys = new Map();
    this.listeners = new Map();
  }

  on(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(handler);
    return () => this.listeners.get(type)?.delete(handler);
  }

  emit(type, detail) {
    for (const handler of this.listeners.get(type) || []) {
      try {
        const result = handler(detail);
        if (result && typeof result.catch === 'function') {
          result.catch((error) => {
            console.error(`RuntimeTransport ${type} listener failed:`, error);
            if (type === 'event') {
              this.emit('error', error);
              this.socket?.close(1011, 'event handler failed');
            }
          });
        }
      } catch (error) {
        console.error(`RuntimeTransport ${type} listener failed:`, error);
        if (type === 'event') {
          this.emit('error', error);
          this.socket?.close(1011, 'event handler failed');
        }
      }
    }
  }

  async emitAsync(type, detail) {
    for (const handler of [...(this.listeners.get(type) || [])]) {
      await handler(detail);
    }
  }

  headers(extra = {}, json = true) {
    return {
      Accept: 'application/json',
      ...(json ? { 'Content-Type': 'application/json' } : {}),
      ...extra,
    };
  }

  async parseResponse(response) {
    if (response.status === 204) return null;
    const contentType = response.headers?.get?.('content-type') || '';
    if (!contentType.includes('application/json')) return response.text();
    const payload = await response.json();
    if (payload && typeof payload.ok === 'boolean' && ('data' in payload || 'error' in payload)) {
      if (!payload.ok) {
        const error = new Error(payloadMessage(payload, 'Local runtime request failed.'));
        error.code = payload.error?.code || '';
        error.retryable = payload.error?.retryable === true;
        error.requestId = payload.requestId || '';
        error.nativeRejection = typeof payload.error?.code === 'string' && payload.error.code.length > 0
          && typeof payload.error?.message === 'string';
        throw error;
      }
      return payload.data ?? null;
    }
    return payload;
  }

  async request(path, options = {}) {
    if (!this.fetchImpl) throw new Error('Fetch API is not available.');
    const response = await this.fetchImpl(`${this.origin}${path}`, {
      ...options,
      credentials: 'include',
      // AndroidAsync selects its body parser from Content-Type, even for GET.
      // Bodyless reads must not advertise an empty JSON document.
      headers: this.headers(options.headers, options.json !== false && options.body !== undefined),
    });
    const payload = await this.parseResponse(response);
    if (!response.ok) {
      throw new Error(payloadMessage(payload, `Local runtime request failed (${response.status}).`));
    }
    return payload;
  }

  getHealth() {
    return this.request('/health', { method: 'GET' });
  }

  async bootstrap(bootstrapToken) {
    if (!bootstrapToken) throw new Error('缺少一次性 Renderer bootstrap token。');
    const payload = await this.request(`${LOCAL_API_PREFIX}/bootstrap`, {
      method: 'POST',
      body: JSON.stringify({ clientVersion: CLIENT_VERSION, bootstrapToken }),
    });
    if (payload?.protocolVersion !== '2.0') {
      throw new Error('Local Runtime protocol version must be 2.0.');
    }
    this.bootstrapped = true;
    return payload;
  }

  getRuntimeSettings() {
    return this.request(`${LOCAL_API_PREFIX}/settings`, { method: 'GET' });
  }

  putRuntimeSettings(settings) {
    return this.request(`${LOCAL_API_PREFIX}/settings`, {
      method: 'PUT',
      body: JSON.stringify(settings),
    });
  }

  setupRuntimeSettings(settings) {
    return this.request(`${LOCAL_API_PREFIX}/settings/setup`, {
      method: 'POST',
      body: JSON.stringify(settings),
    });
  }

  testRuntimeSettings(settings) {
    return this.request(`${LOCAL_API_PREFIX}/settings/test`, {
      method: 'POST',
      body: JSON.stringify(settings),
    });
  }

  getRuntimeStatus() {
    return this.request(`${LOCAL_API_PREFIX}/status`, { method: 'GET' });
  }

  setMotionEnabled(enabled) {
    return this.request(`${LOCAL_API_PREFIX}/motion`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    });
  }

  async deviceRequest(path, options = {}, timeoutMs = 2500) {
    const abort = new AbortController();
    const timeout = this.setTimeoutImpl(() => abort.abort(), timeoutMs);
    try {
      return await this.request(`${LOCAL_API_PREFIX}/device${path}`, { ...options, signal: abort.signal });
    } finally { this.clearTimeoutImpl(timeout); }
  }

  getDeviceStatus() {
    return this.deviceRequest('/status', { method: 'GET' });
  }

  putDeviceSettings(settings) {
    return this.deviceRequest('/settings', {
      method: 'PUT', body: JSON.stringify(settings),
    });
  }

  async sendDeviceAction(action, options = {}) {
    try {
      const result = await this.deviceRequest('/action', {
        method: 'POST', body: JSON.stringify({ action }), ...options,
      }, DEVICE_ACTION_TIMEOUT_MS[action] || 2500);
      if (result?.accepted !== true) throw new Error('機器動作回應格式無效。');
      return result;
    } catch (error) {
      // A missing/invalid reply cannot prove the physical action did not start.
      // Stop once under its own deadline; never replay the original action.
      if (!error.nativeRejection) {
        try {
          await this.deviceRequest('/action', {
            method: 'POST', body: JSON.stringify({ action: 'stop' }),
          }, DEVICE_ACTION_TIMEOUT_MS.stop);
        } catch { /* Native safety still owns an unconfirmed stop. */ }
      }
      throw error;
    }
  }

  setDeviceAttention(phase) {
    return this.deviceRequest('/attention', {
      method: 'PUT', body: JSON.stringify({ phase }),
    });
  }

  setRemoteEnabled(enabled) {
    return this.deviceRequest('/remote', {
      method: 'PUT', body: JSON.stringify({ enabled }),
    });
  }

  captureCamera() {
    return this.deviceRequest('/camera/capture', {
      method: 'POST', body: JSON.stringify({}),
    }, 15000);
  }

  async getCameraImage(artifactId = '', options = {}) {
    const path = artifactId ? encodeURIComponent(artifactId) : 'frame';
    const response = await this.fetchImpl(`${this.origin}${LOCAL_API_PREFIX}/device/camera/${path}`, {
      method: 'GET', credentials: 'include', cache: 'no-store',
      headers: { Accept: 'image/jpeg' }, ...options,
    });
    if (!response.ok) {
      await this.parseResponse(response);
      throw new Error(`無法取得相機畫面（${response.status}）。`);
    }
    const blob = await response.blob();
    if (blob.type !== 'image/jpeg' || blob.size > 2 * 1024 * 1024 || !blob.size) {
      throw new Error('相機回傳的影像格式或大小無效。');
    }
    return blob;
  }

  startNewSession(idempotencyKey = this.randomUuidImpl()) {
    return this.request(`${LOCAL_API_PREFIX}/conversation/new-session`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({}),
    });
  }

  async getConversation() {
    const payload = await this.request(`${LOCAL_API_PREFIX}/conversation`, { method: 'GET' });
    if (payload?.sessionId) {
      this.sessionId = payload.sessionId;
    }
    this.cursor = Number(payload?.lastSequence || 0);
    return payload || {};
  }

  connectEvents({ after = this.cursor } = {}) {
    if (!this.WebSocketImpl) throw new Error('WebSocket API is not available.');
    this.closedByClient = false;
    this.clearReconnect();
    if (
      this.socket &&
      (this.socket.readyState === this.WebSocketImpl.OPEN ||
        this.socket.readyState === this.WebSocketImpl.CONNECTING)
    ) {
      return;
    }

    const query = new URLSearchParams();
    if (after) query.set('after', String(after));
    const queryString = query.toString();
    const suffix = queryString ? `?${queryString}` : '';
    const url = `${asWsOrigin(this.origin)}${LOCAL_API_PREFIX}/events${suffix}`;
    const socket = new this.WebSocketImpl(url);
    const generation = ++this.socketGeneration;
    let eventQueue = this.eventQueueTail.catch(() => {});
    this.socket = socket;

    const enqueueSocketWork = (work) => {
      eventQueue = eventQueue
        .then(async () => {
          if (this.socket !== socket || this.socketGeneration !== generation) return;
          await work();
        })
        .catch((error) => {
          if (this.socket !== socket || this.socketGeneration !== generation) return;
          this.socketGeneration += 1;
          if (error?.protocolError) this.emit('protocolError', error);
          this.emit('error', error);
          socket.close(
            error?.protocolError ? 1002 : 1011,
            error?.protocolError ? 'invalid event' : 'event handler failed',
          );
        });
      this.eventQueueTail = eventQueue;
    };

    socket.onopen = () => {
      if (this.socket !== socket) return;
      this.reconnectAttempt = 0;
      enqueueSocketWork(() => this.emitAsync('runtimeConnected', { connected: true }));
    };
    socket.onmessage = (event) => {
      const frame = event.data;
      enqueueSocketWork(async () => {
        let envelope;
        try {
          envelope = JSON.parse(frame);
        } catch (cause) {
          const error = new Error(`Invalid runtime event: ${cause.message}`);
          error.protocolError = true;
          throw error;
        }
        await this.emitAsync('event', envelope);
      });
    };
    socket.onerror = () => {
      if (this.socket === socket && this.socketGeneration === generation) {
        this.emit('error', new Error('Local runtime WebSocket error.'));
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      if (this.socketGeneration === generation) this.socketGeneration += 1;
      this.socket = null;
      if (this.closedByClient) {
        this.emit('connection', { state: 'OFFLINE' });
      } else {
        this.emit('connection', { state: 'DEGRADED' });
        this.scheduleReconnect();
      }
    };
  }

  acknowledge(sequence) {
    if (Number.isInteger(sequence) && sequence > this.cursor) this.cursor = sequence;
  }

  resetCursor(sequence) {
    if (Number.isInteger(sequence) && sequence >= 0) this.cursor = sequence;
  }

  scheduleReconnect() {
    this.clearReconnect();
    const index = Math.min(this.reconnectAttempt, this.reconnectDelays.length - 1);
    const baseDelay = this.reconnectDelays[index];
    const jitter = (this.randomImpl() * 2 - 1) * this.reconnectJitter;
    const delay = Math.max(0, Math.round(baseDelay * (1 + jitter)));
    this.reconnectAttempt += 1;
    this.reconnectTimer = this.setTimeoutImpl?.(() => {
      this.reconnectTimer = null;
      this.connectEvents({ after: this.cursor });
    }, delay);
  }

  clearReconnect() {
    if (this.reconnectTimer !== null) this.clearTimeoutImpl?.(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  failProtocol(error) {
    const cause = error instanceof Error ? error : new Error(String(error));
    this.emit('protocolError', cause);
    this.emit('error', cause);
    this.socketGeneration += 1;
    this.socket?.close(1002, 'invalid event sequence');
  }

  async uploadVoiceTurn({ turnId, audio, language = 'zh-TW', durationMs }) {
    if (!this.fetchImpl) throw new Error('Fetch API is not available.');
    if (!(audio instanceof Blob) || audio.type !== 'audio/wav') {
      throw new Error('Voice turn audio must be an audio/wav Blob.');
    }
    if (audio.size > MAX_VOICE_BYTES) throw new Error('Voice turn exceeds the 2 MiB limit.');
    const normalizedDuration = durationMs || wavDurationMs(audio.size);
    if (normalizedDuration > MAX_VOICE_DURATION_MS) {
      throw new Error('Voice turn exceeds the 30 second limit.');
    }
    const form = new FormData();
    form.set('clientTurnId', turnId);
    form.set('durationMs', String(normalizedDuration));
    form.set('language', language);
    form.set('audio', audio, `${turnId}.wav`);
    const response = await this.fetchImpl(`${this.origin}${LOCAL_API_PREFIX}/conversation/turns`, {
      method: 'POST',
      credentials: 'include',
      headers: this.headers({ 'Idempotency-Key': turnId }, false),
      body: form,
    });
    const payload = await this.parseResponse(response);
    if (!response.ok) {
      throw new Error(payloadMessage(payload, `Voice turn upload failed (${response.status}).`));
    }
    return payload;
  }

  submitTextTurn({ turnId, text, language = 'zh-TW' }) {
    return this.request(`${LOCAL_API_PREFIX}/conversation/turns`, {
      method: 'POST',
      headers: { 'Idempotency-Key': turnId },
      body: JSON.stringify({ clientTurnId: turnId, text, language }),
    });
  }

  cancelTurn(turnId, reason = 'user_interaction', idempotencyKey = this.randomUuidImpl()) {
    return this.request(`${LOCAL_API_PREFIX}/conversation/cancel`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ ...(turnId ? { turnId } : {}), reason }),
    }).catch(() => null);
  }

  async sendToolResult(result) {
    const statusMap = { success: 'succeeded', error: 'failed', rejected: 'rejected' };
    const body = {
      status: statusMap[result.status] || result.status,
      updatedAt: new Date().toISOString(),
      ...(result.status === 'success' ? { output: result.result } : {}),
      ...(result.error ? { error: result.error } : {}),
    };
    return this.request(
      `${LOCAL_API_PREFIX}/conversation/tool-calls/${encodeURIComponent(result.callId)}`,
      {
        method: 'PUT',
        headers: { 'Idempotency-Key': result.callId },
        body: JSON.stringify(body),
      },
    );
  }

  stableIdempotencyKey(scope, value) {
    const mapKey = `${scope}:${value}`;
    const now = this.nowImpl();
    const existing = this.idempotencyKeys.get(mapKey);
    if (existing && existing.expiresAt > now) return existing.value;
    const idempotencyKey = this.randomUuidImpl();
    this.idempotencyKeys.set(mapKey, { value: idempotencyKey, expiresAt: now + 5 * 60 * 1000 });
    if (this.idempotencyKeys.size > 200) this.idempotencyKeys.delete(this.idempotencyKeys.keys().next().value);
    return idempotencyKey;
  }

  async sendPlayback(status, turnId, extra = {}) {
    const body = {
      turnId,
      artifactId: extra.artifactId,
      status,
      timestamp: new Date().toISOString(),
      ...(extra.reason ? { reason: extra.reason } : {}),
    };
    return this.request(`${LOCAL_API_PREFIX}/conversation/playback`, {
      method: 'POST',
      headers: {
        'Idempotency-Key': this.stableIdempotencyKey(
          'playback',
          `${turnId}:${extra.artifactId}:${status}`,
        ),
      },
      body: JSON.stringify(body),
    });
  }

  async resolveAudio(payload = {}) {
    if (!payload.artifactId) throw new Error('Playback event contains no local audio artifact.');
    if (!this.fetchImpl) throw new Error('Fetch API is not available.');

    const path = `${LOCAL_API_PREFIX}/conversation/audio/${encodeURIComponent(payload.artifactId)}`;
    const url = `${this.origin}${path}`;
    const response = await this.fetchImpl(url, {
      credentials: 'include',
      headers: this.headers({}, false),
    });
    if (!response.ok) throw new Error(`Audio download failed (${response.status}).`);
    const blob = await response.blob();
    if (blob.size > MAX_AUDIO_BYTES) throw new Error('Audio artifact exceeds the 10 MiB limit.');
    if (payload.byteLength && blob.size !== payload.byteLength) {
      throw new Error('Audio artifact size does not match metadata.');
    }
    if (payload.mimeType && blob.type && blob.type !== payload.mimeType) {
      throw new Error('Audio artifact content type does not match metadata.');
    }
    if (payload.sha256) {
      const actualDigest = await sha256Hex(blob);
      if (!actualDigest) throw new Error('This renderer cannot validate the audio artifact digest.');
      if (actualDigest !== payload.sha256.toLowerCase()) {
        throw new Error('Audio artifact digest does not match metadata.');
      }
    }
    return blob;
  }

  close() {
    this.closedByClient = true;
    this.clearReconnect();
    this.socketGeneration += 1;
    if (this.socket) {
      this.socket.close(1000, 'client shutdown');
      this.socket = null;
    }
  }
}
