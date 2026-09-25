import { EXPRESSIONS, renderFace } from './face.js';

const heroFace = document.querySelector('#hero-face');
const nativeFace = document.querySelector('#native-face');
const expressionCards = [...document.querySelectorAll('.expression-card')];
const sleepControl = document.querySelector('#sleep-control');
const faceSize = document.querySelector('#face-size');
const faceSizeValue = document.querySelector('#face-size-value');
const mouthLevel = document.querySelector('#mouth-level');
const mouthLevelValue = document.querySelector('#mouth-level-value');
const themeToggle = document.querySelector('#theme-toggle');
const motionToggle = document.querySelector('#motion-toggle');
const speechToggle = document.querySelector('#speech-toggle');
const layoutButtons = [...document.querySelectorAll('[data-layout]')];
const currentName = document.querySelector('#current-name');
const currentCode = document.querySelector('#current-code');
const selectionStatus = document.querySelector('#selection-status');
const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');

const state = {
  emotion: 'NEUTRAL',
  sleeping: false,
  size: 112,
  mouthLevel: 0,
  dark: true,
  layout: 'full',
  motion: !motionPreference.matches,
  motionOverridden: false,
  speechSimulation: false,
  speechStartedAt: 0,
};

function currentExpression() {
  return state.sleeping
    ? { code: 'SLEEPING', name: '睡眠' }
    : EXPRESSIONS.find(({ code }) => code === state.emotion);
}

function paintStaticCards() {
  for (const card of expressionCards) {
    renderFace(card.querySelector('canvas'), {
      emotion: card.dataset.emotion,
      size: state.size,
      dark: state.dark,
    });
  }
}

function updateSelection() {
  const current = currentExpression();
  currentName.textContent = current.name;
  currentCode.textContent = current.code;
  heroFace.setAttribute('aria-label', `Zenbo 像素臉：${current.name}`);
  nativeFace.setAttribute('aria-label', `原生尺寸 Zenbo 像素臉：${current.name}`);
  selectionStatus.textContent = `目前表情：${current.name}${state.sleeping ? '，睡眠狀態' : ''}`;

  for (const card of expressionCards) {
    const selected = !state.sleeping && card.dataset.emotion === state.emotion;
    card.classList.toggle('is-selected', selected);
    card.setAttribute('aria-pressed', String(selected));
  }
  sleepControl.classList.toggle('is-selected', state.sleeping);
  sleepControl.setAttribute('aria-pressed', String(state.sleeping));
  mouthLevel.disabled = state.sleeping || state.speechSimulation;
  speechToggle.disabled = state.sleeping || !state.motion;
}

function updateButtons() {
  themeToggle.setAttribute('aria-pressed', String(!state.dark));
  themeToggle.textContent = '淺色比較';
  motionToggle.setAttribute('aria-pressed', String(state.motion));
  motionToggle.textContent = `眨眼：${state.motion ? '開' : '關'}`;
  speechToggle.setAttribute('aria-pressed', String(state.speechSimulation));
  speechToggle.textContent = '模擬說話';
  document.documentElement.classList.toggle('dark-stage', state.dark);
  updateSelection();
}

function stopSpeechSimulation() {
  state.speechSimulation = false;
  state.speechStartedAt = 0;
  updateButtons();
}

for (const card of expressionCards) {
  card.addEventListener('click', () => {
    state.emotion = card.dataset.emotion;
    state.sleeping = false;
    updateSelection();
  });
}

for (const button of layoutButtons) {
  button.addEventListener('click', () => {
    state.layout = button.dataset.layout;
    for (const option of layoutButtons) {
      option.setAttribute('aria-pressed', String(option.dataset.layout === state.layout));
    }
  });
}

sleepControl.addEventListener('click', () => {
  state.sleeping = !state.sleeping;
  if (state.sleeping) stopSpeechSimulation();
  updateSelection();
});

faceSize.addEventListener('input', () => {
  state.size = Number(faceSize.value);
  faceSizeValue.value = `${state.size} px`;
  paintStaticCards();
});

mouthLevel.addEventListener('input', () => {
  state.mouthLevel = Number(mouthLevel.value);
  mouthLevelValue.value = `${state.mouthLevel} / 4`;
});

themeToggle.addEventListener('click', () => {
  state.dark = !state.dark;
  paintStaticCards();
  updateButtons();
});

motionToggle.addEventListener('click', () => {
  state.motionOverridden = true;
  state.motion = !state.motion;
  if (!state.motion) stopSpeechSimulation();
  updateButtons();
});

speechToggle.addEventListener('click', () => {
  if (state.sleeping || !state.motion) return;
  state.speechSimulation = !state.speechSimulation;
  state.speechStartedAt = state.speechSimulation ? performance.now() : 0;
  updateButtons();
});

motionPreference.addEventListener?.('change', (event) => {
  if (state.motionOverridden) return;
  state.motion = !event.matches;
  if (!state.motion) stopSpeechSimulation();
  updateButtons();
});

let lastPaintAt = 0;
let lastFrameKey = '';
function frame(now) {
  requestAnimationFrame(frame);
  if (now - lastPaintAt < 1000 / 20) return;
  lastPaintAt = now;

  const blinkPhase = now % 5300;
  const blink = state.motion && blinkPhase > 0 && blinkPhase < 135;
  const levels = [0, 1, 3, 2, 4, 2, 1, 0, 3, 1, 0];
  const simulatedLevel = levels[Math.floor((now - state.speechStartedAt) / 145) % levels.length];
  const level = state.sleeping ? 0 : state.speechSimulation ? simulatedLevel : state.mouthLevel;
  mouthLevelValue.value = `${level} / 4`;

  const frameKey = [state.sleeping, state.emotion, state.size, level, state.dark, blink, state.layout].join(':');
  if (frameKey === lastFrameKey) return;
  lastFrameKey = frameKey;

  const options = {
    emotion: state.sleeping ? 'SLEEPING' : state.emotion,
    size: state.size,
    mouthLevel: level,
    dark: state.dark,
    blink,
    layout: state.layout,
  };
  renderFace(heroFace, options);
  renderFace(nativeFace, options);
}

paintStaticCards();
updateButtons();
requestAnimationFrame(frame);
