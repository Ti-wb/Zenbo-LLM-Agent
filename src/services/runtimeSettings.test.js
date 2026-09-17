import { describe, expect, it } from 'vitest';
import { publicSettings, runtimeSettingsBody, settingsReadiness } from './runtimeSettings';
import { NATIVE_TOOL_NAMES, WEB_TOOL_NAMES } from './toolOwnership';

describe('runtime settings boundary', () => {
  it('distinguishes a reachable Profile from ready tools and configured speech', () => {
    expect(settingsReadiness({ capabilities: { streaming: true } })).toMatchObject({
      hermesReachable: true, pluginAvailable: false, toolsReady: false,
      sttConfigured: false, ttsConfigured: false,
    });
    expect(settingsReadiness({
      capabilities: {},
      plugin: { available: true, tools: [...NATIVE_TOOL_NAMES, ...WEB_TOOL_NAMES], speech: { sttConfigured: true, ttsConfigured: true } },
    })).toEqual({
      hermesReachable: true, pluginAvailable: true, toolsReady: true,
      sttConfigured: true, ttsConfigured: true, missingTools: [],
    });
    expect(settingsReadiness({ plugin: { available: true, tools: NATIVE_TOOL_NAMES } }).missingTools).toEqual(WEB_TOOL_NAMES);
  });

  it('whitelists only non-secret renderer settings and flattens redacted context', () => {
    const settings = publicSettings({
      gatewayUrl: 'https://gateway.example',
      context: { robotName: 'Kira', language: 'zh-TW' },
      autoListen: false,
      model: 'must-remain-profile-managed',
      apiKey: 'must-not-survive',
      hasApiKey: true,
      pin: '123456',
      sessionToken: 'must-not-survive',
    });

    expect(settings).toMatchObject({ robotName: 'Kira', language: 'zh-TW' });
    expect(settings).not.toHaveProperty('apiKey');
    expect(settings.hasApiKey).toBe(true);
    expect(settings).not.toHaveProperty('pin');
    expect(settings).not.toHaveProperty('sessionToken');
    expect(settings).not.toHaveProperty('autoListen');
    expect(settings).not.toHaveProperty('model');
  });

  it('builds the exact atomic native settings body without renderer-only fields', () => {
    const body = runtimeSettingsBody(
      {
        gatewayUrl: 'https://gateway.example',
        trustMode: 'CONFIRMED_SPKI_PIN',
        certificatePin: 'sha256/example=',
        robotName: 'Kira',
        language: 'zh-TW',
        autoListen: true,
        onboardingComplete: true,
      },
      'write-only-device-token',
      'sha256/example=',
    );

    expect(body).toEqual({
      gatewayUrl: 'https://gateway.example',
      apiKey: 'write-only-device-token',
      trustMode: 'CONFIRMED_SPKI_PIN',
      context: { robotName: 'Kira', language: 'zh-TW' },
      certificatePin: 'sha256/example=',
      confirmedFingerprint: 'sha256/example=',
    });
    expect(body).not.toHaveProperty('autoListen');
    expect(body).not.toHaveProperty('onboardingComplete');
  });
});
