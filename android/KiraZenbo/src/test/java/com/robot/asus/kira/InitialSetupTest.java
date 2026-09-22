package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class InitialSetupTest {
    private static final String SYNTHETIC_KEY = "synthetic-setup-key-not-a-secret";

    @Test public void setupNeedsNoManagementPinAndCannotRunTwice() throws Exception {
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        FakeCredentials credentials = new FakeCredentials();
        InitialSetup.configure(settings, credentials, request());
        assertTrue(settings.isOnboardingComplete());
        assertTrue(settings.isEnabled());
        assertEquals("https://example.com/proxy/p/robot/v1", settings.getGatewayUrl());
        assertEquals(SYNTHETIC_KEY, credentials.value);
        assertThrows(IllegalStateException.class,
                () -> InitialSetup.configure(settings, credentials, request()));
        assertEquals(1, credentials.saves);
    }

    @Test public void rejectsRetiredPinFieldsAndStillRequiresTlsConfirmation() throws Exception {
        for (String field : new String[]{"pin", "confirmPin"}) {
            assertThrows(org.json.JSONException.class,
                    () -> InitialSetup.validate(request().put(field, "123456")));
        }
        String tlsPin = "sha256/" + "A".repeat(43) + "=";
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        FakeCredentials credentials = new FakeCredentials();
        JSONObject pinned = request().put("trustMode", GatewaySettings.CONFIRMED_SPKI_PIN)
                .put("certificatePin", tlsPin);
        assertThrows(IllegalArgumentException.class, () -> InitialSetup.validate(pinned));
        pinned.put("confirmedFingerprint", "sha256/" + "B".repeat(43) + "=");
        assertThrows(IllegalStateException.class, () -> InitialSetup.configure(settings, credentials, pinned));
        assertFalse(settings.isOnboardingComplete());
        assertEquals(0, credentials.saves);
        pinned.put("confirmedFingerprint", tlsPin);
        InitialSetup.configure(settings, credentials, pinned);
        assertEquals(GatewaySettings.CONFIRMED_SPKI_PIN, settings.getTrustMode());
        assertEquals(tlsPin, settings.getCertificatePin());
    }

    @Test public void credentialAndCompletionWriteFailuresRestoreStateAndAllowRetry() throws Exception {
        for (boolean failCompletion : new boolean[]{false, true}) {
            MemoryPreferences store = new MemoryPreferences();
            GatewaySettings settings = new GatewaySettings(store);
            settings.update(new JSONObject().put("gatewayUrl", "https://example.com/p/original/v1"));
            settings.setMotionEnabled(true);
            settings.persistRemoteSessionState("original_session");
            String originalDeviceId = settings.getDeviceId();
            JSONObject original = settings.snapshotForRollback();
            FakeCredentials credentials = new FakeCredentials();
            credentials.value = failCompletion ? null : "synthetic-previous-key";
            String originalKey = credentials.value;
            if (failCompletion) credentials.afterNextSave = () -> store.failCommits = true;
            else credentials.failNextSave = true;

            assertThrows(IllegalStateException.class,
                    () -> InitialSetup.configure(settings, credentials, request()));
            assertFalse(settings.isOnboardingComplete());
            assertFalse(store.getBoolean("onboarding_complete", false));
            assertEquals(original.toString(), settings.snapshotForRollback().toString());
            assertEquals(originalDeviceId, settings.getDeviceId());
            assertTrue(settings.isMotionEnabled());
            assertEquals(originalKey, credentials.value);

            store.failCommits = false;
            InitialSetup.configure(settings, credentials, request());
            assertTrue(settings.isOnboardingComplete());
            assertEquals(SYNTHETIC_KEY, credentials.value);
        }
    }

    private static JSONObject request() throws Exception {
        return new JSONObject().put("gatewayUrl", "https://example.com/proxy/p/robot/v1")
                .put("apiKey", SYNTHETIC_KEY).put("trustMode", GatewaySettings.SYSTEM_TRUST)
                .put("context", new JSONObject().put("robotName", "Test Zenbo").put("language", "zh-TW"));
    }

    private static final class FakeCredentials implements InitialSetup.Credentials {
        String value;
        int saves;
        boolean failNextSave;
        Runnable afterNextSave;
        @Override public String load() { return value; }
        @Override public void save(String key) throws Exception {
            saves++;
            if (failNextSave) {
                failNextSave = false;
                throw new java.io.IOException("synthetic credential write failure");
            }
            value = key;
            Runnable afterSave = afterNextSave;
            afterNextSave = null;
            if (afterSave != null) afterSave.run();
        }
        @Override public void clear() { value = null; }
    }
}
