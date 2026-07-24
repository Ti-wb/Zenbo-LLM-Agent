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
const localContractDir = join(repoRoot, 'contracts/local-runtime');
const localOpenApiPath = join(localContractDir, 'openapi.json');
const localBootstrapPath = join(localContractDir, 'bootstrap-security.md');
const localEventsPath = join(localContractDir, 'events.md');
const localEventSchemaPath = join(localContractDir, 'schemas/event.schema.json');
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

const gatewayStates = [
  'UNCONFIGURED',
  'CONNECTING',
  'READY',
  'DEGRADED',
  'AUTH_ERROR',
  'TLS_ERROR',
  'INCOMPATIBLE',
  'OFFLINE',
];

const turnStates = [
  'IDLE',
  'LISTENING',
  'UPLOADING',
  'TRANSCRIBING',
  'THINKING',
  'AWAITING_TOOL',
  'SYNTHESIZING',
  'SPEAKING',
  'ERROR',
];

const localErrorCodes = [
  'INVALID_REQUEST',
  'UNAUTHORIZED',
  'FORBIDDEN_ORIGIN',
  'RATE_LIMITED',
  'NOT_FOUND',
  'CONFLICT',
  'PAYLOAD_TOO_LARGE',
  'UNSUPPORTED_MEDIA_TYPE',
  'GATEWAY_UNCONFIGURED',
  'GATEWAY_AUTH',
  'GATEWAY_TLS',
  'GATEWAY_INCOMPATIBLE',
  'GATEWAY_OFFLINE',
  'SESSION_EXPIRED',
  'TURN_CANCELLED',
  'ROBOT_INITIALIZING',
  'ROBOT_UNAVAILABLE',
  'TOOL_REJECTED',
  'TIMEOUT',
  'ARTIFACT_EXPIRED',
  'INTERNAL_ERROR',
  'INVALID_BOOTSTRAP_TOKEN',
  'SETUP_REQUIRED',
  'ALREADY_CONFIGURED',
  'SETTINGS_LOCKED',
  'INVALID_PIN',
  'INVALID_SETTINGS',
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
    if (schema.$ref === '../../agent-gateway/schemas/ws-envelope.schema.json') {
      return validateJsonSchema(instance, wsSchema, wsSchema, path);
    }
    if (schema.$ref.startsWith('../openapi.json#')) {
      const pointer = schema.$ref.slice('../openapi.json'.length);
      return validateJsonSchema(
        instance,
        pointerValue(localOpenApi, pointer),
        localOpenApi,
        path,
      );
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

  if (schema.if) {
    const conditionMatches = validateJsonSchema(instance, schema.if, rootSchema, path).length === 0;
    if (conditionMatches && schema.then) {
      errors.push(...validateJsonSchema(instance, schema.then, rootSchema, path));
    } else if (!conditionMatches && schema.else) {
      errors.push(...validateJsonSchema(instance, schema.else, rootSchema, path));
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
    const propertyCount = Object.keys(instance).length;
    if (schema.minProperties !== undefined && propertyCount < schema.minProperties) {
      fail(`must contain at least ${schema.minProperties} properties`);
    }
    if (schema.maxProperties !== undefined && propertyCount > schema.maxProperties) {
      fail(`must contain at most ${schema.maxProperties} properties`);
    }

    for (const required of schema.required ?? []) {
      if (!(required in instance)) fail(`missing required property ${required}`);
    }

    for (const [trigger, dependencies] of Object.entries(schema.dependentRequired ?? {})) {
      if (!(trigger in instance)) continue;
      for (const dependency of dependencies) {
        if (!(dependency in instance)) {
          fail(`property ${trigger} requires property ${dependency}`);
        }
      }
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

function validateLocalSettingsWriteSemantics(request, kind) {
  const errors = [];
  if (kind === 'setup' && request.pin !== request.confirmPin) {
    errors.push('$.confirmPin: must exactly match pin');
  }
  if (
    ('certificatePin' in request || 'confirmedFingerprint' in request) &&
    request.certificatePin !== request.confirmedFingerprint
  ) {
    errors.push('$.confirmedFingerprint: must exactly match certificatePin');
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

function operationHeaders(openApi, operation, documentPath = openApiPath) {
  return (operation.parameters ?? []).map((parameter) => {
    const resolved = parameter.$ref
      ? resolveOpenApiRef(openApi, documentPath, parameter.$ref)
      : parameter;
    return resolved.name;
  });
}

function validateLocalOpenApi(localOpenApi) {
  assert.equal(localOpenApi.openapi, '3.1.0');
  assert.equal(localOpenApi.info.version, '0.1.0');
  assert.equal(localOpenApi.servers.length, 1);
  assert.equal(localOpenApi.servers[0].url, 'http://127.0.0.1:8787/api/v1');
  assert.equal(localOpenApi.servers[0]['x-loopback-only'], true);
  assert.deepEqual(localOpenApi.security, [{ LocalSessionCookie: [] }]);

  const cookie = localOpenApi.components.securitySchemes.LocalSessionCookie;
  assert.equal(cookie.type, 'apiKey');
  assert.equal(cookie.in, 'cookie');
  assert.equal(cookie.name, 'zenbo_local_session');

  const expectedOperations = new Map([
    ['/bootstrap', ['post']],
    ['/status', ['get']],
    ['/settings', ['get', 'put']],
    ['/settings/setup', ['post']],
    ['/settings/unlock', ['post']],
    ['/settings/test', ['post']],
    ['/conversation', ['get']],
    ['/conversation/turns', ['post']],
    ['/conversation/cancel', ['post']],
    ['/conversation/tool-calls/{callId}', ['put']],
    ['/conversation/playback', ['post']],
    ['/conversation/audio/{artifactId}', ['get']],
    ['/events', ['get']],
  ]);
  assert.deepEqual(
    new Set(Object.keys(localOpenApi.paths)),
    new Set(expectedOperations.keys()),
  );

  for (const [path, methods] of expectedOperations) {
    for (const method of methods) {
      const operation = localOpenApi.paths[path][method];
      assert(operation, `Missing local ${method.toUpperCase()} ${path}`);
      assert(Object.keys(operation.responses).length > 0);
    }
  }

  const bootstrap = localOpenApi.paths['/bootstrap'].post;
  assert.deepEqual(bootstrap.security, []);
  const setCookie = bootstrap.responses['200'].headers['Set-Cookie'].description;
  for (const marker of ['HttpOnly', 'SameSite=Strict', 'Path=/api/v1', 'Domain is forbidden']) {
    assert(setCookie.includes(marker), `Bootstrap cookie lacks ${marker}`);
  }

  const originProtected = [
    ['/bootstrap', 'post'],
    ['/settings', 'put'],
    ['/settings/setup', 'post'],
    ['/settings/unlock', 'post'],
    ['/settings/test', 'post'],
    ['/conversation/turns', 'post'],
    ['/conversation/cancel', 'post'],
    ['/conversation/tool-calls/{callId}', 'put'],
    ['/conversation/playback', 'post'],
    ['/events', 'get'],
  ];
  for (const [path, method] of originProtected) {
    const headers = operationHeaders(
      localOpenApi,
      localOpenApi.paths[path][method],
      localOpenApiPath,
    );
    assert(headers.includes('Origin'), `${method} ${path} lacks exact Origin`);
  }
  assert.equal(
    localOpenApi.components.parameters.RendererOrigin.schema.const,
    'http://127.0.0.1:8787',
  );

  const events = localOpenApi.paths['/events'].get;
  assert.equal(events['x-websocket'], true);
  assert.equal(
    events['x-message-schema'],
    './schemas/event.schema.json',
  );
  assert(events.parameters.some((parameter) => parameter.name === 'after'));

  const envelopeFields = ['ok', 'requestId', 'data', 'error'];
  assert.deepEqual(localOpenApi.components.schemas.SuccessEnvelope.required, envelopeFields);
  assert.deepEqual(localOpenApi.components.schemas.ErrorEnvelope.required, envelopeFields);

  assert.deepEqual(localOpenApi.components.schemas.LocalErrorCode.enum, localErrorCodes);
  assert.equal(
    localOpenApi.components.schemas.LocalError.properties.code.$ref,
    '#/components/schemas/LocalErrorCode',
  );
  assert.deepEqual(localOpenApi.components.schemas.GatewayState.enum, gatewayStates);
  assert.equal(
    localOpenApi.components.schemas.StatusData.properties.gatewayState.$ref,
    '#/components/schemas/GatewayState',
  );
  assert.deepEqual(localOpenApi.components.schemas.TurnState.enum, turnStates);

  const settingsData = localOpenApi.components.schemas.SettingsData;
  const settings = settingsData.properties;
  for (const required of ['trustMode', 'credentialConfigured', 'certificatePinConfigured']) {
    assert(settingsData.required.includes(required), `SettingsData lacks ${required}`);
  }
  for (const secret of ['deviceToken', 'deviceCredential', 'certificatePin', 'confirmedFingerprint']) {
    assert(!(secret in settings), `Redacted SettingsData exposes ${secret}`);
  }

  const settingsSetup = localOpenApi.components.schemas.SettingsSetupRequest;
  assert.deepEqual(settingsSetup.required, [
    'pin',
    'confirmPin',
    'gatewayUrl',
    'deviceToken',
    'trustMode',
    'agentProfile',
    'context',
  ]);
  assert.equal(settingsSetup.properties.deviceToken.writeOnly, true);
  assert.equal(settingsSetup.properties.trustMode.$ref, '#/components/schemas/TrustMode');
  assert(settingsSetup.properties.certificatePin);
  assert(settingsSetup.properties.confirmedFingerprint);
  assert.equal(
    localOpenApi.paths['/settings/setup'].post.responses['200'].content['application/json']
      .schema.$ref,
    '#/components/schemas/SettingsResponse',
  );

  const settingsUpdate = localOpenApi.components.schemas.SettingsUpdateRequest;
  assert.deepEqual(settingsUpdate.required, ['trustMode']);
  assert(!('deviceCredential' in settingsUpdate.properties));
  assert.equal(settingsUpdate.properties.deviceToken.writeOnly, true);
  assert.equal(settingsUpdate.properties.trustMode.$ref, '#/components/schemas/TrustMode');
  assert(settingsUpdate.properties.certificatePin);
  assert(settingsUpdate.properties.confirmedFingerprint);

  const bootstrapRequest = localOpenApi.components.schemas.BootstrapRequest;
  assert(bootstrapRequest.required.includes('bootstrapToken'));
  assert.equal(bootstrapRequest.properties.bootstrapToken.minLength, 43);
  assert.equal(bootstrapRequest.properties.bootstrapToken.writeOnly, true);

  const settingsTestRequest = localOpenApi.components.schemas.SettingsTestRequest;
  assert.deepEqual(settingsTestRequest.required, [
    'gatewayUrl',
    'trustMode',
    'agentProfile',
  ]);
  assert.equal(settingsTestRequest.additionalProperties, false);
  assert.equal(settingsTestRequest.properties.trustMode.$ref, '#/components/schemas/TrustMode');
  assert.equal(settingsTestRequest.properties.deviceToken.writeOnly, true);
  const settingsTestDescription = localOpenApi.paths['/settings/test'].post.description;
  assert(settingsTestDescription.includes('setup is required'));
  assert(settingsTestDescription.includes('unlock lease'));

  const settingsTestData = localOpenApi.components.schemas.SettingsTestData;
  assert.deepEqual(settingsTestData.required, [
    'reachable',
    'tlsTrusted',
    'latencyMs',
    'protocolVersion',
    'confirmationRequired',
    'fingerprint',
    'authenticated',
    'capabilitiesReceived',
  ]);
  assert.equal(
    settingsTestData.properties.fingerprint.anyOf[0].$ref,
    '#/components/schemas/SpkiFingerprint',
  );

  const conversation = localOpenApi.components.schemas.ConversationData;
  assert.deepEqual(conversation.required, [
    'sessionId',
    'activeTurnId',
    'turnState',
    'lastSequence',
    'transcript',
    'assistantText',
  ]);
  assert.equal(conversation.properties.turnState.$ref, '#/components/schemas/TurnState');
  assert(!('state' in conversation.properties));
  assert(!('events' in conversation.properties));

  const cancel = localOpenApi.components.schemas.CancelTurnRequest;
  assert.deepEqual(cancel.required, ['reason']);
  assert.deepEqual(cancel.properties.reason.enum, [
    'barge_in',
    'user_interaction',
    'screen_off',
    'sleep',
  ]);
  assert.deepEqual(
    localOpenApi.components.schemas.CancelResultData.required,
    ['remoteCancelled', 'robotStopped'],
  );

  const turns = localOpenApi.paths['/conversation/turns'].post;
  assert(turns.requestBody.content['application/json']);
  assert(turns.requestBody.content['multipart/form-data']);
  assert.equal(
    localOpenApi.components.schemas.VoiceTurnRequest.properties.audio['x-max-bytes'],
    2097152,
  );
  assert.equal(
    localOpenApi.components.schemas.VoiceTurnRequest.properties.durationMs.maximum,
    30000,
  );

  assert(!('/conversation/tools' in localOpenApi.paths));
  assert.equal(
    localOpenApi.paths['/conversation/tool-calls/{callId}'].put.requestBody.content[
      'application/json'
    ].schema.$ref,
    '../agent-gateway/openapi.json#/components/schemas/ToolCallUpdate',
  );
  assert.equal(
    localOpenApi.paths['/conversation/playback'].post.requestBody.content[
      'application/json'
    ].schema.$ref,
    '../agent-gateway/openapi.json#/components/schemas/PlaybackUpdate',
  );

  const audioSuccess = localOpenApi.paths['/conversation/audio/{artifactId}'].get.responses['200'];
  assert(!audioSuccess.content['application/json']);

  for (const [path, methods] of expectedOperations) {
    for (const method of methods) {
      const operation = localOpenApi.paths[path][method];
      for (const [status, responseRef] of Object.entries(operation.responses)) {
        if (status === '101' || (path === '/conversation/audio/{artifactId}' && status === '200')) {
          continue;
        }
        const response = responseRef.$ref
          ? resolveOpenApiRef(localOpenApi, localOpenApiPath, responseRef.$ref)
          : responseRef;
        const media = response.content?.['application/json'];
        assert(media, `${method} ${path} ${status} lacks uniform JSON envelope`);
        const responseSchema = media.schema.$ref
          ? resolveOpenApiRef(localOpenApi, localOpenApiPath, media.schema.$ref)
          : media.schema;
        if (Number(status) >= 200 && Number(status) < 300) {
          assert(
            responseSchema.allOf?.some(
              (entry) => entry.$ref === '#/components/schemas/SuccessEnvelope',
            ),
            `${method} ${path} ${status} is not a SuccessEnvelope`,
          );
        } else {
          assert.equal(
            media.schema.$ref,
            '#/components/schemas/ErrorEnvelope',
            `${method} ${path} ${status} is not an ErrorEnvelope`,
          );
        }
      }
    }
  }

  walk(localOpenApi, (value, path) => {
    if ('$ref' in value) {
      resolveOpenApiRef(localOpenApi, localOpenApiPath, value.$ref);
      const siblingKeys = Object.keys(value).filter((key) => key !== '$ref');
      assert.equal(siblingKeys.length, 0, `${path} has unsupported $ref siblings`);
    }
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
const localOpenApi = readJson(localOpenApiPath);
const localBootstrap = readFileSync(localBootstrapPath, 'utf8');
const localEvents = readFileSync(localEventsPath, 'utf8');
const localEventSchema = readJson(localEventSchemaPath);

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
const emotionValues = ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED'];
const fixtureManifest = readJson(join(validFixtureDir, 'tool-manifest-minimal.json'));
const fixtureEmotionTool = fixtureManifest.tools.find((tool) => tool.name === 'show_emotion');
assert(fixtureEmotionTool, 'show_emotion fixture is missing');
assert.deepEqual(fixtureEmotionTool.inputSchema.required, ['emotion']);
assert.deepEqual(fixtureEmotionTool.inputSchema.properties.emotion.enum, emotionValues);
assert.equal(fixtureEmotionTool.inputSchema.properties.durationMs.minimum, 0);
assert.equal(fixtureEmotionTool.inputSchema.properties.durationMs.maximum, 30000);
assert.deepEqual(fixtureEmotionTool.resultSchema.required, ['ok', 'emotion', 'durationMs']);
assert.deepEqual(fixtureEmotionTool.resultSchema.properties.emotion.enum, emotionValues);
assert.equal(fixtureEmotionTool.resultSchema.properties.durationMs.minimum, 0);
assert.equal(fixtureEmotionTool.resultSchema.properties.durationMs.maximum, 30000);
const fixtureEmotionCall = readJson(join(validFixtureDir, 'ws-tool-call.json'));
assert.deepEqual(fixtureEmotionCall.data.arguments, { emotion: 'HAPPY', durationMs: 0 });
const fixtureEmotionResult = readJson(join(validFixtureDir, 'local-tool-call-result.json'));
assert.deepEqual(fixtureEmotionResult.output, {
  ok: true,
  emotion: 'HAPPY',
  durationMs: 0,
});
assert(wsProtocol.includes('authoritative\n  `session.snapshot`'));
assert(!wsProtocol.includes('fails with `410`'));
assert(wsProtocol.includes('restart resumes only when the marker is empty'));
validateOpenApi(openApi);
validateLocalOpenApi(localOpenApi);
assert(localBootstrap.includes('HttpOnly; SameSite=Strict'));
assert(localBootstrap.includes('MUST bind only to IPv4 loopback `127.0.0.1:8787`'));
assert(localBootstrap.includes('Every verification attempt consumes the token'));
assert(localBootstrap.includes('validates and persists the entire request atomically'));
assert(localBootstrap.includes('`certificatePinConfigured`'));
assert(localBootstrap.includes('`deviceToken`'));
assert(localEvents.includes('ws://127.0.0.1:8787/api/v1/events'));
assert.equal(localEventSchema.$schema, 'https://json-schema.org/draft/2020-12/schema');
assert.deepEqual(
  localEventSchema.$defs.localControlEnvelope.properties.type.enum,
  [
    'local.gateway.state',
    'local.robot.state',
    'local.screen.state',
    'local.interaction',
  ],
);
assert(!('sequence' in localEventSchema.$defs.localControlEnvelope.properties));
const localGatewayEventData = localEventSchema.$defs.gatewayState;
assert.equal(
  localGatewayEventData.properties.state.$ref,
  '../openapi.json#/components/schemas/GatewayState',
);
assert.equal(localGatewayEventData.properties.detail.maxLength, 512);
assert.equal(
  localGatewayEventData.properties.errorCode.$ref,
  '../openapi.json#/components/schemas/LocalErrorCode',
);

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
  if (file.startsWith('local-tool-call')) {
    return {
      schema: openApi.components.schemas.ToolCallUpdate,
      root: openApi,
      toolManifest: false,
    };
  }
  if (file.startsWith('local-playback')) {
    return {
      schema: openApi.components.schemas.PlaybackUpdate,
      root: openApi,
      toolManifest: false,
    };
  }
  if (file.startsWith('local-event-')) {
    return {
      schema: localEventSchema,
      root: localEventSchema,
      toolManifest: false,
    };
  }
  if (file.startsWith('local-bootstrap-replayed-token')) {
    return {
      schema: {},
      root: {},
      toolManifest: false,
      bootstrapReplay: true,
    };
  }
  const localSchemas = {
    'local-bootstrap-request': 'BootstrapRequest',
    'local-status-response': 'StatusResponse',
    'local-error-envelope': 'ErrorEnvelope',
    'local-settings-setup': 'SettingsSetupRequest',
    'local-settings-update': 'SettingsUpdateRequest',
    'local-settings-response': 'SettingsResponse',
    'local-settings-test-request': 'SettingsTestRequest',
    'local-settings-test-response': 'SettingsTestResponse',
    'local-conversation-response': 'ConversationResponse',
    'local-text-turn': 'TextTurnRequest',
    'local-cancel-turn': 'CancelTurnRequest',
  };
  const localPrefix = Object.keys(localSchemas).find((prefix) => file.startsWith(prefix));
  if (localPrefix) {
    return {
      schema: localOpenApi.components.schemas[localSchemas[localPrefix]],
      root: localOpenApi,
      toolManifest: false,
      settingsWriteKind:
        localPrefix === 'local-settings-setup'
          ? 'setup'
          : localPrefix === 'local-settings-update'
            ? 'update'
            : undefined,
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
  if (target.settingsWriteKind) {
    errors.push(...validateLocalSettingsWriteSemantics(fixture, target.settingsWriteKind));
  }
  if (target.bootstrapReplay) {
    errors.push(
      ...validateJsonSchema(
        fixture.first,
        localOpenApi.components.schemas.BootstrapRequest,
        localOpenApi,
        '$.first',
      ),
      ...validateJsonSchema(
        fixture.replay,
        localOpenApi.components.schemas.BootstrapRequest,
        localOpenApi,
        '$.replay',
      ),
    );
    if (fixture.first?.bootstrapToken === fixture.replay?.bootstrapToken) {
      errors.push('$.replay.bootstrapToken: one-time bootstrap token was replayed');
    }
  }
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
  if (target.settingsWriteKind) {
    errors.push(...validateLocalSettingsWriteSemantics(fixture, target.settingsWriteKind));
  }
  if (target.bootstrapReplay) {
    errors.push(
      ...validateJsonSchema(
        fixture.first,
        localOpenApi.components.schemas.BootstrapRequest,
        localOpenApi,
        '$.first',
      ),
      ...validateJsonSchema(
        fixture.replay,
        localOpenApi.components.schemas.BootstrapRequest,
        localOpenApi,
        '$.replay',
      ),
    );
    if (fixture.first?.bootstrapToken === fixture.replay?.bootstrapToken) {
      errors.push('$.replay.bootstrapToken: one-time bootstrap token was replayed');
    }
  }
  assert(errors.length > 0, `${file} should be rejected`);
  invalidCount += 1;
}

console.log(`OpenAPI contract: OK (${Object.keys(openApi.paths).length} paths)`);
console.log(`Local Runtime contract: OK (${Object.keys(localOpenApi.paths).length} paths)`);
console.log(`Valid fixtures: OK (${validCount})`);
console.log(`Invalid fixtures: OK (${invalidCount} rejected)`);
