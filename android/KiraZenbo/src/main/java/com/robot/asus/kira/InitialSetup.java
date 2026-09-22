package com.robot.asus.kira;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Validates and saves the native initial-setup form as one rollback-safe transaction. */
final class InitialSetup {
    private InitialSetup() { }

    interface Credentials {
        String load();
        void save(String key) throws Exception;
        void clear();
    }

    static synchronized void configure(GatewaySettings settings, DeviceCredentialStore credentials,
                                       JSONObject body) throws Exception {
        configure(settings, new Credentials() {
            @Override public String load() { return credentials.load(); }
            @Override public void save(String key) throws Exception { credentials.save(key); }
            @Override public void clear() { credentials.clear(); }
        }, body);
    }

    static synchronized void configure(GatewaySettings settings, Credentials credentials,
                                       JSONObject body) throws Exception {
        if (settings.isOnboardingComplete()) throw new IllegalStateException("Initial setup is already complete");
        JSONObject config = validate(body);
        JSONObject snapshot = settings.snapshotForRollback();
        String previousCredential = credentials.load();
        try {
            settings.update(config);
            credentials.save(body.getString("apiKey").trim());
            settings.completeOnboarding();
        } catch (Exception error) {
            IllegalStateException failure = new IllegalStateException("Initial setup could not be saved");
            try { settings.restore(snapshot); }
            catch (Exception rollbackError) { failure.addSuppressed(rollbackError); }
            try { if (previousCredential == null) credentials.clear(); else credentials.save(previousCredential); }
            catch (Exception rollbackError) { failure.addSuppressed(rollbackError); }
            throw failure;
        }
    }

    static JSONObject validate(JSONObject body) throws Exception {
        onlyKeys(body, "gatewayUrl", "apiKey", "trustMode",
                "certificatePin", "confirmedFingerprint", "context");
        String key = string(body, "apiKey").trim();
        if (key.length() < 16 || key.length() > 4096) throw new IllegalArgumentException("API key length is invalid");
        String url = string(body, "gatewayUrl");
        GatewaySettings.validateGatewayUrl(url);
        String trust = string(body, "trustMode");
        if (!(GatewaySettings.SYSTEM_TRUST.equals(trust) || GatewaySettings.CONFIRMED_SPKI_PIN.equals(trust)))
            throw new IllegalArgumentException("Trust mode is invalid");
        JSONObject context = body.optJSONObject("context");
        if (context == null) throw new IllegalArgumentException("Robot context is required");
        onlyKeys(context, "robotName", "language");
        String name = string(context, "robotName").trim();
        String language = string(context, "language");
        if (name.isEmpty() || name.length() > 64 || !language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))
            throw new IllegalArgumentException("Robot context is invalid");
        if (body.has("certificatePin") != body.has("confirmedFingerprint"))
            throw new IllegalArgumentException("Certificate confirmation is incomplete");
        JSONObject config = new JSONObject().put("gatewayUrl", url).put("trustMode", trust)
                .put("context", context).put("enabled", true);
        if (body.has("certificatePin")) config.put("certificatePin", string(body, "certificatePin"))
                .put("confirmedFingerprint", string(body, "confirmedFingerprint"));
        return config;
    }
    private static String string(JSONObject value, String key) throws JSONException {
        Object found = value.opt(key);
        if (!(found instanceof String)) throw new JSONException("Required setup field is missing or invalid");
        return (String) found;
    }
    private static void onlyKeys(JSONObject value, String... names) throws JSONException {
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new JSONException("Unsupported setup field");
    }
}
