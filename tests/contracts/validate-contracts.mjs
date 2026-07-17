import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(testDir, '../..');
const contractDir = join(repoRoot, 'contracts/agent-gateway');
const openApiPath = join(contractDir, 'openapi.json');
const wsSchemaPath = join(contractDir, 'schemas/ws-envelope.schema.json');
const toolSchemaPath = join(contractDir, 'schemas/tool-manifest.schema.json');
const wsProtocolPath = join(contractDir, 'ws-protocol.md');
const validFixtureDir = join(testDir, 'fixtures/valid');
const invalidFixtureDir = join(testDir, 'fixtures/invalid');

const requiredEventTypes = [
  'session.ready',
  'session.snapshot',
  'turn.accepted',
  'stt.final',
  'agent.thinking',
  'tool.call',
  'agent.text.final',
  'tts.ready',
  'turn.completed',
  'turn.error',
  'session.expired',
  'turn.cancelled',
  'session.closed',
];

const deviceToolAllowlist = [
  'get_system_status',
  'start_robot_following',
  'stop_robot_following',
  'look_at_user',
  'show_emotion',
  'go_to_sleep',
];

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function deepEqual(left, right) {
  try {
    assert.deepStrictEqual(left, right);
    return true;
  } catch {
    return false;
  }
}

function pointerValue(document, pointer) {
  if (pointer === '' || pointer === '#') return document;
  assert(pointer.startsWith('#/'), `Unsupported JSON pointer: ${pointer}`);
  return pointer
    .slice(2)
    .split('/')
    .map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce((value, part) => {
      assert(
        value !== null && typeof value === 'object' && part in value,
        `Unresolved JSON pointer ${pointer}`,
      );
      return value[part];
    }, document);
}

function instanceType(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (Number.isInteger(value)) return 'integer';
  if (typeof value === 'number') return 'number';
  return typeof value;
}

function matchesType(value, type) {
  if (type === 'number') return typeof value === 'number' && Number.isFinite(value);
  if (type === 'integer') return Number.isInteger(value);
  if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value);
  if (type === 'array') return Array.isArray(value);
  if (type === 'null') return value === null;
  return typeof value === type;
}

function validateFormat(value, format) {
  if (format === 'uuid') {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  }
  if (format === 'date-time') {
    return (
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
      Number.isFinite(Date.parse(value))
    );
  }
  if (format === 'uri-reference') {
    return value.length > 0 && !/\s/.test(value);
  }
  return true;
}

function validateJsonSchema(instance, schema, rootSchema, path = '$') {
  const errors = [];
  const fail = (message) => errors.push(`${path}: ${message}`);

  if (schema === true || schema === undefined) return errors;
  if (schema === false) {
    fail('schema is false');
    return errors;
  }

  if (schema.$ref) {
    if (schema.$ref === './schemas/tool-manifest.schema.json') {
      return validateJsonSchema(instance, toolSchema, toolSchema, path);
    }
    assert(schema.$ref.startsWith('#'), `Fixture validator cannot resolve ref: ${schema.$ref}`);
    return validateJsonSchema(instance, pointerValue(rootSchema, schema.$ref), rootSchema, path);
  }

  if (schema.allOf) {
    for (const child of schema.allOf) {
      errors.push(...validateJsonSchema(instance, child, rootSchema, path));
    }
  }

  if (schema.anyOf) {
    const results = schema.anyOf.map((child) =>
      validateJsonSchema(instance, child, rootSchema, path),
    );
    if (!results.some((result) => result.length === 0)) {
      fail('does not satisfy anyOf');
    }
  }

  if (schema.oneOf) {
    const matchCount = schema.oneOf.filter(
      (child) => validateJsonSchema(instance, child, rootSchema, path).length === 0,
    ).length;
    if (matchCount !== 1) {
      fail(`must satisfy exactly one oneOf branch; matched ${matchCount}`);
    }
  }

  if ('const' in schema && !deepEqual(instance, schema.const)) {
    fail(`must equal ${JSON.stringify(schema.const)}`);
  }

  if (schema.enum && !schema.enum.some((candidate) => deepEqual(instance, candidate))) {
    fail(`must be one of ${JSON.stringify(schema.enum)}`);
  }

  if (schema.type) {
    const expected = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!expected.some((type) => matchesType(instance, type))) {
      fail(`expected ${expected.join('|')}, got ${instanceType(instance)}`);
      return errors;
    }
  }

  if (typeof instance === 'string') {
    if (schema.minLength !== undefined && instance.length < schema.minLength) {
      fail(`length must be >= ${schema.minLength}`);
    }
    if (schema.maxLength !== undefined && instance.length > schema.maxLength) {
      fail(`length must be <= ${schema.maxLength}`);
    }
    if (schema.pattern && !new RegExp(schema.pattern, 'u').test(instance)) {
      fail(`must match ${schema.pattern}`);
    }
    if (schema.format && !validateFormat(instance, schema.format)) {
      fail(`must have format ${schema.format}`);
    }
  }

  if (typeof instance === 'number' && Number.isFinite(instance)) {
    if (schema.minimum !== undefined && instance < schema.minimum) {
      fail(`must be >= ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && instance > schema.maximum) {
      fail(`must be <= ${schema.maximum}`);
    }
  }

  if (Array.isArray(instance)) {
    if (schema.minItems !== undefined && instance.length < schema.minItems) {
      fail(`must contain at least ${schema.minItems} items`);
    }
    if (schema.maxItems !== undefined && instance.length > schema.maxItems) {
      fail(`must contain at most ${schema.maxItems} items`);
    }
    if (schema.uniqueItems) {
      for (let index = 0; index < instance.length; index += 1) {
        if (instance.slice(0, index).some((item) => deepEqual(item, instance[index]))) {
          fail(`item ${index} is duplicated`);
        }
      }
    }

    const prefixLength = schema.prefixItems?.length ?? 0;
    schema.prefixItems?.forEach((itemSchema, index) => {
      if (index < instance.length) {
        errors.push(
          ...validateJsonSchema(instance[index], itemSchema, rootSchema, `${path}[${index}]`),
        );
      }
    });
    if (schema.items !== undefined) {
      for (let index = prefixLength; index < instance.length; index += 1) {
        errors.push(
          ...validateJsonSchema(instance[index], schema.items, rootSchema, `${path}[${index}]`),
        );
      }
    }
  }

  if (instance !== null && typeof instance === 'object' && !Array.isArray(instance)) {
    for (const required of schema.required ?? []) {
      if (!(required in instance)) fail(`missing required property ${required}`);
    }

    const declared = schema.properties ?? {};
    for (const [key, value] of Object.entries(instance)) {
      if (key in declared) {
        errors.push(
          ...validateJsonSchema(value, declared[key], rootSchema, `${path}.${key}`),
        );
      } else if (schema.additionalProperties === false) {
        fail(`unexpected property ${key}`);
      } else if (
        schema.additionalProperties &&
        typeof schema.additionalProperties === 'object'
      ) {
        errors.push(
          ...validateJsonSchema(
            value,
            schema.additionalProperties,
            rootSchema,
            `${path}.${key}`,
          ),
        );
      }
    }
  }

  return errors;
}

function validateToolManifestSemantics(manifest) {
  const errors = [];
  const seenNames = new Set();

  for (const [index, tool] of (manifest.tools ?? []).entries()) {
    if (seenNames.has(tool.name)) {
      errors.push(`$.tools[${index}].name: duplicate tool name ${tool.name}`);
    }
    seenNames.add(tool.name);

    for (const schemaName of ['inputSchema', 'resultSchema']) {
      const objectSchema = tool[schemaName];
      if (!objectSchema || typeof objectSchema !== 'object') continue;
      const properties = objectSchema.properties ?? {};
      for (const requiredName of objectSchema.required ?? []) {
        if (!(requiredName in properties)) {
          errors.push(
            `$.tools[${index}].${schemaName}: required property ${requiredName} is not declared`,
          );
        }
      }
    }
  }

  return errors;
}

function resolveOpenApiRef(document, documentPath, ref) {
  if (ref.startsWith('#')) return pointerValue(document, ref);

  const [relativePath, fragment = ''] = ref.split('#');
  const targetPath = resolve(dirname(documentPath), relativePath);
  assert(existsSync(targetPath), `Missing external $ref target ${ref}`);
  const target = readJson(targetPath);
  return fragment ? pointerValue(target, `#${fragment}`) : target;
}

function walk(value, visitor, path = '$') {
  if (value === null || typeof value !== 'object') return;
  visitor(value, path);
  if (Array.isArray(value)) {
    value.forEach((child, index) => walk(child, visitor, `${path}[${index}]`));
  } else {
    for (const [key, child] of Object.entries(value)) {
      walk(child, visitor, `${path}.${key}`);
    }
  }
}

function operationHeaders(openApi, operation) {
  return (operation.parameters ?? []).map((parameter) => {
    const resolved = parameter.$ref
      ? resolveOpenApiRef(openApi, openApiPath, parameter.$ref)
      : parameter;
    return resolved.name;
  });
}

function validateOpenApi(openApi) {
  assert.equal(openApi.openapi, '3.1.0');
  assert.equal(openApi.info.version, '0.1.0');
  assert(openApi.servers.some((server) => server.url.endsWith('/agent/v1')));
  assert.deepEqual(openApi.security, [{ DeviceBearer: [] }]);
  assert.equal(openApi.components.securitySchemes.DeviceBearer.scheme, 'bearer');

  const expectedOperations = new Map([
    ['/capabilities', ['get']],
    ['/sessions', ['post']],
    ['/sessions/{sessionId}', ['get', 'delete']],
    ['/sessions/{sessionId}/turns', ['post']],
    ['/sessions/{sessionId}/turns/{turnId}/cancel', ['post']],
    ['/sessions/{sessionId}/tool-calls/{callId}', ['put']],
    ['/sessions/{sessionId}/audio/{artifactId}', ['get']],
    ['/sessions/{sessionId}/playback', ['post']],
    ['/sessions/{sessionId}/events', ['get']],
  ]);

  assert.deepEqual(new Set(Object.keys(openApi.paths)), new Set(expectedOperations.keys()));

  for (const [path, methods] of expectedOperations) {
    const pathItem = openApi.paths[path];
    for (const method of methods) {
      const operation = pathItem[method];
      assert(operation, `Missing ${method.toUpperCase()} ${path}`);
      const headers = operationHeaders(openApi, operation);
      assert(headers.includes('X-Zenbo-Device-Id'), `${method} ${path} lacks device header`);
      assert(headers.includes('X-Zenbo-Protocol'), `${method} ${path} lacks protocol header`);
      assert(Object.keys(operation.responses).length > 0, `${method} ${path} lacks responses`);
    }
  }

  const turnSchema = openApi.components.schemas.TurnUpload;
  assert.equal(turnSchema.properties.audio['x-max-bytes'], 2097152);
  assert.equal(turnSchema.properties.durationMs.maximum, 30000);
  assert.equal(openApi.components.schemas.Capabilities.properties.audioOutput.properties.maxBytes.const, 10485760);
  assert.deepEqual(openApi.components.schemas.GatewayEventType.enum, requiredEventTypes);
  assert(openApi.components.schemas.Capabilities.required.includes('agentProfiles'));
  assert(openApi.components.schemas.Capabilities.required.includes('retentionPolicy'));
  assert.deepEqual(
    openApi.components.schemas.RetentionPolicy.required,
    ['rawAudio', 'transcript'],
  );

  const turnContent = openApi.paths['/sessions/{sessionId}/turns'].post.requestBody.content;
  assert(turnContent['application/json'], 'Text turn request is missing');
  assert(turnContent['multipart/form-data'], 'WAV turn request is missing');
  assert.equal(openApi.components.parameters.IdempotencyKey.schema.format, 'uuid');

  const playback = openApi.components.schemas.PlaybackUpdate;
  assert(playback.required.includes('turnId'));
  assert(playback.required.includes('artifactId'));
  assert(!('playbackId' in playback.properties));

  const eventOperation = openApi.paths['/sessions/{sessionId}/events'].get;
  assert.equal(eventOperation['x-websocket'], true);
  assert(eventOperation.parameters.some((parameter) => parameter.name === 'after'));
  assert('409' in eventOperation.responses);
  assert(!('410' in eventOperation.responses));
  assert.deepEqual(eventOperation['x-stale-cursor-recovery'], {
    eventType: 'session.snapshot',
    cursorField: 'data.lastSequence',
  });

  const createSession = openApi.components.schemas.CreateSessionRequest;
  assert(createSession.required.includes('agentProfile'));
  assert(createSession.required.includes('context'));
  assert.deepEqual(createSession.properties.context.required, ['robotName', 'language']);

  const toolCallStatuses = openApi.components.schemas.ToolCallUpdate.properties.status.enum;
  assert.deepEqual(toolCallStatuses, ['accepted', 'succeeded', 'failed', 'rejected']);
  assert.equal(
    openApi.components.schemas.ToolCallUpdate.properties.output['x-max-bytes'],
    16384,
  );

  assert.deepEqual(playback.properties.status.enum, ['started', 'completed', 'interrupted']);
  assert.deepEqual(playback.properties.reason.enum, [
    'barge_in',
    'screen_off',
    'playback_error',
    'client_cancelled',
  ]);

  walk(openApi, (value, path) => {
    if ('$ref' in value) {
      resolveOpenApiRef(openApi, openApiPath, value.$ref);
      const siblingKeys = Object.keys(value).filter((key) => key !== '$ref');
      assert.equal(siblingKeys.length, 0, `${path} has unsupported $ref siblings`);
    }
  });
}

const openApi = readJson(openApiPath);
const wsSchema = readJson(wsSchemaPath);
const toolSchema = readJson(toolSchemaPath);
const wsProtocol = readFileSync(wsProtocolPath, 'utf8');

assert.equal(wsSchema.$schema, 'https://json-schema.org/draft/2020-12/schema');
assert.equal(toolSchema.$schema, 'https://json-schema.org/draft/2020-12/schema');
assert.deepEqual(wsSchema.required, [
  'protocolVersion',
  'eventId',
  'sequence',
  'sessionId',
  'turnId',
  'type',
  'timestamp',
  'data',
]);
assert.deepEqual(wsSchema.properties.type.enum, requiredEventTypes);
assert.deepEqual(
  wsSchema.$defs.ttsReady.required,
  ['artifactId', 'mimeType', 'byteLength', 'sha256', 'expiresAt'],
);
assert(!('playbackId' in wsSchema.$defs.ttsReady.properties));
assert(wsSchema.$defs.toolCall.required.includes('timeoutMs'));
assert.equal(wsSchema.$defs.toolCall.properties.timeoutMs.minimum, 100);
assert.equal(wsSchema.$defs.toolCall.properties.timeoutMs.maximum, 15000);

const toolDefinition = toolSchema.$defs.tool;
assert.deepEqual(toolDefinition.properties.name.enum, deviceToolAllowlist);
assert.deepEqual(toolDefinition.properties.owner.enum, ['native', 'web']);
assert.deepEqual(toolDefinition.properties.sideEffect.enum, ['none', 'ui', 'physical']);
assert(wsProtocol.includes('authoritative\n  `session.snapshot`'));
assert(!wsProtocol.includes('fails with `410`'));
validateOpenApi(openApi);

function fixtureSchema(file) {
  if (file.startsWith('tool-manifest-')) {
    return { schema: toolSchema, root: toolSchema, toolManifest: true };
  }
  if (file.startsWith('http-capabilities')) {
    return {
      schema: openApi.components.schemas.Capabilities,
      root: openApi,
      toolManifest: false,
    };
  }
  if (file.startsWith('http-text-turn')) {
    return {
      schema: openApi.components.schemas.TextTurn,
      root: openApi,
      toolManifest: false,
    };
  }
  if (file.startsWith('http-create-session')) {
    return {
      schema: openApi.components.schemas.CreateSessionRequest,
      root: openApi,
      toolManifest: false,
    };
  }
  if (file.startsWith('http-playback')) {
    return {
      schema: openApi.components.schemas.PlaybackUpdate,
      root: openApi,
      toolManifest: false,
    };
  }
  if (file.startsWith('http-tool-call')) {
    return {
      schema: openApi.components.schemas.ToolCallUpdate,
      root: openApi,
      toolManifest: false,
    };
  }
  return { schema: wsSchema, root: wsSchema, toolManifest: false };
}

let validCount = 0;
for (const file of readdirSync(validFixtureDir).sort()) {
  if (!file.endsWith('.json')) continue;
  const fixture = readJson(join(validFixtureDir, file));
  const target = fixtureSchema(file);
  const errors = validateJsonSchema(fixture, target.schema, target.root);
  if (target.toolManifest) errors.push(...validateToolManifestSemantics(fixture));
  assert.equal(errors.length, 0, `${file} should be valid:\n${errors.join('\n')}`);
  validCount += 1;
}

let invalidCount = 0;
for (const file of readdirSync(invalidFixtureDir).sort()) {
  if (!file.endsWith('.json')) continue;
  const fixture = readJson(join(invalidFixtureDir, file));
  const target = fixtureSchema(file);
  const errors = validateJsonSchema(fixture, target.schema, target.root);
  if (target.toolManifest) errors.push(...validateToolManifestSemantics(fixture));
  assert(errors.length > 0, `${file} should be rejected`);
  invalidCount += 1;
}

console.log(`OpenAPI contract: OK (${Object.keys(openApi.paths).length} paths)`);
console.log(`Valid fixtures: OK (${validCount})`);
console.log(`Invalid fixtures: OK (${invalidCount} rejected)`);
