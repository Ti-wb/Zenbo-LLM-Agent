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

    public synchronized void setup(String pin) throws Exception {
        if (isConfigured()) throw new IllegalStateException("Admin PIN is already configured");
        validatePin(pin);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = derive(pin, salt, ITERATIONS);
        if (!preferences.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(KEY_ITERATIONS, ITERATIONS)
                .commit()) {
            throw new IllegalStateException("Could not store PIN verifier");
        }
    }

    public synchronized boolean verify(String pin) {
        if (!isConfigured() || pin == null) return false;
        try {
            byte[] salt = Base64.decode(preferences.getString(KEY_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(preferences.getString(KEY_HASH, ""), Base64.NO_WRAP);
            byte[] actual = derive(pin, salt, preferences.getInt(KEY_ITERATIONS, ITERATIONS));
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
