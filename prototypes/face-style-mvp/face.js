// Independent eye-only visual study, drawn on a native 160 × 100 canvas.
export const FACE_WIDTH = 160;
export const FACE_HEIGHT = 100;

export const EXPRESSIONS = Object.freeze([
  { code: 'NEUTRAL', name: '中性' },
  { code: 'HAPPY', name: '開心' },
  { code: 'CURIOUS', name: '好奇' },
  { code: 'CONCERNED', name: '擔心' },
  { code: 'EXCITED', name: '興奮' },
]);

const LIGHT = '#fcfaf5';
const DARK = '#061018';
const INK = '#15191a';
const GLOW = '#fffdfa';

const even = (value) => Math.round(value / 2) * 2;
const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

function block(ctx, x, y, width, height, color) {
  ctx.fillStyle = color;
  ctx.fillRect(even(x), even(y), even(width), even(height));
}

function roundedEye(ctx, cx, cy, width, height, lean, foreground, background, glint = true) {
  const rows = height / 2;
  for (let row = 0; row < rows; row += 1) {
    const edge = Math.min(row, rows - row - 1);
    const inset = edge === 0 ? 6 : edge === 1 ? 2 : 0;
    const shift = even(((row / Math.max(1, rows - 1)) - 0.5) * lean);
    block(ctx, cx - width / 2 + inset + shift, cy - height / 2 + row * 2,
      width - inset * 2, 2, foreground);
  }
  if (glint) {
    // A cutout belongs to the eye silhouette, rather than an extra face mark.
    block(ctx, cx - width / 4 - lean / 4, cy - height / 4, 4, 10, background);
  }
}

function happyEye(ctx, cx, cy, color) {
  // A heavy pixel arc, with no detached brow or static mouth.
  block(ctx, cx - 18, cy + 6, 8, 6, color);
  block(ctx, cx - 14, cy, 8, 8, color);
  block(ctx, cx - 8, cy - 6, 10, 8, color);
  block(ctx, cx, cy - 8, 8, 8, color);
  block(ctx, cx + 6, cy - 4, 8, 8, color);
  block(ctx, cx + 12, cy + 2, 6, 8, color);
}

function sleepingEye(ctx, cx, cy, color) {
  block(ctx, cx - 16, cy, 32, 4, color);
  block(ctx, cx - 16, cy - 2, 4, 2, color);
  block(ctx, cx + 12, cy - 2, 4, 2, color);
}

function drawEyes(ctx, emotion, span, foreground, background, blink) {
  const width = even(span * 0.25);
  const height = even(span * 0.38);
  const offset = even((span - width) / 2);
  const left = 80 - offset;
  const right = 80 + offset;

  if (emotion === 'SLEEPING') {
    sleepingEye(ctx, left, 48, foreground);
    sleepingEye(ctx, right, 48, foreground);
    return;
  }
  if (blink) {
    block(ctx, left - width / 2, 44, width, 4, foreground);
    block(ctx, right - width / 2, 44, width, 4, foreground);
    return;
  }

  switch (emotion) {
    case 'HAPPY':
      happyEye(ctx, left, 43, foreground);
      happyEye(ctx, right, 43, foreground);
      break;
    case 'CURIOUS':
      roundedEye(ctx, left - 2, 40, width + 2, height + 4, -2, foreground, background);
      roundedEye(ctx, right + 2, 47, width - 8, height - 10, 2, foreground, background);
      break;
    case 'CONCERNED':
      // Inward-leaning silhouettes make a worried expression without brows.
      roundedEye(ctx, left, 45, width - 2, height - 4, -12, foreground, background);
      roundedEye(ctx, right, 45, width - 2, height - 4, 12, foreground, background);
      break;
    case 'EXCITED':
      roundedEye(ctx, left, 43, width + 4, height + 2, -4, foreground, background);
      roundedEye(ctx, right, 43, width + 4, height + 2, 4, foreground, background);
      break;
    default:
      roundedEye(ctx, left, 43, width, height, 0, foreground, background);
      roundedEye(ctx, right, 43, width, height, 0, foreground, background);
  }
}

function drawSpeechMouth(ctx, level, foreground, background) {
  if (level <= 0) return;
  const widths = [0, 8, 12, 16, 20];
  const heights = [0, 2, 4, 8, 12];
  const width = widths[level];
  const height = heights[level];
  block(ctx, 80 - width / 2, 72, width, height, foreground);
  if (height >= 8) block(ctx, 78, 74, 4, 2, background);
}

export function renderFace(canvas, {
  emotion = 'NEUTRAL',
  size = 112,
  mouthLevel = 0,
  dark = true,
  blink = false,
  layout = 'full',
} = {}) {
  const ctx = canvas.getContext('2d', { alpha: false });
  if (!ctx) return;
  ctx.imageSmoothingEnabled = false;
  const background = dark ? DARK : LIGHT;
  const foreground = dark ? GLOW : INK;
  block(ctx, 0, 0, FACE_WIDTH, FACE_HEIGHT, background);

  // The second layout is illustrative: it reserves a top strip within the
  // same 8:5 screen. The proposed full layout extends the eyes up to ~20 px.
  const reserved = layout === 'reserved';
  const span = reserved ? clamp(even(size) - 12, 88, 116) : clamp(even(size), 100, 128);
  ctx.save();
  if (reserved) ctx.translate(0, 14);
  drawEyes(ctx, emotion, span, foreground, background, blink && emotion !== 'SLEEPING');
  if (emotion !== 'SLEEPING') drawSpeechMouth(ctx, clamp(Math.round(mouthLevel), 0, 4), foreground, background);
  ctx.restore();
}
