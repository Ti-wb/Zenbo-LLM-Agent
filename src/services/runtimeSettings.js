import { DEFAULT_SETTINGS } from '../stores/runtime';

export function publicSettings(value = {}) {
  const source = {
    ...value,
    gatewayUrl: value.gatewayUrl || '',
    robotName: value.robotName || value.context?.robotName || value.deviceName,
    language: value.language || value.context?.language,
  };
  return Object.fromEntries(
    Object.keys(DEFAULT_SETTINGS)
      .filter((key) => source[key] !== undefined)
      .map((key) => [key, source[key]]),
  );
}

export function runtimeSettingsBody(settings, deviceToken = '', confirmedFingerprint = '') {
  return {
    gatewayUrl: settings.gatewayUrl,
    ...(deviceToken ? { deviceToken } : {}),
    trustMode: settings.trustMode,
    agentProfile: settings.agentProfile,
    context: {
      robotName: settings.robotName,
      language: settings.language,
    },
    ...(settings.trustMode === 'CONFIRMED_SPKI_PIN'
      ? {
          certificatePin: settings.certificatePin,
          confirmedFingerprint,
        }
      : {}),
  };
}
