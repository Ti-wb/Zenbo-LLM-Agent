import qrcode from 'qrcode-generator';

// Match the Native LAN listener's plain RFC1918 IPv4 home-page addresses.
// Reject rather than strip credentials, queries, fragments or unexpected routes.
export function isLanControlUrl(value) {
  if (typeof value !== 'string') return false;
  const match = /^http:\/\/((?:[0-9]{1,3}\.){3}[0-9]{1,3}):8788\/$/.exec(value);
  if (!match || match[0] !== value) return false;
  const parts = match[1].split('.');
  const bytes = parts.map(Number);
  if (parts.some((part, index) => String(bytes[index]) !== part || bytes[index] > 255)) return false;
  return bytes[0] === 10 || (bytes[0] === 172 && bytes[1] >= 16 && bytes[1] <= 31)
    || (bytes[0] === 192 && bytes[1] === 168);
}

export function createLanPairingUrl(url, pairingCode) {
  if (!isLanControlUrl(url) || typeof pairingCode !== 'string' || !/^[0-9]{8}$/.test(pairingCode)) return null;
  // The fragment is consumed by the remote page; it never enters an HTTP URL.
  return `${url}#pair=${pairingCode}`;
}

export function createLanControlQr(url, pairingCode) {
  const payload = createLanPairingUrl(url, pairingCode);
  if (!payload) return null;
  const code = qrcode(0, 'M');
  code.addData(payload, 'Byte');
  code.make();
  const count = code.getModuleCount();
  const quietZone = 4;
  const path = [];
  for (let row = 0; row < count; row++) {
    for (let column = 0; column < count; column++) {
      if (code.isDark(row, column)) path.push(`M${column + quietZone},${row + quietZone}h1v1h-1z`);
    }
  }
  return { size: count + quietZone * 2, path: path.join('') };
}
