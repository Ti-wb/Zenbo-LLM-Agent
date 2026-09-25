import { normalizeEmotion, starEye } from './pixelFaceModel.js';

export const EYE_FACE_WIDTH = 160;
export const EYE_FACE_HEIGHT = 100;
export const EYE_FACE_BACKGROUND = '#061018';
const EYE_COLOR = '#fffdfa';
const EYE_SPAN = 112;

const even = (value) => Math.round(value / 2) * 2;
const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

function block(context, x, y, width, height, color) {
  context.fillStyle = color;
  context.fillRect(even(x), even(y), even(width), even(height));
}

function roundedEye(context, cx, cy, width, height, lean) {
  const rows = height / 2;
  for (let row = 0; row < rows; row += 1) {
    const edge = Math.min(row, rows - row - 1);
    const inset = edge === 0 ? 6 : edge === 1 ? 2 : 0;
    const shift = even(((row / Math.max(1, rows - 1)) - 0.5) * lean);
    block(context, cx - width / 2 + inset + shift, cy - height / 2 + row * 2,
      width - inset * 2, 2, EYE_COLOR);
  }
  block(context, cx - width / 4 - lean / 4, cy - height / 4, 4, 10, EYE_FACE_BACKGROUND);
}

function happyEye(context, cx, cy) {
  block(context, cx - 18, cy + 6, 8, 6, EYE_COLOR);
  block(context, cx - 14, cy, 8, 8, EYE_COLOR);
  block(context, cx - 8, cy - 6, 10, 8, EYE_COLOR);
  block(context, cx, cy - 8, 8, 8, EYE_COLOR);
  block(context, cx + 6, cy - 4, 8, 8, EYE_COLOR);
  block(context, cx + 12, cy + 2, 6, 8, EYE_COLOR);
}

function sleepingEye(context, cx) {
  block(context, cx - 16, 48, 32, 4, EYE_COLOR);
  block(context, cx - 16, 46, 4, 2, EYE_COLOR);
  block(context, cx + 12, 46, 4, 2, EYE_COLOR);
}

function drawEyes(context, emotion, blink) {
  const width = even(EYE_SPAN * 0.25);
  const height = even(EYE_SPAN * 0.38);
  const offset = even((EYE_SPAN - width) / 2);
  const left = EYE_FACE_WIDTH / 2 - offset;
  const right = EYE_FACE_WIDTH / 2 + offset;

  if (emotion === 'SLEEPING') {
    sleepingEye(context, left);
    sleepingEye(context, right);
    return;
  }
  if (blink) {
    block(context, left - width / 2, 44, width, 4, EYE_COLOR);
    block(context, right - width / 2, 44, width, 4, EYE_COLOR);
    return;
  }

  switch (emotion) {
    case 'HAPPY':
      happyEye(context, left, 43);
      happyEye(context, right, 43);
      break;
    case 'CURIOUS':
      roundedEye(context, left - 2, 40, width + 2, height + 4, -2);
      roundedEye(context, right + 2, 47, width - 8, height - 10, 2);
      break;
    case 'CONCERNED':
      roundedEye(context, left, 45, width - 2, height - 4, -12);
      roundedEye(context, right, 45, width - 2, height - 4, 12);
      break;
    case 'EXCITED':
      // Reuse the original PixelFace star shape, moved to the new eye centers.
      for (const center of [left, right]) {
        for (const rectangle of starEye(center, 42)) {
          block(context, rectangle.x, rectangle.y, rectangle.width, rectangle.height, EYE_COLOR);
        }
      }
      break;
    default:
      roundedEye(context, left, 43, width, height, 0);
      roundedEye(context, right, 43, width, height, 0);
  }
}

function drawSpeechMouth(context, band) {
  if (band <= 0) return;
  const width = [0, 8, 12, 16, 20][band];
  const height = [0, 2, 4, 8, 12][band];
  block(context, EYE_FACE_WIDTH / 2 - width / 2, 72, width, height, EYE_COLOR);
  if (height >= 8) block(context, 78, 74, 4, 2, EYE_FACE_BACKGROUND);
}

export function renderEyeFace(context, { emotion = 'NEUTRAL', mouthBand = 0, blink = false } = {}) {
  if (!context) return;
  context.imageSmoothingEnabled = false;
  context.fillStyle = EYE_FACE_BACKGROUND;
  context.fillRect(0, 0, EYE_FACE_WIDTH, EYE_FACE_HEIGHT);

  const resolvedEmotion = emotion === 'SLEEPING'
    ? 'SLEEPING'
    : normalizeEmotion(emotion).toUpperCase();
  drawEyes(context, resolvedEmotion, blink && resolvedEmotion !== 'SLEEPING');
  if (resolvedEmotion !== 'SLEEPING') {
    drawSpeechMouth(context, clamp(Math.round(mouthBand), 0, 4));
  }
}
