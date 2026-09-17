import { describe, expect, it, vi } from 'vitest';
import {
  consumeBootstrapToken,
  LOCAL_API_PREFIX,
  RuntimeTransport,
  wavDurationMs,
} from './runtimeTransport';

function jsonResponse(body, status = 200, wrapped = true) {
  const payload = wrapped
    ? { ok: status >= 200 && status < 300, requestId: 'request-1', data: body, error: null }
    : body;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => payload,
    text: async () => JSON.stringify(payload),
  };
}

class FakeWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static instances = [];

  constructor(url) {
    this.url = url;
    this.readyState = FakeWebSocket.CONNECTING;
    this.sent = [];
    this.close = vi.fn((code, reason) => {
      this.closeCode = code;
      this.closeReason = reason;
      this.onclose?.();
    });
    FakeWebSocket.instances.push(this);
  }

  open() {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }

  send(value) {
    this.sent.push(value);
  }
}

describe('RuntimeTransport', () => {
  it('requires Local Runtime 2.0 at the loopback bootstrap boundary', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ protocolVersion: '1.0' }));
    const transport = new RuntimeTransport({ fetchImpl });
    await expect(transport.bootstrap('bootstrap-secret')).rejects.toThrow('2.0');
    expect(transport.bootstrapped).toBe(false);
    expect(fetchImpl.mock.calls[0][0]).toBe('http://127.0.0.1:8787/api/v2/bootstrap');
  });
  it('exchanges the one-time fragment secret for an HttpOnly cookie session', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ protocolVersion: '2.0', expiresAt: 1234 }))
      .mockResolvedValueOnce(jsonResponse({ state: 'ready' }))
      .mockResolvedValueOnce(jsonResponse({ sessionId: 'remote-1', lastSequence: 8 }));
    const transport = new RuntimeTransport({ origin: 'http://127.0.0.1:8787/', fetchImpl });

    await transport.bootstrap('one-time-secret');
    await transport.getRuntimeStatus();
    await transport.getConversation();

    expect(fetchImpl.mock.calls.map(([url]) => url)).toEqual([
      `http://127.0.0.1:8787${LOCAL_API_PREFIX}/bootstrap`,
      `http://127.0.0.1:8787${LOCAL_API_PREFIX}/status`,
      `http://127.0.0.1:8787${LOCAL_API_PREFIX}/conversation`,
    ]);
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({
      clientVersion: '0.1.0',
      bootstrapToken: 'one-time-secret',
    });
    expect(fetchImpl.mock.calls.every(([, request]) => request.credentials === 'include')).toBe(true);
    expect(fetchImpl.mock.calls[1][1].headers).not.toHaveProperty('X-Zenbo-Device-Id');
    expect(fetchImpl.mock.calls[1][1].headers).not.toHaveProperty('X-Zenbo-Protocol');
    expect(fetchImpl.mock.calls[1][1].headers).not.toHaveProperty('X-Zenbo-Session');
    expect(transport.bootstrapped).toBe(true);
    expect(transport.cursor).toBe(8);
  });

  it('consumes and clears the bootstrap secret before network use', () => {
    const location = {
      hash: '#bootstrapToken=abc_123-token',
      pathname: '/',
      search: '?mode=launcher',
    };
    const history = { replaceState: vi.fn() };

    expect(consumeBootstrapToken(location, history)).toBe('abc_123-token');
    expect(history.replaceState).toHaveBeenCalledWith(null, '', '/?mode=launcher');
  });

  it('unwraps uniform local errors without leaking the response envelope', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      headers: { get: () => 'application/json' },
      json: async () => ({
        ok: false,
        requestId: '00000000-0000-4000-8000-000000000099',
        data: null,
        error: { code: 'UNAUTHORIZED', message: 'Renderer session expired.', retryable: false },
      }),
    });
    const transport = new RuntimeTransport({ origin: 'http://127.0.0.1:8787', fetchImpl });

    await expect(transport.getRuntimeStatus()).rejects.toMatchObject({
      message: 'Renderer session expired.',
      code: 'UNAUTHORIZED',
      requestId: '00000000-0000-4000-8000-000000000099',
    });
  });

  it('uploads voice turns as authenticated multipart WAV data', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ accepted: true }, 202));
    const transport = new RuntimeTransport({ origin: 'http://127.0.0.1:8787', fetchImpl });
    const audio = new Blob([new Uint8Array(44 + 32000)], { type: 'audio/wav' });

    await transport.uploadVoiceTurn({ turnId: 'turn-1', audio, language: 'zh-TW' });

    const [url, request] = fetchImpl.mock.calls[0];
    expect(url).toBe(`http://127.0.0.1:8787${LOCAL_API_PREFIX}/conversation/turns`);
    expect(request.body).toBeInstanceOf(FormData);
    expect(request.body.get('clientTurnId')).toBe('turn-1');
    expect(request.body.get('durationMs')).toBe('1000');
    expect(request.body.get('audio').type).toBe('audio/wav');
    expect(request.credentials).toBe('include');
    expect(request.headers['Idempotency-Key']).toBe('turn-1');
    expect(request.headers).not.toHaveProperty('X-Zenbo-Session');
    expect(request.headers).not.toHaveProperty('Content-Type');
  });

  it('uses the client turn UUID as the text-turn idempotency key', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ accepted: true }, 202));
    const transport = new RuntimeTransport({ origin: 'http://127.0.0.1:8787', fetchImpl });

    await transport.submitTextTurn({
      turnId: '00000000-0000-4000-8000-000000000010',
      text: '你好',
      language: 'zh-TW',
    });

    const request = fetchImpl.mock.calls[0][1];
    expect(request.headers['Idempotency-Key']).toBe('00000000-0000-4000-8000-000000000010');
    expect(JSON.parse(request.body)).toEqual({
      clientTurnId: '00000000-0000-4000-8000-000000000010',
      text: '你好',
      language: 'zh-TW',
    });
  });

  it('resumes the event stream from the durable cursor with bounded reconnect backoff', () => {
    FakeWebSocket.instances = [];
    const scheduled = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
      reconnectDelays: [500],
      reconnectJitter: 0,
      setTimeoutImpl: (callback, delay) => {
        scheduled.push({ callback, delay });
        return scheduled.length;
      },
      clearTimeoutImpl: vi.fn(),
    });
    transport.cursor = 7;

    transport.connectEvents();
    expect(FakeWebSocket.instances[0].url).toBe('ws://127.0.0.1:8787/api/v2/events?after=7');
    FakeWebSocket.instances[0].open();
    transport.acknowledge(8);
    FakeWebSocket.instances[0].onclose();

    expect(scheduled[0].delay).toBe(500);
    scheduled[0].callback();
    expect(FakeWebSocket.instances[1].url).toContain('after=8');
  });

  it('does not treat a local WebSocket open as Gateway READY', async () => {
    FakeWebSocket.instances = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    const gatewayChanges = vi.fn();
    const runtimeConnected = vi.fn();
    transport.on('connection', gatewayChanges);
    transport.on('runtimeConnected', runtimeConnected);

    transport.connectEvents();
    FakeWebSocket.instances[0].open();

    await vi.waitFor(() => {
      expect(runtimeConnected).toHaveBeenCalledWith({ connected: true });
    });
    expect(gatewayChanges).not.toHaveBeenCalledWith({ state: 'READY' });
    transport.close();
  });

  it('finishes runtime reconnect synchronization before dispatching replay frames', async () => {
    FakeWebSocket.instances = [];
    let releaseRefresh;
    const refreshGate = new Promise((resolve) => {
      releaseRefresh = resolve;
    });
    const order = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    transport.on('runtimeConnected', async () => {
      order.push('refresh-start');
      await refreshGate;
      order.push('refresh-end');
    });
    transport.on('event', async () => {
      order.push('event');
    });

    transport.connectEvents();
    const socket = FakeWebSocket.instances[0];
    socket.open();
    socket.onmessage({ data: JSON.stringify({ type: 'agent.thinking', sequence: 1, data: {} }) });
    await vi.waitFor(() => expect(order).toEqual(['refresh-start']));
    releaseRefresh();
    await vi.waitFor(() => expect(order).toEqual(['refresh-start', 'refresh-end', 'event']));
    transport.close();
  });

  it('serializes fast event frames until the prior async handler commits its cursor', async () => {
    FakeWebSocket.instances = [];
    let releaseFirst;
    let finishSecond;
    const firstGate = new Promise((resolve) => {
      releaseFirst = resolve;
    });
    const secondFinished = new Promise((resolve) => {
      finishSecond = resolve;
    });
    const order = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    transport.on('event', async (envelope) => {
      order.push(`start-${envelope.sequence}`);
      if (envelope.sequence === 1) await firstGate;
      if (envelope.sequence !== transport.cursor + 1) throw new Error('sequence gap');
      transport.acknowledge(envelope.sequence);
      order.push(`end-${envelope.sequence}`);
      if (envelope.sequence === 2) finishSecond();
    });

    transport.connectEvents();
    const socket = FakeWebSocket.instances[0];
    socket.onmessage({ data: JSON.stringify({ type: 'stt.final', sequence: 1, data: {} }) });
    socket.onmessage({ data: JSON.stringify({ type: 'agent.thinking', sequence: 2, data: {} }) });
    await Promise.resolve();
    await Promise.resolve();

    expect(order).toEqual(['start-1']);
    releaseFirst();
    await secondFinished;
    expect(order).toEqual(['start-1', 'end-1', 'start-2', 'end-2']);
    expect(transport.cursor).toBe(2);
    expect(socket.close).not.toHaveBeenCalled();
  });

  it('queues an event behind an authoritative snapshot cursor reset', async () => {
    FakeWebSocket.instances = [];
    let releaseSnapshot;
    let finishNext;
    const snapshotGate = new Promise((resolve) => {
      releaseSnapshot = resolve;
    });
    const nextFinished = new Promise((resolve) => {
      finishNext = resolve;
    });
    const order = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    transport.cursor = 4;
    transport.on('event', async (envelope) => {
      order.push(envelope.type);
      if (envelope.type === 'session.snapshot') {
        await snapshotGate;
        transport.resetCursor(envelope.data.lastSequence);
        return;
      }
      if (envelope.sequence !== transport.cursor + 1) throw new Error('sequence gap');
      transport.acknowledge(envelope.sequence);
      finishNext();
    });

    transport.connectEvents();
    const socket = FakeWebSocket.instances[0];
    socket.onmessage({
      data: JSON.stringify({
        type: 'session.snapshot',
        sequence: 10,
        data: { lastSequence: 10 },
      }),
    });
    socket.onmessage({
      data: JSON.stringify({ type: 'agent.thinking', sequence: 11, data: {} }),
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(order).toEqual(['session.snapshot']);
    releaseSnapshot();
    await nextFinished;
    expect(order).toEqual(['session.snapshot', 'agent.thinking']);
    expect(transport.cursor).toBe(11);
    expect(socket.close).not.toHaveBeenCalled();
  });

  it('drops queued frames from an old socket generation before processing the new socket', async () => {
    FakeWebSocket.instances = [];
    let releaseOld;
    let finishNew;
    const oldGate = new Promise((resolve) => {
      releaseOld = resolve;
    });
    const newFinished = new Promise((resolve) => {
      finishNew = resolve;
    });
    const order = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    transport.on('event', async (envelope) => {
      order.push(`start-${envelope.sequence}`);
      if (envelope.sequence === 1) await oldGate;
      order.push(`end-${envelope.sequence}`);
      if (envelope.sequence === 3) finishNew();
    });

    transport.connectEvents();
    const oldSocket = FakeWebSocket.instances[0];
    oldSocket.onmessage({ data: JSON.stringify({ type: 'stt.final', sequence: 1, data: {} }) });
    oldSocket.onmessage({ data: JSON.stringify({ type: 'agent.thinking', sequence: 2, data: {} }) });
    await Promise.resolve();
    await Promise.resolve();
    transport.close();
    transport.connectEvents();
    const newSocket = FakeWebSocket.instances[1];
    newSocket.onmessage({ data: JSON.stringify({ type: 'agent.thinking', sequence: 3, data: {} }) });

    releaseOld();
    await newFinished;
    expect(order).toEqual(['start-1', 'end-1', 'start-3', 'end-3']);
  });

  it('closes malformed frames with 1002 and failed async handlers with 1011', async () => {
    FakeWebSocket.instances = [];
    const malformed = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    malformed.connectEvents();
    const malformedSocket = FakeWebSocket.instances[0];
    malformedSocket.onmessage({ data: '{not-json' });
    await vi.waitFor(() => {
      expect(malformedSocket.close).toHaveBeenCalledWith(1002, 'invalid event');
    });
    malformed.close();

    FakeWebSocket.instances = [];
    const failedHandler = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    failedHandler.on('event', async () => {
      throw new Error('handler failed');
    });
    failedHandler.connectEvents();
    const failedSocket = FakeWebSocket.instances[0];
    failedSocket.onmessage({ data: JSON.stringify({ type: 'stt.final', sequence: 1, data: {} }) });
    await vi.waitFor(() => {
      expect(failedSocket.close).toHaveBeenCalledWith(1011, 'event handler failed');
    });
    failedHandler.close();
  });

  it('uses dedicated setup/unlock endpoints and never sends PINs with settings PUT', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ unlocked: true }));
    const transport = new RuntimeTransport({ origin: 'http://127.0.0.1:8787', fetchImpl });

    await transport.setupRuntimeSettings({
      pin: '123456',
      confirmPin: '123456',
      gatewayUrl: 'https://gateway.example',
      apiKey: 'fixture-device-token',
      trustMode: 'SYSTEM_TRUST',
      context: { robotName: 'Zenbo K', language: 'zh-TW' },
    });
    await transport.unlockRuntimeSettings({ pin: '123456' });
    await transport.putRuntimeSettings({ gatewayUrl: 'https://gateway.example' });

    expect(fetchImpl.mock.calls.map(([url]) => url)).toEqual([
      'http://127.0.0.1:8787/api/v2/settings/setup',
      'http://127.0.0.1:8787/api/v2/settings/unlock',
      'http://127.0.0.1:8787/api/v2/settings',
    ]);
    expect(JSON.parse(fetchImpl.mock.calls[2][1].body)).not.toHaveProperty('pin');
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toMatchObject({
      pin: '123456',
      trustMode: 'SYSTEM_TRUST',
      context: { robotName: 'Zenbo K', language: 'zh-TW' },
    });
  });

  it('sends emergency cancel without inventing a turn id', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ cancelled: true }));
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl,
      randomUuidImpl: () => '00000000-0000-4000-8000-000000000001',
    });

    await transport.cancelTurn('', 'screen_off');

    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ reason: 'screen_off' });
    expect(fetchImpl.mock.calls[0][1].headers['Idempotency-Key']).toBe(
      '00000000-0000-4000-8000-000000000001',
    );
  });

  it('reuses a stable UUID for replayed playback tuple updates', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ stored: true }));
    const randomUuidImpl = vi.fn(() => '00000000-0000-4000-8000-000000000002');
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl,
      randomUuidImpl,
    });

    await transport.sendPlayback('started', 'turn-1', { artifactId: 'artifact-1' });
    await transport.sendPlayback('started', 'turn-1', { artifactId: 'artifact-1' });

    expect(fetchImpl.mock.calls[0][1].headers['Idempotency-Key']).toBe(
      '00000000-0000-4000-8000-000000000002',
    );
    expect(fetchImpl.mock.calls[1][1].headers['Idempotency-Key']).toBe(
      '00000000-0000-4000-8000-000000000002',
    );
    expect(randomUuidImpl).toHaveBeenCalledOnce();
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toMatchObject({
      turnId: 'turn-1',
      artifactId: 'artifact-1',
      status: 'started',
    });
  });

  it('downloads audio only through the authenticated local artifact relay', async () => {
    const audio = new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/mpeg' });
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200, blob: async () => audio });
    const transport = new RuntimeTransport({ origin: 'http://127.0.0.1:8787', fetchImpl });

    await expect(transport.resolveAudio({ audioUrl: 'https://remote.example/audio' })).rejects.toThrow(
      'no local audio artifact',
    );
    expect(fetchImpl).not.toHaveBeenCalled();

    await expect(
      transport.resolveAudio({ artifactId: 'artifact-1', mimeType: 'audio/mpeg', byteLength: 3 }),
    ).resolves.toBe(audio);
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://127.0.0.1:8787/api/v2/conversation/audio/artifact-1',
      expect.objectContaining({ credentials: 'include' }),
    );
  });

  it('closes a corrupt stream with the WebSocket protocol-error code', () => {
    FakeWebSocket.instances = [];
    const transport = new RuntimeTransport({
      origin: 'http://127.0.0.1:8787',
      fetchImpl: vi.fn(),
      WebSocketImpl: FakeWebSocket,
    });
    const protocolError = vi.fn();
    transport.on('protocolError', protocolError);
    transport.connectEvents();

    transport.failProtocol(new Error('sequence gap'));

    expect(protocolError).toHaveBeenCalledOnce();
    expect(FakeWebSocket.instances[0].close).toHaveBeenCalledWith(1002, 'invalid event sequence');
  });
});

describe('wavDurationMs', () => {
  it('derives duration from mono 16-bit PCM bytes', () => {
    expect(wavDurationMs(44 + 16000 * 2)).toBe(1000);
  });
});
