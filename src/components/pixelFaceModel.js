export const FACE_WIDTH = 160;
export const FACE_HEIGHT = 100;
export const FACE_BACKGROUND = '#061018';

export const FACE_PALETTE = Object.freeze({
  neutral: '#76f4ff',
  happy: '#68ffd1',
  curious: '#6ce8ff',
  concerned: '#ff7f91',
  excited: '#ffdf6c',
  sleeping: '#76f4ff',
});

export const THINKING_TURN_STATES = Object.freeze([
  'UPLOADING',
  'TRANSCRIBING',
  'THINKING',
  'AWAITING_TOOL',
  'SYNTHESIZING',
]);

const KNOWN_EMOTIONS = new Set(['neutral', 'happy', 'curious', 'concerned', 'excited']);
const BLINK_PERIOD_MS = 5200;
const BLINK_DURATION_MS = 167;
const BLINK_SCALES = Object.freeze([1, 0.6, 0.12, 0.6, 1]);
const EQUALIZER_THRESHOLDS = Object.freeze([0.12, 0.375, 0.625, 0.875]);
const EQUALIZER_HYSTERESIS = 0.04;
const SPEECH_ENTRY_MS = 100;
const SPEECH_EXIT_TO_LINE_MS = 80;
const SPEECH_EXIT_MS = 180;
const EQUALIZER_HEIGHTS = Object.freeze({
  1: [2, 4, 2],
  2: [2, 6, 2],
  3: [4, 6, 4],
  4: [4, 8, 4],
});

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}

function numeric(value, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function rect(x, y, width, height) {
  return {
    x: Math.round(x),
    y: Math.round(y),
    width: Math.round(width),
    height: Math.round(height),
  };
}

function sanitizeRectangles(rectangles) {
  return rectangles
    .map((item) => {
      const x = clamp(Math.round(item.x), 0, FACE_WIDTH);
      const y = clamp(Math.round(item.y), 0, FACE_HEIGHT);
      const width = clamp(Math.round(item.width), 0, FACE_WIDTH - x);
      const height = clamp(Math.round(item.height), 0, FACE_HEIGHT - y);
      return { x, y, width, height };
    })
    .filter((item) => item.width > 0 && item.height > 0);
}

function capsuleScanlines(centerX, centerY, width, height) {
  const rowCount = Math.round(height / 2);
  let widths;

  if (rowCount === 6) {
    widths = [width - 8, width - 2, width, width, width - 2, width - 8];
  } else if (rowCount === 7) {
    widths = [width - 12, width - 4, width, width, width, width - 4, width - 12];
  } else {
    widths = Array.from({ length: rowCount }, (_, index) => {
      if (index === 0 || index === rowCount - 1) return width - 8;
      if (index === 1 || index === rowCount - 2) return width - 4;
      return width;
    });
  }

  const top = centerY - height / 2;
  return widths.map((rowWidth, index) =>
    rect(centerX - rowWidth / 2, top + index * 2, rowWidth, 2),
  );
}

function happyEye(centerX, centerY) {
  const top = centerY - 7;
  return [
    rect(centerX - 8, top, 16, 2),
    rect(centerX - 12, top + 2, 24, 2),
    rect(centerX - 14, top + 4, 8, 2),
    rect(centerX + 6, top + 4, 8, 2),
    rect(centerX - 16, top + 6, 8, 2),
    rect(centerX + 8, top + 6, 8, 2),
    rect(centerX - 16, top + 8, 6, 2),
    rect(centerX + 10, top + 8, 6, 2),
    rect(centerX - 16, top + 10, 4, 2),
    rect(centerX + 12, top + 10, 4, 2),
    rect(centerX - 16, top + 12, 4, 2),
    rect(centerX + 12, top + 12, 4, 2),
  ];
}

function concernedEye(centerX, centerY, side) {
  const leftRows = [
    [4, -6, 8],
    [-4, -4, 20],
    [-12, -2, 28],
    [-16, 0, 32],
    [-16, 2, 24],
    [-12, 4, 12],
  ];

  return leftRows.map(([relativeX, relativeY, width]) => {
    const x = side === 'left' ? relativeX : -relativeX - width;
    return rect(centerX + x, centerY + relativeY, width, 2);
  });
}

function sleepingEye(centerX) {
  return [rect(centerX - 14, 42, 28, 4)];
}

function emotionEyes(emotion) {
  switch (emotion) {
    case 'happy':
      return {
        left: happyEye(52, 42),
        right: happyEye(108, 42),
        centers: { left: { x: 52, y: 42 }, right: { x: 108, y: 42 } },
      };
    case 'curious':
      return {
        left: capsuleScanlines(50, 40, 36, 14),
        right: capsuleScanlines(110, 43, 24, 12),
        centers: { left: { x: 50, y: 40 }, right: { x: 110, y: 43 } },
      };
    case 'concerned':
      return {
        left: concernedEye(52, 42, 'left'),
        right: concernedEye(108, 42, 'right'),
        centers: { left: { x: 52, y: 42 }, right: { x: 108, y: 42 } },
        corners: {
          left: { innerY: 38, outerY: 42 },
          right: { innerY: 38, outerY: 42 },
        },
      };
    case 'excited':
      return {
        left: capsuleScanlines(54, 40, 18, 30),
        right: capsuleScanlines(106, 40, 18, 30),
        centers: { left: { x: 54, y: 40 }, right: { x: 106, y: 40 } },
      };
    case 'sleeping':
      return {
        left: sleepingEye(52),
        right: sleepingEye(108),
        centers: { left: { x: 52, y: 44 }, right: { x: 108, y: 44 } },
      };
    default:
      return {
        left: capsuleScanlines(52, 42, 34, 14),
        right: capsuleScanlines(108, 42, 34, 14),
        centers: { left: { x: 52, y: 42 }, right: { x: 108, y: 42 } },
      };
  }
}

function shortMouth(width = 8) {
  return [rect(80 - width / 2, 71, width, 2)];
}

function emotionMouth(emotion) {
  switch (emotion) {
    case 'happy':
      return [rect(75, 70, 2, 2), rect(77, 72, 6, 2), rect(83, 70, 2, 2)];
    case 'curious':
    case 'sleeping':
      return shortMouth(6);
    case 'concerned':
      return [rect(75, 72, 2, 2), rect(77, 70, 6, 2), rect(83, 72, 2, 2)];
    case 'excited':
      return [
        rect(75, 69, 10, 2),
        rect(75, 71, 2, 2),
        rect(83, 71, 2, 2),
        rect(75, 73, 10, 2),
      ];
    default:
      return shortMouth();
  }
}

function shiftRectangles(rectangles, offsetX) {
  if (!offsetX) return rectangles.map((item) => ({ ...item }));
  return rectangles.map((item) => ({ ...item, x: item.x + offsetX }));
}

function squashRectangles(rectangles, centerY, scale) {
  if (scale === 1) return rectangles.map((item) => ({ ...item }));

  return rectangles.map((item) => {
    const top = centerY + (item.y - centerY) * scale;
    const bottom = centerY + (item.y + item.height - centerY) * scale;
    const y = Math.round(top);
    const height = Math.max(2, Math.round((bottom - top) / 2) * 2);
    return rect(item.x, y, item.width, height);
  });
}

function blinkState(elapsedMs, reducedMotion, sleeping) {
  if (reducedMotion || sleeping) return { frame: null, scale: 1 };

  const phase = ((elapsedMs % BLINK_PERIOD_MS) + BLINK_PERIOD_MS) % BLINK_PERIOD_MS;
  const startsAt = BLINK_PERIOD_MS - BLINK_DURATION_MS;
  if (phase < startsAt) return { frame: null, scale: 1 };

  const frameDuration = BLINK_DURATION_MS / BLINK_SCALES.length;
  const frame = Math.min(
    BLINK_SCALES.length - 1,
    Math.floor((phase - startsAt) / frameDuration),
  );
  return { frame, scale: BLINK_SCALES[frame] };
}

function thinkingOffset(elapsedMs, turnState, reducedMotion, sleeping) {
  if (reducedMotion || sleeping || !THINKING_TURN_STATES.includes(turnState)) return 0;
  const drift = Math.sin((elapsedMs / BLINK_PERIOD_MS) * Math.PI * 2);
  if (drift >= 0.5) return 2;
  if (drift <= -0.5) return -2;
  return 0;
}

function nominalEqualizerBand(level) {
  if (level <= EQUALIZER_THRESHOLDS[0]) return 0;
  if (level <= EQUALIZER_THRESHOLDS[1]) return 1;
  if (level <= EQUALIZER_THRESHOLDS[2]) return 2;
  if (level <= EQUALIZER_THRESHOLDS[3]) return 3;
  return 4;
}

export function normalizeEmotion(emotion) {
  const key = String(emotion || 'NEUTRAL').toLowerCase();
  return KNOWN_EMOTIONS.has(key) ? key : 'neutral';
}

export function resolveEqualizerBand(mouthLevel, previousBand) {
  const level = clamp(numeric(mouthLevel), 0, 1);
  const nominal = nominalEqualizerBand(level);
  if (!Number.isInteger(previousBand) || previousBand < 0 || previousBand > 4) {
    return nominal;
  }

  let band = previousBand;
  while (
    band < 4 &&
    level > EQUALIZER_THRESHOLDS[band] + EQUALIZER_HYSTERESIS
  ) {
    band += 1;
  }
  while (
    band > 0 &&
    level < EQUALIZER_THRESHOLDS[band - 1] - EQUALIZER_HYSTERESIS
  ) {
    band -= 1;
  }
  return band;
}

export function equalizerMouth(band) {
  if (!EQUALIZER_HEIGHTS[band]) return shortMouth();
  return EQUALIZER_HEIGHTS[band].map((height, index) =>
    rect(75 + index * 4, 72 - height / 2, 2, height),
  );
}

export function resolveFaceFrame({
  emotion = 'NEUTRAL',
  mouthLevel = 0,
  sleeping = false,
  turnState = 'IDLE',
  elapsedMs = 0,
  reducedMotion = false,
  previousEqualizerBand,
} = {}) {
  const normalizedTurnState = String(turnState || 'IDLE').toUpperCase();
  const requestedEmotion = normalizeEmotion(emotion);
  const resolvedEmotion = sleeping
    ? 'sleeping'
    : normalizedTurnState === 'LISTENING'
      ? 'curious'
      : requestedEmotion;
  const elapsed = Math.max(0, numeric(elapsedMs));
  const eyes = emotionEyes(resolvedEmotion);
  const driftX = thinkingOffset(elapsed, normalizedTurnState, reducedMotion, sleeping);
  const blink = blinkState(elapsed, reducedMotion, sleeping);
  const leftEye = sanitizeRectangles(
    squashRectangles(
      shiftRectangles(eyes.left, driftX),
      eyes.centers.left.y,
      blink.scale,
    ),
  );
  const rightEye = sanitizeRectangles(
    squashRectangles(
      shiftRectangles(eyes.right, driftX),
      eyes.centers.right.y,
      blink.scale,
    ),
  );
  const speaking = normalizedTurnState === 'SPEAKING' && !sleeping;
  const equalizerBand = speaking
    ? resolveEqualizerBand(mouthLevel, previousEqualizerBand)
    : 0;
  const mouth = sanitizeRectangles(
    speaking ? equalizerMouth(equalizerBand) : emotionMouth(resolvedEmotion),
  );
  const speechClosedMouth = sanitizeRectangles(shortMouth());

  return {
    width: FACE_WIDTH,
    height: FACE_HEIGHT,
    backgroundColor: FACE_BACKGROUND,
    color: FACE_PALETTE[resolvedEmotion],
    emotion: resolvedEmotion,
    turnState: normalizedTurnState,
    leftEye,
    rightEye,
    mouth,
    speechClosedMouth,
    equalizerBand,
    speaking,
    blinkFrame: blink.frame,
    eyeOffsetX: driftX,
    metrics: {
      eyeCenters: eyes.centers,
      concernedCorners: eyes.corners || null,
    },
  };
}

const EYE_TRANSITION_SLITS = Object.freeze({
  left: Object.freeze({ x: 44, y: 41, width: 16, height: 2 }),
  right: Object.freeze({ x: 100, y: 41, width: 16, height: 2 }),
});
const SLEEP_TRANSITION_SLITS = Object.freeze({
  left: Object.freeze({ x: 38, y: 43, width: 28, height: 2 }),
  right: Object.freeze({ x: 94, y: 43, width: 28, height: 2 }),
});
const SLEEP_CLOSE_KEYFRAMES = Object.freeze([
  Object.freeze({ progress: 0, closure: 0 }),
  Object.freeze({ progress: 0.25, closure: 0.4 }),
  Object.freeze({ progress: 0.5, closure: 0.9 }),
  Object.freeze({ progress: 0.75, closure: 1 }),
]);

function interpolateNumber(from, to, progress) {
  return from + (to - from) * progress;
}

function snapEvenDimension(value) {
  return Math.max(2, Math.round(value / 2) * 2);
}

function snapCenter(value, anchor) {
  const fraction = value - Math.floor(value);
  if (Math.abs(fraction - 0.5) < Number.EPSILON * 4) {
    return value < anchor ? Math.floor(value) : Math.ceil(value);
  }
  return Math.round(value);
}

function snapCoordinateToGrid(value, anchor) {
  return anchor + snapCenter((value - anchor) / 2, 0) * 2;
}

function mergeIntervals(intervals) {
  const sorted = intervals
    .map((interval) => ({ ...interval }))
    .sort((left, right) => left.start - right.start || left.end - right.end);
  const merged = [];
  for (const interval of sorted) {
    const previous = merged.at(-1);
    if (previous && interval.start <= previous.end) {
      previous.end = Math.max(previous.end, interval.end);
    } else {
      merged.push(interval);
    }
  }
  return merged;
}

function evenInterval(interval, anchor) {
  const width = interval.end - interval.start;
  if (width % 2 === 0) return interval;
  const expandedWidth = width + 1;
  const containsAnchor = interval.start <= anchor && anchor <= interval.end;
  const center = containsAnchor
    ? anchor
    : snapCenter((interval.start + interval.end) / 2, anchor);
  let start = center - expandedWidth / 2;
  start = clamp(start, 0, FACE_WIDTH - expandedWidth);
  return { start, end: start + expandedWidth };
}

function logicalSpans(intervals, anchor) {
  let spans = mergeIntervals(intervals);
  for (let iteration = 0; iteration < 2; iteration += 1) {
    spans = mergeIntervals(spans.map((interval) => evenInterval(interval, anchor)));
  }
  return spans.map((interval) => evenInterval(interval, anchor));
}

function bridgeSpans(above, below, anchor) {
  const intersections = [];
  for (const upper of above) {
    for (const lower of below) {
      const start = Math.max(upper.start, lower.start);
      const end = Math.min(upper.end, lower.end);
      if (end > start) intersections.push({ start, end });
    }
  }
  return logicalSpans(intersections, anchor);
}

function unionLogicalRectangles(rectangles, slit) {
  const items = sanitizeRectangles(rectangles);
  const top = Math.min(...items.map((item) => item.y));
  const bottom = Math.max(...items.map((item) => item.y + item.height));
  const horizontalAnchor = slit.x + slit.width / 2;
  const bands = [];
  for (let y = top; y < bottom; y += 2) {
    const intervals = items
      .filter((item) => item.y < y + 2 && item.y + item.height > y)
      .map((item) => ({ start: item.x, end: item.x + item.width }));
    bands.push({ y, spans: logicalSpans(intervals, horizontalAnchor) });
  }
  for (let index = 0; index < bands.length; index += 1) {
    if (bands[index].spans.length) continue;
    let above = null;
    for (let previous = index - 1; previous >= 0; previous -= 1) {
      if (bands[previous].spans.length) {
        above = bands[previous];
        break;
      }
    }
    const below = bands.slice(index + 1).find((band) => band.spans.length);
    if (above && below) {
      bands[index].spans = bridgeSpans(
        above.spans,
        below.spans,
        horizontalAnchor,
      );
    }
  }

  const united = [];
  let activeSpans = new Map();
  for (const band of bands) {
    const nextSpans = new Map();
    for (const span of band.spans) {
      const width = span.end - span.start;
      const key = `${span.start}:${width}`;
      const active = activeSpans.get(key);
      if (active) {
        active.height += 2;
        nextSpans.set(key, active);
      } else {
        const rectangle = rect(span.start, band.y, width, 2);
        united.push(rectangle);
        nextSpans.set(key, rectangle);
      }
    }
    activeSpans = nextSpans;
  }
  return united;
}

export function morphRectanglesToSlit(rectangles, slit, progress) {
  const amount = clamp(numeric(progress), 0, 1);
  if (amount === 0) return sanitizeRectangles(rectangles);
  if (amount === 1) return sanitizeRectangles([slit]);

  const slitCenterX = slit.x + slit.width / 2;
  const slitCenterY = slit.y + slit.height / 2;
  const transformed = rectangles.map((item) => {
    const width = snapEvenDimension(
      interpolateNumber(item.width, slit.width, amount),
    );
    const height = snapEvenDimension(
      interpolateNumber(item.height, slit.height, amount),
    );
    const centerX = snapCenter(
      interpolateNumber(item.x + item.width / 2, slitCenterX, amount),
      slitCenterX,
    );
    const centerY = snapCenter(
      interpolateNumber(item.y + item.height / 2, slitCenterY, amount),
      slitCenterY,
    );
    return rect(
      snapCoordinateToGrid(centerX - width / 2, slit.x),
      snapCoordinateToGrid(centerY - height / 2, slit.y),
      width,
      height,
    );
  });
  return unionLogicalRectangles(transformed, slit);
}

export function morphRectanglesThroughSlit(
  fromRectangles,
  toRectangles,
  slit,
  progress,
) {
  const amount = clamp(numeric(progress), 0, 1);
  if (amount === 0) return sanitizeRectangles(fromRectangles);
  if (amount === 1) return sanitizeRectangles(toRectangles);
  if (amount <= 0.5) {
    return morphRectanglesToSlit(fromRectangles, slit, amount * 2);
  }
  return morphRectanglesToSlit(toRectangles, slit, (1 - amount) * 2);
}

export function interpolateFaceFrames(fromFrame, toFrame, progress) {
  if (!fromFrame) return toFrame;
  return {
    ...toFrame,
    leftEye: morphRectanglesThroughSlit(
      fromFrame.leftEye,
      toFrame.leftEye,
      EYE_TRANSITION_SLITS.left,
      progress,
    ),
    rightEye: morphRectanglesThroughSlit(
      fromFrame.rightEye,
      toFrame.rightEye,
      EYE_TRANSITION_SLITS.right,
      progress,
    ),
  };
}

function sleepClosureAt(progress) {
  for (let index = 1; index < SLEEP_CLOSE_KEYFRAMES.length; index += 1) {
    const previous = SLEEP_CLOSE_KEYFRAMES[index - 1];
    const next = SLEEP_CLOSE_KEYFRAMES[index];
    if (progress <= next.progress) {
      const localProgress =
        (progress - previous.progress) / (next.progress - previous.progress);
      return interpolateNumber(previous.closure, next.closure, localProgress);
    }
  }
  return 1;
}

function interpolateSleepEye(fromEye, toEye, slit, progress, phase) {
  const amount = clamp(numeric(progress), 0, 1);
  if (phase === 'sleeping' && amount <= 0.75) {
    return morphRectanglesToSlit(
      fromEye,
      slit,
      sleepClosureAt(amount),
    );
  }
  if (phase === 'sleeping') {
    return morphRectanglesToSlit(
      toEye,
      slit,
      1 - (amount - 0.75) / 0.25,
    );
  }
  if (amount <= 0.25) {
    return morphRectanglesToSlit(fromEye, slit, amount / 0.25);
  }
  return morphRectanglesToSlit(toEye, slit, sleepClosureAt(1 - amount));
}

export function interpolateSleepFrames(fromFrame, toFrame, progress, phase) {
  if (!fromFrame) {
    return {
      ...toFrame,
      leftEye: sanitizeRectangles(toFrame.leftEye),
      rightEye: sanitizeRectangles(toFrame.rightEye),
    };
  }
  const amount = clamp(numeric(progress), 0, 1);
  return {
    ...toFrame,
    leftEye: sanitizeRectangles(
      interpolateSleepEye(
        fromFrame.leftEye,
        toFrame.leftEye,
        SLEEP_TRANSITION_SLITS.left,
        amount,
        phase,
      ),
    ),
    rightEye: sanitizeRectangles(
      interpolateSleepEye(
        fromFrame.rightEye,
        toFrame.rightEye,
        SLEEP_TRANSITION_SLITS.right,
        amount,
        phase,
      ),
    ),
  };
}

export function resolveSpeechTransitionMouth({
  phase,
  elapsedMs,
  fromMouth,
  closedMouth,
  targetMouth,
}) {
  const elapsed = Math.max(0, numeric(elapsedMs));
  if (phase === 'entering' && elapsed < SPEECH_ENTRY_MS) {
    return morphRectanglesToSlit(
      fromMouth,
      closedMouth[0],
      elapsed / SPEECH_ENTRY_MS,
    );
  }
  if (phase === 'leaving' && elapsed < SPEECH_EXIT_TO_LINE_MS) {
    return morphRectanglesToSlit(
      fromMouth,
      closedMouth[0],
      elapsed / SPEECH_EXIT_TO_LINE_MS,
    );
  }
  if (phase === 'leaving' && elapsed < SPEECH_EXIT_MS) {
    const openingProgress =
      (elapsed - SPEECH_EXIT_TO_LINE_MS) /
      (SPEECH_EXIT_MS - SPEECH_EXIT_TO_LINE_MS);
    return morphRectanglesToSlit(
      targetMouth,
      closedMouth[0],
      1 - openingProgress,
    );
  }
  return targetMouth.map((item) => ({ ...item }));
}

function drawRectangles(context, rectangles, color, spread, alpha) {
  context.fillStyle = color;
  context.globalAlpha = alpha;
  for (const item of rectangles) {
    context.fillRect(
      item.x - spread,
      item.y - spread,
      item.width + spread * 2,
      item.height + spread * 2,
    );
  }
}

export function renderFace(context, frame) {
  if (!context || !frame) return;

  const rectangles = [...frame.leftEye, ...frame.rightEye, ...frame.mouth];
  context.imageSmoothingEnabled = false;
  context.save();
  context.clearRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  context.globalAlpha = 1;
  context.fillStyle = frame.backgroundColor;
  context.fillRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  drawRectangles(context, rectangles, frame.color, 2, 0.08);
  drawRectangles(context, rectangles, frame.color, 1, 0.16);
  drawRectangles(context, rectangles, frame.color, 0, 1);
  context.restore();
}
