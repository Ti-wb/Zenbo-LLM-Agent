import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateJsonSchema } from './schema-validator.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const read = (path) => JSON.parse(readFileSync(join(root, path), 'utf8'));
const api = read('contracts/local-runtime/openapi.json');
const event = read('contracts/local-runtime/schemas/event.schema.json');
const conversationEvent = read('contracts/local-runtime/schemas/conversation-event.schema.json');
const manifest = read('contracts/hermes-zenbo/device-tools.json');
const channel = read('contracts/hermes-zenbo/schemas/device-channel.schema.json');
const pluginManifest = read('integrations/hermes-zenbo/device-tools.json');
assert.deepEqual(pluginManifest, manifest, 'Packaged plugin tool definitions drifted from the shared contract');
const allowlist = ['get_system_status', 'start_robot_following', 'stop_robot_following', 'look_at_user', 'show_emotion', 'go_to_sleep'];

assert.equal(api.openapi, '3.1.0');
assert.equal(api.info.version, '2.0.0');
assert.deepEqual(api.servers.map((server) => server.url), ['http://127.0.0.1:8787/api/v2']);
assert.equal(api.servers[0]['x-loopback-only'], true);
assert.deepEqual(api.security, [{ LocalSessionCookie: [] }]);
assert.equal(api.components.securitySchemes.LocalSessionCookie.name, 'zenbo_local_session');
assert.equal(api.components.parameters.RendererOrigin.schema.const, 'http://127.0.0.1:8787');
assert.equal(api.components.schemas.BootstrapData.properties.protocolVersion.const, '2.0');
assert.deepEqual(api.components.schemas.SuccessEnvelope.required, ['ok', 'requestId', 'data', 'error']);
assert.deepEqual(api.components.schemas.ErrorEnvelope.required, ['ok', 'requestId', 'data', 'error']);
assert(api.components.schemas.LocalErrorCode.enum.includes('TURN_BUSY'));
assert(api.components.schemas.StatusData.required.includes('lastSequence'));
assert(api.components.schemas.ConversationData.required.includes('lastSequence'));

const expectedOperations = {
  '/bootstrap': ['post'], '/status': ['get'], '/settings': ['get', 'put'],
  '/settings/setup': ['post'], '/settings/unlock': ['post'], '/settings/test': ['post'],
  '/conversation': ['get'], '/conversation/turns': ['post'], '/conversation/cancel': ['post'],
  '/conversation/tool-calls/{callId}': ['put'], '/conversation/playback': ['post'],
  '/conversation/audio/{artifactId}': ['get'], '/events': ['get'],
};
assert.deepEqual(Object.keys(api.paths).sort(), Object.keys(expectedOperations).sort());
for (const [path, methods] of Object.entries(expectedOperations)) {
  for (const method of methods) {
    const operation = api.paths[path][method];
    assert(operation, `${method} ${path} missing`);
    if (method !== 'get' || path === '/events') {
      assert(operation.parameters.some((item) => item.$ref === '#/components/parameters/RendererOrigin'), `${method} ${path} lacks Origin`);
    }
  }
}
assert.deepEqual(api.paths['/bootstrap'].post.security, []);
assert(api.paths['/bootstrap'].post.responses['200'].headers['Set-Cookie'].description.includes('Path=/api/v2'));
assert.equal(api.paths['/events'].get['x-websocket'], true);
assert(api.paths['/events'].get.parameters.some((item) => item.name === 'after'));

const schemas = api.components.schemas;
for (const name of ['SettingsSetupRequest', 'SettingsUpdateRequest', 'SettingsTestRequest']) {
  assert.equal(schemas[name].properties.apiKey.writeOnly, true);
  assert(!('model' in schemas[name].properties), 'Hermes profile owns model selection');
  assert.equal(schemas[name].additionalProperties, false);
  assert(!('deviceToken' in schemas[name].properties));
}
for (const secret of ['apiKey', 'deviceToken', 'certificatePin', 'confirmedFingerprint']) {
  assert(!(secret in schemas.SettingsData.properties), `SettingsData leaks ${secret}`);
}
assert(schemas.SettingsData.required.includes('hasApiKey'));
assert(!('model' in schemas.SettingsData.properties));
assert.equal(schemas.VoiceTurnRequest.properties.audio['x-max-bytes'], 2097152);
assert.equal(schemas.VoiceTurnRequest.properties.durationMs.maximum, 30000);

for (const schema of [event.$defs.localControlEnvelope, conversationEvent]) {
  assert.equal(schema.properties.protocolVersion.const, '2.0');
  assert(schema.required.includes('sequence'));
  assert.equal(schema.additionalProperties, false);
}
assert(!('sessionId' in event.$defs.localControlEnvelope.properties));
assert(!('turnId' in event.$defs.localControlEnvelope.properties));
assert.equal(conversationEvent.$defs.ttsReady.properties.artifacts.minItems, 1);
assert.equal(conversationEvent.$defs.audioArtifact.properties.byteLength.maximum, 10485760);
assert.equal(manifest.protocolVersion, '2.0');
const channelTool = channel.oneOf.find((entry) => entry.properties.type.const === 'tool.call');
for (const tool of manifest.tools) {
  const validation = channelTool.allOf.find((entry) => entry.if.properties.toolName.const === tool.name);
  assert.deepEqual(validation.then.properties.arguments, tool.inputSchema);
}
assert.deepEqual(manifest.tools.map((tool) => tool.name), allowlist);
for (const tool of manifest.tools) {
  assert.equal(tool.owner, ['show_emotion', 'go_to_sleep'].includes(tool.name) ? 'web' : 'native');
  assert.equal(tool.timeoutMs, 5000);
  assert.equal(tool.version, '1.0.0');
  for (const kind of ['inputSchema', 'resultSchema']) {
    assert.equal(tool[kind].additionalProperties, false);
    for (const field of tool[kind].required) assert(field in tool[kind].properties);
  }
}
assert.equal(new Set(channel.oneOf.map((entry) => entry.properties.type.const)).size, channel.oneOf.length);
assert(!existsSync(join(root, 'contracts/agent-gateway')), 'Retired remote contract remains');
assert(!existsSync(join(root, 'tests/fake-gateway')), 'Retired remote test server remains');

const mapping = {
  'local-conversation-event-': [conversationEvent, conversationEvent],
  'local-event-': [event, event],
  'local-tool-call': [schemas.ToolCallUpdate, api],
  'local-playback': [schemas.PlaybackUpdate, api],
  'local-bootstrap-request': [schemas.BootstrapRequest, api],
  'local-status-response': [schemas.StatusResponse, api],
  'local-error-envelope': [schemas.ErrorEnvelope, api],
  'local-settings-setup': [schemas.SettingsSetupRequest, api],
  'local-settings-update': [schemas.SettingsUpdateRequest, api],
  'local-settings-response': [schemas.SettingsResponse, api],
  'local-settings-test-request': [schemas.SettingsTestRequest, api],
  'local-settings-test-response': [schemas.SettingsTestResponse, api],
  'local-conversation-response': [schemas.ConversationResponse, api],
  'local-text-turn': [schemas.TextTurnRequest, api],
  'local-cancel-turn': [schemas.CancelTurnRequest, api],
};
let validCount = 0;
let invalidCount = 0;
for (const group of ['valid', 'invalid']) {
  for (const file of readdirSync(join(root, `tests/contracts/fixtures/${group}`)).sort()) {
    if (!file.endsWith('.json')) continue;
    const value = read(`tests/contracts/fixtures/${group}/${file}`);
    let errors;
    if (file === 'local-bootstrap-replayed-token.json') {
      errors = value.first.bootstrapToken === value.replay.bootstrapToken ? ['replayed bootstrap token'] : [];
    } else {
      const prefix = Object.keys(mapping).find((item) => file.startsWith(item));
      assert(prefix, `No fixture schema: ${file}`);
      errors = validateJsonSchema(value, ...mapping[prefix]);
      if (file.startsWith('local-settings-setup') && value.pin !== value.confirmPin) errors.push('PIN confirmation mismatch');
      if (file.startsWith('local-settings-') && ('certificatePin' in value || 'confirmedFingerprint' in value) && value.certificatePin !== value.confirmedFingerprint) errors.push('Fingerprint mismatch');
    }
    if (group === 'valid') {
      assert.equal(errors.length, 0, `${file}: ${errors.join('\n')}`);
      validCount += 1;
    } else {
      assert(errors.length > 0, `${file} was not rejected`);
      invalidCount += 1;
    }
  }
}
console.log(`Local Runtime 2.0: ${Object.keys(api.paths).length} paths; ${validCount} valid and ${invalidCount} rejected fixtures`);
console.log('Hermes Zenbo: six strict device tools; plugin channel schema; no retired Gateway contracts');
