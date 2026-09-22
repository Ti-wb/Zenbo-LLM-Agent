package com.robot.asus.kira;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2 verifier for settings unlock. The PIN itself is never persisted. */
public final class AdminPinStore {
    private static final String PREFS = "admin_pin_verifier";
    private static final String KEY_SALT = "salt";
    private static final String KEY_HASH = "hash";
    private static final String KEY_ITERATIONS = "iterations";
    private static final int ITERATIONS = 150_000;
    private static final int KEY_BITS = 256;

    private final SharedPreferences preferences;
    private final SecureRandom random = new SecureRandom();

    public AdminPinStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean isConfigured() {
        return preferences.contains(KEY_SALT) && preferences.contains(KEY_HASH);
    }

    public void setup(String pin) throws Exception {
        setup(prepareSetup(pin));
    }

    PreparedVerifier prepareSetup(String pin) throws Exception {
        validatePin(pin);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = derive(pin, salt, ITERATIONS);
        return new PreparedVerifier(salt, hash);
    }

    synchronized void setup(PreparedVerifier verifier) throws Exception {
        if (isConfigured()) throw new IllegalStateException("Admin PIN is already configured");
        if (!preferences.edit()
                .putString(KEY_SALT, Base64.encodeToString(verifier.salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(verifier.hash, Base64.NO_WRAP))
                .putInt(KEY_ITERATIONS, ITERATIONS)
                .commit()) {
            throw new IllegalStateException("Could not store PIN verifier");
        }
    }

    public boolean verify(String pin) {
        if (pin == null) return false;
        try {
            byte[] salt;
            byte[] expected;
            int iterations;
            // Status/settings queries must never wait on the expensive derivation.
            synchronized (this) {
                if (!isConfigured()) return false;
                salt = Base64.decode(preferences.getString(KEY_SALT, ""), Base64.NO_WRAP);
                expected = Base64.decode(preferences.getString(KEY_HASH, ""), Base64.NO_WRAP);
                iterations = preferences.getInt(KEY_ITERATIONS, ITERATIONS);
            }
            byte[] actual = derive(pin, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception error) {
            return false;
        }
    }

    public synchronized void clear() {
        preferences.edit().clear().commit();
    }

    public static void validatePin(String pin) {
        if (pin == null || !pin.matches("[0-9]{6,12}")) {
            throw new IllegalArgumentException("Admin PIN must contain 6 to 12 digits");
        }
    }

    static final class PreparedVerifier {
        private final byte[] salt;
        private final byte[] hash;

        private PreparedVerifier(byte[] salt, byte[] hash) {
            this.salt = salt;
            this.hash = hash;
        }
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
        try {
            // PBKDF2-HMAC-SHA1 is available on API 23; the high iteration count,
            // per-install salt, and constant-time comparison protect this local gate.
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
