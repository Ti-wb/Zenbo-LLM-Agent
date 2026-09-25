import { describe, expect, it } from 'vitest';
import { renderEyeFace, EYE_FACE_HEIGHT, EYE_FACE_WIDTH } from './eyeFaceModel.js';
import { starEye } from './pixelFaceModel.js';

function rectangles(options) {
  const painted = [];
  const context = {
    fillStyle: '',
    fillRect(x, y, width, height) {
      painted.push({ x, y, width, height, color: this.fillStyle });
    },
  };
  renderEyeFace(context, options);
  return painted;
}

describe('eye-only face', () => {
  it('uses the original star geometry for EXCITED', () => {
    const expected = [38, 122].flatMap((center) => starEye(center, 42))
      .map((rectangle) => ({ ...rectangle, color: '#fffdfa' }));
    expect(rectangles({ emotion: 'EXCITED' }).slice(1)).toEqual(expected);
  });

  it('keeps the mouth hidden until audio has an open band', () => {
    for (const emotion of ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED', 'SLEEPING']) {
      const idle = rectangles({ emotion });
      expect(idle.slice(1).some(({ y }) => y >= 70)).toBe(false);
    }
    expect(rectangles({ emotion: 'NEUTRAL', mouthBand: 4 })
      .some(({ y, color }) => y >= 70 && color === '#fffdfa')).toBe(true);
    expect(rectangles({ emotion: 'SLEEPING', mouthBand: 4 })
      .some(({ y }) => y >= 70)).toBe(false);
  });

  it('keeps every expression inside the native 160×100 canvas', () => {
    for (const emotion of ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED', 'SLEEPING']) {
      for (const { x, y, width, height } of rectangles({ emotion, mouthBand: 4 })) {
        expect(x).toBeGreaterThanOrEqual(0);
        expect(y).toBeGreaterThanOrEqual(0);
        expect(x + width).toBeLessThanOrEqual(EYE_FACE_WIDTH);
        expect(y + height).toBeLessThanOrEqual(EYE_FACE_HEIGHT);
      }
    }
  });
});
