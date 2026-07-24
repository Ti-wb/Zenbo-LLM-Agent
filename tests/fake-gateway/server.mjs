import { createHash, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

import { encodeWebSocketFrame, WebSocketFrameDecoder } from './websocket.mjs';

export const FIXTURE_DEVICE_TOKEN = 'fake-device-token-for-tests-only';
export const FIXTURE_DEVICE_ID = 'zenbo-k-fixture-device';
export const PROTOCOL_VERSION = '1.0';

const API_PREFIX = '/agent/v1';
const MAX_JSON_BYTES = 64 * 1024;
const MAX_WAV_BYTES = 2 * 1024 * 1024;
const MAX_TOOL_OUTPUT_BYTES = 16 * 1024;
const WEBSOCKET_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const EVENT_TYPES = [
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

const TOOL_ALLOWLIST = new Set([
  'get_system_status',
  'start_robot_following',
  'stop_robot_following',
  'look_at_user',
  'show_emotion',
  'go_to_sleep',
]);
const EMOTION_VALUES = ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED'];
const SHOW_EMOTION_INPUT_SCHEMA = {
  type: 'object',
  properties: {
    emotion: { type: 'string', enum: EMOTION_VALUES },
    durationMs: { type: 'integer', minimum: 0, maximum: 30000 },
  },
  required: ['emotion'],
  additionalProperties: false,
};
const SHOW_EMOTION_RESULT_SCHEMA = {
  type: 'object',
  properties: {
    ok: { type: 'boolean' },
    emotion: { type: 'string', enum: EMOTION_VALUES },
    durationMs: { type: 'integer', minimum: 0, maximum: 30000 },
  },
  required: ['ok', 'emotion', 'durationMs'],
  additionalProperties: false,
};

export const CAPABILITIES = Object.freeze({
  protocolVersion: PROTOCOL_VERSION,
  audioInput: {
    contentTypes: ['audio/wav'],
    maxBytes: MAX_WAV_BYTES,
    maxDurationMs: 30000,
  },
  audioOutput: {
    contentTypes: ['audio/wav'],
    maxBytes: 10 * 1024 * 1024,
  },
  eventTypes: EVENT_TYPES,
  agentProfiles: [
    {
      id: 'default',
      displayName: 'Fake Gateway Agent',
      languages: ['zh-TW', 'en-US'],
      isDefault: true,
    },
  ],
  retentionPolicy: {
    rawAudio: { retained: false, maxAgeSeconds: 0 },
    transcript: { retained: true, maxAgeSeconds: 300 },
  },
});

class HttpProblem extends Error {
  constructor(status, code, message, { retryable = false, headers = {} } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
    this.headers = headers;
  }
}

function isoTime(milliseconds) {
  return new Date(milliseconds).toISOString();
}

function isUuid(value) {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

function isDateTime(value) {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function sha256(buffer, encoding = 'hex') {
  return createHash('sha256').update(buffer).digest(encoding);
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    const entries = Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`);
    return `{${entries.join(',')}}`;
  }
  return JSON.stringify(value);
}

function assertObject(value, message = 'Expected a JSON object') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new HttpProblem(400, 'INVALID_REQUEST', message);
  }
}

function assertExactKeys(value, required, allowed = required) {
  assertObject(value);
  for (const key of required) {
    if (!(key in value)) throw new HttpProblem(400, 'INVALID_REQUEST', `Missing ${key}`);
  }
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) {
      throw new HttpProblem(400, 'INVALID_REQUEST', `Unexpected field ${key}`);
    }
  }
}

function createProblem(error) {
  const titles = {
    400: 'Bad Request',
    401: 'Unauthorized',
    404: 'Not Found',
    409: 'Conflict',
    410: 'Gone',
    413: 'Payload Too Large',
    415: 'Unsupported Media Type',
    422: 'Unprocessable Content',
    426: 'Upgrade Required',
    429: 'Too Many Requests',
    500: 'Internal Server Error',
  };
  return {
    type: 'about:blank',
    title: titles[error.status] ?? 'Request Failed',
    status: error.status,
    detail: error.message,
    code: error.code,
    retryable: Boolean(error.retryable),
    requestId: randomUUID(),
  };
}

function sendJson(response, status, body, headers = {}) {
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': bytes.length,
    'Cache-Control': 'no-store',
    ...headers,
  });
  response.end(bytes);
}

function sendProblem(response, error) {
  const body = createProblem(error);
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(error.status, {
    'Content-Type': 'application/problem+json; charset=utf-8',
    'Content-Length': bytes.length,
    'Cache-Control': 'no-store',
    ...error.headers,
  });
  response.end(bytes);
}

function sendEmpty(response, status, headers = {}) {
  response.writeHead(status, { 'Cache-Control': 'no-store', ...headers });
  response.end();
}

async function readBody(request, maximumBytes) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > maximumBytes) {
      throw new HttpProblem(413, 'PAYLOAD_TOO_LARGE', 'Request body exceeds the fake gateway limit');
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJson(request, maximumBytes = MAX_JSON_BYTES) {
  const contentType = String(request.headers['content-type'] ?? '').split(';', 1)[0].trim();
  if (contentType !== 'application/json') {
    throw new HttpProblem(415, 'UNSUPPORTED_MEDIA_TYPE', 'Expected application/json');
  }
  const bytes = await readBody(request, maximumBytes);
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'Malformed JSON request body');
  }
}

function parseMultipart(buffer, contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;\s]+))/i.exec(contentType);
  const boundary = match?.[1] ?? match?.[2];
  if (!boundary) throw new HttpProblem(400, 'INVALID_REQUEST', 'Missing multipart boundary');

  const fields = new Map();
  const marker = `--${boundary}`;
  for (let part of buffer.toString('latin1').split(marker).slice(1)) {
    if (part.startsWith('--')) break;
    if (part.startsWith('\r\n')) part = part.slice(2);
    if (part.endsWith('\r\n')) part = part.slice(0, -2);
    const separator = part.indexOf('\r\n\r\n');
    if (separator < 0) continue;
    const rawHeaders = part.slice(0, separator);
    const rawValue = part.slice(separator + 4);
    const disposition = rawHeaders
      .split('\r\n')
      .find((line) => line.toLowerCase().startsWith('content-disposition:'));
    const name = /\bname="([^"]+)"/i.exec(disposition ?? '')?.[1];
    if (!name || fields.has(name)) {
      throw new HttpProblem(400, 'INVALID_REQUEST', 'Invalid or duplicate multipart field');
    }
    const partContentType = rawHeaders
      .split('\r\n')
      .find((line) => line.toLowerCase().startsWith('content-type:'))
      ?.split(':', 2)[1]
      ?.trim()
      ?.toLowerCase();
    fields.set(name, {
      bytes: Buffer.from(rawValue, 'latin1'),
      contentType: partContentType,
    });
  }
  return fields;
}

function textField(fields, name, required = true) {
  const field = fields.get(name);
  if (!field) {
    if (!required) return undefined;
    throw new HttpProblem(400, 'INVALID_REQUEST', `Missing multipart field ${name}`);
  }
  return field.bytes.toString('utf8');
}

export function createFixtureWav() {
  const sampleCount = 320;
  const dataBytes = sampleCount * 2;
  const wav = Buffer.alloc(44 + dataBytes);
  wav.write('RIFF', 0, 'ascii');
  wav.writeUInt32LE(36 + dataBytes, 4);
  wav.write('WAVE', 8, 'ascii');
  wav.write('fmt ', 12, 'ascii');
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(16000, 24);
  wav.writeUInt32LE(32000, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write('data', 36, 'ascii');
  wav.writeUInt32LE(dataBytes, 40);
  return wav;
}

function validateWav(audio) {
  if (!Buffer.isBuffer(audio) || audio.length < 44) {
    throw new HttpProblem(422, 'INVALID_AUDIO', 'WAV body is too short');
  }
  if (audio.length > MAX_WAV_BYTES) {
    throw new HttpProblem(413, 'PAYLOAD_TOO_LARGE', 'WAV body exceeds 2 MiB');
  }
  if (audio.toString('ascii', 0, 4) !== 'RIFF' || audio.toString('ascii', 8, 12) !== 'WAVE') {
    throw new HttpProblem(422, 'INVALID_AUDIO', 'Audio is not a RIFF/WAVE file');
  }
}

function validateSessionRequest(body) {
  assertExactKeys(body, ['client', 'agentProfile', 'context', 'toolManifest']);
  assertExactKeys(
    body.client,
    ['appVersion', 'platform', 'robotModel', 'locale'],
    ['appVersion', 'platform', 'robotModel', 'osVersion', 'locale'],
  );
  if (
    !body.client.appVersion ||
    body.client.platform !== 'android' ||
    body.client.robotModel !== 'zenbo-k' ||
    !body.client.locale
  ) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'Invalid Android client descriptor');
  }
  if (body.agentProfile !== 'default') {
    throw new HttpProblem(400, 'INVALID_AGENT_PROFILE', 'Fake gateway supports profile default only');
  }
  assertExactKeys(body.context, ['robotName', 'language']);
  if (!body.context.robotName || !body.context.language) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'Robot context is incomplete');
  }

  const manifest = body.toolManifest;
  assertExactKeys(manifest, ['protocolVersion', 'manifestVersion', 'tools']);
  if (manifest.protocolVersion !== PROTOCOL_VERSION || !manifest.manifestVersion) {
    throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Invalid tool manifest version');
  }
  if (!Array.isArray(manifest.tools)) {
    throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Manifest tools must be an array');
  }
  const names = new Set();
  for (const tool of manifest.tools) {
    assertExactKeys(tool, [
      'name',
      'owner',
      'version',
      'description',
      'inputSchema',
      'resultSchema',
      'sideEffect',
      'idempotent',
      'requiresConfirmation',
      'timeoutMs',
    ]);
    if (!TOOL_ALLOWLIST.has(tool.name) || names.has(tool.name)) {
      throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Unknown or duplicate device tool');
    }
    if (!['native', 'web'].includes(tool.owner) || !/^\d+\.\d+\.\d+$/.test(tool.version)) {
      throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Invalid tool owner or version');
    }
    if (!Number.isInteger(tool.timeoutMs) || tool.timeoutMs < 100 || tool.timeoutMs > 15000) {
      throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Invalid tool timeout');
    }
    if (
      typeof tool.description !== 'string' ||
      !tool.description ||
      !['none', 'ui', 'physical'].includes(tool.sideEffect) ||
      typeof tool.idempotent !== 'boolean' ||
      typeof tool.requiresConfirmation !== 'boolean'
    ) {
      throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Invalid tool metadata');
    }
    for (const schema of [tool.inputSchema, tool.resultSchema]) {
      if (
        !schema ||
        schema.type !== 'object' ||
        schema.additionalProperties !== false ||
        (schema.required !== undefined && !Array.isArray(schema.required))
      ) {
        throw new HttpProblem(400, 'INVALID_TOOL_MANIFEST', 'Invalid tool object schema');
      }
    }
    if (
      tool.name === 'show_emotion' &&
      (
        tool.owner !== 'web' ||
        tool.sideEffect !== 'ui' ||
        stableStringify(tool.inputSchema) !== stableStringify(SHOW_EMOTION_INPUT_SCHEMA) ||
        stableStringify(tool.resultSchema) !== stableStringify(SHOW_EMOTION_RESULT_SCHEMA)
      )
    ) {
      throw new HttpProblem(
        400,
        'INVALID_TOOL_MANIFEST',
        'show_emotion must use the canonical emotion and durationMs schemas',
      );
    }
    names.add(tool.name);
  }
}

function validateShowEmotionOutput(output) {
  assertExactKeys(output, ['ok', 'emotion', 'durationMs']);
  if (
    output.ok !== true ||
    !EMOTION_VALUES.includes(output.emotion) ||
    !Number.isInteger(output.durationMs) ||
    output.durationMs < 0 ||
    output.durationMs > 30000
  ) {
    throw new HttpProblem(422, 'INVALID_TOOL_RESULT', 'Invalid show_emotion output');
  }
}

function validateToolUpdate(body, toolName) {
  assertExactKeys(body, ['status', 'updatedAt'], ['status', 'updatedAt', 'output', 'error']);
  if (!['accepted', 'succeeded', 'failed', 'rejected'].includes(body.status)) {
    throw new HttpProblem(422, 'INVALID_TOOL_RESULT', 'Invalid tool result status');
  }
  if (!isDateTime(body.updatedAt)) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'updatedAt must be an RFC 3339 timestamp');
  }
  if (body.status === 'succeeded' && !('output' in body)) {
    throw new HttpProblem(422, 'INVALID_TOOL_RESULT', 'Succeeded tool result requires output');
  }
  if (['failed', 'rejected'].includes(body.status)) {
    assertExactKeys(body.error, ['code', 'message', 'retryable']);
  }
  if ('output' in body && Buffer.byteLength(JSON.stringify(body.output)) > MAX_TOOL_OUTPUT_BYTES) {
    throw new HttpProblem(413, 'PAYLOAD_TOO_LARGE', 'Tool output exceeds 16 KiB');
  }
  if (body.status === 'succeeded' && toolName === 'show_emotion') {
    validateShowEmotionOutput(body.output);
  }
}

function validatePlayback(body) {
  assertExactKeys(
    body,
    ['turnId', 'artifactId', 'status', 'timestamp'],
    ['turnId', 'artifactId', 'status', 'timestamp', 'positionMs', 'reason'],
  );
  if (!isUuid(body.turnId) || !isUuid(body.artifactId) || !isDateTime(body.timestamp)) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'Invalid playback correlation or timestamp');
  }
  if (!['started', 'completed', 'interrupted'].includes(body.status)) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'Invalid playback status');
  }
  if (
    body.positionMs !== undefined &&
    (!Number.isInteger(body.positionMs) || body.positionMs < 0)
  ) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'positionMs must be a non-negative integer');
  }
  if (
    body.status === 'interrupted' &&
    !['barge_in', 'screen_off', 'playback_error', 'client_cancelled'].includes(body.reason)
  ) {
    throw new HttpProblem(400, 'INVALID_REQUEST', 'Interrupted playback requires a reason');
  }
}

function argumentValue(argv, index, flag) {
  const value = argv[index + 1];
  if (!value || value.startsWith('--')) throw new Error(`${flag} requires a value`);
  return value;
}

export function parseCliArguments(argv) {
  const config = {
    host: '127.0.0.1',
    port: 8788,
    deviceId: FIXTURE_DEVICE_ID,
    tlsCertPath: null,
    tlsKeyPath: null,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    if (flag === '--port') {
      const value = Number(argumentValue(argv, index, flag));
      if (!Number.isInteger(value) || value < 0 || value > 65535) {
        throw new Error('--port must be an integer from 0 through 65535');
      }
      config.port = value;
      index += 1;
    } else if (flag === '--host') {
      config.host = argumentValue(argv, index, flag);
      index += 1;
    } else if (flag === '--device-id') {
      config.deviceId = argumentValue(argv, index, flag);
      index += 1;
    } else if (flag === '--tls-cert') {
      config.tlsCertPath = argumentValue(argv, index, flag);
      index += 1;
    } else if (flag === '--tls-key') {
      config.tlsKeyPath = argumentValue(argv, index, flag);
      index += 1;
    } else {
      throw new Error(`Unknown argument ${flag}`);
    }
  }
  if (Boolean(config.tlsCertPath) !== Boolean(config.tlsKeyPath)) {
    throw new Error('--tls-cert and --tls-key must be provided together');
  }
  return config;
}

export class FakeAgentGateway {
  constructor({
    host = '127.0.0.1',
    port = 0,
    deviceId = FIXTURE_DEVICE_ID,
    deviceToken = FIXTURE_DEVICE_TOKEN,
    retentionLimit = 64,
    artifactTtlMs = 60_000,
    now = () => Date.now(),
    tlsCert = null,
    tlsKey = null,
  } = {}) {
    const hasTlsCert = tlsCert !== null && tlsCert !== undefined;
    const hasTlsKey = tlsKey !== null && tlsKey !== undefined;
    if (hasTlsCert !== hasTlsKey) {
      throw new TypeError('tlsCert and tlsKey must be provided together');
    }
    if (
      hasTlsCert &&
      ((typeof tlsCert === 'string' && tlsCert.length === 0) ||
        (Buffer.isBuffer(tlsCert) && tlsCert.length === 0) ||
        (typeof tlsKey === 'string' && tlsKey.length === 0) ||
        (Buffer.isBuffer(tlsKey) && tlsKey.length === 0))
    ) {
      throw new TypeError('tlsCert and tlsKey must not be empty');
    }
    this.host = host;
    this.port = port;
    this.deviceId = deviceId;
    this.deviceToken = deviceToken;
    this.retentionLimit = Math.max(1, retentionLimit);
    this.artifactTtlMs = artifactTtlMs;
    this.now = now;
    this.tlsOptions = hasTlsCert ? { cert: tlsCert, key: tlsKey } : null;
    this.secure = Boolean(this.tlsOptions);
    this.sessions = new Map();
    this.idempotency = new Map();
    this.sockets = new Set();
    this.httpServer = null;
    this.origin = null;
  }

  async start() {
    if (this.httpServer) return this.origin;
    const requestListener = (request, response) => {
      this._handleHttp(request, response).catch((error) => {
        if (!response.headersSent) {
          sendProblem(
            response,
            error instanceof HttpProblem
              ? error
              : new HttpProblem(500, 'INTERNAL_ERROR', 'Fake gateway request failed'),
          );
        } else {
          response.destroy();
        }
      });
    };
    this.httpServer = this.secure
      ? createHttpsServer(this.tlsOptions, requestListener)
      : createHttpServer(requestListener);
    this.httpServer.on('upgrade', (request, socket, head) => {
      this._handleUpgrade(request, socket, head);
    });
    this.httpServer.on('connection', (socket) => {
      this.sockets.add(socket);
      socket.once('close', () => this.sockets.delete(socket));
    });
    await new Promise((resolveListen, rejectListen) => {
      this.httpServer.once('error', rejectListen);
      this.httpServer.listen(this.port, this.host, () => {
        this.httpServer.off('error', rejectListen);
        resolveListen();
      });
    });
    const address = this.httpServer.address();
    this.origin = `${this.secure ? 'https' : 'http'}://${this.host}:${address.port}`;
    return this.origin;
  }

  async stop() {
    if (!this.httpServer) return;
    const server = this.httpServer;
    this.httpServer = null;
    for (const socket of this.sockets) socket.destroy();
    await new Promise((resolveClose) => server.close(() => resolveClose()));
    this.sockets.clear();
  }

  get apiBaseUrl() {
    if (!this.origin) throw new Error('Fake gateway is not started');
    return `${this.origin}${API_PREFIX}`;
  }

  _authProblem(request) {
    if (
      request.headers.authorization !== `Bearer ${this.deviceToken}` ||
      request.headers['x-zenbo-device-id'] !== this.deviceId
    ) {
      return new HttpProblem(401, 'UNAUTHORIZED', 'Invalid fixture token or device binding');
    }
    if (request.headers['x-zenbo-protocol'] !== PROTOCOL_VERSION) {
      return new HttpProblem(426, 'PROTOCOL_MISMATCH', 'X-Zenbo-Protocol must be 1.0');
    }
    return null;
  }

  _requireAuth(request) {
    const problem = this._authProblem(request);
    if (problem) throw problem;
  }

  _idempotencyKey(request) {
    const key = request.headers['idempotency-key'];
    if (!isUuid(key)) {
      throw new HttpProblem(400, 'INVALID_IDEMPOTENCY_KEY', 'Idempotency-Key must be a UUID');
    }
    return key;
  }

  _idempotencyReplay(response, scope, key, signature) {
    const stored = this.idempotency.get(`${scope}:${key}`);
    if (!stored) return false;
    if (stored.signature !== signature) {
      throw new HttpProblem(409, 'IDEMPOTENCY_CONFLICT', 'Idempotency key reused with different input');
    }
    if (stored.body === null) sendEmpty(response, stored.status, stored.headers);
    else sendJson(response, stored.status, stored.body, stored.headers);
    return true;
  }

  _storeIdempotent(response, scope, key, signature, status, body, headers = {}) {
    this.idempotency.set(`${scope}:${key}`, { signature, status, body, headers });
    if (body === null) sendEmpty(response, status, headers);
    else sendJson(response, status, body, headers);
  }

  _sessionView(session) {
    return {
      sessionId: session.id,
      deviceId: session.deviceId,
      protocolVersion: PROTOCOL_VERSION,
      state: session.state,
      createdAt: session.createdAt,
      expiresAt: session.expiresAt,
      lastSequence: session.cursor,
    };
  }

  _getSession(sessionId) {
    const session = this.sessions.get(sessionId);
    if (!session) throw new HttpProblem(404, 'SESSION_NOT_FOUND', 'Session does not exist');
    return session;
  }

  async _handleHttp(request, response) {
    this._requireAuth(request);
    const url = new URL(request.url, this.origin ?? 'http://127.0.0.1');
    const path = url.pathname;

    if (request.method === 'GET' && path === `${API_PREFIX}/capabilities`) {
      sendJson(response, 200, CAPABILITIES);
      return;
    }
    if (request.method === 'POST' && path === `${API_PREFIX}/sessions`) {
      await this._createSession(request, response);
      return;
    }

    let match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)$`).exec(path);
    if (match && request.method === 'GET') {
      sendJson(response, 200, this._sessionView(this._getSession(match[1])));
      return;
    }
    if (match && request.method === 'DELETE') {
      this._deleteSession(match[1], response);
      return;
    }

    match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)/turns$`).exec(path);
    if (match && request.method === 'POST') {
      await this._createTurn(match[1], request, response);
      return;
    }

    match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)/turns/([^/]+)/cancel$`).exec(path);
    if (match && request.method === 'POST') {
      await this._cancelTurn(match[1], match[2], request, response);
      return;
    }

    match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)/tool-calls/([^/]+)$`).exec(path);
    if (match && request.method === 'PUT') {
      await this._updateToolCall(match[1], match[2], request, response);
      return;
    }

    match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)/audio/([^/]+)$`).exec(path);
    if (match && request.method === 'GET') {
      this._downloadArtifact(match[1], match[2], response);
      return;
    }

    match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)/playback$`).exec(path);
    if (match && request.method === 'POST') {
      await this._recordPlayback(match[1], request, response);
      return;
    }

    if (new RegExp(`^${API_PREFIX}/sessions/[^/]+/events$`).test(path)) {
      throw new HttpProblem(426, 'WEBSOCKET_UPGRADE_REQUIRED', 'Use a WebSocket upgrade');
    }
    throw new HttpProblem(404, 'NOT_FOUND', 'Fake gateway route does not exist');
  }

  async _createSession(request, response) {
    const key = this._idempotencyKey(request);
    const body = await readJson(request);
    const signature = stableStringify(body);
    if (this._idempotencyReplay(response, 'create-session', key, signature)) return;
    validateSessionRequest(body);

    const now = this.now();
    const session = {
      id: randomUUID(),
      deviceId: this.deviceId,
      state: 'active',
      createdAt: isoTime(now),
      expiresAt: isoTime(now + 60 * 60 * 1000),
      cursor: 0,
      events: [],
      clients: new Set(),
      turns: new Map(),
      calls: new Map(),
      artifacts: new Map(),
      playback: new Map(),
      activeTurnId: null,
      request: body,
    };
    this.sessions.set(session.id, session);
    const location = `${API_PREFIX}/sessions/${session.id}`;
    this._storeIdempotent(
      response,
      'create-session',
      key,
      signature,
      201,
      this._sessionView(session),
      { Location: location },
    );
  }

  _deleteSession(sessionId, response) {
    const session = this._getSession(sessionId);
    if (session.state !== 'closed') {
      this._appendEvent(session, null, 'session.closed', { reason: 'client_request' });
      session.state = 'closed';
      session.activeTurnId = null;
      setImmediate(() => {
        for (const client of session.clients) client.close(1000, 'session closed');
      });
    }
    sendEmpty(response, 204);
  }

  async _parseTurn(request) {
    const contentType = String(request.headers['content-type'] ?? '');
    if (contentType.toLowerCase().startsWith('application/json')) {
      const body = await readJson(request);
      assertExactKeys(body, ['clientTurnId', 'text'], ['clientTurnId', 'text', 'language']);
      if (!isUuid(body.clientTurnId) || typeof body.text !== 'string' || !body.text.trim()) {
        throw new HttpProblem(400, 'INVALID_REQUEST', 'Invalid text turn');
      }
      if (body.text.length > 16000) {
        throw new HttpProblem(413, 'PAYLOAD_TOO_LARGE', 'Text turn exceeds 16,000 characters');
      }
      return {
        clientTurnId: body.clientTurnId,
        transcript: body.text,
        language: body.language ?? 'zh-TW',
        signature: stableStringify(body),
      };
    }
    if (contentType.toLowerCase().startsWith('multipart/form-data')) {
      const bytes = await readBody(request, MAX_WAV_BYTES + 64 * 1024);
      const fields = parseMultipart(bytes, contentType);
      const audio = fields.get('audio');
      if (!audio || audio.contentType !== 'audio/wav') {
        throw new HttpProblem(415, 'UNSUPPORTED_MEDIA_TYPE', 'audio part must be audio/wav');
      }
      validateWav(audio.bytes);
      const clientTurnId = textField(fields, 'clientTurnId');
      const durationMs = Number(textField(fields, 'durationMs'));
      const language = textField(fields, 'language', false) ?? 'zh-TW';
      if (!isUuid(clientTurnId) || !Number.isInteger(durationMs) || durationMs < 1 || durationMs > 30000) {
        throw new HttpProblem(422, 'INVALID_AUDIO', 'Invalid WAV turn ID or duration');
      }
      const signature = stableStringify({
        clientTurnId,
        durationMs,
        language,
        audioSha256: sha256(audio.bytes),
      });
      return { clientTurnId, transcript: 'Fake WAV transcription.', language, signature };
    }
    throw new HttpProblem(415, 'UNSUPPORTED_MEDIA_TYPE', 'Expected JSON text or multipart WAV');
  }

  async _createTurn(sessionId, request, response) {
    const session = this._getSession(sessionId);
    const key = this._idempotencyKey(request);
    const input = await this._parseTurn(request);
    const scope = `turn:${sessionId}`;
    if (this._idempotencyReplay(response, scope, key, input.signature)) return;
    if (session.state !== 'active') {
      throw new HttpProblem(409, 'SESSION_NOT_ACTIVE', 'Session is not active');
    }
    if (session.activeTurnId) {
      const active = session.turns.get(session.activeTurnId);
      if (active && !['completed', 'cancelled', 'failed'].includes(active.state)) {
        throw new HttpProblem(409, 'TURN_IN_PROGRESS', 'Only one fake turn may be active');
      }
    }

    const now = this.now();
    const turn = {
      id: randomUUID(),
      clientTurnId: input.clientTurnId,
      state: 'accepted',
      transcript: input.transcript,
      language: input.language,
      updatedAt: isoTime(now),
      callId: null,
    };
    session.turns.set(turn.id, turn);
    session.activeTurnId = turn.id;
    const accepted = {
      sessionId,
      turnId: turn.id,
      clientTurnId: turn.clientTurnId,
      state: 'accepted',
      acceptedAt: isoTime(now),
    };
    this._storeIdempotent(
      response,
      scope,
      key,
      input.signature,
      202,
      accepted,
      { Location: `${API_PREFIX}/sessions/${sessionId}/turns/${turn.id}` },
    );
    setImmediate(() => this._beginTurn(session, turn));
  }

  _beginTurn(session, turn) {
    if (session.state !== 'active' || turn.state !== 'accepted') return;
    turn.state = 'processing';
    turn.updatedAt = isoTime(this.now());
    this._appendEvent(session, turn.id, 'turn.accepted', {});
    this._appendEvent(session, turn.id, 'stt.final', {
      text: turn.transcript,
      language: turn.language,
    });
    this._appendEvent(session, turn.id, 'agent.thinking', {});

    const tools = session.request.toolManifest.tools;
    const tool = tools.find((entry) => entry.name === 'get_system_status') ?? tools[0];
    if (!tool) {
      this._finalizeSuccessfulTurn(session, turn, 'Fake gateway response without a device tool.');
      return;
    }
    const callId = randomUUID();
    const timeoutMs = Math.min(tool.timeoutMs, 5000);
    const call = {
      id: callId,
      turnId: turn.id,
      toolName: tool.name,
      update: null,
      terminal: false,
    };
    session.calls.set(callId, call);
    turn.callId = callId;
    turn.state = 'waiting_for_tool';
    this._appendEvent(session, turn.id, 'tool.call', {
      callId,
      toolName: tool.name,
      toolVersion: tool.version,
      arguments: tool.name === 'show_emotion'
        ? { emotion: 'HAPPY', durationMs: 0 }
        : {},
      timeoutMs,
      deadlineAt: isoTime(this.now() + timeoutMs),
    });
  }

  async _updateToolCall(sessionId, callId, request, response) {
    const session = this._getSession(sessionId);
    if (session.state !== 'active') {
      throw new HttpProblem(409, 'SESSION_NOT_ACTIVE', 'Session is not active');
    }
    const call = session.calls.get(callId);
    if (!call) throw new HttpProblem(404, 'TOOL_CALL_NOT_FOUND', 'Tool call does not exist');
    const body = await readJson(request, MAX_TOOL_OUTPUT_BYTES + 4096);
    validateToolUpdate(body, call.toolName);
    const representation = stableStringify(body);
    if (call.update && stableStringify(call.update) === representation) {
      sendJson(response, 200, call.update);
      return;
    }
    if (call.terminal) {
      throw new HttpProblem(409, 'TOOL_RESULT_TERMINAL', 'Terminal tool result cannot be replaced');
    }
    if (call.update?.status === 'accepted' && body.status === 'accepted') {
      call.update = body;
      sendJson(response, 200, body);
      return;
    }

    call.update = body;
    call.terminal = ['succeeded', 'failed', 'rejected'].includes(body.status);
    sendJson(response, 200, body);
    if (!call.terminal) return;
    const turn = session.turns.get(call.turnId);
    if (!turn || ['completed', 'cancelled', 'failed'].includes(turn.state)) return;
    setImmediate(() => {
      if (body.status === 'succeeded') {
        this._finalizeSuccessfulTurn(
          session,
          turn,
          `Fake gateway response after ${call.toolName}.`,
        );
      } else {
        this._finalizeFailedTurn(session, turn, body.error);
      }
    });
  }

  _finalizeSuccessfulTurn(session, turn, text) {
    if (['completed', 'cancelled', 'failed'].includes(turn.state)) return;
    const bytes = createFixtureWav();
    const artifactId = randomUUID();
    const expiresAtMs = this.now() + this.artifactTtlMs;
    const artifact = {
      id: artifactId,
      turnId: turn.id,
      mimeType: 'audio/wav',
      bytes,
      sha256: sha256(bytes),
      digest: sha256(bytes, 'base64'),
      expiresAtMs,
    };
    session.artifacts.set(artifactId, artifact);
    this._appendEvent(session, turn.id, 'agent.text.final', { text });
    this._appendEvent(session, turn.id, 'tts.ready', {
      artifactId,
      mimeType: artifact.mimeType,
      byteLength: bytes.length,
      sha256: artifact.sha256,
      expiresAt: isoTime(expiresAtMs),
    });
    this._appendEvent(session, turn.id, 'turn.completed', {});
    turn.state = 'completed';
    turn.updatedAt = isoTime(this.now());
    if (session.activeTurnId === turn.id) session.activeTurnId = null;
  }

  _finalizeFailedTurn(session, turn, error) {
    if (['completed', 'cancelled', 'failed'].includes(turn.state)) return;
    const detail = error ?? {
      code: 'TOOL_FAILED',
      message: 'Fake device tool failed',
      retryable: false,
    };
    this._appendEvent(session, turn.id, 'turn.error', { error: detail });
    turn.state = 'failed';
    turn.updatedAt = isoTime(this.now());
    if (session.activeTurnId === turn.id) session.activeTurnId = null;
  }

  async _cancelTurn(sessionId, turnId, request, response) {
    const session = this._getSession(sessionId);
    const key = this._idempotencyKey(request);
    const body = await readJson(request);
    assertExactKeys(body, ['reason']);
    if (!['client_request', 'superseded', 'timeout'].includes(body.reason)) {
      throw new HttpProblem(400, 'INVALID_REQUEST', 'Invalid cancellation reason');
    }
    const signature = stableStringify(body);
    const scope = `cancel:${sessionId}:${turnId}`;
    if (this._idempotencyReplay(response, scope, key, signature)) return;
    const turn = session.turns.get(turnId);
    if (!turn) throw new HttpProblem(404, 'TURN_NOT_FOUND', 'Turn does not exist');

    const wasTerminal = ['completed', 'cancelled', 'failed'].includes(turn.state);
    if (!wasTerminal) {
      turn.state = 'cancelled';
      turn.updatedAt = isoTime(this.now());
      if (session.activeTurnId === turn.id) session.activeTurnId = null;
    }
    const result = { turnId: turn.id, state: turn.state, updatedAt: turn.updatedAt };
    this._storeIdempotent(response, scope, key, signature, 202, result);
    if (!wasTerminal) {
      setImmediate(() => {
        this._appendEvent(session, turn.id, 'turn.cancelled', { reason: body.reason });
      });
    }
  }

  _downloadArtifact(sessionId, artifactId, response) {
    const session = this._getSession(sessionId);
    const artifact = session.artifacts.get(artifactId);
    if (!artifact) throw new HttpProblem(404, 'ARTIFACT_NOT_FOUND', 'Audio artifact does not exist');
    if (this.now() >= artifact.expiresAtMs) {
      throw new HttpProblem(410, 'ARTIFACT_EXPIRED', 'Audio artifact has expired');
    }
    response.writeHead(200, {
      'Content-Type': artifact.mimeType,
      'Content-Length': artifact.bytes.length,
      Digest: `sha-256=:${artifact.digest}:`,
      'Cache-Control': 'no-store',
    });
    response.end(artifact.bytes);
  }

  async _recordPlayback(sessionId, request, response) {
    const session = this._getSession(sessionId);
    const key = this._idempotencyKey(request);
    const body = await readJson(request);
    validatePlayback(body);
    const signature = stableStringify(body);
    const scope = `playback:${sessionId}`;
    if (this._idempotencyReplay(response, scope, key, signature)) return;
    const artifact = session.artifacts.get(body.artifactId);
    if (!artifact || artifact.turnId !== body.turnId) {
      throw new HttpProblem(404, 'ARTIFACT_NOT_FOUND', 'Playback artifact correlation failed');
    }
    const correlation = `${body.turnId}:${body.artifactId}`;
    const prior = session.playback.get(correlation);
    if (!prior && body.status !== 'started') {
      throw new HttpProblem(409, 'PLAYBACK_ORDER', 'Playback must report started first');
    }
    if (prior) {
      if (stableStringify(prior) === signature) {
        this._storeIdempotent(response, scope, key, signature, 202, null);
        return;
      }
      if (['completed', 'interrupted'].includes(prior.status) || body.status === 'started') {
        throw new HttpProblem(409, 'PLAYBACK_TERMINAL', 'Playback transition is not monotonic');
      }
    }
    session.playback.set(correlation, body);
    this._storeIdempotent(response, scope, key, signature, 202, null);
  }

  _appendEvent(session, turnId, type, data) {
    const event = {
      protocolVersion: PROTOCOL_VERSION,
      eventId: randomUUID(),
      sequence: session.cursor + 1,
      sessionId: session.id,
      turnId,
      type,
      timestamp: isoTime(this.now()),
      data,
    };
    session.cursor = event.sequence;
    session.events.push(event);
    while (session.events.length > this.retentionLimit) session.events.shift();
    for (const client of session.clients) {
      if (event.sequence > client.lastSent) {
        client.send(event);
        client.lastSent = event.sequence;
      }
    }
    return event;
  }

  _rejectUpgrade(socket, error) {
    const body = Buffer.from(JSON.stringify(createProblem(error)));
    const title = error.status === 401 ? 'Unauthorized' : error.status === 426 ? 'Upgrade Required' : 'Request Failed';
    socket.end(
      `HTTP/1.1 ${error.status} ${title}\r\n` +
        'Content-Type: application/problem+json; charset=utf-8\r\n' +
        `Content-Length: ${body.length}\r\n` +
        'Connection: close\r\n' +
        '\r\n' +
        body,
    );
  }

  _handleUpgrade(request, socket, head) {
    try {
      const authProblem = this._authProblem(request);
      if (authProblem) throw authProblem;
      const url = new URL(request.url, this.origin ?? 'http://127.0.0.1');
      const match = new RegExp(`^${API_PREFIX}/sessions/([^/]+)/events$`).exec(url.pathname);
      if (!match) throw new HttpProblem(404, 'NOT_FOUND', 'WebSocket route does not exist');
      const session = this._getSession(match[1]);
      if (!['active', 'closing'].includes(session.state)) {
        throw new HttpProblem(409, 'SESSION_NOT_ACTIVE', 'Session event stream is closed');
      }
      const afterText = url.searchParams.get('after');
      const after = afterText === null ? 0 : Number(afterText);
      if (!Number.isSafeInteger(after) || after < 0) {
        throw new HttpProblem(400, 'INVALID_CURSOR', 'after must be a non-negative integer');
      }
      if (after > session.cursor) {
        throw new HttpProblem(409, 'CURSOR_AHEAD', 'Replay cursor is ahead of the session cursor');
      }
      const key = request.headers['sec-websocket-key'];
      if (
        request.headers.upgrade?.toLowerCase() !== 'websocket' ||
        request.headers['sec-websocket-version'] !== '13' ||
        typeof key !== 'string'
      ) {
        throw new HttpProblem(426, 'WEBSOCKET_UPGRADE_REQUIRED', 'Invalid WebSocket upgrade');
      }

      const accept = createHash('sha1').update(`${key}${WEBSOCKET_GUID}`).digest('base64');
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          `Sec-WebSocket-Accept: ${accept}\r\n` +
          '\r\n',
      );
      const client = this._createWebSocketClient(session, socket, after);
      session.clients.add(client);
      if (head?.length) client.decoder.push(head);

      queueMicrotask(() => {
        if (client.closed) return;
        client.send({
          protocolVersion: PROTOCOL_VERSION,
          eventId: randomUUID(),
          sequence: after,
          sessionId: session.id,
          turnId: null,
          type: 'session.ready',
          timestamp: isoTime(this.now()),
          data: { resumedAfter: after, gatewayTime: isoTime(this.now()) },
        });

        const earliestSequence = session.events[0]?.sequence ?? session.cursor + 1;
        const stale = session.cursor > 0 && after < earliestSequence - 1;
        if (stale) {
          client.send({
            protocolVersion: PROTOCOL_VERSION,
            eventId: randomUUID(),
            sequence: session.cursor,
            sessionId: session.id,
            turnId: null,
            type: 'session.snapshot',
            timestamp: isoTime(this.now()),
            data: {
              state: session.state,
              lastSequence: session.cursor,
              activeTurnId: session.activeTurnId,
            },
          });
          client.lastSent = session.cursor;
          return;
        }
        for (const event of session.events) {
          if (event.sequence > after) {
            client.send(event);
            client.lastSent = event.sequence;
          }
        }
      });
    } catch (error) {
      this._rejectUpgrade(
        socket,
        error instanceof HttpProblem
          ? error
          : new HttpProblem(500, 'INTERNAL_ERROR', 'WebSocket upgrade failed'),
      );
    }
  }

  _createWebSocketClient(session, socket, after) {
    const client = {
      socket,
      lastSent: after,
      closed: false,
      decoder: null,
      send(value) {
        if (!this.closed && !socket.destroyed) {
          socket.write(encodeWebSocketFrame(JSON.stringify(value)));
        }
      },
      close(code = 1000, reason = '') {
        if (this.closed) return;
        this.closed = true;
        const reasonBytes = Buffer.from(reason).subarray(0, 123);
        const payload = Buffer.alloc(2 + reasonBytes.length);
        payload.writeUInt16BE(code, 0);
        reasonBytes.copy(payload, 2);
        if (!socket.destroyed) socket.end(encodeWebSocketFrame(payload, { opcode: 0x8 }));
      },
    };
    const remove = () => {
      client.closed = true;
      session.clients.delete(client);
    };
    client.decoder = new WebSocketFrameDecoder({
      requireMasked: true,
      onFrame: ({ opcode, payload }) => {
        if (opcode === 0x8) {
          client.close(1000, 'closed');
        } else if (opcode === 0x9) {
          socket.write(encodeWebSocketFrame(payload, { opcode: 0xa }));
        } else if (opcode !== 0xa) {
          client.close(1002, 'gateway stream is outbound only');
        }
      },
      onError: () => client.close(1002, 'invalid frame'),
    });
    socket.on('data', (chunk) => client.decoder.push(chunk));
    socket.once('close', remove);
    socket.once('error', remove);
    return client;
  }
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : null;
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    const config = parseCliArguments(process.argv.slice(2));
    const tlsCert = config.tlsCertPath ? readFileSync(config.tlsCertPath) : null;
    const tlsKey = config.tlsKeyPath ? readFileSync(config.tlsKeyPath) : null;
    const gateway = new FakeAgentGateway({
      host: config.host,
      port: config.port,
      deviceId: config.deviceId,
      tlsCert,
      tlsKey,
    });
    const origin = await gateway.start();
    process.stdout.write(`Fake Agent Gateway listening at ${origin}${API_PREFIX}\n`);
    process.stdout.write(`Fixture device: ${config.deviceId}\n`);
    process.stdout.write('Fixture token is documented in tests/fake-gateway/README.md\n');

    const shutdown = async () => {
      await gateway.stop();
      process.exit(0);
    };
    process.once('SIGINT', shutdown);
    process.once('SIGTERM', shutdown);
  } catch (error) {
    process.stderr.write(`Fake Agent Gateway: ${error.message}\n`);
    process.exitCode = 1;
  }
}
