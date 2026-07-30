import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { openWebSocket as openGatewayWebSocket } from './client.mjs';
import {
  CAPABILITIES,
  createFixtureWav,
  FakeAgentGateway,
  FIXTURE_DEVICE_ID,
  FIXTURE_DEVICE_TOKEN,
  parseCliArguments,
  PROTOCOL_VERSION,
} from './server.mjs';

const ENVELOPE_FIELDS = [
  'data',
  'eventId',
  'protocolVersion',
  'sequence',
  'sessionId',
  'timestamp',
  'turnId',
  'type',
];
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const fixtureToolManifest = {
  protocolVersion: '1.0',
  manifestVersion: 'fake-gateway-test-1',
  tools: [
    {
      name: 'get_system_status',
      owner: 'native',
      version: '1.0.0',
      description: 'Return deterministic fake robot status.',
      inputSchema: {
        type: 'object',
        properties: {},
        required: [],
        additionalProperties: false,
      },
      resultSchema: {
        type: 'object',
        properties: { ready: { type: 'boolean' } },
        required: ['ready'],
        additionalProperties: false,
      },
      sideEffect: 'none',
      idempotent: true,
      requiresConfirmation: false,
      timeoutMs: 5000,
    },
  ],
};
const emotionToolManifest = {
  protocolVersion: '1.0',
  manifestVersion: 'fake-gateway-emotion-1',
  tools: [
    {
      name: 'show_emotion',
      owner: 'web',
      version: '1.0.0',
      description: 'Display one allowlisted robot emotion.',
      inputSchema: {
        type: 'object',
        properties: {
          emotion: {
            type: 'string',
            enum: ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED'],
          },
          durationMs: { type: 'integer', minimum: 0, maximum: 30000 },
        },
        required: ['emotion'],
        additionalProperties: false,
      },
      resultSchema: {
        type: 'object',
        properties: {
          ok: { type: 'boolean' },
          emotion: {
            type: 'string',
            enum: ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED'],
          },
          durationMs: { type: 'integer', minimum: 0, maximum: 30000 },
        },
        required: ['ok', 'emotion', 'durationMs'],
        additionalProperties: false,
      },
      sideEffect: 'ui',
      idempotent: true,
      requiresConfirmation: false,
      timeoutMs: 5000,
    },
  ],
};

function authHeaders(overrides = {}) {
  return {
    Authorization: `Bearer ${FIXTURE_DEVICE_TOKEN}`,
    'X-Zenbo-Device-Id': FIXTURE_DEVICE_ID,
    'X-Zenbo-Protocol': PROTOCOL_VERSION,
    ...overrides,
  };
}

function openWebSocket(url, headers = authHeaders()) {
  return openGatewayWebSocket(url, headers, { timeoutMs: 2000 });
}

function sessionRequest(overrides = {}) {
  return {
    client: {
      appVersion: '0.1.0-test',
      platform: 'android',
      robotModel: 'zenbo-k',
      osVersion: '6.0.1',
      locale: 'zh-TW',
    },
    agentProfile: 'default',
    context: { robotName: 'Kira', language: 'zh-TW' },
    toolManifest: fixtureToolManifest,
    ...overrides,
  };
}

async function jsonRequest(url, { method = 'GET', body, headers = {} } = {}) {
  return fetch(url, {
    method,
    headers: {
      ...authHeaders(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function responseJson(response) {
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function createSession(gateway, key = '10000000-0000-4000-8000-000000000001', body = sessionRequest()) {
  const response = await jsonRequest(`${gateway.apiBaseUrl}/sessions`, {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': key },
  });
  assert.equal(response.status, 201);
  return responseJson(response);
}

function assertEnvelope(event, { sessionId, turnId, type, sequence }) {
  assert.deepEqual(Object.keys(event).sort(), ENVELOPE_FIELDS);
  assert.equal(event.protocolVersion, '1.0');
  assert.match(event.eventId, UUID_PATTERN);
  assert.equal(event.sessionId, sessionId);
  assert.equal(event.turnId, turnId);
  assert.equal(event.type, type);
  assert.equal(event.sequence, sequence);
  assert(Number.isFinite(Date.parse(event.timestamp)));
  assert(event.data && typeof event.data === 'object' && !Array.isArray(event.data));
}

async function withGateway(options, callback) {
  const gateway = new FakeAgentGateway(options);
  await gateway.start();
  try {
    await callback(gateway);
  } finally {
    await gateway.stop();
  }
}

test('TLS CLI/config requires a certificate and private key as an atomic pair', () => {
  assert.deepEqual(parseCliArguments([]), {
    host: '127.0.0.1',
    port: 8788,
    deviceId: FIXTURE_DEVICE_ID,
    tlsCertPath: null,
    tlsKeyPath: null,
  });
  assert.throws(
    () => parseCliArguments(['--tls-cert', '/outside-repo/test-cert.pem']),
    /must be provided together/,
  );
  assert.throws(
    () => parseCliArguments(['--tls-key', '/outside-repo/test-key.pem']),
    /must be provided together/,
  );
  assert.deepEqual(
    parseCliArguments([
      '--port',
      '9443',
      '--host',
      '192.0.2.10',
      '--device-id',
      'native-device-id',
      '--tls-cert',
      '/outside-repo/test-cert.pem',
      '--tls-key',
      '/outside-repo/test-key.pem',
    ]),
    {
      host: '192.0.2.10',
      port: 9443,
      deviceId: 'native-device-id',
      tlsCertPath: '/outside-repo/test-cert.pem',
      tlsKeyPath: '/outside-repo/test-key.pem',
    },
  );

  assert.throws(
    () => new FakeAgentGateway({ tlsCert: Buffer.from('certificate') }),
    /must be provided together/,
  );
  assert.throws(
    () => new FakeAgentGateway({ tlsCert: Buffer.alloc(0), tlsKey: Buffer.alloc(0) }),
    /must not be empty/,
  );
  const configured = new FakeAgentGateway({
    tlsCert: Buffer.from('certificate'),
    tlsKey: Buffer.from('private-key'),
  });
  assert.equal(configured.secure, true);

  const serverPath = fileURLToPath(new URL('./server.mjs', import.meta.url));
  const cli = spawnSync(
    process.execPath,
    [serverPath, '--tls-cert', '/outside-repo/test-cert.pem'],
    { encoding: 'utf8' },
  );
  assert.equal(cli.status, 1);
  assert.match(cli.stderr, /--tls-cert and --tls-key must be provided together/);
});

test('show_emotion uses the canonical emotion and durationMs tool roundtrip', async () => {
  await withGateway({}, async (gateway) => {
    const legacyManifest = {
      ...emotionToolManifest,
      manifestVersion: 'legacy-expression',
      tools: [
        {
          ...emotionToolManifest.tools[0],
          inputSchema: {
            type: 'object',
            properties: { expression: { type: 'string' } },
            required: ['expression'],
            additionalProperties: false,
          },
        },
      ],
    };
    const rejected = await jsonRequest(`${gateway.apiBaseUrl}/sessions`, {
      method: 'POST',
      body: sessionRequest({ toolManifest: legacyManifest }),
      headers: { 'Idempotency-Key': '10000000-0000-4000-8000-000000000040' },
    });
    assert.equal(rejected.status, 400);

    const session = await createSession(
      gateway,
      '10000000-0000-4000-8000-000000000041',
      sessionRequest({ toolManifest: emotionToolManifest }),
    );
    const events = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events`,
    );
    await events.client.take(1);
    const turnResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/turns`,
      {
        method: 'POST',
        body: {
          clientTurnId: '20000000-0000-4000-8000-000000000041',
          text: '請用開心的表情回答',
          language: 'zh-TW',
        },
        headers: { 'Idempotency-Key': '30000000-0000-4000-8000-000000000041' },
      },
    );
    assert.equal(turnResponse.status, 202);
    const firstEvents = await events.client.take(4);
    const toolCall = firstEvents[3];
    assert.equal(toolCall.type, 'tool.call');
    assert.equal(toolCall.data.toolName, 'show_emotion');
    assert.deepEqual(toolCall.data.arguments, { emotion: 'HAPPY', durationMs: 0 });

    const invalidResult = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/tool-calls/${toolCall.data.callId}`,
      {
        method: 'PUT',
        body: {
          status: 'succeeded',
          updatedAt: new Date().toISOString(),
          output: { ok: true, emotion: 'HAPPY', durationMs: 30001 },
        },
      },
    );
    assert.equal(invalidResult.status, 422);

    const validResult = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/tool-calls/${toolCall.data.callId}`,
      {
        method: 'PUT',
        body: {
          status: 'succeeded',
          updatedAt: new Date().toISOString(),
          output: { ok: true, emotion: 'HAPPY', durationMs: 0 },
        },
      },
    );
    assert.equal(validResult.status, 200);
    await events.client.take(3);
    events.client.destroy();
  });
});

test('happy path covers capabilities, tool result, TTS artifact, playback, replay, and delete', async () => {
  let now = Date.parse('2026-07-17T08:00:00.000Z');
  await withGateway({ now: () => now, artifactTtlMs: 1000 }, async (gateway) => {
    const capabilitiesResponse = await jsonRequest(`${gateway.apiBaseUrl}/capabilities`);
    assert.equal(capabilitiesResponse.status, 200);
    assert.deepEqual(await responseJson(capabilitiesResponse), CAPABILITIES);

    const session = await createSession(gateway);
    assert.match(session.sessionId, UUID_PATTERN);
    assert.equal(session.protocolVersion, '1.0');
    assert.equal(session.lastSequence, 0);

    const initialUpgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events`,
    );
    assert.equal(initialUpgrade.status, 101);
    const [ready] = await initialUpgrade.client.take(1);
    assertEnvelope(ready, {
      sessionId: session.sessionId,
      turnId: null,
      type: 'session.ready',
      sequence: 0,
    });
    assert.equal(ready.data.resumedAfter, 0);

    const textTurn = {
      clientTurnId: '20000000-0000-4000-8000-000000000001',
      text: '請回報系統狀態',
      language: 'zh-TW',
    };
    const turnResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/turns`,
      {
        method: 'POST',
        body: textTurn,
        headers: { 'Idempotency-Key': '30000000-0000-4000-8000-000000000001' },
      },
    );
    assert.equal(turnResponse.status, 202);
    const turn = await responseJson(turnResponse);
    assert.match(turn.turnId, UUID_PATTERN);
    assert.equal(turn.clientTurnId, textTurn.clientTurnId);

    const firstEvents = await initialUpgrade.client.take(4);
    assert.deepEqual(
      firstEvents.map((event) => event.type),
      ['turn.accepted', 'stt.final', 'agent.thinking', 'tool.call'],
    );
    firstEvents.forEach((event, index) => {
      assertEnvelope(event, {
        sessionId: session.sessionId,
        turnId: turn.turnId,
        type: event.type,
        sequence: index + 1,
      });
    });
    assert.equal(firstEvents[1].data.text, textTurn.text);
    const toolCall = firstEvents[3];
    assert.equal(toolCall.data.toolName, 'get_system_status');
    assert.match(toolCall.data.callId, UUID_PATTERN);
    assert.equal(toolCall.data.timeoutMs, 5000);

    const acceptedResult = {
      status: 'accepted',
      updatedAt: new Date(now).toISOString(),
    };
    let toolResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/tool-calls/${toolCall.data.callId}`,
      { method: 'PUT', body: acceptedResult },
    );
    assert.equal(toolResponse.status, 200);
    assert.deepEqual(await responseJson(toolResponse), acceptedResult);

    const succeededResult = {
      status: 'succeeded',
      updatedAt: new Date(now).toISOString(),
      output: { ready: true },
    };
    toolResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/tool-calls/${toolCall.data.callId}`,
      { method: 'PUT', body: succeededResult },
    );
    assert.equal(toolResponse.status, 200);
    assert.deepEqual(await responseJson(toolResponse), succeededResult);
    toolResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/tool-calls/${toolCall.data.callId}`,
      { method: 'PUT', body: succeededResult },
    );
    assert.equal(toolResponse.status, 200);
    assert.deepEqual(await responseJson(toolResponse), succeededResult);

    const finalEvents = await initialUpgrade.client.take(3);
    assert.deepEqual(
      finalEvents.map((event) => event.type),
      ['agent.text.final', 'tts.ready', 'turn.completed'],
    );
    finalEvents.forEach((event, index) => {
      assertEnvelope(event, {
        sessionId: session.sessionId,
        turnId: turn.turnId,
        type: event.type,
        sequence: index + 5,
      });
    });
    const artifactMetadata = finalEvents[1].data;
    assert.equal(artifactMetadata.mimeType, 'audio/wav');
    assert.match(artifactMetadata.sha256, /^[a-f0-9]{64}$/);
    assert.equal(Date.parse(artifactMetadata.expiresAt), now + 1000);

    const unauthenticatedArtifact = await fetch(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/audio/${artifactMetadata.artifactId}`,
    );
    assert.equal(unauthenticatedArtifact.status, 401);

    const artifactResponse = await fetch(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/audio/${artifactMetadata.artifactId}`,
      { headers: authHeaders() },
    );
    assert.equal(artifactResponse.status, 200);
    assert.equal(artifactResponse.headers.get('content-type'), 'audio/wav');
    const artifactBytes = Buffer.from(await artifactResponse.arrayBuffer());
    assert.equal(artifactBytes.length, artifactMetadata.byteLength);
    assert.equal(createHash('sha256').update(artifactBytes).digest('hex'), artifactMetadata.sha256);
    const digestBase64 = createHash('sha256').update(artifactBytes).digest('base64');
    assert.equal(artifactResponse.headers.get('digest'), `sha-256=:${digestBase64}:`);

    const playbackStarted = {
      turnId: turn.turnId,
      artifactId: artifactMetadata.artifactId,
      status: 'started',
      timestamp: new Date(now).toISOString(),
      positionMs: 0,
    };
    let playbackResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/playback`,
      {
        method: 'POST',
        body: playbackStarted,
        headers: { 'Idempotency-Key': '40000000-0000-4000-8000-000000000001' },
      },
    );
    assert.equal(playbackResponse.status, 202);
    playbackResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/playback`,
      {
        method: 'POST',
        body: playbackStarted,
        headers: { 'Idempotency-Key': '40000000-0000-4000-8000-000000000001' },
      },
    );
    assert.equal(playbackResponse.status, 202);
    playbackResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/playback`,
      {
        method: 'POST',
        body: {
          ...playbackStarted,
          status: 'completed',
          positionMs: 20,
        },
        headers: { 'Idempotency-Key': '40000000-0000-4000-8000-000000000002' },
      },
    );
    assert.equal(playbackResponse.status, 202);

    const sessionResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}`,
    );
    assert.equal(sessionResponse.status, 200);
    assert.equal((await responseJson(sessionResponse)).lastSequence, 7);

    const replayUpgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events?after=4`,
    );
    assert.equal(replayUpgrade.status, 101);
    const replay = await replayUpgrade.client.take(4);
    assert.equal(replay[0].type, 'session.ready');
    assert.equal(replay[0].sequence, 4);
    assert.deepEqual(
      replay.slice(1).map((event) => event.eventId),
      finalEvents.map((event) => event.eventId),
    );

    now += 1001;
    const expiredArtifact = await fetch(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/audio/${artifactMetadata.artifactId}`,
      { headers: authHeaders() },
    );
    assert.equal(expiredArtifact.status, 410);
    assert.equal((await responseJson(expiredArtifact)).code, 'ARTIFACT_EXPIRED');

    const deleteResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}`,
      { method: 'DELETE' },
    );
    assert.equal(deleteResponse.status, 204);
    const [closedEvent] = await replayUpgrade.client.take(1);
    assert.equal(closedEvent.type, 'session.closed');
    assert.equal(closedEvent.sequence, 8);
    const repeatedDelete = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}`,
      { method: 'DELETE' },
    );
    assert.equal(repeatedDelete.status, 204);

    initialUpgrade.client.destroy();
    replayUpgrade.client.destroy();
  });
});

test('session and text turn idempotency return the stored result and reject changed input', async () => {
  await withGateway({}, async (gateway) => {
    const createKey = '10000000-0000-4000-8000-000000000010';
    const body = sessionRequest();
    const first = await createSession(gateway, createKey, body);
    const second = await createSession(gateway, createKey, body);
    assert.equal(second.sessionId, first.sessionId);

    const changedCreate = await jsonRequest(`${gateway.apiBaseUrl}/sessions`, {
      method: 'POST',
      body: sessionRequest({ context: { robotName: 'Different', language: 'zh-TW' } }),
      headers: { 'Idempotency-Key': createKey },
    });
    assert.equal(changedCreate.status, 409);
    assert.equal((await responseJson(changedCreate)).code, 'IDEMPOTENCY_CONFLICT');

    const upgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${first.sessionId}/events`,
    );
    await upgrade.client.take(1);
    const turnKey = '30000000-0000-4000-8000-000000000010';
    const turnBody = {
      clientTurnId: '20000000-0000-4000-8000-000000000010',
      text: 'idempotent turn',
    };
    const firstTurnResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${first.sessionId}/turns`,
      {
        method: 'POST',
        body: turnBody,
        headers: { 'Idempotency-Key': turnKey },
      },
    );
    const firstTurn = await responseJson(firstTurnResponse);
    const secondTurnResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${first.sessionId}/turns`,
      {
        method: 'POST',
        body: turnBody,
        headers: { 'Idempotency-Key': turnKey },
      },
    );
    const secondTurn = await responseJson(secondTurnResponse);
    assert.equal(firstTurnResponse.status, 202);
    assert.equal(secondTurnResponse.status, 202);
    assert.equal(secondTurn.turnId, firstTurn.turnId);

    const changedTurn = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${first.sessionId}/turns`,
      {
        method: 'POST',
        body: { ...turnBody, text: 'different input' },
        headers: { 'Idempotency-Key': turnKey },
      },
    );
    assert.equal(changedTurn.status, 409);
    assert.equal((await responseJson(changedTurn)).code, 'IDEMPOTENCY_CONFLICT');

    const events = await upgrade.client.take(4);
    assert.deepEqual(
      events.map((event) => event.sequence),
      [1, 2, 3, 4],
    );
    await new Promise((resolveWait) => setTimeout(resolveWait, 30));
    assert.equal(upgrade.client.messages.length, 0, 'idempotent retry emitted duplicate events');
    upgrade.client.destroy();
  });
});

test('multipart WAV turn can be cancelled idempotently', async () => {
  await withGateway({}, async (gateway) => {
    const session = await createSession(
      gateway,
      '10000000-0000-4000-8000-000000000020',
    );
    const upgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events`,
    );
    await upgrade.client.take(1);

    const form = new FormData();
    form.append('clientTurnId', '20000000-0000-4000-8000-000000000020');
    form.append('durationMs', '20');
    form.append('language', 'zh-TW');
    form.append('audio', new Blob([createFixtureWav()], { type: 'audio/wav' }), 'turn.wav');
    const turnResponse = await fetch(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/turns`,
      {
        method: 'POST',
        headers: authHeaders({
          'Idempotency-Key': '30000000-0000-4000-8000-000000000020',
        }),
        body: form,
      },
    );
    assert.equal(turnResponse.status, 202);
    const turn = await responseJson(turnResponse);
    const events = await upgrade.client.take(4);
    assert.equal(events[1].type, 'stt.final');
    assert.equal(events[1].data.text, 'Fake WAV transcription.');

    const cancelBody = { reason: 'client_request' };
    const cancelKey = '50000000-0000-4000-8000-000000000020';
    let cancelResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/turns/${turn.turnId}/cancel`,
      {
        method: 'POST',
        body: cancelBody,
        headers: { 'Idempotency-Key': cancelKey },
      },
    );
    assert.equal(cancelResponse.status, 202);
    assert.equal((await responseJson(cancelResponse)).state, 'cancelled');
    const [cancelled] = await upgrade.client.take(1);
    assertEnvelope(cancelled, {
      sessionId: session.sessionId,
      turnId: turn.turnId,
      type: 'turn.cancelled',
      sequence: 5,
    });

    cancelResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/turns/${turn.turnId}/cancel`,
      {
        method: 'POST',
        body: cancelBody,
        headers: { 'Idempotency-Key': cancelKey },
      },
    );
    assert.equal(cancelResponse.status, 202);
    await new Promise((resolveWait) => setTimeout(resolveWait, 30));
    assert.equal(upgrade.client.messages.length, 0, 'cancel retry emitted duplicate event');
    upgrade.client.destroy();
  });
});

test('stale replay cursor receives session.ready then authoritative snapshot', async () => {
  await withGateway({ retentionLimit: 3 }, async (gateway) => {
    const session = await createSession(
      gateway,
      '10000000-0000-4000-8000-000000000030',
    );
    const live = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events`,
    );
    await live.client.take(1);
    const turnResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/turns`,
      {
        method: 'POST',
        body: {
          clientTurnId: '20000000-0000-4000-8000-000000000030',
          text: 'force retained history rollover',
        },
        headers: { 'Idempotency-Key': '30000000-0000-4000-8000-000000000030' },
      },
    );
    const turn = await responseJson(turnResponse);
    const firstEvents = await live.client.take(4);
    const callId = firstEvents[3].data.callId;
    const toolResponse = await jsonRequest(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/tool-calls/${callId}`,
      {
        method: 'PUT',
        body: {
          status: 'succeeded',
          updatedAt: new Date().toISOString(),
          output: { ready: true },
        },
      },
    );
    assert.equal(toolResponse.status, 200);
    await live.client.take(3);
    live.client.destroy();

    const stale = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events?after=0`,
    );
    const recovery = await stale.client.take(2);
    assertEnvelope(recovery[0], {
      sessionId: session.sessionId,
      turnId: null,
      type: 'session.ready',
      sequence: 0,
    });
    assertEnvelope(recovery[1], {
      sessionId: session.sessionId,
      turnId: null,
      type: 'session.snapshot',
      sequence: 7,
    });
    assert.deepEqual(recovery[1].data, {
      state: 'active',
      lastSequence: 7,
      activeTurnId: null,
    });
    assert.equal(turn.state, 'accepted');
    await new Promise((resolveWait) => setTimeout(resolveWait, 30));
    assert.equal(stale.client.messages.length, 0, 'snapshot recovery replayed stale history');
    stale.client.destroy();
  });
});

test('HTTP and WebSocket reject bad authentication, device binding, protocol, and cursor', async () => {
  await withGateway({}, async (gateway) => {
    let response = await fetch(`${gateway.apiBaseUrl}/capabilities`);
    assert.equal(response.status, 401);
    assert.equal((await responseJson(response)).code, 'UNAUTHORIZED');

    response = await fetch(`${gateway.apiBaseUrl}/capabilities`, {
      headers: authHeaders({ 'X-Zenbo-Device-Id': 'different-device' }),
    });
    assert.equal(response.status, 401);

    response = await fetch(`${gateway.apiBaseUrl}/capabilities`, {
      headers: authHeaders({ 'X-Zenbo-Protocol': '2.0' }),
    });
    assert.equal(response.status, 426);
    assert.equal((await responseJson(response)).code, 'PROTOCOL_MISMATCH');

    const session = await createSession(
      gateway,
      '10000000-0000-4000-8000-000000000040',
    );
    let upgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events`,
      authHeaders({ Authorization: 'Bearer wrong-fixture-token' }),
    );
    assert.equal(upgrade.status, 401);
    assert.equal(upgrade.body.code, 'UNAUTHORIZED');

    upgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events`,
      authHeaders({ 'X-Zenbo-Protocol': '2.0' }),
    );
    assert.equal(upgrade.status, 426);
    assert.equal(upgrade.body.code, 'PROTOCOL_MISMATCH');

    upgrade = await openWebSocket(
      `${gateway.apiBaseUrl}/sessions/${session.sessionId}/events?after=1`,
    );
    assert.equal(upgrade.status, 409);
    assert.equal(upgrade.body.code, 'CURSOR_AHEAD');
  });
});
