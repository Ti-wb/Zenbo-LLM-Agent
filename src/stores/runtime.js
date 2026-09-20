import { defineStore } from 'pinia';

export const GatewayState = Object.freeze({
  UNCONFIGURED: 'UNCONFIGURED',
  CONNECTING: 'CONNECTING',
  READY: 'READY',
  DEGRADED: 'DEGRADED',
  AUTH_ERROR: 'AUTH_ERROR',
  TLS_ERROR: 'TLS_ERROR',
  INCOMPATIBLE: 'INCOMPATIBLE',
  OFFLINE: 'OFFLINE',
});

export const TurnState = Object.freeze({
  IDLE: 'IDLE',
  LISTENING: 'LISTENING',
  UPLOADING: 'UPLOADING',
  TRANSCRIBING: 'TRANSCRIBING',
  THINKING: 'THINKING',
  AWAITING_TOOL: 'AWAITING_TOOL',
  SYNTHESIZING: 'SYNTHESIZING',
  SPEAKING: 'SPEAKING',
  ERROR: 'ERROR',
});

export const Emotion = Object.freeze({
  NEUTRAL: 'NEUTRAL',
  HAPPY: 'HAPPY',
  CURIOUS: 'CURIOUS',
  CONCERNED: 'CONCERNED',
  EXCITED: 'EXCITED',
});

export const RuntimeEventType = Object.freeze({
  SESSION_READY: 'session.ready',
  SESSION_SNAPSHOT: 'session.snapshot',
  TURN_ACCEPTED: 'turn.accepted',
  STT_FINAL: 'stt.final',
  AGENT_THINKING: 'agent.thinking',
  TOOL_CALL: 'tool.call',
  AGENT_TEXT_FINAL: 'agent.text.final',
  TTS_READY: 'tts.ready',
  TURN_COMPLETED: 'turn.completed',
  TURN_ERROR: 'turn.error',
  TURN_CANCELLED: 'turn.cancelled',
  SESSION_EXPIRED: 'session.expired',
  SESSION_CLOSED: 'session.closed',
  LOCAL_GATEWAY_STATE: 'local.gateway.state',
  LOCAL_ROBOT_STATE: 'local.robot.state',
  LOCAL_SCREEN_STATE: 'local.screen.state',
  LOCAL_INTERACTION: 'local.interaction',
});

const runtimeEventTypes = new Set(Object.values(RuntimeEventType));

// Upper-case aliases keep state-machine call sites terse while exporting named enums.
export const GATEWAY_STATES = GatewayState;
export const CONNECTION_STATES = GatewayState;
export const TURN_STATES = TurnState;

export const SUPPORTED_EMOTIONS = Object.freeze(Object.values(Emotion));

export const DEFAULT_SETTINGS = Object.freeze({
  gatewayUrl: 'https://hermes.example.com/hermes-api/p/robot/v1',
  trustMode: 'SYSTEM_TRUST',
  certificatePin: '',
  hasApiKey: false,
  robotName: 'Zenbo K',
  language: 'zh-TW',
  onboardingComplete: false,
});

const turnTransitions = Object.freeze({
  reset: TURN_STATES.IDLE,
  speech_started: TURN_STATES.LISTENING,
  upload_started: TURN_STATES.UPLOADING,
  transcription_started: TURN_STATES.TRANSCRIBING,
  thinking_started: TURN_STATES.THINKING,
  tool_started: TURN_STATES.AWAITING_TOOL,
  synthesis_started: TURN_STATES.SYNTHESIZING,
  playback_started: TURN_STATES.SPEAKING,
  failed: TURN_STATES.ERROR,
});

export function nextTurnState(currentState, event) {
  if (event === 'wake') return TURN_STATES.IDLE;
  return turnTransitions[event] ?? currentState;
}

export const useRuntimeStore = defineStore('runtime', {
  state: () => ({
    connectionState: CONNECTION_STATES.UNCONFIGURED,
    turnState: TURN_STATES.IDLE,
    sessionId: '',
    robotReady: false,
    robotMoving: false,
    motionEnabled: false,
    battery: { percentage: null, charging: null },
    turnBusy: false,
    activeTurnId: '',
    lastSequence: 0,
    transcript: '',
    assistantText: '',
    pendingEmotion: '',
    pendingEmotionDurationMs: 0,
    explicitEmotion: Emotion.NEUTRAL,
    emotionExpiresAt: 0,
    replyEmotionFallback: false,
    mouthLevel: 0,
    sleeping: false,
    micEnabled: false,
    waitingForPreviousTurn: false,
    recoveryNotice: '',
    settingsOpen: false,
    error: '',
    settings: { ...DEFAULT_SETTINGS },
  }),

  getters: {
    batteryLabel(state) {
      return `${state.battery.percentage === null ? '--' : state.battery.percentage}%`;
    },

    effectiveEmotion(state) {
      if (state.connectionState !== CONNECTION_STATES.READY) return Emotion.NEUTRAL;
      if (state.turnState === TURN_STATES.LISTENING) return Emotion.CURIOUS;
      if (
        state.turnState === TURN_STATES.UPLOADING ||
        state.turnState === TURN_STATES.TRANSCRIBING ||
        state.turnState === TURN_STATES.THINKING ||
        state.turnState === TURN_STATES.AWAITING_TOOL ||
        state.turnState === TURN_STATES.SYNTHESIZING
      ) {
        return Emotion.CURIOUS;
      }
      if (state.turnState === TURN_STATES.ERROR) return Emotion.CONCERNED;
      if (state.turnState === TURN_STATES.SPEAKING && state.replyEmotionFallback) {
        return Emotion.CURIOUS;
      }
      return SUPPORTED_EMOTIONS.includes(state.explicitEmotion)
        ? state.explicitEmotion
        : Emotion.NEUTRAL;
    },

    statusLabel(state) {
      if (state.sleeping) return '休眠中';
      if (state.waitingForPreviousTurn) return '等待前一個回合結束';
      if (state.connectionState !== CONNECTION_STATES.READY) {
        const connectionLabels = {
          [CONNECTION_STATES.UNCONFIGURED]: '請先完成設定',
          [CONNECTION_STATES.CONNECTING]: '正在連線',
          [CONNECTION_STATES.DEGRADED]: '正在恢復連線',
          [CONNECTION_STATES.AUTH_ERROR]: '連線驗證失敗',
          [CONNECTION_STATES.TLS_ERROR]: '連線憑證需要處理',
          [CONNECTION_STATES.INCOMPATIBLE]: '服務版本不相容',
          [CONNECTION_STATES.OFFLINE]: '尚未連線',
        };
        return connectionLabels[state.connectionState] ?? '尚未連線';
      }
      if (state.recoveryNotice && [TURN_STATES.IDLE, TURN_STATES.LISTENING].includes(state.turnState)) {
        return state.recoveryNotice;
      }
      const labels = {
        [TURN_STATES.IDLE]: '準備好了',
        [TURN_STATES.LISTENING]: '我在聽',
        [TURN_STATES.UPLOADING]: '傳送語音',
        [TURN_STATES.TRANSCRIBING]: '辨識中',
        [TURN_STATES.THINKING]: '想一想',
        [TURN_STATES.AWAITING_TOOL]: '處理任務',
        [TURN_STATES.SYNTHESIZING]: '準備回答',
        [TURN_STATES.SPEAKING]: '說話中',
        [TURN_STATES.ERROR]: '需要幫忙',
      };
      return labels[state.turnState] ?? '準備好了';
    },
  },

  actions: {
    setBattery(battery) {
      const percentage = battery?.percentage;
      this.battery = {
        percentage: Number.isInteger(percentage) && percentage >= 0 && percentage <= 100
          ? percentage : null,
        charging: typeof battery?.charging === 'boolean' ? battery.charging : null,
      };
    },

    transition(event, payload = {}) {
      if (['speech_started', 'wake', 'failed'].includes(event)) this.recoveryNotice = '';
      if (['reset', 'speech_started', 'failed', 'wake'].includes(event)) {
        this.resetEmotion();
        this.waitingForPreviousTurn = false;
      }
      this.turnState = nextTurnState(this.turnState, event);
      if (payload.turnId !== undefined) this.activeTurnId = payload.turnId || '';
      if (payload.transcript !== undefined) this.transcript = payload.transcript || '';
      if (payload.assistantText !== undefined) {
        this.assistantText = payload.assistantText || '';
      }
      if (payload.error !== undefined) this.error = payload.error || '';
    },

    setConnection(state, error = '') {
      this.connectionState = state;
      this.error = error;
      if (state !== CONNECTION_STATES.READY) this.resetEmotion();
    },

    setSession(session = {}) {
      if (session.sessionId !== undefined) {
        this.sessionId = session.sessionId || '';
      }
    },

    applyEnvelope(envelope) {
      if (envelope?.protocolVersion !== '2.0') {
        this.error = 'Native 事件必須使用 Local Runtime 2.0。';
        return false;
      }
      if (!runtimeEventTypes.has(envelope?.type)) {
        this.error = `不支援的 Native 事件：${envelope?.type || '(missing)'}`;
        return false;
      }
      const sequence = envelope?.sequence;
      if (envelope.type === RuntimeEventType.SESSION_SNAPSHOT) {
        const snapshotSequence = envelope?.data?.lastSequence;
        const valid =
          Number.isInteger(sequence) &&
          sequence >= 0 &&
          Number.isInteger(snapshotSequence) &&
          snapshotSequence === sequence;
        if (!valid) this.error = 'session.snapshot 缺少有效的 authoritative cursor';
        return valid;
      }
      if (!Number.isInteger(sequence) || sequence < 1) {
        this.error = '保留事件必須包含正整數 sequence';
        return false;
      }
      if (sequence && sequence <= this.lastSequence) return false;
      if (sequence && sequence !== this.lastSequence + 1) {
        this.error = `事件序列中斷：預期 ${this.lastSequence + 1}，收到 ${sequence}`;
        return false;
      }
      return true;
    },

    commitEnvelope(envelope) {
      if (envelope?.type === RuntimeEventType.SESSION_SNAPSHOT) return;
      const sequence = envelope?.sequence;
      if (sequence > this.lastSequence) this.lastSequence = sequence;
    },

    applySessionSnapshot(snapshot = {}) {
      this.applyConversationSnapshot(snapshot);
    },

    applyConversationSnapshot(snapshot = {}) {
      this.resetEmotion();
      this.$patch({
        sessionId: snapshot.sessionId || '',
        activeTurnId: snapshot.activeTurnId || '',
        turnState: Object.values(TURN_STATES).includes(snapshot.turnState)
          ? snapshot.turnState
          : TURN_STATES.IDLE,
        lastSequence: Number.isInteger(snapshot.lastSequence) ? snapshot.lastSequence : 0,
        transcript: snapshot.transcript || '',
        assistantText: snapshot.assistantText || '',
        error: '',
      });
    },

    setEmotion(emotion, durationMs = 0) {
      this.explicitEmotion = SUPPORTED_EMOTIONS.includes(emotion) ? emotion : Emotion.NEUTRAL;
      this.emotionExpiresAt = durationMs > 0 ? Date.now() + durationMs : 0;
      this.replyEmotionFallback = false;
    },

    queueEmotion(emotion, durationMs = 0) {
      this.pendingEmotion = SUPPORTED_EMOTIONS.includes(emotion) ? emotion : Emotion.NEUTRAL;
      this.pendingEmotionDurationMs = durationMs;
    },

    activatePendingEmotion(now = Date.now()) {
      const hasPendingEmotion = SUPPORTED_EMOTIONS.includes(this.pendingEmotion);
      const emotion = hasPendingEmotion
        ? this.pendingEmotion
        : Emotion.NEUTRAL;
      const durationMs = this.pendingEmotionDurationMs;
      this.explicitEmotion = emotion;
      this.emotionExpiresAt = durationMs > 0 ? now + durationMs : 0;
      // Playback can look attentive without inferring sentiment or overriding
      // an explicit NEUTRAL. Keep this separate from tool-owned idle emotion.
      this.replyEmotionFallback = !hasPendingEmotion;
      this.pendingEmotion = '';
      this.pendingEmotionDurationMs = 0;
    },

    resetEmotion() {
      this.pendingEmotion = '';
      this.pendingEmotionDurationMs = 0;
      this.explicitEmotion = Emotion.NEUTRAL;
      this.emotionExpiresAt = 0;
      this.replyEmotionFallback = false;
    },

    clearExpiredEmotion(now = Date.now()) {
      if (this.emotionExpiresAt && now >= this.emotionExpiresAt) {
        this.explicitEmotion = Emotion.NEUTRAL;
        this.emotionExpiresAt = 0;
        this.replyEmotionFallback = this.turnState === TURN_STATES.SPEAKING;
      }
    },

    goToSleep() {
      this.sleeping = true;
      this.micEnabled = false;
      this.transition('reset', { turnId: '' });
    },

    wakeUp() {
      this.sleeping = false;
      this.transition('wake');
    },

    patchSettings(settings) {
      const publicSettings = {};
      for (const key of Object.keys(DEFAULT_SETTINGS)) {
        if (settings?.[key] !== undefined) publicSettings[key] = settings[key];
      }
      this.settings = { ...this.settings, ...publicSettings };
    },
  },
});
