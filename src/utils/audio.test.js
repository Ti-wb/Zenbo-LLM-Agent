import { describe, expect, it } from 'vitest';
import { encodePcm16Wav } from './audio';

describe('audio utilities', () => {
  it('encodes mono PCM samples as a valid little-endian WAV', async () => {
    const blob = encodePcm16Wav(new Float32Array([-2, -0.5, 0, 0.5, 2]), 16000);
    const view = new DataView(await blob.arrayBuffer());
    const ascii = (offset, length) =>
      Array.from({ length }, (_, index) => String.fromCharCode(view.getUint8(offset + index))).join('');

    expect(blob.type).toBe('audio/wav');
    expect(ascii(0, 4)).toBe('RIFF');
    expect(ascii(8, 4)).toBe('WAVE');
    expect(view.getUint16(22, true)).toBe(1);
    expect(view.getUint32(24, true)).toBe(16000);
    expect(view.getUint16(34, true)).toBe(16);
    expect(view.getUint32(40, true)).toBe(10);
    expect(view.getInt16(44, true)).toBe(-32768);
    expect(view.getInt16(52, true)).toBe(32767);
  });
});
