function matchesType(value, type) {
  if (type === 'array') return Array.isArray(value);
  if (type === 'integer') return Number.isInteger(value);
  if (type === 'number') return typeof value === 'number' && Number.isFinite(value);
  if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value);
  if (type === 'null') return value === null;
  return typeof value === type;
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    String(value || ''),
  );
}

function parseIsoDateTime(value) {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value)
  ) {
    return Number.NaN;
  }
  return Date.parse(value);
}

export function validateToolArguments(schema = {}, value) {
  const errors = [];
  if (!matchesType(value, schema.type || 'object')) {
    return ['arguments must be an object'];
  }

  for (const required of schema.required || []) {
    if (value[required] === undefined) errors.push(`${required} is required`);
  }

  for (const [name, propertySchema] of Object.entries(schema.properties || {})) {
    const propertyValue = value[name];
    if (propertyValue === undefined) continue;
    if (propertySchema.type && !matchesType(propertyValue, propertySchema.type)) {
      errors.push(`${name} must be ${propertySchema.type}`);
      continue;
    }
    if (propertySchema.enum && !propertySchema.enum.includes(propertyValue)) {
      errors.push(`${name} must be one of ${propertySchema.enum.join(', ')}`);
    }
    if (typeof propertyValue === 'number') {
      if (propertySchema.minimum !== undefined && propertyValue < propertySchema.minimum) {
        errors.push(`${name} must be >= ${propertySchema.minimum}`);
      }
      if (propertySchema.maximum !== undefined && propertyValue > propertySchema.maximum) {
        errors.push(`${name} must be <= ${propertySchema.maximum}`);
      }
    }
  }

  if (schema.additionalProperties === false) {
    const known = new Set(Object.keys(schema.properties || {}));
    for (const name of Object.keys(value)) {
      if (!known.has(name)) errors.push(`${name} is not allowed`);
    }
  }
  return errors;
}

export class DeviceToolRegistry {
  constructor(options = {}) {
    this.tools = new Map();
    this.inflight = new Map();
    this.completed = new Map();
    this.timeoutMs = options.timeoutMs || 15000;
    this.maxCompleted = options.maxCompleted || 100;
    this.completedTtlMs = options.completedTtlMs || 5 * 60 * 1000;
    this.nowImpl = options.nowImpl || Date.now;
  }

  register(definition) {
    if (!definition?.name || typeof definition.handler !== 'function') {
      throw new Error('A device tool requires a name and handler.');
    }
    if (this.tools.has(definition.name)) {
      throw new Error(`Device tool already registered: ${definition.name}`);
    }
    this.tools.set(definition.name, {
      description: '',
      owner: 'web',
      version: '1.0.0',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      resultSchema: { type: 'object', properties: {}, additionalProperties: false },
      sideEffect: 'none',
      idempotent: true,
      requiresConfirmation: false,
      timeoutMs: this.timeoutMs,
      ...definition,
    });
    return () => this.tools.delete(definition.name);
  }

  manifest() {
    return [...this.tools.values()]
      .map(({
        name,
        owner,
        version,
        description,
        inputSchema,
        resultSchema,
        sideEffect,
        idempotent,
        requiresConfirmation,
        timeoutMs,
      }) => ({
        name,
        owner,
        version,
        description,
        inputSchema,
        resultSchema,
        sideEffect,
        idempotent,
        requiresConfirmation,
        timeoutMs,
      }))
      .sort((left, right) => left.name.localeCompare(right.name));
  }

  async execute(envelope, options = {}) {
    const payload = envelope?.data || {};
    const callId = payload.callId;
    const name = payload.toolName;
    const args = payload.arguments;
    if (!isUuid(callId)) throw new Error('Device tool callId must be a UUID.');

    const completed = this.completed.get(callId);
    if (completed && completed.expiresAt > this.nowImpl()) return completed.result;
    if (completed) this.completed.delete(callId);
    if (this.inflight.has(callId)) return this.inflight.get(callId);

    const execution = this.executeOnce({
      callId,
      name,
      args,
      deadlineAt: payload.deadlineAt,
      toolVersion: payload.toolVersion,
      timeoutMs: payload.timeoutMs,
      onAccepted: options.onAccepted,
    });
    this.inflight.set(callId, execution);
    try {
      const result = await execution;
      this.remember(callId, result);
      return result;
    } finally {
      this.inflight.delete(callId);
    }
  }

  async executeOnce({ callId, name, args, deadlineAt, toolVersion, timeoutMs, onAccepted }) {
    const tool = this.tools.get(name);
    if (!tool) {
      return this.errorResult(callId, name, 'TOOL_NOT_FOUND', `Unknown device tool: ${name}`, 'rejected');
    }

    if (typeof toolVersion !== 'string' || !/^[0-9]+\.[0-9]+\.[0-9]+$/.test(toolVersion)) {
      return this.errorResult(
        callId,
        name,
        'INVALID_TOOL_CALL',
        'Device tool call requires a semantic toolVersion.',
        'rejected',
      );
    }

    if (toolVersion !== tool.version) {
      return this.errorResult(
        callId,
        name,
        'TOOL_VERSION_MISMATCH',
        `Unsupported ${name} version: ${toolVersion}`,
        'rejected',
      );
    }

    const deadline = parseIsoDateTime(deadlineAt);
    if (!Number.isFinite(deadline)) {
      return this.errorResult(
        callId,
        name,
        'INVALID_TOOL_CALL',
        'Device tool call requires an ISO deadlineAt.',
        'rejected',
      );
    }

    if (deadline <= this.nowImpl()) {
      return this.errorResult(
        callId,
        name,
        'TOOL_DEADLINE_EXPIRED',
        'Device tool deadline expired.',
        'rejected',
      );
    }

    if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 15000) {
      return this.errorResult(
        callId,
        name,
        'INVALID_TIMEOUT',
        'Device tool timeoutMs must be an integer from 100 to 15000.',
        'rejected',
      );
    }

    const validationErrors = validateToolArguments(tool.inputSchema, args);
    if (validationErrors.length) {
      return this.errorResult(
        callId,
        name,
        'INVALID_ARGUMENTS',
        validationErrors.join('; '),
        'rejected',
      );
    }

    await onAccepted?.({ callId, name, status: 'accepted' });

    const deadlineBudget = Math.max(1, deadline - this.nowImpl());
    const executionTimeout = Math.min(tool.timeoutMs, timeoutMs, deadlineBudget);

    let timeout;
    try {
      const output = await Promise.race([
        Promise.resolve(tool.handler(args, { callId, name })),
        new Promise((_, reject) => {
          timeout = setTimeout(() => reject(new Error('Device tool timed out.')), executionTimeout);
        }),
      ]);
      const normalizedOutput = output ?? { ok: true };
      const resultErrors = validateToolArguments(tool.resultSchema, normalizedOutput);
      if (resultErrors.length) {
        return this.errorResult(
          callId,
          name,
          'INVALID_TOOL_RESULT',
          resultErrors.join('; '),
        );
      }
      const byteLength = new TextEncoder().encode(JSON.stringify(normalizedOutput)).byteLength;
      if (byteLength > 16 * 1024) {
        return this.errorResult(
          callId,
          name,
          'TOOL_OUTPUT_TOO_LARGE',
          'Device tool output exceeds 16 KiB.',
        );
      }
      return { callId, name, status: 'success', result: normalizedOutput };
    } catch (error) {
      const code = error.message === 'Device tool timed out.' ? 'TOOL_TIMEOUT' : 'TOOL_FAILED';
      return this.errorResult(callId, name, code, error.message || String(error));
    } finally {
      clearTimeout(timeout);
    }
  }

  errorResult(callId, name, code, message, status = 'error') {
    return { callId, name, status, error: { code, message, retryable: false } };
  }

  remember(callId, result) {
    this.completed.set(callId, { result, expiresAt: this.nowImpl() + this.completedTtlMs });
    while (this.completed.size > this.maxCompleted) {
      this.completed.delete(this.completed.keys().next().value);
    }
  }
}
