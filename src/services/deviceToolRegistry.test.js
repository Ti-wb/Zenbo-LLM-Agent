import { describe, expect, it, vi } from 'vitest';
import { DeviceToolRegistry, validateToolArguments } from './deviceToolRegistry';

function callId(number) {
  return `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;
}

function registerEmotionTool(registry, handler = vi.fn(() => ({ ok: true }))) {
  registry.register({
    name: 'show_emotion',
    owner: 'web',
    version: '1.0.0',
    description: 'Show an emotion.',
    inputSchema: {
      type: 'object',
      required: ['emotion'],
      additionalProperties: false,
      properties: { emotion: { type: 'string', enum: ['HAPPY', 'CONCERNED'] } },
    },
    resultSchema: {
      type: 'object',
      required: ['ok'],
      properties: {
        ok: { type: 'boolean' },
        emotion: { type: 'string', enum: ['HAPPY', 'CONCERNED'] },
      },
      additionalProperties: false,
    },
    sideEffect: 'ui',
    idempotent: true,
    requiresConfirmation: false,
    handler,
  });
  return handler;
}

describe('DeviceToolRegistry', () => {
  it('publishes sorted, explicitly owned manifests', () => {
    const registry = new DeviceToolRegistry();
    registerEmotionTool(registry);
    registry.register({
      name: 'go_to_sleep',
      handler: () => ({ ok: true }),
    });

    expect(registry.manifest().map(({ name }) => name)).toEqual(['go_to_sleep', 'show_emotion']);
    expect(registry.manifest()[1]).toMatchObject({
      owner: 'web',
      version: '1.0.0',
      sideEffect: 'ui',
      requiresConfirmation: false,
    });
  });

  it('correlates and de-duplicates concurrent and replayed calls by callId', async () => {
    let resolveHandler;
    const handler = vi.fn(
      () => new Promise((resolve) => {
        resolveHandler = resolve;
      }),
    );
    const registry = new DeviceToolRegistry();
    registerEmotionTool(registry, handler);
    const envelope = {
      type: 'tool.call',
      data: {
        callId: callId(1),
        toolName: 'show_emotion',
        toolVersion: '1.0.0',
        arguments: { emotion: 'HAPPY' },
        timeoutMs: 1000,
        deadlineAt: '2999-01-01T00:00:00Z',
      },
    };

    const first = registry.execute(envelope);
    const concurrentReplay = registry.execute(envelope);
    await Promise.resolve();
    expect(handler).toHaveBeenCalledTimes(1);
    resolveHandler({ ok: true, emotion: 'HAPPY' });

    await expect(first).resolves.toMatchObject({ callId: callId(1), status: 'success' });
    await expect(concurrentReplay).resolves.toMatchObject({ callId: callId(1), status: 'success' });
    await registry.execute(envelope);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('rejects malformed, expired, and wrong-version calls before invoking the handler', async () => {
    const registry = new DeviceToolRegistry();
    const handler = registerEmotionTool(registry);
    const base = {
      callId: callId(2),
      toolName: 'show_emotion',
      toolVersion: '1.0.0',
      arguments: { emotion: 'ANGRY' },
      timeoutMs: 1000,
      deadlineAt: '2999-01-01T00:00:00Z',
    };

    const invalid = await registry.execute({ data: base });
    expect(invalid).toMatchObject({
      status: 'rejected',
      error: { code: 'INVALID_ARGUMENTS', retryable: false },
    });

    const expired = await registry.execute({
      data: {
        ...base,
        callId: callId(3),
        arguments: { emotion: 'HAPPY' },
        deadlineAt: '2000-01-01T00:00:00Z',
      },
    });
    expect(expired.error.code).toBe('TOOL_DEADLINE_EXPIRED');

    const mismatch = await registry.execute({
      data: {
        ...base,
        callId: callId(4),
        arguments: { emotion: 'HAPPY' },
        toolVersion: '2.0.0',
      },
    });
    expect(mismatch.error.code).toBe('TOOL_VERSION_MISMATCH');
    expect(handler).not.toHaveBeenCalled();
  });

  it('reports accepted before execution and expires completed-call cache after five minutes', async () => {
    let now = 1000;
    const registry = new DeviceToolRegistry({ nowImpl: () => now });
    const handler = registerEmotionTool(registry);
    const onAccepted = vi.fn();
    const envelope = {
      data: {
        callId: callId(5),
        toolName: 'show_emotion',
        toolVersion: '1.0.0',
        arguments: { emotion: 'HAPPY' },
        timeoutMs: 1000,
        deadlineAt: '2999-01-01T00:00:00Z',
      },
    };

    await registry.execute(envelope, { onAccepted });
    await registry.execute(envelope, { onAccepted });
    expect(onAccepted).toHaveBeenCalledOnce();
    expect(handler).toHaveBeenCalledOnce();

    now += 5 * 60 * 1000 + 1;
    await registry.execute(envelope, { onAccepted });
    expect(onAccepted).toHaveBeenCalledTimes(2);
    expect(handler).toHaveBeenCalledTimes(2);
  });

  it('fails a tool whose JSON result exceeds sixteen KiB', async () => {
    const registry = new DeviceToolRegistry();
    registry.register({
      name: 'show_emotion',
      version: '1.0.0',
      inputSchema: {
        type: 'object',
        required: ['emotion'],
        properties: { emotion: { type: 'string' } },
        additionalProperties: false,
      },
      resultSchema: {
        type: 'object',
        required: ['text'],
        properties: { text: { type: 'string' } },
        additionalProperties: false,
      },
      handler: () => ({ text: 'x'.repeat(17 * 1024) }),
    });

    const result = await registry.execute({
      data: {
        callId: callId(6),
        toolName: 'show_emotion',
        toolVersion: '1.0.0',
        arguments: { emotion: 'HAPPY' },
        timeoutMs: 1000,
        deadlineAt: '2999-01-01T00:00:00Z',
      },
    });

    expect(result).toMatchObject({ status: 'error', error: { code: 'TOOL_OUTPUT_TOO_LARGE' } });
  });

  it('requires UUID correlation, complete metadata, and a valid result schema', async () => {
    const registry = new DeviceToolRegistry({ nowImpl: () => Date.parse('2026-07-17T00:00:00Z') });
    registerEmotionTool(registry, () => ({ ok: 'yes' }));

    await expect(
      registry.execute({
        data: {
          callId: 'not-a-uuid',
          toolName: 'show_emotion',
          toolVersion: '1.0.0',
          arguments: { emotion: 'HAPPY' },
          timeoutMs: 1000,
          deadlineAt: '2026-07-17T00:00:01Z',
        },
      }),
    ).rejects.toThrow('callId must be a UUID');

    const missingDeadline = await registry.execute({
      data: {
        callId: callId(7),
        toolName: 'show_emotion',
        toolVersion: '1.0.0',
        arguments: { emotion: 'HAPPY' },
        timeoutMs: 1000,
      },
    });
    expect(missingDeadline).toMatchObject({
      status: 'rejected',
      error: { code: 'INVALID_TOOL_CALL' },
    });

    const invalidResult = await registry.execute({
      data: {
        callId: callId(8),
        toolName: 'show_emotion',
        toolVersion: '1.0.0',
        arguments: { emotion: 'HAPPY' },
        timeoutMs: 1000,
        deadlineAt: '2026-07-17T00:00:01Z',
      },
    });
    expect(invalidResult).toMatchObject({
      status: 'error',
      error: { code: 'INVALID_TOOL_RESULT' },
    });
  });
});

describe('validateToolArguments', () => {
  it('rejects missing and additional properties', () => {
    const schema = {
      type: 'object',
      required: ['value'],
      additionalProperties: false,
      properties: { value: { type: 'integer', minimum: 1, maximum: 3 } },
    };

    expect(validateToolArguments(schema, { extra: true })).toEqual([
      'value is required',
      'extra is not allowed',
    ]);
    expect(validateToolArguments(schema, { value: 4 })).toEqual(['value must be <= 3']);
  });
});
