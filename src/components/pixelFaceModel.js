export const FACE_WIDTH = 160;
export const FACE_HEIGHT = 100;
export const FACE_BACKGROUND = '#061018';

export const FACE_PALETTE = Object.freeze({
  neutral: '#78f3d3',
  happy: '#8bffe0',
  curious: '#79eaff',
  concerned: '#96dcdf',
  excited: '#ffe395',
  sleeping: '#78ccc9',
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

// Every silhouette is built from 2 px scanlines. Its highlights are separate
// layers clipped to the current silhouette, including during blinks and morphs.
function roundedEye(centerX, centerY, width, height) {
  const rows = height / 2;
  return Array.from({ length: rows }, (_, index) => {
    const edgeDistance = Math.min(index, rows - index - 1);
    const inset = edgeDistance === 0 ? 6 : edgeDistance === 1 ? 2 : 0;
    return rect(centerX - width / 2 + inset, centerY - height / 2 + index * 2, width - inset * 2, 2);
  });
}

function happyEye(centerX, centerY) {
  return [
    rect(centerX - 6, centerY - 6, 12, 4),
    rect(centerX - 10, centerY - 4, 20, 4),
    rect(centerX - 14, centerY, 8, 4),
    rect(centerX + 6, centerY, 8, 4),
    rect(centerX - 14, centerY + 4, 4, 4),
    rect(centerX + 10, centerY + 4, 4, 4),
  ];
}

function worriedEye(centerX, side) {
  return roundedEye(centerX, 44, 22, 22).map((item) => {
    // Lift the inner corner: a worried, asking-for-help expression, never a V scowl.
    const innerOffset = side === 'left' ? 2 : -2;
    return { ...item, x: item.x + (item.y < 38 ? innerOffset : 0) };
  });
}

function starEye(centerX, centerY) {
  const pixels = [
    '......##......', '......##......', '.....####.....', '.....####.....',
    '##############', '.############.', '..##########..', '...########...',
    '...########...', '..##########..', '..####..####..', '.####....####.',
    '.###......###.', '.##........##.',
  ];
  const rectangles = [];
  pixels.forEach((row, index) => {
    for (const match of row.matchAll(/#+/g)) {
      rectangles.push(rect(centerX - 14 + match.index * 2, centerY - 14 + index * 2, match[0].length * 2, 2));
    }
  });
  return rectangles;
}

function sleepingEye(centerX) {
  return [
    rect(centerX - 12, 42, 4, 6),
    rect(centerX - 8, 44, 16, 4),
    rect(centerX + 8, 42, 4, 6),
  ];
}

function emotionEyes(emotion) {
  const centers = { left: { x: 52, y: 42 }, right: { x: 108, y: 42 } };
  switch (emotion) {
    case 'happy':
      return { left: happyEye(52, 42), right: happyEye(108, 42), centers };
    case 'curious':
      return {
        left: roundedEye(50, 40, 26, 32),
        right: roundedEye(108, 44, 22, 24),
        centers: { left: { x: 50, y: 40 }, right: { x: 108, y: 44 } },
      };
    case 'concerned':
      return {
        left: worriedEye(52, 'left'), right: worriedEye(108, 'right'), centers,
        corners: { left: { innerY: 25, outerY: 29 }, right: { innerY: 25, outerY: 29 } },
      };
    case 'excited':
      return { left: starEye(52, 41), right: starEye(108, 41), centers };
    case 'sleeping':
      return {
        left: sleepingEye(52), right: sleepingEye(108),
        centers: { left: { x: 52, y: 45 }, right: { x: 108, y: 45 } },
      };
    default:
      return { left: roundedEye(52, 42, 24, 30), right: roundedEye(108, 42, 24, 30), centers };
  }
}

function shortMouth(width = 8) {
  return [rect(80 - width / 2, 71, width, 2)];
}

function openSmile(width, height) {
  const rows = height / 2;
  return Array.from({ length: rows }, (_, index) => {
    const inset = index < rows - 3 ? 0 : (index - rows + 4) * 2;
    return rect(80 - width / 2 + inset, 66 + index * 2, width - inset * 2, 2);
  });
}

function emotionMouth(emotion) {
  switch (emotion) {
    case 'happy':
      return openSmile(22, 14);
    case 'curious':
      return [rect(77, 67, 6, 2), rect(75, 69, 2, 6), rect(83, 69, 2, 6), rect(77, 75, 6, 2)];
    case 'sleeping':
      return shortMouth(6);
    case 'concerned':
      return [rect(72, 73, 4, 2), rect(76, 71, 8, 2), rect(84, 73, 4, 2)];
    case 'excited':
      return openSmile(26, 18);
    default:
      return [rect(73, 69, 2, 4), rect(75, 73, 10, 2), rect(85, 69, 2, 4)];
  }
}

function shiftRectangles(rectangles, offsetX = 0, offsetY = 0) {
  return rectangles.map((item) => ({ ...item, x: item.x + offsetX, y: item.y + offsetY }));
}

function clipRectangles(rectangles, mask) {
  const clipped = [];
  for (const item of rectangles) {
    for (const region of mask) {
      const x = Math.max(item.x, region.x);
      const y = Math.max(item.y, region.y);
      const right = Math.min(item.x + item.width, region.x + region.width);
      const bottom = Math.min(item.y + item.height, region.y + region.height);
      if (right > x && bottom > y) clipped.push(rect(x, y, right - x, bottom - y));
    }
  }
  return clipped;
}

function eyebrowGeometry(emotion) {
  if (emotion === 'curious') {
    return [rect(37, 18, 16, 2), rect(53, 20, 8, 2), rect(100, 26, 14, 2)];
  }
  if (emotion === 'concerned') {
    return [rect(39, 29, 8, 2), rect(47, 27, 8, 2), rect(55, 25, 8, 2),
      rect(97, 25, 8, 2), rect(105, 27, 8, 2), rect(113, 29, 8, 2)];
  }
  return [];
}

function eyeDetails(emotion, centers) {
  if (['happy', 'sleeping', 'excited'].includes(emotion)) return { highlights: [], eyeShadows: [] };
  const highlights = [];
  const eyeShadows = [];
  for (const center of [centers.left, centers.right]) {
    highlights.push(rect(center.x - 6, center.y - 8, 6, 6), rect(center.x + 4, center.y + 6, 2, 2));
    eyeShadows.push(rect(center.x + 4, center.y + 8, 6, 4));
  }
  return { highlights, eyeShadows };
}

function sparkle(x, y, size = 2) {
  return [rect(x - size, y, size * 3, size), rect(x, y - size, size, size * 3)];
}

function sleepZ(x, y) {
  return [
    rect(x, y, 5, 1),
    rect(x + 3, y + 1, 1, 1),
    rect(x + 2, y + 2, 1, 1),
    rect(x + 1, y + 3, 1, 1),
    rect(x, y + 4, 5, 1),
  ];
}

function expressiveDetails(emotion, elapsed, reducedMotion, speaking) {
  const cheekWidth = ['happy', 'excited'].includes(emotion) ? 14 : 10;
  const cheeks = [rect(31 - cheekWidth / 2, 61, cheekWidth, 4), rect(129 - cheekWidth / 2, 61, cheekWidth, 4)];
  const joyfulMouth = !speaking && ['happy', 'excited'].includes(emotion);
  const tongueY = emotion === 'excited' ? 76 : 74;
  const sparkleVisible = emotion === 'excited' && !speaking && (reducedMotion || elapsed < 1200);
  const sparkleStep = reducedMotion ? 0 : Math.floor(elapsed / 300) % 2;
  const sleepFloat = reducedMotion ? 0 : Math.floor((elapsed % 5800) / 1450) * 2;
  return {
    cheeks,
    cheekOpacity: emotion === 'sleeping' ? 0.22 : ['happy', 'excited'].includes(emotion) ? 0.8 : 0.45,
    mouthShadows: joyfulMouth ? [rect(emotion === 'excited' ? 71 : 73, 68, emotion === 'excited' ? 18 : 14, emotion === 'excited' ? 8 : 6)] : [],
    mouthAccents: joyfulMouth ? [rect(76, tongueY, 8, 4)] : [],
    sparkles: sparkleVisible ? [...sparkle(23, 28 - sparkleStep * 2), ...sparkle(137, 35 + sparkleStep * 2)] : [],
    sleepMarks: emotion === 'sleeping' ? [...sleepZ(127, 26 - sleepFloat), ...sleepZ(139, 14 - sleepFloat)] : [],
  };
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
  const phase = elapsedMs % BLINK_PERIOD_MS;
  const cycle = Math.floor(elapsedMs / BLINK_PERIOD_MS);
  const doubleBlink = cycle > 0 && cycle % 3 === 0 && phase >= 60 && phase < 60 + BLINK_DURATION_MS;
  const startsAt = doubleBlink ? 60 : BLINK_PERIOD_MS - BLINK_DURATION_MS;
  if (phase < startsAt || phase >= startsAt + BLINK_DURATION_MS) return { frame: null, scale: 1 };
  const frame = Math.min(BLINK_SCALES.length - 1, Math.floor((phase - startsAt) / (BLINK_DURATION_MS / BLINK_SCALES.length)));
  return { frame, scale: BLINK_SCALES[frame] };
}

function bodyOffset(elapsedMs, emotion, reducedMotion, speaking) {
  if (reducedMotion || speaking) return 0;
  const period = emotion === 'sleeping' ? 5800 : 6400;
  // A single 2 px rise per slow breath, with a long settled rest at baseline.
  return Math.sin((elapsedMs / period) * Math.PI * 2) > 0.65 ? -2 : 0;
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
  expressionElapsedMs = elapsedMs,
  reducedMotion = false,
  previousEqualizerBand,
} = {}) {
  const normalizedTurnState = String(turnState || 'IDLE').toUpperCase();
  const requestedEmotion = normalizeEmotion(emotion);
  const resolvedEmotion = sleeping ? 'sleeping'
    : normalizedTurnState === 'LISTENING' ? 'curious' : requestedEmotion;
  const elapsed = Math.max(0, numeric(elapsedMs));
  const expressionElapsed = Math.max(0, numeric(expressionElapsedMs));
  const speaking = normalizedTurnState === 'SPEAKING' && !sleeping;
  const eyes = emotionEyes(resolvedEmotion);
  const driftX = thinkingOffset(elapsed, normalizedTurnState, reducedMotion, sleeping);
  const breathY = bodyOffset(elapsed, resolvedEmotion, reducedMotion, speaking);
  const bounceY = !reducedMotion && !speaking && resolvedEmotion === 'excited' && expressionElapsed < 600
    ? expressionElapsed >= 120 && expressionElapsed < 360 ? -2 : 0 : 0;
  const offsetY = breathY + bounceY;
  const blink = blinkState(elapsed, reducedMotion, sleeping);
  const winkPhase = expressionElapsed % 11000;
  const winking = !reducedMotion && !speaking && resolvedEmotion === 'happy'
    && blink.frame === null && winkPhase >= 7000 && winkPhase < 7260;
  const leftEye = sanitizeRectangles(shiftRectangles(
    squashRectangles(eyes.left, eyes.centers.left.y, winking ? 0.12 : blink.scale), driftX, offsetY,
  ));
  const rightEye = sanitizeRectangles(shiftRectangles(
    squashRectangles(eyes.right, eyes.centers.right.y, blink.scale), driftX, offsetY,
  ));
  const equalizerBand = speaking ? resolveEqualizerBand(mouthLevel, previousEqualizerBand) : 0;
  const mouth = sanitizeRectangles(shiftRectangles(
    speaking ? equalizerMouth(equalizerBand) : emotionMouth(resolvedEmotion), 0, offsetY,
  ));
  const eyeDetail = eyeDetails(resolvedEmotion, eyes.centers);
  const details = expressiveDetails(resolvedEmotion, expressionElapsed, reducedMotion, speaking);
  const eyesMask = [...leftEye, ...rightEye];
  return {
    width: FACE_WIDTH, height: FACE_HEIGHT, backgroundColor: FACE_BACKGROUND,
    color: FACE_PALETTE[resolvedEmotion], emotion: resolvedEmotion, turnState: normalizedTurnState,
    leftEye, rightEye, mouth,
    speechClosedMouth: shortMouth(),
    brows: blink.scale < 0.3 ? [] : shiftRectangles(eyebrowGeometry(resolvedEmotion), driftX, offsetY),
    highlights: clipRectangles(shiftRectangles(eyeDetail.highlights, driftX, offsetY), eyesMask),
    eyeShadows: clipRectangles(shiftRectangles(eyeDetail.eyeShadows, driftX, offsetY), eyesMask),
    cheeks: shiftRectangles(details.cheeks, 0, offsetY),
    cheekOpacity: details.cheekOpacity,
    mouthShadows: clipRectangles(shiftRectangles(details.mouthShadows, 0, offsetY), mouth),
    mouthAccents: clipRectangles(shiftRectangles(details.mouthAccents, 0, offsetY), mouth),
    sparkles: details.sparkles,
    sleepMarks: details.sleepMarks,
    detailOpacity: 1,
    equalizerBand, speaking, blinkFrame: blink.frame, winking,
    eyeOffsetX: driftX, bodyOffsetY: offsetY,
    metrics: { eyeCenters: eyes.centers, concernedCorners: eyes.corners || null },
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
      Math.round(centerX - width / 2),
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
  const amount = clamp(numeric(progress), 0, 1);
  const frame = {
    ...toFrame,
    leftEye: morphRectanglesThroughSlit(
      fromFrame.leftEye,
      toFrame.leftEye,
      EYE_TRANSITION_SLITS.left,
      amount,
    ),
    rightEye: morphRectanglesThroughSlit(
      fromFrame.rightEye,
      toFrame.rightEye,
      EYE_TRANSITION_SLITS.right,
      amount,
    ),
    mouth: toFrame.speaking ? toFrame.mouth : morphRectanglesThroughSlit(
      fromFrame.mouth, toFrame.mouth, toFrame.speechClosedMouth[0], amount,
    ),
  };
  return transitionDetails(fromFrame, toFrame, frame, amount);
}

function transitionDetails(fromFrame, toFrame, frame, amount) {
  const source = amount < 0.5 ? fromFrame : toFrame;
  const eyes = [...frame.leftEye, ...frame.rightEye];
  const eyeHeight = (items) => Math.max(...items.map((item) => item.y + item.height))
    - Math.min(...items.map((item) => item.y));
  const eyesOpen = Math.min(eyeHeight(frame.leftEye), eyeHeight(frame.rightEye)) >= 8;
  return {
    ...frame,
    detailOpacity: (source.detailOpacity ?? 1) * Math.abs(1 - amount * 2),
    brows: eyesOpen ? source.brows || [] : [],
    highlights: eyesOpen ? clipRectangles(source.highlights || [], eyes) : [],
    eyeShadows: eyesOpen ? clipRectangles(source.eyeShadows || [], eyes) : [],
    cheeks: source.cheeks || [],
    cheekOpacity: source.cheekOpacity ?? 0.45,
    mouthShadows: frame.speaking ? [] : clipRectangles(source.mouthShadows || [], frame.mouth),
    mouthAccents: frame.speaking ? [] : clipRectangles(source.mouthAccents || [], frame.mouth),
    sparkles: toFrame.emotion === 'sleeping' ? [] : source.sparkles || [],
    sleepMarks: toFrame.emotion === 'sleeping' && amount > 0.75 ? toFrame.sleepMarks : [],
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
  const frame = {
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
    mouth: morphRectanglesThroughSlit(
      fromFrame.mouth, toFrame.mouth, toFrame.speechClosedMouth[0], amount,
    ),
  };
  return transitionDetails(fromFrame, toFrame, frame, amount);
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
  const detailOpacity = frame.detailOpacity ?? 1;
  context.imageSmoothingEnabled = false;
  context.save();
  context.clearRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  context.globalAlpha = 1;
  context.fillStyle = frame.backgroundColor;
  context.fillRect(0, 0, FACE_WIDTH, FACE_HEIGHT);
  drawRectangles(context, rectangles, frame.color, 2, 0.08);
  drawRectangles(context, rectangles, frame.color, 1, 0.16);
  drawRectangles(context, rectangles, frame.color, 0, 1);
  drawRectangles(context, frame.brows || [], frame.color, 0, detailOpacity * 0.82);
  drawRectangles(context, frame.eyeShadows || [], '#279c99', 0, detailOpacity * 0.65);
  drawRectangles(context, frame.highlights || [], '#edfff5', 0, detailOpacity);
  drawRectangles(context, frame.cheeks || [], '#f3a6a5', 0, detailOpacity * (frame.cheekOpacity ?? 0.45));
  drawRectangles(context, frame.mouthShadows || [], '#13333a', 0, detailOpacity);
  drawRectangles(context, frame.mouthAccents || [], '#f8b4b5', 0, detailOpacity);
  drawRectangles(context, frame.sparkles || [], '#ffedbc', 0, detailOpacity * 0.8);
  drawRectangles(context, frame.sleepMarks || [], '#8fcdca', 0, detailOpacity * 0.5);
  context.restore();
}
