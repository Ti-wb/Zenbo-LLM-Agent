package com.robot.asus.kira;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** App-private, non-secret configuration for the native Hermes client. */
public final class GatewaySettings {
    private static final String PREFS = "hermes_settings_v1";
    private static final String KEY_URL = "gateway_url";
    private static final String KEY_PIN = "certificate_pin";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_REMOTE_SESSION_ID = "remote_session_id";
    private static final String KEY_SESSION_GATEWAY_IDENTITY = "session_gateway_identity";
    private static final String KEY_ACTIVE_RUN_ID = "active_run_id";
    private static final String KEY_TRUST_MODE = "trust_mode";
    private static final String KEY_ROBOT_NAME = "robot_name";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_PENDING_SUBMISSION = "pending_submission";
    private static final String KEY_MOTION_ENABLED = "motion_enabled";

    public static final String SYSTEM_TRUST = "SYSTEM_TRUST";
    public static final String CONFIRMED_SPKI_PIN = "CONFIRMED_SPKI_PIN";

    private final SharedPreferences preferences;

    static final class RemoteSessionState {
        final String sessionId;
        final String activeRunId;
        RemoteSessionState(String sessionId, String activeRunId) {
            this.sessionId = sessionId;
            this.activeRunId = activeRunId;
        }
    }

    public GatewaySettings(Context context) {
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    GatewaySettings(SharedPreferences preferences) {
        this.preferences = preferences;
        if (!preferences.contains(KEY_DEVICE_ID)) {
            preferences.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply();
        }
    }

    public synchronized String getGatewayUrl() {
        return preferences.getString(KEY_URL, HermesEndpoints.DEFAULT_BASE_URL);
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

    public synchronized String getTrustMode() {
        return preferences.getString(KEY_TRUST_MODE, SYSTEM_TRUST);
    }

    public synchronized String getRobotName() {
        return preferences.getString(KEY_ROBOT_NAME, "Zenbo K");
    }

    public synchronized String getLanguage() {
        return preferences.getString(KEY_LANGUAGE, "zh-TW");
    }

    public synchronized boolean isMotionEnabled() {
        return preferences.getBoolean(KEY_MOTION_ENABLED, false);
    }

    synchronized void setMotionEnabled(boolean enabled) {
        if (!preferences.edit().putBoolean(KEY_MOTION_ENABLED, enabled).commit()) {
            throw new IllegalStateException("Could not save motion preference");
        }
    }

    synchronized RemoteSessionState loadRemoteSessionState() {
        String sessionId = preferences.getString(KEY_REMOTE_SESSION_ID, "");
        String activeRunId = preferences.getString(KEY_ACTIVE_RUN_ID, "");
        if (sessionId == null || sessionId.isEmpty()) return null;
        try {
            HermesEndpoints.requireId(sessionId);
            if (activeRunId != null && !activeRunId.isEmpty()) HermesEndpoints.requireId(activeRunId);
            if (!getGatewayIdentity().equals(preferences.getString(KEY_SESSION_GATEWAY_IDENTITY, ""))) {
                clearRemoteSessionState();
                return null;
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Saved Hermes session requires recovery", error);
        }
        return new RemoteSessionState(sessionId, activeRunId == null ? "" : activeRunId);
    }

    synchronized void persistRemoteSessionState(String sessionId) {
        HermesEndpoints.requireId(sessionId);
        if (!preferences.edit().putString(KEY_REMOTE_SESSION_ID, sessionId)
                .putString(KEY_SESSION_GATEWAY_IDENTITY, getGatewayIdentity())
                .remove(KEY_ACTIVE_RUN_ID).commit()) {
            throw new IllegalStateException("Could not persist Hermes session");
        }
    }

    synchronized void clearRemoteSessionState() {
        String sessionId = preferences.getString(KEY_REMOTE_SESSION_ID, null);
        String identity = preferences.getString(KEY_SESSION_GATEWAY_IDENTITY, null);
        String runId = preferences.getString(KEY_ACTIVE_RUN_ID, null);
        String pending = preferences.getString(KEY_PENDING_SUBMISSION, null);
        if (!preferences.edit().remove(KEY_REMOTE_SESSION_ID).remove(KEY_SESSION_GATEWAY_IDENTITY)
                .remove(KEY_ACTIVE_RUN_ID).remove(KEY_PENDING_SUBMISSION).commit()) {
            // SharedPreferences updates memory even if the disk commit fails. Keep the current
            // session usable when a new-conversation request could not be saved.
            preferences.edit().putString(KEY_REMOTE_SESSION_ID, sessionId)
                    .putString(KEY_SESSION_GATEWAY_IDENTITY, identity).putString(KEY_ACTIVE_RUN_ID, runId)
                    .putString(KEY_PENDING_SUBMISSION, pending).commit();
            throw new IllegalStateException("Could not clear Hermes session");
        }
    }

    synchronized void persistPendingSubmission(String turnId, JSONObject body) {
        UUID.fromString(turnId);
        try {
            JSONObject pending = new JSONObject().put("turnId", turnId)
                    .put("body", body).put("createdAt", System.currentTimeMillis());
            if (!preferences.edit().putString(KEY_PENDING_SUBMISSION, pending.toString()).commit()) {
                throw new IllegalStateException("Could not persist Hermes submission");
            }
        } catch (JSONException error) { throw new IllegalArgumentException("Invalid Hermes submission", error); }
    }

    synchronized JSONObject loadPendingSubmission() {
        String stored = preferences.getString(KEY_PENDING_SUBMISSION, "");
        if (stored == null || stored.isEmpty()) return null;
        try { return new JSONObject(stored); }
        catch (JSONException error) { throw new IllegalStateException("Saved Hermes submission is invalid", error); }
    }

    synchronized void clearPendingSubmission() {
        if (!preferences.edit().remove(KEY_PENDING_SUBMISSION).commit()) {
            throw new IllegalStateException("Could not clear Hermes submission");
        }
    }

    synchronized void persistAcceptedRun(String sessionId, String runId) {
        HermesEndpoints.requireId(sessionId);
        HermesEndpoints.requireId(runId);
        if (!preferences.edit().putString(KEY_REMOTE_SESSION_ID, sessionId)
                .putString(KEY_SESSION_GATEWAY_IDENTITY, getGatewayIdentity())
                .putString(KEY_ACTIVE_RUN_ID, runId).remove(KEY_PENDING_SUBMISSION).commit()) {
            throw new IllegalStateException("Could not persist Hermes run");
        }
    }

    synchronized String getGatewayIdentity() {
        return gatewayIdentity(
                getGatewayUrl(),
                getTrustMode(),
                getCertificatePin(),
                getDeviceId(),
                getRobotName(),
                getLanguage()
        );
    }

    static String gatewayIdentity(
            String gatewayUrl,
            String trustMode,
            String certificatePin,
            String deviceId,
            String robotName,
            String language
    ) {
        String material =
                (gatewayUrl == null ? "" : gatewayUrl.trim()) + "\n"
                        + (trustMode == null ? "" : trustMode) + "\n"
                        + (certificatePin == null ? "" : certificatePin) + "\n"
                        + (deviceId == null ? "" : deviceId) + "\n"
                        + (robotName == null ? "" : robotName) + "\n"
                        + (language == null ? "" : language);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public synchronized void update(JSONObject input) throws JSONException {
        SharedPreferences.Editor editor = preferences.edit();
        boolean invalidatesRemoteSession =
                input.has("gatewayUrl")
                        || input.has("certificatePin")
                        || input.has("trustMode")
                        || input.has("tlsTrust")
                        || input.has("context")
                        || input.has("robotName")
                        || input.has("language");
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
        JSONObject context = input.optJSONObject("context");
        if (context != null) {
            String robotName = context.optString("robotName", "").trim();
            String language = context.optString("language", "").trim();
            if (robotName.isEmpty() || ProtocolStrings.length(robotName) > 64) throw new JSONException("context.robotName is invalid");
            if (!language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) throw new JSONException("context.language is invalid");
            editor.putString(KEY_ROBOT_NAME, robotName);
            editor.putString(KEY_LANGUAGE, language);
        }
        if (input.has("robotName")) {
            String value = input.optString("robotName", "").trim();
            if (value.isEmpty() || ProtocolStrings.length(value) > 40) throw new JSONException("robotName is invalid");
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
        if (invalidatesRemoteSession) {
            editor.remove(KEY_REMOTE_SESSION_ID);
            editor.remove(KEY_PENDING_SUBMISSION);
            editor.remove(KEY_SESSION_GATEWAY_IDENTITY);
            editor.remove(KEY_ACTIVE_RUN_ID);
        }
        if (!editor.commit()) throw new JSONException("Could not persist Gateway settings");
    }

    public synchronized JSONObject toJson(boolean hasCredential) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("gatewayUrl", getGatewayUrl().isEmpty() ? JSONObject.NULL : getGatewayUrl());
        json.put("certificatePinConfigured", !getCertificatePin().isEmpty());
        json.put("trustMode", getTrustMode());
        json.put("context", new JSONObject()
                .put("robotName", getRobotName())
                .put("language", getLanguage()));
        json.put("hasApiKey", hasCredential);
        return json;
    }

    synchronized JSONObject snapshotForRollback() throws JSONException {
        return new JSONObject()
                .put("gatewayUrl", getGatewayUrl())
                .put("certificatePin", getCertificatePin())
                .put("trustMode", getTrustMode())
                .put("robotName", getRobotName())
                .put("language", getLanguage())
                .put("enabled", isEnabled())
                .put("pendingSubmission", preferences.getString(KEY_PENDING_SUBMISSION, ""))
                .put("remoteSessionId", preferences.getString(KEY_REMOTE_SESSION_ID, ""))
                .put("sessionGatewayIdentity",
                        preferences.getString(KEY_SESSION_GATEWAY_IDENTITY, ""))
                .put("activeRunId", preferences.getString(KEY_ACTIVE_RUN_ID, ""));
    }

    synchronized void restore(JSONObject snapshot) throws JSONException {
        boolean committed = preferences.edit()
                .putString(KEY_URL, snapshot.optString("gatewayUrl", ""))
                .putString(KEY_PIN, snapshot.optString("certificatePin", ""))
                .putString(KEY_TRUST_MODE, snapshot.optString("trustMode", SYSTEM_TRUST))
                .putString(KEY_ROBOT_NAME, snapshot.optString("robotName", "Zenbo K"))
                .putString(KEY_LANGUAGE, snapshot.optString("language", "zh-TW"))
                .putBoolean(KEY_ENABLED, snapshot.optBoolean("enabled", false))
                .putString(KEY_PENDING_SUBMISSION, snapshot.optString("pendingSubmission", ""))
                .putString(KEY_REMOTE_SESSION_ID, snapshot.optString("remoteSessionId", ""))
                .putString(KEY_SESSION_GATEWAY_IDENTITY, snapshot.optString("sessionGatewayIdentity", ""))
                .putString(KEY_ACTIVE_RUN_ID, snapshot.optString("activeRunId", ""))
                .commit();
        if (!committed) throw new JSONException("Could not roll back Gateway settings");
    }

    public static void validateGatewayUrl(String value) throws JSONException {
        if (value.isEmpty()) throw new JSONException("gatewayUrl is required");
        if (value.length() > 2048) throw new JSONException("gatewayUrl exceeds 2048 characters");
        try {
            new HermesEndpoints(value);
        } catch (IllegalArgumentException error) {
            throw new JSONException(error.getMessage());
        }
    }
}
