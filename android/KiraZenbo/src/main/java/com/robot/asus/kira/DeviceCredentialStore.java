package com.robot.asus.kira;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores one device-scoped bearer credential encrypted by Android Keystore. */
public final class DeviceCredentialStore {
    private static final String TAG = "DeviceCredentialStore";
    private static final String KEY_ALIAS = "zenbo.agent.gateway.device.v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String FILE_NAME = "gateway-credential-v1.json";

    private final File credentialFile;
    private final AtomicFile atomicCredentialFile;

    public DeviceCredentialStore(Context context) {
        credentialFile = new File(context.getNoBackupFilesDir(), FILE_NAME);
        atomicCredentialFile = new AtomicFile(credentialFile);
    }

    public synchronized boolean hasCredential() {
        return credentialFile.isFile() && load() != null;
    }

    public synchronized void save(String token) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalStateException("Android Keystore AES-GCM requires API 23");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Device token must not be empty");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));

        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        json.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));

        FileOutputStream output = null;
        try {
            output = atomicCredentialFile.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            atomicCredentialFile.finishWrite(output);
        } catch (Exception error) {
            if (output != null) atomicCredentialFile.failWrite(output);
            throw error;
        }
    }

    public synchronized String load() {
        if (!credentialFile.isFile()) return null;
        try {
            JSONObject json = new JSONObject(readFile(credentialFile));
            byte[] iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            Log.e(TAG, "Encrypted gateway credential is unavailable; re-pairing is required", error);
            return null;
        }
    }

    public synchronized void clear() {
        if (credentialFile.exists() && !credentialFile.delete()) {
            Log.w(TAG, "Could not delete gateway credential file");
        }
    }

    private SecretKey getExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) {
            throw new IllegalStateException("Gateway key is missing");
        }
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    private SecretKey getOrCreateKey() throws Exception {
        try {
            return getExistingKey();
        } catch (IllegalStateException missing) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build();
            generator.init(specification);
            return generator.generateKey();
        }
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
}
