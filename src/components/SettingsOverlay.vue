<script setup>
import { reactive, ref, watch } from 'vue';

const props = defineProps({
  open: { type: Boolean, default: false },
  settings: { type: Object, required: true },
  connectionState: { type: String, default: 'offline' },
  error: { type: String, default: '' },
  saving: { type: Boolean, default: false },
  testing: { type: Boolean, default: false },
  testResult: { type: Object, default: null },
});

const emit = defineEmits(['close', 'save', 'test']);
const draft = reactive({});
const validationError = ref('');

watch(
  () => [props.open, props.settings],
  () => {
    for (const key of Object.keys(draft)) delete draft[key];
    Object.assign(draft, props.settings, {
      deviceToken: '',
      pin: '',
      confirmPin: '',
      unlockPin: '',
      confirmedFingerprint: '',
    });
    validationError.value = '';
  },
  { immediate: true, deep: true },
);

watch(
  () => props.testResult,
  (result) => {
    if (result?.fingerprint) {
      draft.certificatePin = result.fingerprint;
      draft.confirmedFingerprint = '';
    }
  },
);

function publicDraft() {
  return {
    gatewayUrl: String(draft.gatewayUrl || '').trim(),
    deviceToken: String(draft.deviceToken || '').trim(),
    trustMode: draft.trustMode || 'SYSTEM_TRUST',
    certificatePin: String(draft.certificatePin || '').trim(),
    agentProfile: String(draft.agentProfile || 'default').trim(),
    robotName: String(draft.robotName || 'Zenbo K').trim(),
    language: draft.language || 'zh-TW',
    onboardingComplete: true,
  };
}

function clearSensitiveDraft() {
  draft.deviceToken = '';
  draft.pin = '';
  draft.confirmPin = '';
  draft.unlockPin = '';
}

function save() {
  validationError.value = '';
  if (!props.settings.onboardingComplete && draft.pin !== draft.confirmPin) {
    validationError.value = 'Setup PIN 與確認 PIN 不一致。';
    return;
  }
  if (
    draft.trustMode === 'CONFIRMED_SPKI_PIN' &&
    (!draft.certificatePin || draft.confirmedFingerprint !== draft.certificatePin)
  ) {
    validationError.value = '請先測試連線，並明確確認畫面顯示的 SPKI fingerprint。';
    return;
  }

  const payload = {
    ...publicDraft(),
    pin: String(draft.pin || ''),
    confirmPin: String(draft.confirmPin || ''),
    unlockPin: String(draft.unlockPin || ''),
    confirmedFingerprint: String(draft.confirmedFingerprint || ''),
  };
  emit('save', payload);
  clearSensitiveDraft();
}

function testGateway() {
  validationError.value = '';
  if (!draft.gatewayUrl) {
    validationError.value = '請先輸入 Gateway URL。';
    return;
  }
  if (props.settings.onboardingComplete && !draft.unlockPin) {
    validationError.value = '測試前必須輸入 unlock PIN。';
    return;
  }
  emit('test', {
    ...publicDraft(),
    unlockPin: String(draft.unlockPin),
  });
  draft.unlockPin = '';
  draft.deviceToken = '';
}
</script>

<template>
  <div v-if="open" class="overlay" role="dialog" aria-modal="true" aria-labelledby="settings-title">
    <form class="settings-card" @submit.prevent="save">
      <div class="heading-row">
        <div>
          <p class="eyebrow">ZENBO AGENT</p>
          <h1 id="settings-title">{{ settings.onboardingComplete ? '設定' : '開始使用' }}</h1>
        </div>
        <button
          v-if="settings.onboardingComplete"
          class="close-button"
          type="button"
          aria-label="關閉設定"
          @click="emit('close')"
        >
          ×
        </button>
      </div>

      <p class="intro">
        語音與表情留在 Zenbo；辨識、LLM 與語音合成由 Agent Gateway 處理。API 金鑰不會存放在瀏覽器。
      </p>

      <label>
        <span>Gateway URL</span>
        <input
          v-model="draft.gatewayUrl"
          type="url"
          inputmode="url"
          autocomplete="off"
          placeholder="https://agent.example.com"
          pattern="https://.*"
          required
        />
        <small>由本機 Runtime 連線；Web Renderer 不直接連 Gateway。</small>
      </label>

      <label>
        <span>Device token</span>
        <input
          v-model="draft.deviceToken"
          type="password"
          autocomplete="new-password"
          minlength="16"
          maxlength="4096"
          :required="!settings.onboardingComplete"
          :placeholder="settings.onboardingComplete ? '留空以保留既有 token' : '貼上裝置專用 token'"
        />
        <small>只在這次儲存時送往本機 Runtime，不會寫入 Pinia、localStorage 或讀回畫面。</small>
      </label>

      <div v-if="!settings.onboardingComplete" class="field-grid">
        <label>
          <span>設定管理 PIN</span>
          <input
            v-model="draft.pin"
            type="password"
            inputmode="numeric"
            autocomplete="new-password"
            minlength="6"
            maxlength="12"
            pattern="[0-9]{6,12}"
            required
          />
          <small>只保存加鹽 verifier，不保存 PIN 本身。</small>
        </label>
        <label>
          <span>確認管理 PIN</span>
          <input
            v-model="draft.confirmPin"
            type="password"
            inputmode="numeric"
            autocomplete="new-password"
            minlength="6"
            maxlength="12"
            pattern="[0-9]{6,12}"
            required
          />
        </label>
      </div>

      <label v-else>
        <span>Unlock PIN</span>
        <input
          v-model="draft.unlockPin"
          type="password"
          inputmode="numeric"
          autocomplete="current-password"
          minlength="6"
          maxlength="12"
          pattern="[0-9]{6,12}"
          required
        />
        <small>每次更新或測試前都要解鎖；PIN 不會離開本機 Runtime。</small>
      </label>

      <div class="field-grid">
        <label>
          <span>TLS trust</span>
          <select v-model="draft.trustMode">
            <option value="SYSTEM_TRUST">SYSTEM_TRUST</option>
            <option value="CONFIRMED_SPKI_PIN">CONFIRMED_SPKI_PIN</option>
          </select>
        </label>
        <label>
          <span>Agent profile</span>
          <input
            v-model="draft.agentProfile"
            type="text"
            maxlength="64"
            pattern="[A-Za-z0-9._-]+"
            autocomplete="off"
          />
        </label>
      </div>

      <div v-if="draft.trustMode === 'CONFIRMED_SPKI_PIN'" class="pin-panel">
        <label>
          <span>SPKI fingerprint</span>
          <input
            v-model="draft.certificatePin"
            type="text"
            :readonly="settings.onboardingComplete"
            pattern="sha256/[A-Za-z0-9+/]{43}="
            required
            placeholder="先按「解鎖並測試 TLS」取得 sha256/…"
          />
        </label>
        <label class="toggle-row confirm-pin">
          <input
            v-model="draft.confirmedFingerprint"
            type="checkbox"
            :true-value="draft.certificatePin"
            false-value=""
          />
          <span>我已核對並確認這個 Gateway 憑證 fingerprint</span>
        </label>
      </div>

      <div class="field-grid">
        <label>
          <span>Robot name</span>
          <input v-model="draft.robotName" type="text" maxlength="64" autocomplete="off" />
        </label>
        <label>
          <span>語言</span>
          <select v-model="draft.language">
            <option value="zh-TW">繁體中文</option>
            <option value="en-US">English</option>
            <option value="de-DE">Deutsch</option>
            <option value="ja-JP">日本語</option>
          </select>
        </label>
      </div>

      <p v-if="testResult" class="test-result">
        TLS：{{ testResult.subject || '憑證可用' }}<br />
        <code>{{ testResult.fingerprint }}</code>
        <span v-if="testResult.confirmationRequired"><br />儲存前必須明確確認這個 fingerprint。</span>
      </p>
      <p v-if="validationError || error" class="error-message">{{ validationError || error }}</p>
      <div class="footer-row">
        <span class="runtime-state">本機 Runtime：{{ connectionState }}</span>
        <div class="actions">
          <button
            class="secondary-button"
            type="button"
            :disabled="saving || testing"
            @click="testGateway"
          >
            {{ testing ? '測試中…' : settings.onboardingComplete ? '解鎖並測試 Gateway' : '測試 Gateway' }}
          </button>
          <button class="primary-button" type="submit" :disabled="saving || testing">
            {{ saving ? '儲存中…' : settings.onboardingComplete ? '解鎖、儲存並重新連線' : '設定 PIN 並啟用' }}
          </button>
        </div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  z-index: 10;
  inset: 0;
  display: grid;
  overflow: auto;
  place-items: center;
  padding: 28px;
  background: rgb(2 10 16 / 88%);
  backdrop-filter: blur(14px);
}

.settings-card {
  width: min(620px, 100%);
  padding: 28px;
  border: 1px solid rgb(117 235 255 / 22%);
  border-radius: 22px;
  background: linear-gradient(145deg, rgb(13 35 47 / 96%), rgb(7 20 29 / 98%));
  box-shadow: 0 24px 80px rgb(0 0 0 / 45%);
}

.heading-row,
.footer-row,
.toggle-row {
  display: flex;
  align-items: center;
}

.heading-row {
  justify-content: space-between;
}

.eyebrow {
  margin: 0 0 4px;
  color: #76f4ff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.22em;
}

h1 {
  margin: 0;
  font-size: 28px;
}

.intro {
  margin: 14px 0 22px;
  color: rgb(225 246 250 / 70%);
  font-size: 14px;
  line-height: 1.6;
}

label {
  display: grid;
  gap: 7px;
  margin-top: 16px;
  color: rgb(233 251 255 / 82%);
  font-size: 13px;
}

input,
select {
  width: 100%;
  min-height: 44px;
  padding: 0 13px;
  border: 1px solid rgb(126 220 237 / 22%);
  border-radius: 11px;
  outline: none;
  background: rgb(2 13 20 / 72%);
  color: #effdff;
}

input:focus,
select:focus {
  border-color: #76f4ff;
  box-shadow: 0 0 0 3px rgb(118 244 255 / 10%);
}

small {
  display: block;
  color: rgb(201 229 235 / 52%);
  font-size: 11px;
  line-height: 1.4;
}

.field-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.toggle-row {
  grid-template-columns: auto 1fr;
  gap: 12px;
  padding: 13px;
  border-radius: 12px;
  background: rgb(104 218 239 / 7%);
}

.toggle-row input {
  width: 20px;
  min-height: 20px;
  accent-color: #60e8f5;
}

.error-message {
  margin: 16px 0 0;
  color: #ff9bab;
  font-size: 13px;
}

.footer-row {
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-top: 24px;
}

.runtime-state {
  color: rgb(209 238 244 / 56%);
  font-size: 12px;
}

.primary-button,
.secondary-button,
.close-button {
  border: 0;
  cursor: pointer;
}

.primary-button {
  min-height: 44px;
  padding: 0 18px;
  border-radius: 12px;
  background: #76f4ff;
  color: #041319;
  font-weight: 750;
}

.secondary-button {
  min-height: 44px;
  padding: 0 16px;
  border: 1px solid rgb(118 244 255 / 28%);
  border-radius: 12px;
  background: rgb(24 64 77 / 55%);
  color: #dffaff;
}

.primary-button:disabled,
.secondary-button:disabled {
  cursor: wait;
  opacity: 0.55;
}

.close-button {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: rgb(215 247 251 / 78%);
  font-size: 24px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.pin-panel {
  padding: 0 14px 14px;
  border: 1px solid rgb(255 220 112 / 20%);
  border-radius: 13px;
  background: rgb(255 220 112 / 5%);
}

.confirm-pin {
  margin-top: 12px;
}

.test-result {
  padding: 12px;
  border-radius: 10px;
  background: rgb(102 255 201 / 8%);
  color: rgb(214 255 240 / 80%);
  font-size: 12px;
  line-height: 1.6;
}

.test-result code {
  overflow-wrap: anywhere;
  color: #76f4ff;
  user-select: text;
}

@media (max-width: 560px), (max-height: 560px) {
  .overlay {
    align-items: start;
    padding: 12px;
  }

  .settings-card {
    padding: 20px;
  }

  .field-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .footer-row,
  .actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
