import { describe, expect, it } from 'vitest';
import {
  FACE_BACKGROUND,
  FACE_HEIGHT,
  FACE_PALETTE,
  FACE_WIDTH,
  equalizerMouth,
  interpolateFaceFrames,
  interpolateSleepFrames,
  morphRectanglesThroughSlit,
  renderFace,
  resolveEqualizerBand,
  resolveFaceFrame,
  resolveSpeechTransitionMouth,
} from './pixelFaceModel.js';

function bounds(rectangles) {
  const left = Math.min(...rectangles.map((item) => item.x));
  const top = Math.min(...rectangles.map((item) => item.y));
  const right = Math.max(...rectangles.map((item) => item.x + item.width));
  const bottom = Math.max(...rectangles.map((item) => item.y + item.height));
  return { x: left, y: top, width: right - left, height: bottom - top };
}

function allFaceRectangles(frame) {
  return [...frame.leftEye, ...frame.rightEye, ...frame.mouth];
}

function sortedRectangles(rectangles) {
  return rectangles
    .map((item) => ({ ...item }))
    .sort((left, right) =>
      left.y - right.y ||
      left.x - right.x ||
      left.width - right.width ||
      left.height - right.height,
    );
}

function mirroredRectangles(rectangles) {
  return sortedRectangles(
    rectangles.map((item) => ({
      ...item,
      x: FACE_WIDTH - item.x - item.width,
    })),
  );
}

function boundsCenter(rectangles) {
  const box = bounds(rectangles);
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

function expectContinuousBounds(first, second, tolerance = 2) {
  const firstBounds = bounds(first);
  const secondBounds = bounds(second);
  const firstEdges = [
    firstBounds.x,
    firstBounds.y,
    firstBounds.x + firstBounds.width,
    firstBounds.y + firstBounds.height,
  ];
  const secondEdges = [
    secondBounds.x,
    secondBounds.y,
    secondBounds.x + secondBounds.width,
    secondBounds.y + secondBounds.height,
  ];
  for (let index = 0; index < firstEdges.length; index += 1) {
    expect(Math.abs(firstEdges[index] - secondEdges[index])).toBeLessThanOrEqual(
      tolerance,
    );
  }
}

function expectIntegerBoundedEven(rectangles) {
  for (const item of rectangles) {
    for (const value of [item.x, item.y, item.width, item.height]) {
      expect(Number.isInteger(value)).toBe(true);
    }
    expect(item.width).toBeGreaterThan(0);
    expect(item.height).toBeGreaterThan(0);
    expect(item.width % 2).toBe(0);
    expect(item.height % 2).toBe(0);
    expect(item.x).toBeGreaterThanOrEqual(0);
    expect(item.y).toBeGreaterThanOrEqual(0);
    expect(item.x + item.width).toBeLessThanOrEqual(FACE_WIDTH);
    expect(item.y + item.height).toBeLessThanOrEqual(FACE_HEIGHT);
  }
}

function expectIntegerBoundedConnected(rectangles) {
  expectIntegerBoundedEven(rectangles);
  const pixels = new Set();
  for (const item of rectangles) {
    for (let y = item.y; y < item.y + item.height; y += 1) {
      for (let x = item.x; x < item.x + item.width; x += 1) {
        pixels.add(`${x}:${y}`);
      }
    }
  }

  expect(pixels.size).toBeGreaterThan(0);
  const [first] = pixels;
  const queue = [first];
  const visited = new Set([first]);
  for (let index = 0; index < queue.length; index += 1) {
    const [x, y] = queue[index].split(':').map(Number);
    for (const [nextX, nextY] of [
      [x - 1, y],
      [x + 1, y],
      [x, y - 1],
      [x, y + 1],
    ]) {
      const key = `${nextX}:${nextY}`;
      if (pixels.has(key) && !visited.has(key)) {
        visited.add(key);
        queue.push(key);
      }
    }
  }
  expect(visited.size).toBe(pixels.size);
}

describe('resolveFaceFrame', () => {
  it.each([
    ['NEUTRAL', 'neutral', '#76f4ff', [34, 14], [34, 14]],
    ['HAPPY', 'happy', '#68ffd1', [32, 14], [32, 14]],
    ['CURIOUS', 'curious', '#6ce8ff', [36, 14], [24, 12]],
    ['CONCERNED', 'concerned', '#ff7f91', [32, 12], [32, 12]],
    ['EXCITED', 'excited', '#ffdf6c', [18, 30], [18, 30]],
  ])(
    'builds the pupil-free %s A-style eye geometry',
    (emotion, key, color, leftSize, rightSize) => {
      const frame = resolveFaceFrame({ emotion, reducedMotion: true });

      expect(frame.emotion).toBe(key);
      expect(frame.color).toBe(color);
      expect([bounds(frame.leftEye).width, bounds(frame.leftEye).height]).toEqual(leftSize);
      expect([bounds(frame.rightEye).width, bounds(frame.rightEye).height]).toEqual(rightSize);
      expect(frame.leftEye.every((item) => item.height === 2)).toBe(true);
      expect(frame).not.toHaveProperty('pupil');
      expect(frame).not.toHaveProperty('highlight');
    },
  );

  it('falls back to neutral geometry and palette for an unknown emotion', () => {
    const fallback = resolveFaceFrame({ emotion: 'SURPRISED', reducedMotion: true });
    const neutral = resolveFaceFrame({ emotion: 'NEUTRAL', reducedMotion: true });

    expect(fallback.emotion).toBe('neutral');
    expect(fallback.color).toBe(FACE_PALETTE.neutral);
    expect(fallback.leftEye).toEqual(neutral.leftEye);
    expect(fallback.rightEye).toEqual(neutral.rightEye);
  });

  it('uses a static curious face while listening', () => {
    const frame = resolveFaceFrame({
      emotion: 'HAPPY',
      turnState: 'LISTENING',
      elapsedMs: 1300,
    });

    expect(frame.emotion).toBe('curious');
    expect(frame.color).toBe(FACE_PALETTE.curious);
    expect(frame.eyeOffsetX).toBe(0);
    expect(bounds(frame.leftEye)).toMatchObject({ width: 36, height: 14 });
    expect(bounds(frame.rightEye)).toMatchObject({ width: 24, height: 12 });
  });

  it('keeps concerned inner corners four pixels above the outer corners', () => {
    const frame = resolveFaceFrame({ emotion: 'CONCERNED', reducedMotion: true });

    expect(frame.metrics.concernedCorners.left.innerY).toBe(
      frame.metrics.concernedCorners.left.outerY - 4,
    );
    expect(frame.metrics.concernedCorners.right.innerY).toBe(
      frame.metrics.concernedCorners.right.outerY - 4,
    );
  });

  it('uses the exact A-style non-speaking mouths', () => {
    const neutral = resolveFaceFrame({ emotion: 'NEUTRAL', reducedMotion: true });
    const happy = resolveFaceFrame({ emotion: 'HAPPY', reducedMotion: true });
    const curious = resolveFaceFrame({ emotion: 'CURIOUS', reducedMotion: true });
    const concerned = resolveFaceFrame({ emotion: 'CONCERNED', reducedMotion: true });
    const excited = resolveFaceFrame({ emotion: 'EXCITED', reducedMotion: true });

    expect(bounds(neutral.mouth)).toMatchObject({ width: 8, height: 2 });
    expect(bounds(happy.mouth)).toMatchObject({ width: 10, height: 4 });
    expect(bounds(curious.mouth)).toMatchObject({ width: 6, height: 2 });
    expect(bounds(concerned.mouth)).toMatchObject({ width: 10, height: 4 });
    expect(bounds(excited.mouth)).toMatchObject({ width: 10, height: 6 });
    expect(excited.mouth).toHaveLength(4);
  });

  it('overrides every other state with the sleeping face', () => {
    const frame = resolveFaceFrame({
      emotion: 'EXCITED',
      mouthLevel: 1,
      sleeping: true,
      turnState: 'SPEAKING',
      elapsedMs: 5100,
      previousEqualizerBand: 4,
    });

    expect(frame.emotion).toBe('sleeping');
    expect(frame.speaking).toBe(false);
    expect(frame.equalizerBand).toBe(0);
    expect(frame.blinkFrame).toBeNull();
    expect(bounds(frame.leftEye)).toMatchObject({ width: 28, height: 4, y: 42 });
    expect(bounds(frame.rightEye)).toMatchObject({ width: 28, height: 4, y: 42 });
    expect(bounds(frame.mouth)).toMatchObject({ width: 6, height: 2 });
  });

  it('returns integer, even-sized, in-bounds pixel rectangles for every face', () => {
    const cases = [
      ['NEUTRAL', 'IDLE', 0],
      ['HAPPY', 'THINKING', 1300],
      ['CURIOUS', 'LISTENING', 5032],
      ['CONCERNED', 'ERROR', 5100],
      ['EXCITED', 'SPEAKING', 3900],
    ];

    for (const [emotion, turnState, elapsedMs] of cases) {
      const frame = resolveFaceFrame({ emotion, turnState, elapsedMs, mouthLevel: 1 });
      for (const item of allFaceRectangles(frame)) {
        expect(Number.isInteger(item.x)).toBe(true);
        expect(Number.isInteger(item.y)).toBe(true);
        expect(Number.isInteger(item.width)).toBe(true);
        expect(Number.isInteger(item.height)).toBe(true);
        expect(item.width % 2).toBe(0);
        expect(item.height % 2).toBe(0);
        expect(item.x).toBeGreaterThanOrEqual(0);
        expect(item.y).toBeGreaterThanOrEqual(0);
        expect(item.x + item.width).toBeLessThanOrEqual(FACE_WIDTH);
        expect(item.y + item.height).toBeLessThanOrEqual(FACE_HEIGHT);
      }
    }
  });
});

describe('motion semantics', () => {
  it('uses a five-frame blink at the end of each 5.2 second period', () => {
    const before = resolveFaceFrame({ elapsedMs: 5032 });
    const opening = resolveFaceFrame({ elapsedMs: 5033 });
    const partial = resolveFaceFrame({ elapsedMs: 5067 });
    const slit = resolveFaceFrame({ elapsedMs: 5100 });
    const reopening = resolveFaceFrame({ elapsedMs: 5134 });
    const open = resolveFaceFrame({ elapsedMs: 5167 });

    expect(before.blinkFrame).toBeNull();
    expect(opening.blinkFrame).toBe(0);
    expect(partial.blinkFrame).toBe(1);
    expect(slit.blinkFrame).toBe(2);
    expect(reopening.blinkFrame).toBe(3);
    expect(open.blinkFrame).toBe(4);
    expect(bounds(slit.leftEye).height).toBeLessThan(bounds(before.leftEye).height);
    expect(bounds(open.leftEye)).toEqual(bounds(before.leftEye));
  });

  it('moves both eyes together by at most two pixels while thinking', () => {
    const centered = resolveFaceFrame({
      emotion: 'NEUTRAL',
      turnState: 'THINKING',
      elapsedMs: 0,
    });
    const right = resolveFaceFrame({
      emotion: 'NEUTRAL',
      turnState: 'AWAITING_TOOL',
      elapsedMs: 1300,
    });
    const left = resolveFaceFrame({
      emotion: 'NEUTRAL',
      turnState: 'SYNTHESIZING',
      elapsedMs: 3900,
    });

    expect(centered.eyeOffsetX).toBe(0);
    expect(right.eyeOffsetX).toBe(2);
    expect(left.eyeOffsetX).toBe(-2);
    expect(bounds(right.leftEye).x - bounds(centered.leftEye).x).toBe(2);
    expect(bounds(right.rightEye).x - bounds(centered.rightEye).x).toBe(2);
  });

  it('disables blink and thinking drift under reduced motion', () => {
    const frame = resolveFaceFrame({
      emotion: 'NEUTRAL',
      turnState: 'THINKING',
      elapsedMs: 5100,
      reducedMotion: true,
    });

    expect(frame.blinkFrame).toBeNull();
    expect(frame.eyeOffsetX).toBe(0);
    expect(bounds(frame.leftEye)).toMatchObject({ width: 34, height: 14 });
  });
});

describe('speech equalizer', () => {
  it.each([
    [-1, 0],
    [Number.NaN, 0],
    [0.12, 0],
    [0.13, 1],
    [0.375, 1],
    [0.376, 2],
    [0.625, 2],
    [0.626, 3],
    [0.875, 3],
    [0.876, 4],
    [2, 4],
  ])('maps mouth level %s to band %s and clamps invalid ranges', (level, expected) => {
    expect(resolveEqualizerBand(level)).toBe(expected);
  });

  it('uses ±0.04 hysteresis around equalizer thresholds', () => {
    expect(resolveEqualizerBand(0.15, 0)).toBe(0);
    expect(resolveEqualizerBand(0.17, 0)).toBe(1);
    expect(resolveEqualizerBand(0.09, 1)).toBe(1);
    expect(resolveEqualizerBand(0.07, 1)).toBe(0);
    expect(resolveEqualizerBand(0.84, 4)).toBe(4);
    expect(resolveEqualizerBand(0.83, 4)).toBe(3);
  });

  it.each([
    [0, [2]],
    [1, [2, 4, 2]],
    [2, [2, 6, 2]],
    [3, [4, 6, 4]],
    [4, [4, 8, 4]],
  ])('renders equalizer band %s with the specified heights', (band, heights) => {
    const mouth = equalizerMouth(band);

    expect(mouth.map((item) => item.height)).toEqual(heights);
    if (band > 0) {
      expect(mouth.map((item) => item.x)).toEqual([75, 79, 83]);
      expect(bounds(mouth).width).toBe(10);
      expect(bounds(mouth).height).toBeLessThanOrEqual(8);
    } else {
      expect(bounds(mouth)).toMatchObject({ width: 8, height: 2 });
    }
  });

  it('shows equalizer geometry only while speaking and resets on exit', () => {
    const speaking = resolveFaceFrame({
      emotion: 'HAPPY',
      turnState: 'SPEAKING',
      mouthLevel: 1,
    });
    const exited = resolveFaceFrame({
      emotion: 'HAPPY',
      turnState: 'IDLE',
      mouthLevel: 1,
      previousEqualizerBand: 4,
    });
    const reduced = resolveFaceFrame({
      emotion: 'HAPPY',
      turnState: 'SPEAKING',
      mouthLevel: 0.75,
      reducedMotion: true,
    });

    expect(speaking.equalizerBand).toBe(4);
    expect(speaking.mouth.map((item) => item.height)).toEqual([4, 8, 4]);
    expect(exited.equalizerBand).toBe(0);
    expect(exited.mouth).toHaveLength(3);
    expect(reduced.equalizerBand).toBe(3);
    expect(reduced.mouth.map((item) => item.height)).toEqual([4, 6, 4]);
  });

  it('converges to a short line for 100ms before entering speech', () => {
    const emotionMouth = resolveFaceFrame({
      emotion: 'EXCITED',
      reducedMotion: true,
    }).mouth;
    const speaking = resolveFaceFrame({
      turnState: 'SPEAKING',
      mouthLevel: 1,
      reducedMotion: true,
    });

    expect(
      resolveSpeechTransitionMouth({
        phase: 'entering',
        elapsedMs: 0,
        fromMouth: emotionMouth,
        closedMouth: speaking.speechClosedMouth,
        targetMouth: speaking.mouth,
      }),
    ).toEqual(emotionMouth);
    expect(
      resolveSpeechTransitionMouth({
        phase: 'entering',
        elapsedMs: 99,
        fromMouth: emotionMouth,
        closedMouth: speaking.speechClosedMouth,
        targetMouth: speaking.mouth,
      }),
    ).toEqual(speaking.speechClosedMouth);
    expect(
      resolveSpeechTransitionMouth({
        phase: 'entering',
        elapsedMs: 100,
        fromMouth: emotionMouth,
        closedMouth: speaking.speechClosedMouth,
        targetMouth: speaking.mouth,
      }),
    ).toEqual(speaking.mouth);
  });

  it('keeps excited-to-speaking mid-frames centered, united, and continuous', () => {
    const excitedMouth = resolveFaceFrame({
      emotion: 'EXCITED',
      reducedMotion: true,
    }).mouth;
    const speaking = resolveFaceFrame({
      turnState: 'SPEAKING',
      mouthLevel: 1,
      reducedMotion: true,
    });
    const options = {
      phase: 'entering',
      fromMouth: excitedMouth,
      closedMouth: speaking.speechClosedMouth,
      targetMouth: speaking.mouth,
    };
    const before = resolveSpeechTransitionMouth({ ...options, elapsedMs: 49 });
    const middle = resolveSpeechTransitionMouth({ ...options, elapsedMs: 50 });
    const after = resolveSpeechTransitionMouth({ ...options, elapsedMs: 51 });

    expect(boundsCenter(middle).x).toBe(80);
    expect(new Set(middle.map((item) => JSON.stringify(item))).size).toBe(
      middle.length,
    );
    expectContinuousBounds(before, middle);
    expectContinuousBounds(middle, after);
    expect(middle.every((item) => Number.isInteger(item.x))).toBe(true);
    expect(middle.every((item) => Number.isInteger(item.y))).toBe(true);
  });

  it('collapses speech bars to a line before restoring emotion within 180ms', () => {
    const speaking = resolveFaceFrame({
      turnState: 'SPEAKING',
      mouthLevel: 1,
      reducedMotion: true,
    });
    const emotionMouth = resolveFaceFrame({
      emotion: 'HAPPY',
      reducedMotion: true,
    }).mouth;
    const options = {
      phase: 'leaving',
      fromMouth: speaking.mouth,
      closedMouth: speaking.speechClosedMouth,
      targetMouth: emotionMouth,
    };

    expect(resolveSpeechTransitionMouth({ ...options, elapsedMs: 0 })).toEqual(
      speaking.mouth,
    );
    expect(resolveSpeechTransitionMouth({ ...options, elapsedMs: 80 })).toEqual(
      speaking.speechClosedMouth,
    );
    expect(resolveSpeechTransitionMouth({ ...options, elapsedMs: 130 })).not.toEqual(
      speaking.speechClosedMouth,
    );
    expect(resolveSpeechTransitionMouth({ ...options, elapsedMs: 180 })).toEqual(
      emotionMouth,
    );
  });
});

describe('frame interpolation and renderer', () => {
  it('closes neutral eyes to common slits before opening happy arches', () => {
    const from = resolveFaceFrame({ emotion: 'NEUTRAL', reducedMotion: true });
    const to = resolveFaceFrame({ emotion: 'HAPPY', reducedMotion: true });
    const fromSnapshot = structuredClone(from);
    const toSnapshot = structuredClone(to);
    const before = interpolateFaceFrames(from, to, 0.49);
    const halfway = interpolateFaceFrames(from, to, 0.5);
    const after = interpolateFaceFrames(from, to, 0.51);

    expect(interpolateFaceFrames(from, to, 0).leftEye).toEqual(from.leftEye);
    expect(interpolateFaceFrames(from, to, 1).leftEye).toEqual(to.leftEye);
    expect(halfway.leftEye).toEqual([{ x: 44, y: 41, width: 16, height: 2 }]);
    expect(halfway.rightEye).toEqual([{ x: 100, y: 41, width: 16, height: 2 }]);
    expect(mirroredRectangles(halfway.leftEye)).toEqual(
      sortedRectangles(halfway.rightEye),
    );
    expect(mirroredRectangles(before.leftEye)).toEqual(
      sortedRectangles(before.rightEye),
    );
    expect(mirroredRectangles(after.leftEye)).toEqual(
      sortedRectangles(after.rightEye),
    );
    expectContinuousBounds(before.leftEye, halfway.leftEye);
    expectContinuousBounds(halfway.leftEye, after.leftEye);
    expect(from).toEqual(fromSnapshot);
    expect(to).toEqual(toSnapshot);
  });

  it('removes curious asymmetry continuously before opening concerned eyes', () => {
    const curious = resolveFaceFrame({ emotion: 'CURIOUS', reducedMotion: true });
    const concerned = resolveFaceFrame({
      emotion: 'CONCERNED',
      reducedMotion: true,
    });
    const reverse = (progress) =>
      interpolateFaceFrames(concerned, curious, 1 - progress);
    const before = interpolateFaceFrames(curious, concerned, 0.49);
    const halfway = interpolateFaceFrames(curious, concerned, 0.5);
    const after = interpolateFaceFrames(curious, concerned, 0.51);
    const concernedOpening = interpolateFaceFrames(curious, concerned, 0.75);

    expect(interpolateFaceFrames(curious, concerned, 0).leftEye).toEqual(
      curious.leftEye,
    );
    expect(interpolateFaceFrames(curious, concerned, 1).rightEye).toEqual(
      concerned.rightEye,
    );
    expect(halfway.leftEye).toEqual([{ x: 44, y: 41, width: 16, height: 2 }]);
    expect(mirroredRectangles(halfway.leftEye)).toEqual(
      sortedRectangles(halfway.rightEye),
    );
    expect(mirroredRectangles(concernedOpening.leftEye)).toEqual(
      sortedRectangles(concernedOpening.rightEye),
    );
    expect(interpolateFaceFrames(curious, concerned, 0.25).leftEye).toEqual(
      reverse(0.25).leftEye,
    );
    expect(interpolateFaceFrames(curious, concerned, 0.75).rightEye).toEqual(
      reverse(0.75).rightEye,
    );
    expectContinuousBounds(before.leftEye, halfway.leftEye);
    expectContinuousBounds(halfway.leftEye, after.leftEye);
    for (const item of [...halfway.leftEye, ...halfway.rightEye]) {
      expect(Number.isInteger(item.x)).toBe(true);
      expect(Number.isInteger(item.y)).toBe(true);
      expect(item.x + item.width).toBeLessThanOrEqual(FACE_WIDTH);
      expect(item.y + item.height).toBeLessThanOrEqual(FACE_HEIGHT);
    }
  });

  it('keeps every dense pairwise emotion transition on connected 2px geometry', () => {
    const emotions = ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED'];
    const progressValues = Array.from({ length: 101 }, (_, index) => index / 100);

    for (const fromEmotion of emotions) {
      for (const toEmotion of emotions) {
        const from = resolveFaceFrame({
          emotion: fromEmotion,
          reducedMotion: true,
        });
        const to = resolveFaceFrame({
          emotion: toEmotion,
          reducedMotion: true,
        });
        let previous = null;
        for (const progress of progressValues) {
          const frame = interpolateFaceFrames(from, to, progress);
          expectIntegerBoundedConnected(frame.leftEye);
          expectIntegerBoundedConnected(frame.rightEye);
          if (previous) {
            expectContinuousBounds(previous.leftEye, frame.leftEye);
            expectContinuousBounds(previous.rightEye, frame.rightEye);
          }
          previous = frame;
        }
      }
    }
  });

  it('keeps dense speech entry and exit mouths on positive even rectangles', () => {
    const emotions = ['NEUTRAL', 'HAPPY', 'CURIOUS', 'CONCERNED', 'EXCITED'];
    const speaking = resolveFaceFrame({
      turnState: 'SPEAKING',
      mouthLevel: 1,
      reducedMotion: true,
    });

    for (const emotion of emotions) {
      const emotionMouth = resolveFaceFrame({
        emotion,
        reducedMotion: true,
      }).mouth;
      const entryOptions = {
        phase: 'entering',
        fromMouth: emotionMouth,
        closedMouth: speaking.speechClosedMouth,
        targetMouth: speaking.mouth,
      };
      const exitOptions = {
        phase: 'leaving',
        fromMouth: speaking.mouth,
        closedMouth: speaking.speechClosedMouth,
        targetMouth: emotionMouth,
      };

      for (let elapsedMs = 0; elapsedMs <= 100; elapsedMs += 1) {
        const mouth = resolveSpeechTransitionMouth({
          ...entryOptions,
          elapsedMs,
        });
        expectIntegerBoundedEven(mouth);
        expect(new Set(mouth.map((item) => JSON.stringify(item))).size).toBe(
          mouth.length,
        );
      }
      for (let elapsedMs = 0; elapsedMs <= 180; elapsedMs += 1) {
        const mouth = resolveSpeechTransitionMouth({
          ...exitOptions,
          elapsedMs,
        });
        expectIntegerBoundedEven(mouth);
        expect(new Set(mouth.map((item) => JSON.stringify(item))).size).toBe(
          mouth.length,
        );
      }
    }
  });

  it('morphs arbitrary topology through one semantic slit with exact endpoints', () => {
    const from = [{ x: 10, y: 10, width: 8, height: 2 }];
    const to = [
      { x: 20, y: 20, width: 2, height: 8 },
      { x: 24, y: 20, width: 2, height: 4 },
    ];
    const slit = { x: 15, y: 15, width: 6, height: 2 };

    expect(morphRectanglesThroughSlit(from, to, slit, -1)).toEqual(from);
    expect(morphRectanglesThroughSlit(from, to, slit, 2)).toEqual(to);
    expect(morphRectanglesThroughSlit(from, to, slit, 0.5)).toEqual([slit]);
  });

  it('closes into sleep and reopens over the same continuous 200ms path', () => {
    const awake = resolveFaceFrame({ emotion: 'NEUTRAL', reducedMotion: true });
    const sleeping = resolveFaceFrame({
      emotion: 'NEUTRAL',
      sleeping: true,
      reducedMotion: true,
    });
    const closing = [0, 0.25, 0.5, 0.75, 1].map((progress) =>
      interpolateSleepFrames(awake, sleeping, progress, 'sleeping'),
    );
    const heights = closing.map((frame) => bounds(frame.leftEye).height);

    expect(closing[0].leftEye).toEqual(awake.leftEye);
    expect(closing.at(-1).leftEye).toEqual(sleeping.leftEye);
    expect(heights).toEqual([14, 10, 4, 2, 4]);

    for (const progress of [0, 0.25, 0.5, 0.75, 1]) {
      const entering = interpolateSleepFrames(
        awake,
        sleeping,
        progress,
        'sleeping',
      );
      const waking = interpolateSleepFrames(
        sleeping,
        awake,
        1 - progress,
        'waking',
      );
      expect(entering.leftEye).toEqual(waking.leftEye);
      expect(entering.rightEye).toEqual(waking.rightEye);
    }

    const midpoint = closing[2];
    expect(mirroredRectangles(midpoint.leftEye)).toEqual(
      sortedRectangles(midpoint.rightEye),
    );
    expect(bounds(midpoint.leftEye).height).toBeLessThan(
      bounds(awake.leftEye).height,
    );
  });

  it('wakes mid-close from the displayed eyes through fixed canonical slits', () => {
    const awake = resolveFaceFrame({ emotion: 'HAPPY', reducedMotion: true });
    const sleeping = resolveFaceFrame({
      emotion: 'HAPPY',
      sleeping: true,
      reducedMotion: true,
    });
    const displayedMidClose = interpolateSleepFrames(
      awake,
      sleeping,
      0.5,
      'sleeping',
    );
    const wakeStart = interpolateSleepFrames(
      displayedMidClose,
      awake,
      0,
      'waking',
    );
    const beforeSlit = interpolateSleepFrames(
      displayedMidClose,
      awake,
      0.24,
      'waking',
    );
    const atSlit = interpolateSleepFrames(
      displayedMidClose,
      awake,
      0.25,
      'waking',
    );
    const afterSlit = interpolateSleepFrames(
      displayedMidClose,
      awake,
      0.26,
      'waking',
    );
    const finished = interpolateSleepFrames(
      displayedMidClose,
      awake,
      1,
      'waking',
    );

    expect(wakeStart.leftEye).toEqual(displayedMidClose.leftEye);
    expect(wakeStart.rightEye).toEqual(displayedMidClose.rightEye);
    expect(atSlit.leftEye).toEqual([{ x: 38, y: 43, width: 28, height: 2 }]);
    expect(atSlit.rightEye).toEqual([{ x: 94, y: 43, width: 28, height: 2 }]);
    expect(boundsCenter(atSlit.leftEye)).toEqual({ x: 52, y: 44 });
    expect(boundsCenter(atSlit.rightEye)).toEqual({ x: 108, y: 44 });
    expectContinuousBounds(beforeSlit.leftEye, atSlit.leftEye);
    expectContinuousBounds(atSlit.leftEye, afterSlit.leftEye);
    expect(finished.leftEye).toEqual(awake.leftEye);
    expect(finished.rightEye).toEqual(awake.rightEye);
  });

  it('returns to sleep mid-wake without replacing the displayed eye topology', () => {
    const awake = resolveFaceFrame({ emotion: 'CURIOUS', reducedMotion: true });
    const sleeping = resolveFaceFrame({
      emotion: 'CURIOUS',
      sleeping: true,
      reducedMotion: true,
    });
    const displayedMidWake = interpolateSleepFrames(
      sleeping,
      awake,
      0.5,
      'waking',
    );
    const sleepStart = interpolateSleepFrames(
      displayedMidWake,
      sleeping,
      0,
      'sleeping',
    );
    const beforeSlit = interpolateSleepFrames(
      displayedMidWake,
      sleeping,
      0.74,
      'sleeping',
    );
    const atSlit = interpolateSleepFrames(
      displayedMidWake,
      sleeping,
      0.75,
      'sleeping',
    );
    const afterSlit = interpolateSleepFrames(
      displayedMidWake,
      sleeping,
      0.76,
      'sleeping',
    );
    const finished = interpolateSleepFrames(
      displayedMidWake,
      sleeping,
      1,
      'sleeping',
    );

    expect(sleepStart.leftEye).toEqual(displayedMidWake.leftEye);
    expect(sleepStart.rightEye).toEqual(displayedMidWake.rightEye);
    expect(atSlit.leftEye).toEqual([{ x: 38, y: 43, width: 28, height: 2 }]);
    expect(atSlit.rightEye).toEqual([{ x: 94, y: 43, width: 28, height: 2 }]);
    expectContinuousBounds(beforeSlit.rightEye, atSlit.rightEye);
    expectContinuousBounds(atSlit.rightEye, afterSlit.rightEye);
    expect(finished.leftEye).toEqual(sleeping.leftEye);
    expect(finished.rightEye).toEqual(sleeping.rightEye);
  });

  it('keeps every dense sleep/wake and nested reversal frame on the integer grid', () => {
    const awake = resolveFaceFrame({ emotion: 'NEUTRAL', reducedMotion: true });
    const sleeping = resolveFaceFrame({
      emotion: 'NEUTRAL',
      sleeping: true,
      reducedMotion: true,
    });
    const progressValues = Array.from({ length: 101 }, (_, index) => index / 100);

    for (const [from, to, phase] of [
      [awake, sleeping, 'sleeping'],
      [sleeping, awake, 'waking'],
    ]) {
      let previous = null;
      for (const progress of progressValues) {
        const frame = interpolateSleepFrames(from, to, progress, phase);
        expectIntegerBoundedConnected(frame.leftEye);
        expectIntegerBoundedConnected(frame.rightEye);
        if (previous) {
          expectContinuousBounds(previous.leftEye, frame.leftEye);
          expectContinuousBounds(previous.rightEye, frame.rightEye);
        }
        previous = frame;
      }
    }

    for (const seedProgress of [0.17, 0.43, 0.68, 0.74, 0.76]) {
      const partialClose = interpolateSleepFrames(
        awake,
        sleeping,
        seedProgress,
        'sleeping',
      );
      const partialWake = interpolateSleepFrames(
        sleeping,
        awake,
        seedProgress,
        'waking',
      );
      let previousWake = null;
      let previousClose = null;

      for (const progress of progressValues) {
        const reversedWake = interpolateSleepFrames(
          partialClose,
          awake,
          progress,
          'waking',
        );
        const reversedClose = interpolateSleepFrames(
          partialWake,
          sleeping,
          progress,
          'sleeping',
        );
        for (const eye of [
          reversedWake.leftEye,
          reversedWake.rightEye,
          reversedClose.leftEye,
          reversedClose.rightEye,
        ]) {
          expectIntegerBoundedConnected(eye);
        }
        if (previousWake) {
          expectContinuousBounds(previousWake.leftEye, reversedWake.leftEye);
          expectContinuousBounds(previousWake.rightEye, reversedWake.rightEye);
          expectContinuousBounds(previousClose.leftEye, reversedClose.leftEye);
          expectContinuousBounds(previousClose.rightEye, reversedClose.rightEye);
        }
        previousWake = reversedWake;
        previousClose = reversedClose;
      }

      expect(
        interpolateSleepFrames(partialClose, awake, 0, 'waking').leftEye,
      ).toEqual(partialClose.leftEye);
      expect(
        interpolateSleepFrames(partialWake, sleeping, 0, 'sleeping').rightEye,
      ).toEqual(partialWake.rightEye);
      expect(
        interpolateSleepFrames(partialClose, awake, 1, 'waking').leftEye,
      ).toEqual(awake.leftEye);
      expect(
        interpolateSleepFrames(partialWake, sleeping, 1, 'sleeping').rightEye,
      ).toEqual(sleeping.rightEye);
    }
  });

  it('keeps the visual-QA 0.43 close to 0.25 wake reversal integral', () => {
    const neutral = resolveFaceFrame({
      emotion: 'NEUTRAL',
      reducedMotion: true,
    });
    const sleeping = resolveFaceFrame({ sleeping: true, reducedMotion: true });
    const partialClose = interpolateSleepFrames(
      neutral,
      sleeping,
      0.43,
      'sleeping',
    );
    const wakeSlit = interpolateSleepFrames(
      partialClose,
      neutral,
      0.25,
      'waking',
    );

    expect(wakeSlit.leftEye).toEqual([{ x: 38, y: 43, width: 28, height: 2 }]);
    expect(wakeSlit.rightEye).toEqual([{ x: 94, y: 43, width: 28, height: 2 }]);
    expectIntegerBoundedConnected(wakeSlit.leftEye);
    expectIntegerBoundedConnected(wakeSlit.rightEye);
  });

  it('renders a deep-blue background, two bounded halos, and crisp cores', () => {
    const calls = [];
    const context = {
      fillStyle: '',
      globalAlpha: 1,
      imageSmoothingEnabled: true,
      save() {},
      restore() {},
      clearRect(...values) {
        calls.push({ type: 'clear', values });
      },
      fillRect(...values) {
        calls.push({
          type: 'fill',
          values,
          fillStyle: this.fillStyle,
          alpha: this.globalAlpha,
        });
      },
    };
    const frame = resolveFaceFrame({ emotion: 'NEUTRAL', reducedMotion: true });
    const shapeCount = allFaceRectangles(frame).length;

    renderFace(context, frame);

    expect(context.imageSmoothingEnabled).toBe(false);
    expect(calls[0]).toEqual({ type: 'clear', values: [0, 0, 160, 100] });
    expect(calls[1]).toMatchObject({
      type: 'fill',
      values: [0, 0, 160, 100],
      fillStyle: FACE_BACKGROUND,
      alpha: 1,
    });
    expect(calls).toHaveLength(2 + shapeCount * 3);
    expect(calls[2].alpha).toBe(0.08);
    expect(calls[2].values[0]).toBe(frame.leftEye[0].x - 2);
    expect(calls[2 + shapeCount].alpha).toBe(0.16);
    expect(calls[2 + shapeCount * 2].alpha).toBe(1);
    expect(calls[2 + shapeCount * 2].values).toEqual([
      frame.leftEye[0].x,
      frame.leftEye[0].y,
      frame.leftEye[0].width,
      frame.leftEye[0].height,
    ]);
  });
});
