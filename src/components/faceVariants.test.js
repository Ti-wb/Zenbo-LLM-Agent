import { describe, expect, it } from 'vitest';
import {
  FACE_VARIANTS,
  FACE_VARIANT_STORAGE_KEY,
  readFaceVariant,
  saveFaceVariant,
} from './faceVariants.js';

describe('face variant preference', () => {
  it('defaults to eyes and restores the original renderer when selected', () => {
    const data = new Map();
    const storage = {
      getItem: (key) => data.get(key) ?? null,
      setItem: (key, value) => data.set(key, value),
    };
    expect(readFaceVariant(storage)).toBe(FACE_VARIANTS.EYES);
    expect(saveFaceVariant(FACE_VARIANTS.CLASSIC, storage)).toBe(FACE_VARIANTS.CLASSIC);
    expect(data.get(FACE_VARIANT_STORAGE_KEY)).toBe(FACE_VARIANTS.CLASSIC);
    expect(readFaceVariant(storage)).toBe(FACE_VARIANTS.CLASSIC);
    expect(saveFaceVariant('unknown', storage)).toBe(FACE_VARIANTS.EYES);
  });

  it('still allows the current session to switch when storage is unavailable', () => {
    const blocked = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); } };
    expect(readFaceVariant(blocked)).toBe(FACE_VARIANTS.EYES);
    expect(saveFaceVariant(FACE_VARIANTS.CLASSIC, blocked)).toBe(FACE_VARIANTS.CLASSIC);
  });
});
