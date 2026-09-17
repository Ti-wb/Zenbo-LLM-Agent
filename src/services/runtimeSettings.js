import { DEFAULT_SETTINGS } from '../stores/runtime';
import { NATIVE_TOOL_NAMES, WEB_TOOL_NAMES } from './toolOwnership';

export function settingsReadiness(result) {
  const plugin = result?.plugin;
  const availableTools = new Set(Array.isArray(plugin?.tools) ? plugin.tools : []);
  const missingTools = [...NATIVE_TOOL_NAMES, ...WEB_TOOL_NAMES]
    .filter((name) => !availableTools.has(name));
  return {
    hermesReachable: Boolean(result?.capabilities),
    pluginAvailable: plugin?.available === true,
    toolsReady: plugin?.available === true && missingTools.length === 0,
    missingTools,
    sttConfigured: plugin?.speech?.sttConfigured === true,
    ttsConfigured: plugin?.speech?.ttsConfigured === true,
  };
}

export function publicSettings(value = {}) {
  const source = {
    ...value,
    gatewayUrl: value.gatewayUrl || DEFAULT_SETTINGS.gatewayUrl,
    robotName: value.robotName || value.context?.robotName || value.deviceName,
    language: value.language || value.context?.language,
  };
  return Object.fromEntries(
    Object.keys(DEFAULT_SETTINGS)
      .filter((key) => source[key] !== undefined)
      .map((key) => [key, source[key]]),
  );
}

export function runtimeSettingsBody(settings, apiKey = '', confirmedFingerprint = '') {
  return {
    gatewayUrl: settings.gatewayUrl,
    ...(apiKey ? { apiKey } : {}),
    trustMode: settings.trustMode,
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
