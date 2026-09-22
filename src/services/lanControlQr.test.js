import { describe, expect, it } from 'vitest';
import { createLanControlQr, createLanPairingUrl, isLanControlUrl } from './lanControlQr';

describe('LAN QR payload boundary', () => {
  it('encodes Native home-page addresses across all private IPv4 ranges', () => {
    for (const address of ['192.168.1.23', '10.1.2.3', '172.16.0.1', '172.31.255.254']) {
      const url = `http://${address}:8788/`;
      expect(isLanControlUrl(url), url).toBe(true);
      expect(createLanPairingUrl(url, '12345678')).toBe(`${url}remote-control.html#pair=12345678`);
      expect(createLanControlQr(url, '12345678'), url).toMatchObject({ path: expect.stringContaining('M') });
    }
    for (const code of [undefined, '123456', '123456789', '12345678\n', '12345678&pin=123456']) {
      expect(createLanControlQr('http://192.168.1.23:8788/', code)).toBeNull();
    }
  });

  it.each([
    ['credentials and pairing data', ['http://token@192.168.1.23:8788/',
      'http://192.168.1.23:8788/?pin=123456', 'http://192.168.1.23:8788/#pairingCode=12345678']],
    ['alternate routes or protocol', ['http://192.168.1.23:8788/remote/pair',
      'https://192.168.1.23:8788/', 'http://192.168.1.23:8787/']],
    ['malformed addresses', ['http://192.168.1.23:8788/\n',
      'http://192.168.01.23:8788/', 'http://192.168.1.256:8788/', undefined]],
    ['non-LAN endpoints', ['http://127.0.0.1:8788/', 'http://172.32.0.1:8788/', 'http://example.com:8788/']],
  ])('rejects %s without generating a QR', (_category, urls) => {
    for (const url of urls) {
      expect(isLanControlUrl(url), String(url)).toBe(false);
      expect(createLanControlQr(url), String(url)).toBeNull();
    }
  });
});
