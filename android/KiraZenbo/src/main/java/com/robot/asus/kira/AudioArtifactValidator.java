package com.robot.asus.kira;

import java.security.MessageDigest;
import java.util.Locale;

/** Pure-Java validation for one authenticated Gateway audio artifact. */
final class AudioArtifactValidator {
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private AudioArtifactValidator() {
    }

    static void validateMetadata(String mimeType, int byteLength, long expiresAt, long now) {
        if (!("audio/mpeg".equals(mimeType) || "audio/wav".equals(mimeType))) {
            throw new IllegalArgumentException("Audio MIME type is not allowed");
        }
        if (byteLength < 1 || byteLength > MAX_BYTES) {
            throw new IllegalArgumentException("Audio metadata length is invalid");
        }
        if (expiresAt <= now) throw new IllegalStateException("Audio artifact has expired");
    }

    static void validatePayload(
            byte[] bytes,
            String actualMimeType,
            String expectedMimeType,
            int expectedLength,
            String expectedSha256
    ) {
        if (!expectedMimeType.equals(actualMimeType)) {
            throw new IllegalArgumentException("Audio artifact MIME type does not match metadata");
        }
        if (bytes == null || bytes.length != expectedLength || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Audio artifact length does not match metadata");
        }
        if (!sha256(bytes).equals(expectedSha256)) {
            throw new IllegalArgumentException("Audio artifact digest does not match metadata");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
