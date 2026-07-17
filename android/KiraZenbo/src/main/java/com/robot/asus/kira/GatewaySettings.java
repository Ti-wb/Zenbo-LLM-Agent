package com.robot.asus.kira;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.UUID;

/** App-private, non-secret configuration for the native Agent Gateway client. */
public final class GatewaySettings {
    private static final String PREFS = "agent_gateway_settings";
    private static final String KEY_URL = "gateway_url";
    private static final String KEY_PIN = "certificate_pin";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_CURSOR = "event_cursor";
    private static final String KEY_TRUST_MODE = "trust_mode";
    private static final String KEY_AGENT_PROFILE = "agent_profile";
    private static final String KEY_ROBOT_NAME = "robot_name";
    private static final String KEY_LANGUAGE = "language";

    public static final String SYSTEM_TRUST = "SYSTEM_TRUST";
    public static final String CONFIRMED_SPKI_PIN = "CONFIRMED_SPKI_PIN";

    private final SharedPreferences preferences;

    public GatewaySettings(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains(KEY_DEVICE_ID)) {
            preferences.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply();
        }
    }

    public synchronized String getGatewayUrl() {
        return preferences.getString(KEY_URL, "");
    }

    public synchronized String getCertificatePin() {
        return preferences.getString(KEY_PIN, "");
    }

    public synchronized boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    public synchronized String getDeviceId() {
        return preferences.getString(KEY_DEVICE_ID, "unknown-device");
    }

    public synchronized long getCursor() {
        return preferences.getLong(KEY_CURSOR, 0L);
    }

    public synchronized String getTrustMode() {
        return preferences.getString(KEY_TRUST_MODE, SYSTEM_TRUST);
    }

    public synchronized String getAgentProfile() {
        return preferences.getString(KEY_AGENT_PROFILE, "default");
    }

    public synchronized String getRobotName() {
        return preferences.getString(KEY_ROBOT_NAME, "Zenbo K");
    }

    public synchronized String getLanguage() {
        return preferences.getString(KEY_LANGUAGE, "zh-TW");
    }

    public synchronized void setCursor(long cursor) {
        if (cursor > getCursor()) {
            preferences.edit().putLong(KEY_CURSOR, cursor).commit();
        }
    }

    public synchronized void replaceCursor(long cursor) {
        preferences.edit().putLong(KEY_CURSOR, Math.max(0L, cursor)).commit();
    }

    public synchronized void update(JSONObject input) throws JSONException {
        SharedPreferences.Editor editor = preferences.edit();
        if (input.has("gatewayUrl")) {
            String url = input.optString("gatewayUrl", "").trim();
            validateGatewayUrl(url);
            editor.putString(KEY_URL, url);
        }
        if (input.has("certificatePin")) {
            String pin = input.optString("certificatePin", "").trim();
            if (!pin.isEmpty() && !pin.matches("sha256/[A-Za-z0-9+/]{43}=")) {
                throw new JSONException("certificatePin must be an OkHttp sha256/ SPKI pin");
            }
            editor.putString(KEY_PIN, pin);
        }
        if (input.has("trustMode") || input.has("tlsTrust")) {
            String trustMode = input.optString("trustMode", input.optString("tlsTrust", SYSTEM_TRUST));
            if ("system".equalsIgnoreCase(trustMode)) trustMode = SYSTEM_TRUST;
            if (!(SYSTEM_TRUST.equals(trustMode) || CONFIRMED_SPKI_PIN.equals(trustMode))) {
                throw new JSONException("trustMode must be SYSTEM_TRUST or CONFIRMED_SPKI_PIN");
            }
            String prospectivePin = input.has("certificatePin")
                    ? input.optString("certificatePin", "").trim()
                    : getCertificatePin();
            if (CONFIRMED_SPKI_PIN.equals(trustMode)) {
                if (prospectivePin.isEmpty()) throw new JSONException("certificatePin is required for CONFIRMED_SPKI_PIN");
                if (!prospectivePin.equals(input.optString("confirmedFingerprint", ""))) {
                    throw new JSONException("confirmedFingerprint must exactly match certificatePin");
                }
            }
            editor.putString(KEY_TRUST_MODE, trustMode);
        }
        if (input.has("agentProfile")) {
            String value = input.optString("agentProfile", "").trim();
            if (!value.matches("[A-Za-z0-9._-]{1,64}")) throw new JSONException("agentProfile is invalid");
            editor.putString(KEY_AGENT_PROFILE, value);
        }
        JSONObject context = input.optJSONObject("context");
        if (context != null) {
            String robotName = context.optString("robotName", "").trim();
            String language = context.optString("language", "").trim();
            if (robotName.isEmpty() || robotName.length() > 64) throw new JSONException("context.robotName is invalid");
            if (!language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) throw new JSONException("context.language is invalid");
            editor.putString(KEY_ROBOT_NAME, robotName);
            editor.putString(KEY_LANGUAGE, language);
        }
        if (input.has("robotName")) {
            String value = input.optString("robotName", "").trim();
            if (value.isEmpty() || value.length() > 40) throw new JSONException("robotName is invalid");
            editor.putString(KEY_ROBOT_NAME, value);
        }
        if (input.has("language")) {
            String value = input.optString("language", "").trim();
            if (!value.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) throw new JSONException("language is invalid");
            editor.putString(KEY_LANGUAGE, value);
        }
        if (input.has("enabled")) {
            editor.putBoolean(KEY_ENABLED, input.optBoolean("enabled", false));
        }
        if (!editor.commit()) throw new JSONException("Could not persist Gateway settings");
    }

    public synchronized JSONObject toJson(boolean hasCredential) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("gatewayUrl", getGatewayUrl().isEmpty() ? JSONObject.NULL : getGatewayUrl());
        json.put("certificatePinConfigured", !getCertificatePin().isEmpty());
        json.put("trustMode", getTrustMode());
        json.put("agentProfile", getAgentProfile());
        json.put("context", new JSONObject()
                .put("robotName", getRobotName())
                .put("language", getLanguage()));
        json.put("credentialConfigured", hasCredential);
        return json;
    }

    synchronized JSONObject snapshotForRollback() throws JSONException {
        return new JSONObject()
                .put("gatewayUrl", getGatewayUrl())
                .put("certificatePin", getCertificatePin())
                .put("trustMode", getTrustMode())
                .put("agentProfile", getAgentProfile())
                .put("robotName", getRobotName())
                .put("language", getLanguage())
                .put("enabled", isEnabled())
                .put("cursor", getCursor());
    }

    synchronized void restore(JSONObject snapshot) throws JSONException {
        boolean committed = preferences.edit()
                .putString(KEY_URL, snapshot.optString("gatewayUrl", ""))
                .putString(KEY_PIN, snapshot.optString("certificatePin", ""))
                .putString(KEY_TRUST_MODE, snapshot.optString("trustMode", SYSTEM_TRUST))
                .putString(KEY_AGENT_PROFILE, snapshot.optString("agentProfile", "default"))
                .putString(KEY_ROBOT_NAME, snapshot.optString("robotName", "Zenbo K"))
                .putString(KEY_LANGUAGE, snapshot.optString("language", "zh-TW"))
                .putBoolean(KEY_ENABLED, snapshot.optBoolean("enabled", false))
                .putLong(KEY_CURSOR, snapshot.optLong("cursor", 0L))
                .commit();
        if (!committed) throw new JSONException("Could not roll back Gateway settings");
    }

    public static void validateGatewayUrl(String value) throws JSONException {
        if (value.isEmpty()) throw new JSONException("gatewayUrl is required");
        if (value.length() > 2048) throw new JSONException("gatewayUrl exceeds 2048 characters");
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!value.startsWith("https://") || !"https".equals(scheme)) {
                throw new JSONException("gatewayUrl must use https://");
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new JSONException("gatewayUrl must contain a host");
            }
            if (uri.getUserInfo() != null) {
                throw new JSONException("gatewayUrl must not contain credentials");
            }
        } catch (IllegalArgumentException error) {
            throw new JSONException("gatewayUrl is invalid");
        }
    }
}
