import { describe, expect, it } from 'vitest';
import { createLanControlQr, isLanControlUrl } from './lanControlQr';

describe('LAN QR payload boundary', () => {
  it.each(['http://192.168.1.23:8788/', 'http://10.1.2.3:8788/', 'http://172.16.0.1:8788/', 'http://172.31.255.254:8788/'])(
    'accepts the exact Native home-page address %s', (url) => {
      expect(isLanControlUrl(url)).toBe(true);
      expect(createLanControlQr(url)).toMatchObject({ path: expect.stringContaining('M') });
    },
  );

  it.each([
    'http://192.168.1.23:8788/?pin=123456',
    'http://192.168.1.23:8788/#pairingCode=12345678',
    'http://token@192.168.1.23:8788/',
    'http://192.168.1.23:8788/remote/pair',
    'http://192.168.1.23:8788/\n',
    'http://192.168.01.23:8788/',
    'http://192.168.1.256:8788/',
    'http://127.0.0.1:8788/',
    'http://172.32.0.1:8788/',
    'http://example.com:8788/',
    'https://192.168.1.23:8788/',
    'http://192.168.1.23:8787/',
    undefined,
  ])('rejects credentials, alternate routes and non-LAN endpoints: %s', (url) => {
    expect(isLanControlUrl(url)).toBe(false);
    expect(createLanControlQr(url)).toBeNull();
  });
});
