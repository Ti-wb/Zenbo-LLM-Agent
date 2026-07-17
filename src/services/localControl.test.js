import { describe, expect, it } from 'vitest';
import { decodeLocalControl } from './localControl';

function localControl(type, data, extra = {}) {
  return {
    protocolVersion: '1.0',
    eventId: '00000000-0000-4000-8000-000000000001',
    type,
    timestamp: '2026-07-17T08:00:00.000Z',
    data,
    ...extra,
  };
}

describe('decodeLocalControl', () => {
  it.each([
    [
      'local.gateway.state',
      { state: 'READY' },
      { kind: 'gateway', state: 'READY', detail: '', errorCode: '' },
    ],
    ['local.robot.state', { ready: true, moving: false }, { kind: 'robot', ready: true, moving: false }],
    ['local.screen.state', { state: 'OFF' }, { kind: 'screen', state: 'OFF' }],
    ['local.interaction', { kind: 'HEAD_PRESS' }, { kind: 'interaction', interaction: 'HEAD_PRESS' }],
  ])('decodes %s without touching the remote cursor', (type, data, expected) => {
    expect(decodeLocalControl(localControl(type, data))).toEqual(expected);
  });

  it('rejects cursor-bearing or malformed local controls', () => {
    expect(
      decodeLocalControl(localControl('local.screen.state', { state: 'OFF' }, { sequence: 4 })),
    ).toMatchObject({ kind: 'invalid' });
    expect(decodeLocalControl(localControl('local.interaction', { kind: 'CLICK' }))).toMatchObject(
      { kind: 'invalid' },
    );
    expect(
      decodeLocalControl(
        localControl('local.robot.state', { ready: true, moving: false, token: 'x' }),
      ),
    ).toMatchObject({ kind: 'invalid' });
  });

  it('leaves canonical Gateway envelopes to the remote validator', () => {
    expect(decodeLocalControl({ type: 'stt.final', sequence: 3, data: {} })).toBeNull();
  });
});
