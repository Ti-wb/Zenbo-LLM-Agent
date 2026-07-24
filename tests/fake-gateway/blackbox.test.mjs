import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import test from 'node:test';

import { GatewayClient, responseJson } from './client.mjs';
import {
  FakeAgentGateway,
  FIXTURE_DEVICE_ID,
  FIXTURE_DEVICE_TOKEN,
} from './server.mjs';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MILLISECOND_UTC_PATTERN = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/;
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
const PROBLEM_FIELDS = [
  'code',
  'detail',
  'requestId',
  'retryable',
  'status',
  'title',
  'type',
];
const REQUIRED_PROBLEM_FIELDS = [
  'code',
  'requestId',
  'retryable',
  'status',
  'title',
  'type',
];

function sessionRequest(profile) {
  return {
    client: {
      appVersion: 'blackbox-1',
      platform: 'android',
      robotModel: 'zenbo-k',
      osVersion: 'blackbox',
      locale: 'zh-TW',
    },
    agentProfile: profile,
    context: {
      robotName: 'Kira black-box test',
      language: 'zh-TW',
    },
    toolManifest: {
      protocolVersion: '1.0',
      manifestVersion: 'blackbox-1',
      tools: [],
    },
  };
}

function assertProblem(response, body, status, code) {
  assert.equal(response.status, status);
  assert.match(response.headers.get('content-type') ?? '', /^application\/problem\+json\b/i);
  assert(body && typeof body === 'object' && !Array.isArray(body));
  for (const field of REQUIRED_PROBLEM_FIELDS) assert(field in body, `problem is missing ${field}`);
  for (const field of Object.keys(body)) assert(PROBLEM_FIELDS.includes(field), `unexpected problem field ${field}`);
  assert.equal(body.status, status);
  if (code) assert.equal(body.code, code);
  assert.equal(typeof body.retryable, 'boolean');
  assert.match(body.requestId, UUID_PATTERN);
}

function assertEnvelope(event, { sessionId, turnId, type, sequence }) {
  assert.deepEqual(Object.keys(event).sort(), ENVELOPE_FIELDS);
  assert.equal(event.protocolVersion, '1.0');
  assert.match(event.eventId, UUID_PATTERN);
  assert.equal(event.sessionId, sessionId);
  assert.equal(event.turnId, turnId);
  assert.equal(event.type, type);
  assert.equal(event.sequence, sequence);
  assert.match(event.timestamp, MILLISECOND_UTC_PATTERN);
  assert(event.data && typeof event.data === 'object' && !Array.isArray(event.data));
}

async function runProviderIndependentScenario(client, profile) {
  let sessionId = null;
  const streams = [];
  try {
    const unauthenticated = await client.request('/capabilities', { authenticated: false });
    assertProblem(
      unauthenticated,
      await responseJson(unauthenticated),
      401,
      'UNAUTHORIZED',
    );

    const capabilitiesResponse = await client.request('/capabilities');
    assert.equal(capabilitiesResponse.status, 200);
    const capabilities = await responseJson(capabilitiesResponse);
    assert.equal(capabilities.protocolVersion, '1.0');
    assert(capabilities.eventTypes.includes('session.ready'));
    assert(capabilities.eventTypes.includes('turn.accepted'));
    assert(capabilities.eventTypes.includes('stt.final'));
    assert(capabilities.eventTypes.includes('agent.thinking'));
    assert(capabilities.eventTypes.includes('session.closed'));
    assert(capabilities.audioInput.contentTypes.includes('audio/wav'));
    assert(
      capabilities.agentProfiles.some((candidate) => candidate.id === profile),
      `profile ${profile} is not advertised`,
    );

    const wrongProtocol = await client.request('/capabilities', {
      headers: { 'X-Zenbo-Protocol': '2.0' },
    });
    assertProblem(
      wrongProtocol,
      await responseJson(wrongProtocol),
      426,
      'PROTOCOL_MISMATCH',
    );

    const createKey = randomUUID();
    const createBody = sessionRequest(profile);
    let createResponse = await client.request('/sessions', {
      method: 'POST',
      body: createBody,
      headers: { 'Idempotency-Key': createKey },
    });
    assert.equal(createResponse.status, 201);
    const session = await responseJson(createResponse);
    sessionId = session.sessionId;
    assert.match(sessionId, UUID_PATTERN);
    assert.equal(session.deviceId, client.deviceId);
    assert.equal(session.protocolVersion, '1.0');
    assert.equal(session.state, 'active');
    assert.equal(session.lastSequence, 0);
    assert.match(session.createdAt, MILLISECOND_UTC_PATTERN);
    assert.match(session.expiresAt, MILLISECOND_UTC_PATTERN);

    createResponse = await client.request('/sessions', {
      method: 'POST',
      body: createBody,
      headers: { 'Idempotency-Key': createKey },
    });
    assert.equal(createResponse.status, 201);
    assert.equal((await responseJson(createResponse)).sessionId, sessionId);

    const createConflict = await client.request('/sessions', {
      method: 'POST',
      body: {
        ...createBody,
        context: { ...createBody.context, robotName: 'Changed black-box input' },
      },
      headers: { 'Idempotency-Key': createKey },
    });
    assertProblem(
      createConflict,
      await responseJson(createConflict),
      409,
      'IDEMPOTENCY_CONFLICT',
    );

    const liveUpgrade = await client.openEvents(sessionId);
    assert.equal(liveUpgrade.status, 101);
    streams.push(liveUpgrade.client);
    const [ready] = await liveUpgrade.client.take(1);
    assertEnvelope(ready, {
      sessionId,
      turnId: null,
      type: 'session.ready',
      sequence: 0,
    });
    assert.equal(ready.data.resumedAfter, 0);

    const turnKey = randomUUID();
    const turnBody = {
      clientTurnId: randomUUID(),
      text: '這是 provider-independent gateway black-box 測試。',
      language: 'zh-TW',
    };
    let turnResponse = await client.request(`/sessions/${sessionId}/turns`, {
      method: 'POST',
      body: turnBody,
      headers: { 'Idempotency-Key': turnKey },
    });
    assert.equal(turnResponse.status, 202);
    const turn = await responseJson(turnResponse);
    assert.match(turn.turnId, UUID_PATTERN);
    assert.equal(turn.sessionId, sessionId);
    assert.equal(turn.clientTurnId, turnBody.clientTurnId);
    assert.equal(turn.state, 'accepted');
    assert.match(turn.acceptedAt, MILLISECOND_UTC_PATTERN);

    turnResponse = await client.request(`/sessions/${sessionId}/turns`, {
      method: 'POST',
      body: turnBody,
      headers: { 'Idempotency-Key': turnKey },
    });
    assert.equal(turnResponse.status, 202);
    assert.equal((await responseJson(turnResponse)).turnId, turn.turnId);

    const turnConflict = await client.request(`/sessions/${sessionId}/turns`, {
      method: 'POST',
      body: { ...turnBody, text: 'Changed turn input' },
      headers: { 'Idempotency-Key': turnKey },
    });
    assertProblem(
      turnConflict,
      await responseJson(turnConflict),
      409,
      'IDEMPOTENCY_CONFLICT',
    );

    const prefix = await liveUpgrade.client.take(3);
    assert.deepEqual(
      prefix.map((event) => event.type),
      ['turn.accepted', 'stt.final', 'agent.thinking'],
    );
    prefix.forEach((event, index) => {
      assertEnvelope(event, {
        sessionId,
        turnId: turn.turnId,
        type: event.type,
        sequence: index + 1,
      });
    });
    assert.equal(prefix[1].data.text, turnBody.text);
    assert.equal(prefix[1].data.language, turnBody.language);

    const replayUpgrade = await client.openEvents(sessionId, 1);
    assert.equal(replayUpgrade.status, 101);
    streams.push(replayUpgrade.client);
    const replay = await replayUpgrade.client.take(3);
    assertEnvelope(replay[0], {
      sessionId,
      turnId: null,
      type: 'session.ready',
      sequence: 1,
    });
    assert.deepEqual(
      replay.slice(1).map((event) => event.eventId),
      prefix.slice(1).map((event) => event.eventId),
    );

    const sessionResponse = await client.request(`/sessions/${sessionId}`);
    assert.equal(sessionResponse.status, 200);
    assert((await responseJson(sessionResponse)).lastSequence >= 3);

    let closeResponse = await client.request(`/sessions/${sessionId}`, { method: 'DELETE' });
    assert.equal(closeResponse.status, 204);
    const closed = await liveUpgrade.client.takeUntil((event) => event.type === 'session.closed');
    assert.equal(closed.sessionId, sessionId);
    assert.equal(closed.turnId, null);
    assert.match(closed.timestamp, MILLISECOND_UTC_PATTERN);

    closeResponse = await client.request(`/sessions/${sessionId}`, { method: 'DELETE' });
    assert.equal(closeResponse.status, 204);

    const closedSessionResponse = await client.request(`/sessions/${sessionId}`);
    assert.equal(closedSessionResponse.status, 200);
    const closedSession = await responseJson(closedSessionResponse);
    assert.equal(closedSession.state, 'closed');
    assert.equal(closedSession.lastSequence, closed.sequence);
  } finally {
    for (const stream of streams) stream?.destroy();
    if (sessionId) {
      try {
        await client.request(`/sessions/${sessionId}`, { method: 'DELETE' });
      } catch {
        // Best-effort cleanup only; retain the original assertion failure.
      }
    }
  }
}

test('provider-independent black-box scenario runs against bundled Fake Gateway', async () => {
  const gateway = new FakeAgentGateway();
  await gateway.start();
  try {
    const client = new GatewayClient({
      baseUrl: gateway.apiBaseUrl,
      deviceToken: FIXTURE_DEVICE_TOKEN,
      deviceId: FIXTURE_DEVICE_ID,
    });
    await runProviderIndependentScenario(client, 'default');
  } finally {
    await gateway.stop();
  }
});

const external = {
  baseUrl: process.env.GATEWAY_BASE_URL?.trim(),
  deviceToken: process.env.GATEWAY_DEVICE_TOKEN?.trim(),
  deviceId: process.env.GATEWAY_DEVICE_ID?.trim(),
  profile: process.env.GATEWAY_PROFILE?.trim() || 'default',
};
const missingExternalVariables = [
  ['GATEWAY_BASE_URL', external.baseUrl],
  ['GATEWAY_DEVICE_TOKEN', external.deviceToken],
  ['GATEWAY_DEVICE_ID', external.deviceId],
]
  .filter(([, value]) => !value)
  .map(([name]) => name);
const externalOptions = missingExternalVariables.length
  ? { skip: `missing ${missingExternalVariables.join(', ')}` }
  : {};

test(
  'provider-independent black-box scenario runs against GATEWAY_BASE_URL',
  externalOptions,
  async () => {
    const client = new GatewayClient(external);
    await runProviderIndependentScenario(client, external.profile);
  },
);
