import { describe, expect, it } from 'vitest';
import { decodeLocalControl } from './localControl';

function localControl(type, data, extra = {}) {
  return {
    protocolVersion: '2.0',
    sequence: 1,
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
    ['local.robot.state', { ready: true, moving: false, motionEnabled: false, battery: { percentage: 75, charging: true } }, { kind: 'robot', ready: true, moving: false, motionEnabled: false, battery: { percentage: 75, charging: true } }],
    ['local.screen.state', { state: 'OFF' }, { kind: 'screen', state: 'OFF' }],
    ['local.interaction', { kind: 'HEAD_PRESS' }, { kind: 'interaction', interaction: 'HEAD_PRESS' }],
  ])('decodes %s with the Native cursor', (type, data, expected) => {
    expect(decodeLocalControl(localControl(type, data))).toEqual(expected);
  });

  it('rejects missing cursors and malformed local controls', () => {
    expect(
      decodeLocalControl(localControl('local.screen.state', { state: 'OFF' }, { sequence: undefined })),
    ).toMatchObject({ kind: 'invalid' });
    expect(decodeLocalControl(localControl('local.interaction', { kind: 'CLICK' }))).toMatchObject(
      { kind: 'invalid' },
    );
    expect(
      decodeLocalControl(
        localControl('local.robot.state', { ready: true, moving: false, motionEnabled: false, battery: { percentage: null, charging: null }, token: 'x' }),
      ),
    ).toMatchObject({ kind: 'invalid' });
    for (const motionEnabled of [undefined, 'true']) {
      expect(decodeLocalControl(localControl('local.robot.state', {
        ready: true, moving: false, motionEnabled, battery: { percentage: null, charging: null },
      }))).toMatchObject({ kind: 'invalid' });
    }
  });

  it('accepts unknown battery data but rejects invalid or extra battery fields', () => {
    const data = { ready: true, moving: false, motionEnabled: false };
    expect(decodeLocalControl(localControl('local.robot.state', {
      ...data, battery: { percentage: null, charging: null },
    }))).toMatchObject({ kind: 'robot', battery: { percentage: null, charging: null } });
    for (const battery of [undefined, { percentage: 101, charging: false },
      { percentage: -1, charging: false }, { percentage: 50.5, charging: false },
      { percentage: 50, charging: 'true' }, { percentage: 50, charging: false, extra: true }]) {
      expect(decodeLocalControl(localControl('local.robot.state', { ...data, battery })))
        .toMatchObject({ kind: 'invalid' });
    }
  });

  it('leaves Native conversation envelopes to the conversation validator', () => {
    expect(decodeLocalControl({ type: 'stt.final', sequence: 3, data: {} })).toBeNull();
  });
});
