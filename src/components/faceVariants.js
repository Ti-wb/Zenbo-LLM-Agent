export const FACE_VARIANTS = Object.freeze({
  CLASSIC: 'classic',
  EYES: 'eyes',
});

export const FACE_VARIANT_STORAGE_KEY = 'zenbo.faceVariant.v1';

export function normalizeFaceVariant(value) {
  return value === FACE_VARIANTS.CLASSIC ? FACE_VARIANTS.CLASSIC : FACE_VARIANTS.EYES;
}

function browserStorage() {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readFaceVariant(storage = browserStorage()) {
  try {
    return normalizeFaceVariant(storage?.getItem(FACE_VARIANT_STORAGE_KEY));
  } catch {
    return FACE_VARIANTS.EYES;
  }
}

export function saveFaceVariant(value, storage = browserStorage()) {
  const variant = normalizeFaceVariant(value);
  try {
    storage?.setItem(FACE_VARIANT_STORAGE_KEY, variant);
  } catch {
    // A disabled storage API does not prevent switching during this session.
  }
  return variant;
}
