import { createHash, randomBytes } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';

import { encodeWebSocketFrame, WebSocketFrameDecoder } from './websocket.mjs';

const WEBSOCKET_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const DEFAULT_TIMEOUT_MS = 15_000;

function normalizedBaseUrl(value) {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new TypeError('Gateway base URL must use http or https');
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new TypeError('Gateway base URL must not contain credentials, a query, or a fragment');
  }
  url.pathname = url.pathname.replace(/\/+$/, '');
  if (!url.pathname || url.pathname === '/') url.pathname = '/agent/v1';
  if (!url.pathname.endsWith('/agent/v1')) {
    throw new TypeError('Gateway base URL must be an origin or end with /agent/v1');
  }
  return url.toString().replace(/\/$/, '');
}

export async function responseJson(response) {
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export class TestWebSocket {
  constructor(socket, head) {
    this.socket = socket;
    this.messages = [];
    this.waiters = [];
    this.closed = false;
    this.decoder = new WebSocketFrameDecoder({
      requireMasked: false,
      onFrame: ({ opcode, payload }) => {
        if (opcode === 0x1) {
          this.messages.push(JSON.parse(payload.toString('utf8')));
          this.#drain();
        } else if (opcode === 0x8) {
          this.closed = true;
        } else if (opcode === 0x9 && !socket.destroyed) {
          socket.write(encodeWebSocketFrame(payload, { opcode: 0xa, masked: true }));
        }
      },
      onError: (error) => this.#fail(error),
    });
    socket.on('data', (chunk) => this.decoder.push(chunk));
    socket.once('close', () => {
      this.closed = true;
      this.#fail(new Error('WebSocket closed before expected messages arrived'));
    });
    socket.once('error', (error) => this.#fail(error));
    if (head?.length) this.decoder.push(head);
  }

  take(count, timeoutMs = DEFAULT_TIMEOUT_MS) {
    if (this.messages.length >= count) return Promise.resolve(this.messages.splice(0, count));
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        const index = this.waiters.findIndex((waiter) => waiter.resolve === resolve);
        if (index >= 0) this.waiters.splice(index, 1);
        reject(new Error(`Timed out waiting for ${count} WebSocket messages`));
      }, timeoutMs);
      this.waiters.push({ count, resolve, reject, timeout });
    });
  }

  async takeUntil(predicate, timeoutMs = DEFAULT_TIMEOUT_MS) {
    const deadline = Date.now() + timeoutMs;
    while (true) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new Error('Timed out waiting for the expected WebSocket message');
      const [message] = await this.take(1, remaining);
      if (predicate(message)) return message;
    }
  }

  destroy() {
    this.closed = true;
    this.socket.destroy();
  }

  #drain() {
    while (this.waiters.length && this.messages.length >= this.waiters[0].count) {
      const waiter = this.waiters.shift();
      clearTimeout(waiter.timeout);
      waiter.resolve(this.messages.splice(0, waiter.count));
    }
  }

  #fail(error) {
    if (!this.waiters.length) return;
    for (const waiter of this.waiters.splice(0)) {
      clearTimeout(waiter.timeout);
      waiter.reject(error);
    }
  }
}

export function openWebSocket(url, headers, { timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const requestFn = target.protocol === 'https:' ? httpsRequest : httpRequest;
    const webSocketKey = randomBytes(16).toString('base64');
    const request = requestFn({
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method: 'GET',
      headers: {
        ...headers,
        Connection: 'Upgrade',
        Upgrade: 'websocket',
        'Sec-WebSocket-Key': webSocketKey,
        'Sec-WebSocket-Version': '13',
      },
    });
    request.setTimeout(timeoutMs, () => request.destroy(new Error('WebSocket upgrade timed out')));
    request.once('upgrade', (response, socket, head) => {
      socket.setTimeout(0);
      const expectedAccept = createHash('sha1')
        .update(`${webSocketKey}${WEBSOCKET_GUID}`)
        .digest('base64');
      if (response.headers['sec-websocket-accept'] !== expectedAccept) {
        socket.destroy();
        reject(new Error('Gateway returned an invalid WebSocket accept header'));
        return;
      }
      resolve({ status: response.statusCode, client: new TestWebSocket(socket, head), body: null });
    });
    request.once('response', (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.once('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let body = null;
        if (text) {
          try {
            body = JSON.parse(text);
          } catch {
            body = text;
          }
        }
        resolve({ status: response.statusCode, client: null, body });
      });
    });
    request.once('error', reject);
    request.end();
  });
}

export class GatewayClient {
  constructor({ baseUrl, deviceToken, deviceId, protocolVersion = '1.0', timeoutMs } = {}) {
    if (!baseUrl) throw new TypeError('Gateway base URL is required');
    if (!deviceToken) throw new TypeError('Gateway device token is required');
    if (!deviceId) throw new TypeError('Gateway device ID is required');
    this.baseUrl = normalizedBaseUrl(baseUrl);
    this.deviceToken = deviceToken;
    this.deviceId = deviceId;
    this.protocolVersion = protocolVersion;
    this.timeoutMs = timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  authHeaders(overrides = {}) {
    return {
      Authorization: `Bearer ${this.deviceToken}`,
      'X-Zenbo-Device-Id': this.deviceId,
      'X-Zenbo-Protocol': this.protocolVersion,
      ...overrides,
    };
  }

  async request(path, {
    method = 'GET',
    body,
    headers = {},
    authenticated = true,
  } = {}) {
    const hasBody = body !== undefined;
    return fetch(new URL(`${this.baseUrl}${path}`), {
      method,
      headers: {
        ...(authenticated ? this.authHeaders() : {}),
        ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      ...(hasBody ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
  }

  openEvents(sessionId, after = 0, headers = this.authHeaders()) {
    const url = new URL(`${this.baseUrl}/sessions/${sessionId}/events`);
    url.searchParams.set('after', String(after));
    return openWebSocket(url, headers, { timeoutMs: this.timeoutMs });
  }
}
