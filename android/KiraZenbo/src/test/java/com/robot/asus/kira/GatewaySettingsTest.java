package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GatewaySettingsTest {
    @Test public void robotNameLimitsCountUnicodeCodePoints() throws Exception {
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        String emoji = "\uD83E\uDD16";
        settings.update(new JSONObject().put("context", new JSONObject()
                .put("robotName", emoji.repeat(64)).put("language", "zh-TW")));
        assertEquals(64, ProtocolStrings.length(settings.getRobotName()));
        assertThrows(org.json.JSONException.class, () -> settings.update(new JSONObject()
                .put("context", new JSONObject().put("robotName", emoji.repeat(65))
                        .put("language", "zh-TW"))));
        settings.update(new JSONObject().put("robotName", emoji.repeat(40)));
        assertEquals(40, ProtocolStrings.length(settings.getRobotName()));
        assertThrows(org.json.JSONException.class,
                () -> settings.update(new JSONObject().put("robotName", emoji.repeat(41))));
    }

    @Test public void defaultsUseTheExplicitProfileWithoutModelOverride() throws Exception {
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        assertEquals(HermesEndpoints.DEFAULT_BASE_URL, settings.getGatewayUrl());
        assertFalse(settings.isEnabled());
        assertFalse(settings.isMotionEnabled());
        JSONObject exposed = settings.toJson(false);
        assertFalse(exposed.has("model"));
        assertFalse(exposed.has("agentProfile"));
        assertFalse(exposed.getBoolean("hasApiKey"));
        assertFalse(exposed.getBoolean("onboardingComplete"));
    }

    @Test public void motionPreferencePersistsIndependentlyOfGatewaySettings() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        GatewaySettings settings = new GatewaySettings(store);
        settings.setMotionEnabled(true);
        assertTrue(new GatewaySettings(store).isMotionEnabled());
        settings.update(new JSONObject().put("gatewayUrl", "https://example.com/p/other/v1"));
        assertTrue(settings.isMotionEnabled());
        settings.setMotionEnabled(false);
        assertFalse(new GatewaySettings(store).isMotionEnabled());
    }

    @Test public void pendingRequestRetainsExactIdempotentBodyAndNeverAppearsInPublicSettings() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        GatewaySettings settings = new GatewaySettings(store);
        settings.persistRemoteSessionState("api_session");
        JSONObject body = HermesClient.runRequest("api_session", "private user text", "zh-TW", "Zenbo K");
        String turn = "11111111-1111-4111-8111-111111111111";
        settings.persistPendingSubmission(turn, body);
        GatewaySettings restarted = new GatewaySettings(store);
        assertEquals(body.toString(), restarted.loadPendingSubmission().getJSONObject("body").toString());
        assertEquals(turn, restarted.loadPendingSubmission().getString("turnId"));
        assertFalse(restarted.toJson(true).toString().contains("private user text"));
        restarted.persistAcceptedRun("api_session", "run_accepted");
        assertNull(restarted.loadPendingSubmission());
        assertEquals("run_accepted", restarted.loadRemoteSessionState().activeRunId);
    }

    @Test public void profileChangeInvalidatesTheOldSessionAndUncertainSubmission() throws Exception {
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        settings.persistRemoteSessionState("api_session");
        settings.persistPendingSubmission("11111111-1111-4111-8111-111111111111", new JSONObject().put("input", "test"));
        settings.update(new JSONObject().put("gatewayUrl", "https://example.com/p/other/v1"));
        assertNull(settings.loadRemoteSessionState());
        assertNull(settings.loadPendingSubmission());
    }

    @Test public void legacyCompletionMigratesWithoutChangingSettingsAndSurvivesCredentialLoss() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        GatewaySettings settings = new GatewaySettings(store);
        String tlsPin = "sha256/" + "A".repeat(43) + "=";
        settings.update(new JSONObject().put("gatewayUrl", "https://example.com/proxy/p/robot/v1")
                .put("trustMode", GatewaySettings.CONFIRMED_SPKI_PIN)
                .put("certificatePin", tlsPin).put("confirmedFingerprint", tlsPin)
                .put("context", new JSONObject().put("robotName", "Existing Zenbo").put("language", "zh-TW"))
                .put("enabled", true));
        settings.setMotionEnabled(true);
        settings.persistRemoteSessionState("api_session");
        settings.persistPendingSubmission("11111111-1111-4111-8111-111111111111", new JSONObject().put("input", "test"));
        java.util.Map<String, Object> expected = new java.util.HashMap<>(store.getAll());
        expected.put("onboarding_complete", true);
        MemoryPreferences legacy = legacyVerifier();

        assertTrue(settings.migrateLegacyOnboarding(legacy));
        assertEquals(expected, store.getAll());
        assertTrue(legacy.getAll().isEmpty());
        GatewaySettings restarted = new GatewaySettings(store);
        assertTrue(restarted.isOnboardingComplete());
        JSONObject publicSettings = restarted.toJson(false);
        assertFalse(publicSettings.getBoolean("hasApiKey"));
        assertTrue(publicSettings.getBoolean("onboardingComplete"));
        JSONObject snapshot = restarted.snapshotForRollback();
        restarted.update(new JSONObject().put("gatewayUrl", "https://example.com/p/changed/v1"));
        restarted.restore(snapshot);
        assertEquals(snapshot.toString(), restarted.snapshotForRollback().toString());
        assertEquals(expected.get("device_id"), restarted.getDeviceId());
        assertTrue(restarted.isMotionEnabled());
        assertTrue(restarted.isOnboardingComplete());
    }

    @Test public void migrationFailureKeepsLegacyEvidenceUntilPersistedRetry() throws Exception {
        MemoryPreferences store = new MemoryPreferences();
        GatewaySettings settings = new GatewaySettings(store);
        MemoryPreferences legacy = legacyVerifier();
        java.util.Map<String, ?> originalVerifier = legacy.getAll();
        store.failCommits = true;
        assertFalse(settings.migrateLegacyOnboarding(legacy));
        assertFalse(store.contains("onboarding_complete"));
        assertEquals(originalVerifier, legacy.getAll());
        // A previously configured install must remain editable even if migration disk I/O fails.
        assertTrue(settings.isOnboardingComplete());
        store.failCommits = false;
        assertTrue(settings.migrateLegacyOnboarding(legacy));
        assertTrue(store.getBoolean("onboarding_complete", false));
        assertTrue(legacy.getAll().isEmpty());
    }

    @Test public void incompleteLegacyAndOrdinarySettingsDoNotCompleteOnboarding() throws Exception {
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        MemoryPreferences legacy = new MemoryPreferences();
        legacy.edit().putString("salt", "synthetic-incomplete").commit();
        assertTrue(settings.migrateLegacyOnboarding(legacy));
        settings.update(new JSONObject().put("gatewayUrl", "https://example.com/p/robot/v1"));
        assertFalse(settings.isOnboardingComplete());
        assertFalse(settings.toJson(true).getBoolean("onboardingComplete"));
        assertTrue(legacy.contains("salt"));
    }

    private static MemoryPreferences legacyVerifier() {
        MemoryPreferences legacy = new MemoryPreferences();
        legacy.edit().putString("salt", "synthetic-old-salt").putString("hash", "synthetic-old-hash")
                .putInt("iterations", 150000).commit();
        return legacy;
    }
}
