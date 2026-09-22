import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateJsonSchema } from '../contracts/schema-validator.mjs';

const json = (name) => JSON.parse(readFileSync(new URL(name, import.meta.url), 'utf8'));
const manifest = json('../../contracts/hermes-zenbo/device-tools.json');
const channel = json('../../contracts/hermes-zenbo/schemas/device-channel.schema.json');
const speech = json('../../contracts/hermes-zenbo/schemas/speech.schema.json');
const valid = (value, schema) => validateJsonSchema(value, schema, schema);
const base = {
  callId: '00000000-0000-4000-8000-000000000001',
  sessionId: 'session-fixture', runId: 'run-fixture',
  turnId: '00000000-0000-4000-8000-000000000002',
};
const call = (name, args) => ({
  type: 'tool.call', ...base, toolName: name, toolVersion: '1.0.0',
  arguments: args, timeoutMs: manifest.tools.find((tool) => tool.name === name)?.timeoutMs || 5000,
  deadlineAt: '2026-09-17T00:00:10Z',
});

test('sanitized recorded discovery exposes native runs and a profile-managed model', () => {
  const models = json('./fixtures/profile-models.json');
  const capabilities = json('./fixtures/profile-capabilities.json');
  for (const fixture of [models, capabilities]) {
    assert.equal(fixture.kind, 'sanitized-recorded-read-only-response');
    assert.equal(fixture.httpStatus, 200);
    assert(fixture.source.includes('/hermes-api/p/robot/v1/'));
  }
  assert(models.response.data.some((model) => model.id === 'profile-model'));
  for (const feature of ['run_submission', 'run_status', 'run_events_sse', 'run_stop', 'session_resources']) {
    assert.equal(capabilities.response.features[feature], true, feature);
  }
  // This is native Hermes discovery. Plugin speech has a separate contract.
  assert.equal(capabilities.response.features.audio_api, false);
  assert.equal(capabilities.response.features.realtime_voice, false);
  assert.equal(capabilities.response.endpoints.run_stop.path, '/v1/runs/{run_id}/stop');
});

test('all eight tools accept only their strict existing input shape', () => {
  const examples = {
    get_system_status: {}, start_robot_following: { enablePreview: false, largePreview: false },
    stop_robot_following: {}, look_at_user: { doa: -180 },
    show_emotion: { emotion: 'HAPPY', durationMs: 0 }, go_to_sleep: {},
    move_robot: { direction: 'forward' }, capture_camera: {},
  };
  for (const tool of manifest.tools) {
    assert.deepEqual(valid(call(tool.name, examples[tool.name]), channel), []);
    assert(valid(call(tool.name, { ...examples[tool.name], deviceId: 'model-selected-device' }), channel).length > 0);
  }
  for (const [name, args] of [
    ['look_at_user', {}], ['look_at_user', { doa: 181 }],
    ['show_emotion', { emotion: 'ANGRY' }], ['show_emotion', { emotion: 'HAPPY', durationMs: 30001 }],
    ['start_robot_following', { enablePreview: 'yes' }], ['shell', {}],
    ['move_robot', { direction: 'stop' }], ['move_robot', { direction: 'forward', distance: 50 }],
    ['capture_camera', { url: 'https://untrusted.invalid/camera' }],
  ]) assert(valid(call(name, args), channel).length > 0, name);
});

test('tool deadlines cover the complete SDK chain and reject another tool deadline', () => {
  for (const [name, args, nativeBudgetMs] of [
    ['start_robot_following', {}, 2000 + 1500 + 3000],
    ['move_robot', { direction: 'forward' }, 2000 + 1500 + 2000],
  ]) {
    const message = call(name, args);
    assert.equal(message.timeoutMs, nativeBudgetMs + 1000);
    assert.deepEqual(valid(message, channel), []);
    for (const timeoutMs of [5000, 6500, 7500].filter((value) => value !== message.timeoutMs)) {
      assert(valid({ ...message, timeoutMs }, channel).length > 0, `${name}: ${timeoutMs}`);
    }
  }
  assert.equal(call('stop_robot_following', {}).timeoutMs, 5000);
  assert(valid({ ...call('stop_robot_following', {}), timeoutMs: 7500 }, channel).length > 0);
});

test('device channel distinguishes activation, terminal results and receipts', () => {
  for (const type of ['run.activate', 'run.active', 'run.inactive']) {
    const { sessionId, runId, turnId } = base;
    assert.deepEqual(valid({ type, sessionId, runId, turnId }, channel), []);
    assert(valid({ type, sessionId, runId }, channel).length > 0);
  }
  const result = { type: 'tool.result', ...base, updatedAt: '2026-09-17T00:00:01Z' };
  assert.deepEqual(valid({ ...result, status: 'accepted' }, channel), []);
  assert.deepEqual(valid({ ...result, status: 'succeeded', output: { accepted: true } }, channel), []);
  assert.deepEqual(valid({ ...result, status: 'rejected', error: { code: 'EXPIRED', message: 'Deadline expired' } }, channel), []);
  for (const status of ['running', 'timed_out', 'succeeded', 'failed', 'rejected']) {
    assert(valid({ ...result, status }, channel).length > 0, status);
  }
  assert(valid({ ...call('get_system_status', {}), timeoutMs: 15000 }, channel).length > 0);
  assert(valid({ ...call('get_system_status', {}), apiKey: 'forbidden' }, channel).length > 0);
});

test('ordered speech artifacts are nonempty and require bounded verifiable audio', () => {
  const artifact = {
    artifactId: '00000000-0000-4000-8000-000000000003', mimeType: 'audio/wav',
    byteLength: 32044, sha256: 'a'.repeat(64), expiresAt: '2026-09-17T00:30:00Z',
  };
  assert.deepEqual(valid({ artifacts: [artifact, { ...artifact, artifactId: base.callId }] }, speech), []);
  for (const value of [
    { artifacts: [] }, { artifacts: [{ ...artifact, mimeType: 'audio/ogg' }] },
    { artifacts: [{ ...artifact, byteLength: 10485761 }] },
    { artifacts: [{ ...artifact, sha256: 'A'.repeat(64) }] },
    { artifacts: [{ ...artifact, url: 'https://untrusted.invalid/audio' }] },
  ]) assert(valid(value, speech).length > 0);
});

test('sanitized recorded profile run omits model and preserves session, SSE completion and idempotency', () => {
  const fixture = json('./fixtures/profile-run.json');
  assert.equal(fixture.kind, 'sanitized-recorded-live-run');
  assert.equal(fixture.sessionDeleted, true);
  const step = (label) => fixture.steps.find((item) => item.label === label);
  const create = step('session-create');
  const submit = step('echo-submit');
  const events = step('echo-events');
  const status = step('echo-status-01');
  const replay = step('echo-idempotent-replay');
  assert.equal(create.http_status, 201);
  assert.equal(create.response.object, 'hermes.session');
  assert.equal(submit.http_status, 202);
  assert(!Object.hasOwn(submit.request_body, 'model'));
  assert.equal(submit.request_body.session_id, create.response.session.id);
  assert.equal(submit.response.status, 'started');
  assert.equal(events.content_type, 'text/event-stream');
  const frames = events.response.split(/\r?\n/).filter((line) => line.startsWith('data:')).map((line) => JSON.parse(line.slice(5).trim()));
  assert(frames.every((frame) => frame.run_id === submit.response.run_id));
  const deltas = frames.filter((frame) => frame.event === 'message.delta').map((frame) => frame.delta).join('');
  assert.equal(deltas, 'HERMES_ZENBO_RUNS_SMOKE_OK');
  const terminal = frames.filter((frame) => ['run.completed', 'run.failed', 'run.cancelled'].includes(frame.event));
  assert.equal(terminal.length, 1);
  assert.equal(terminal[0].event, 'run.completed');
  assert.equal(terminal[0].output, deltas);
  assert.equal(status.response.status, 'completed');
  assert.equal(status.response.output, deltas);
  assert.equal(replay.response.run_id, submit.response.run_id);
  assert.equal(replay.response.replayed, true);
  assert.equal(step('session-delete').http_status, 200);
});
