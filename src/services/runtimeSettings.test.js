import { describe, expect, it } from 'vitest';
import { publicSettings, runtimeSettingsBody } from './runtimeSettings';

describe('runtime settings boundary', () => {
  it('whitelists only non-secret renderer settings and flattens redacted context', () => {
    const settings = publicSettings({
      gatewayUrl: 'https://gateway.example',
      agentProfile: 'default',
      context: { robotName: 'Kira', language: 'zh-TW' },
      autoListen: false,
      deviceToken: 'must-not-survive',
      pin: '123456',
      sessionToken: 'must-not-survive',
    });

    expect(settings).toMatchObject({ robotName: 'Kira', language: 'zh-TW' });
    expect(settings).not.toHaveProperty('deviceToken');
    expect(settings).not.toHaveProperty('pin');
    expect(settings).not.toHaveProperty('sessionToken');
    expect(settings).not.toHaveProperty('autoListen');
  });

  it('builds the exact atomic native settings body without renderer-only fields', () => {
    const body = runtimeSettingsBody(
      {
        gatewayUrl: 'https://gateway.example',
        trustMode: 'CONFIRMED_SPKI_PIN',
        certificatePin: 'sha256/example=',
        agentProfile: 'default',
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
      deviceToken: 'write-only-device-token',
      trustMode: 'CONFIRMED_SPKI_PIN',
      agentProfile: 'default',
      context: { robotName: 'Kira', language: 'zh-TW' },
      certificatePin: 'sha256/example=',
      confirmedFingerprint: 'sha256/example=',
    });
    expect(body).not.toHaveProperty('autoListen');
    expect(body).not.toHaveProperty('onboardingComplete');
  });
});
