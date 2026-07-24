package com.robot.asus.kira;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** App-private, non-secret configuration for the native Agent Gateway client. */
public final class GatewaySettings {
    private static final String PREFS = "agent_gateway_settings";
    private static final String KEY_URL = "gateway_url";
    private static final String KEY_PIN = "certificate_pin";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_CURSOR = "event_cursor";
    private static final String KEY_REMOTE_SESSION_ID = "remote_session_id";
    private static final String KEY_SESSION_GATEWAY_IDENTITY = "session_gateway_identity";
    private static final String KEY_ACTIVE_REMOTE_TURN_ID = "active_remote_turn_id";
    private static final String KEY_REMOTE_TURN_UPLOAD_PENDING = "remote_turn_upload_pending";
    private static final String KEY_TRUST_MODE = "trust_mode";
    private static final String KEY_AGENT_PROFILE = "agent_profile";
    private static final String KEY_ROBOT_NAME = "robot_name";
    private static final String KEY_LANGUAGE = "language";

    public static final String SYSTEM_TRUST = "SYSTEM_TRUST";
    public static final String CONFIRMED_SPKI_PIN = "CONFIRMED_SPKI_PIN";

    private final SharedPreferences preferences;

    static final class RemoteSessionState {
        final String sessionId;
        final long cursor;
        final String activeTurnId;
        final boolean uploadPending;

        RemoteSessionState(
                String sessionId,
                long cursor,
                String activeTurnId,
                boolean uploadPending
        ) {
            this.sessionId = sessionId;
            this.cursor = cursor;
            this.activeTurnId = activeTurnId;
            this.uploadPending = uploadPending;
        }
    }

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

    synchronized RemoteSessionState loadRemoteSessionState() {
        String sessionId = preferences.getString(KEY_REMOTE_SESSION_ID, "");
        String storedIdentity = preferences.getString(KEY_SESSION_GATEWAY_IDENTITY, "");
        String activeTurnId = preferences.getString(KEY_ACTIVE_REMOTE_TURN_ID, "");
        boolean uploadPending = preferences.getBoolean(KEY_REMOTE_TURN_UPLOAD_PENDING, false);
        long cursor = preferences.getLong(KEY_CURSOR, 0L);
        if (sessionId == null || sessionId.isEmpty()) {
            if (cursor != 0L
                    || (storedIdentity != null && !storedIdentity.isEmpty())
                    || (activeTurnId != null && !activeTurnId.isEmpty())
                    || uploadPending) {
                clearRemoteSessionState();
            }
            return null;
        }
        try {
            UUID.fromString(sessionId);
        } catch (IllegalArgumentException error) {
            clearRemoteSessionState();
            return null;
        }
        try {
            if (activeTurnId != null && !activeTurnId.isEmpty()) UUID.fromString(activeTurnId);
        } catch (IllegalArgumentException error) {
            clearRemoteSessionState();
            return null;
        }
        if (cursor < 0L
                || (uploadPending && (activeTurnId == null || activeTurnId.isEmpty()))
                || !getGatewayIdentity().equals(storedIdentity)) {
            clearRemoteSessionState();
            return null;
        }
        return new RemoteSessionState(
                sessionId,
                cursor,
                activeTurnId == null ? "" : activeTurnId,
                uploadPending
        );
    }

    synchronized void persistRemoteSessionState(String sessionId, long cursor) {
        persistRemoteSessionState(sessionId, cursor, "");
    }

    synchronized void persistRemoteSessionState(String sessionId, long cursor, String activeTurnId) {
        persistRemoteSessionState(sessionId, cursor, activeTurnId, false);
    }

    synchronized void persistRemoteSessionState(
            String sessionId,
            long cursor,
            String activeTurnId,
            boolean uploadPending
    ) {
        UUID.fromString(sessionId);
        if (cursor < 0L) throw new IllegalArgumentException("Remote session cursor must be non-negative");
        String normalizedTurnId = activeTurnId == null ? "" : activeTurnId;
        if (!normalizedTurnId.isEmpty()) UUID.fromString(normalizedTurnId);
        if (uploadPending && normalizedTurnId.isEmpty()) {
            throw new IllegalArgumentException("A pending upload requires a client turn marker");
        }
        if (!preferences.edit()
                .putString(KEY_REMOTE_SESSION_ID, sessionId)
                .putLong(KEY_CURSOR, cursor)
                .putString(KEY_SESSION_GATEWAY_IDENTITY, getGatewayIdentity())
                .putString(KEY_ACTIVE_REMOTE_TURN_ID, normalizedTurnId)
                .putBoolean(KEY_REMOTE_TURN_UPLOAD_PENDING, uploadPending)
                .commit()) {
            throw new IllegalStateException("Could not persist remote session state");
        }
    }

    synchronized void commitRemoteEvent(
            String sessionId,
            long cursor,
            String eventType,
            String eventTurnId
    ) {
        RemoteSessionState state = loadRemoteSessionState();
        if (state == null || !state.sessionId.equals(sessionId)) {
            throw new IllegalStateException("Remote session state does not match the active session");
        }
        String activeTurnId = state.activeTurnId;
        boolean uploadPending = state.uploadPending;
        if ("turn.accepted".equals(eventType)) {
            activeTurnId = eventTurnId;
            uploadPending = false;
        }
        if ("turn.completed".equals(eventType)
                || "turn.error".equals(eventType)
                || "turn.cancelled".equals(eventType)) {
            if (shouldClearActiveTurn(activeTurnId, eventTurnId)) {
                activeTurnId = "";
                uploadPending = false;
            }
        }
        if (cursor > state.cursor) {
            persistRemoteSessionState(sessionId, cursor, activeTurnId, uploadPending);
        }
    }

    static boolean shouldClearActiveTurn(String activeTurnId, String terminalTurnId) {
        return activeTurnId != null
                && !activeTurnId.isEmpty()
                && activeTurnId.equals(terminalTurnId);
    }

    synchronized void replaceRemoteSessionSnapshot(
            String sessionId,
            long cursor,
            String activeTurnId
    ) {
        RemoteSessionState state = loadRemoteSessionState();
        if (state == null || !state.sessionId.equals(sessionId)) {
            throw new IllegalStateException("Remote session state does not match the active session");
        }
        String snapshotTurnId = activeTurnId == null ? "" : activeTurnId;
        boolean keepPendingUpload = shouldPreservePendingUploadMarker(
                state.uploadPending,
                snapshotTurnId
        );
        persistRemoteSessionState(
                sessionId,
                cursor,
                keepPendingUpload ? state.activeTurnId : snapshotTurnId,
                keepPendingUpload
        );
    }

    static boolean shouldPreservePendingUploadMarker(
            boolean uploadPending,
            String snapshotActiveTurnId
    ) {
        return uploadPending
                && (snapshotActiveTurnId == null || snapshotActiveTurnId.isEmpty());
    }

    synchronized void markRemoteTurnInFlight(String sessionId, String turnId) {
        RemoteSessionState state = loadRemoteSessionState();
        if (state == null || !state.sessionId.equals(sessionId)) {
            throw new IllegalStateException("Remote session state does not match the active session");
        }
        persistRemoteSessionState(sessionId, state.cursor, turnId, true);
    }

    synchronized boolean replaceRemoteTurnMarker(
            String sessionId,
            String expectedTurnId,
            String remoteTurnId
    ) {
        RemoteSessionState state = loadRemoteSessionState();
        if (state == null || !state.sessionId.equals(sessionId)) return false;
        if ((state.uploadPending && state.activeTurnId.equals(expectedTurnId))
                || (!state.uploadPending && state.activeTurnId.equals(remoteTurnId))) {
            persistRemoteSessionState(sessionId, state.cursor, remoteTurnId, false);
            return true;
        }
        return false;
    }

    synchronized void clearRemoteTurnMarker(String sessionId, String expectedTurnId) {
        RemoteSessionState state = loadRemoteSessionState();
        if (state == null || !state.sessionId.equals(sessionId)) return;
        if (state.uploadPending && state.activeTurnId.equals(expectedTurnId)) {
            persistRemoteSessionState(sessionId, state.cursor, "");
        }
    }

    synchronized void clearRemoteSessionState() {
        if (!preferences.edit()
                .remove(KEY_REMOTE_SESSION_ID)
                .remove(KEY_SESSION_GATEWAY_IDENTITY)
                .remove(KEY_ACTIVE_REMOTE_TURN_ID)
                .remove(KEY_REMOTE_TURN_UPLOAD_PENDING)
                .putLong(KEY_CURSOR, 0L)
                .commit()) {
            throw new IllegalStateException("Could not clear remote session state");
        }
    }

    synchronized String getGatewayIdentity() {
        return gatewayIdentity(
                getGatewayUrl(),
                getTrustMode(),
                getCertificatePin(),
                getDeviceId(),
                getAgentProfile(),
                getRobotName(),
                getLanguage()
        );
    }

    static String gatewayIdentity(
            String gatewayUrl,
            String trustMode,
            String certificatePin,
            String deviceId,
            String agentProfile,
            String robotName,
            String language
    ) {
        String material =
                (gatewayUrl == null ? "" : gatewayUrl.trim()) + "\n"
                        + (trustMode == null ? "" : trustMode) + "\n"
                        + (certificatePin == null ? "" : certificatePin) + "\n"
                        + (deviceId == null ? "" : deviceId) + "\n"
                        + (agentProfile == null ? "" : agentProfile) + "\n"
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
                        || input.has("agentProfile")
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
        if (invalidatesRemoteSession) {
            editor.remove(KEY_REMOTE_SESSION_ID);
            editor.remove(KEY_SESSION_GATEWAY_IDENTITY);
            editor.remove(KEY_ACTIVE_REMOTE_TURN_ID);
            editor.remove(KEY_REMOTE_TURN_UPLOAD_PENDING);
            editor.putLong(KEY_CURSOR, 0L);
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
                .put("cursor", getCursor())
                .put("remoteSessionId", preferences.getString(KEY_REMOTE_SESSION_ID, ""))
                .put("sessionGatewayIdentity",
                        preferences.getString(KEY_SESSION_GATEWAY_IDENTITY, ""))
                .put("activeRemoteTurnId",
                        preferences.getString(KEY_ACTIVE_REMOTE_TURN_ID, ""))
                .put("remoteTurnUploadPending",
                        preferences.getBoolean(KEY_REMOTE_TURN_UPLOAD_PENDING, false));
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
                .putString(KEY_REMOTE_SESSION_ID, snapshot.optString("remoteSessionId", ""))
                .putString(KEY_SESSION_GATEWAY_IDENTITY, snapshot.optString("sessionGatewayIdentity", ""))
                .putString(KEY_ACTIVE_REMOTE_TURN_ID, snapshot.optString("activeRemoteTurnId", ""))
                .putBoolean(KEY_REMOTE_TURN_UPLOAD_PENDING,
                        snapshot.optBoolean("remoteTurnUploadPending", false))
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
