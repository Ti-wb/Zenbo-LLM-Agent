import { randomBytes } from 'node:crypto';

const MAX_FRAME_BYTES = 16 * 1024 * 1024;

export function encodeWebSocketFrame(payload, { opcode = 0x1, masked = false } = {}) {
  const body = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload));
  if (body.length > MAX_FRAME_BYTES) throw new RangeError('WebSocket frame is too large');

  let lengthBytes;
  if (body.length < 126) {
    lengthBytes = Buffer.from([body.length]);
  } else if (body.length <= 0xffff) {
    lengthBytes = Buffer.alloc(3);
    lengthBytes[0] = 126;
    lengthBytes.writeUInt16BE(body.length, 1);
  } else {
    lengthBytes = Buffer.alloc(9);
    lengthBytes[0] = 127;
    lengthBytes.writeBigUInt64BE(BigInt(body.length), 1);
  }

  const first = Buffer.from([0x80 | opcode]);
  if (!masked) return Buffer.concat([first, lengthBytes, body]);

  lengthBytes[0] |= 0x80;
  const mask = randomBytes(4);
  const encoded = Buffer.alloc(body.length);
  for (let index = 0; index < body.length; index += 1) {
    encoded[index] = body[index] ^ mask[index % 4];
  }
  return Buffer.concat([first, lengthBytes, mask, encoded]);
}
export class WebSocketFrameDecoder {
  constructor({ requireMasked = null, onFrame, onError } = {}) {
    this.buffer = Buffer.alloc(0);
    this.requireMasked = requireMasked;
    this.onFrame = onFrame ?? (() => {});
    this.onError = onError ?? (() => {});
    this.failed = false;
  }

  push(chunk) {
    if (this.failed || !chunk?.length) return;
    this.buffer = Buffer.concat([this.buffer, chunk]);
    try {
      this.#decodeAvailable();
    } catch (error) {
      this.failed = true;
      this.onError(error);
    }
  }

  #decodeAvailable() {
    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const final = Boolean(first & 0x80);
      const reserved = first & 0x70;
      const opcode = first & 0x0f;
      const masked = Boolean(second & 0x80);

      if (!final || reserved) throw new Error('Fragmented or reserved WebSocket frame');
      if (this.requireMasked !== null && masked !== this.requireMasked) {
        throw new Error(masked ? 'Unexpected masked frame' : 'Client frame must be masked');
      }

      let offset = 2;
      let length = second & 0x7f;
      if (length === 126) {
        if (this.buffer.length < offset + 2) return;
        length = this.buffer.readUInt16BE(offset);
        offset += 2;
      } else if (length === 127) {
        if (this.buffer.length < offset + 8) return;
        const bigLength = this.buffer.readBigUInt64BE(offset);
        if (bigLength > BigInt(MAX_FRAME_BYTES)) throw new Error('WebSocket frame is too large');
        length = Number(bigLength);
        offset += 8;
      }

      let mask;
      if (masked) {
        if (this.buffer.length < offset + 4) return;
        mask = this.buffer.subarray(offset, offset + 4);
        offset += 4;
      }
      if (length > MAX_FRAME_BYTES) throw new Error('WebSocket frame is too large');
      if (this.buffer.length < offset + length) return;

      const payload = Buffer.from(this.buffer.subarray(offset, offset + length));
      this.buffer = this.buffer.subarray(offset + length);
      if (mask) {
        for (let index = 0; index < payload.length; index += 1) {
          payload[index] ^= mask[index % 4];
        }
      }
      this.onFrame({ opcode, payload });
    }
  }
}
