package com.robot.asus.kira;

/** Pure-Java validation for the Local Runtime voice-turn WAV contract. */
final class WavValidator {
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private WavValidator() {
    }

    static void validate(byte[] bytes, int declaredDurationMs) {
        if (bytes == null || bytes.length < 44 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("WAV audio must be between 44 bytes and 2 MiB");
        }
        if (!asciiEquals(bytes, 0, "RIFF") || !asciiEquals(bytes, 8, "WAVE")) {
            throw new IllegalArgumentException("Audio is not a RIFF/WAVE file");
        }
        int format = -1;
        int channels = -1;
        long sampleRate = -1L;
        int bitsPerSample = -1;
        long dataBytes = -1L;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            long chunkSize = littleEndianUnsignedInt(bytes, offset + 4);
            long chunkEnd = (long) offset + 8L + chunkSize;
            if (chunkEnd > bytes.length) throw new IllegalArgumentException("WAV chunk exceeds the uploaded file");
            if (asciiEquals(bytes, offset, "fmt ")) {
                if (chunkSize < 16L) throw new IllegalArgumentException("WAV fmt chunk is incomplete");
                format = littleEndianUnsignedShort(bytes, offset + 8);
                channels = littleEndianUnsignedShort(bytes, offset + 10);
                sampleRate = littleEndianUnsignedInt(bytes, offset + 12);
                bitsPerSample = littleEndianUnsignedShort(bytes, offset + 22);
            } else if (asciiEquals(bytes, offset, "data")) {
                dataBytes = chunkSize;
            }
            offset = (int) (chunkEnd + (chunkSize & 1L));
        }
        if (format != 1 || channels != 1 || sampleRate != 16_000L || bitsPerSample != 16 || dataBytes < 1L) {
            throw new IllegalArgumentException("WAV must be PCM16, 16 kHz, mono audio");
        }
        long computedDurationMs = dataBytes * 1000L / (sampleRate * channels * (bitsPerSample / 8L));
        if (computedDurationMs < 1L || computedDurationMs > 30_000L) {
            throw new IllegalArgumentException("WAV duration must be between 1 and 30000 ms");
        }
        if (declaredDurationMs < 1 || declaredDurationMs > 30_000L
                || Math.abs(computedDurationMs - declaredDurationMs) > 500L) {
            throw new IllegalArgumentException("durationMs does not match the WAV payload");
        }
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[offset + index] & 0xff) != value.charAt(index)) return false;
        }
        return true;
    }

    private static int littleEndianUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long littleEndianUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }
}
