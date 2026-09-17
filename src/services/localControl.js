const gatewayStates = new Set([
  'UNCONFIGURED',
  'CONNECTING',
  'READY',
  'DEGRADED',
  'AUTH_ERROR',
  'TLS_ERROR',
  'INCOMPATIBLE',
  'OFFLINE',
]);

const localErrorCodes = new Set([
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
  'TURN_BUSY',
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
]);

const envelopeKeys = new Set(['protocolVersion', 'eventId', 'type', 'timestamp', 'sequence', 'data']);

function hasExactKeys(value, required, optional = []) {
  const allowed = new Set([...required, ...optional]);
  return (
    required.every((key) => Object.hasOwn(value, key)) &&
    Object.keys(value).every((key) => allowed.has(key))
  );
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    String(value || ''),
  );
}

function isDateTime(value) {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T/.test(value) &&
    !Number.isNaN(Date.parse(value))
  );
}

export function decodeLocalControl(envelope) {
  const type = String(envelope?.type || '');
  if (!type.startsWith('local.')) return null;
  if (
    !envelope ||
    typeof envelope !== 'object' ||
    Object.keys(envelope).some((key) => !envelopeKeys.has(key)) ||
    envelope.protocolVersion !== '2.0' ||
    !Number.isInteger(envelope.sequence) || envelope.sequence < 1 ||
    !isUuid(envelope.eventId) ||
    !isDateTime(envelope.timestamp) ||
    !envelope.data ||
    typeof envelope.data !== 'object' ||
    Array.isArray(envelope.data)
  ) {
    return { kind: 'invalid', message: 'Invalid local control envelope.' };
  }

  const data = envelope.data;
  switch (type) {
    case 'local.gateway.state':
      return hasExactKeys(data, ['state'], ['detail', 'errorCode']) &&
        gatewayStates.has(data.state) &&
        (data.detail === undefined ||
          (typeof data.detail === 'string' && data.detail.length <= 512)) &&
        (data.errorCode === undefined || localErrorCodes.has(data.errorCode))
        ? {
            kind: 'gateway',
            state: data.state,
            detail: data.detail || '',
            errorCode: data.errorCode || '',
          }
        : { kind: 'invalid', message: 'Invalid local gateway state.' };
    case 'local.robot.state':
      return hasExactKeys(data, ['ready', 'moving']) &&
        typeof data.ready === 'boolean' &&
        typeof data.moving === 'boolean'
        ? { kind: 'robot', ready: data.ready, moving: data.moving }
        : { kind: 'invalid', message: 'Invalid local robot state.' };
    case 'local.screen.state':
      return hasExactKeys(data, ['state']) && (data.state === 'ON' || data.state === 'OFF')
        ? { kind: 'screen', state: data.state }
        : { kind: 'invalid', message: 'Invalid local screen state.' };
    case 'local.interaction':
      return hasExactKeys(data, ['kind']) && data.kind === 'HEAD_PRESS'
        ? { kind: 'interaction', interaction: data.kind }
        : { kind: 'invalid', message: 'Invalid local interaction.' };
    default:
      return { kind: 'invalid', message: `Unknown local control type: ${type}` };
  }
}
