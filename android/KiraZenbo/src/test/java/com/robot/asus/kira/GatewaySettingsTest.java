package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GatewaySettingsTest {
    private static final String FINGERPRINT_A = "a".repeat(64);
    private static final String FINGERPRINT_B = "b".repeat(64);
    private static final String SESSION_A = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION_B = "22222222-2222-4222-8222-222222222222";
    private static final String TURN_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test
    public void sessionCreateIdempotencyKeySurvivesProcessRestart() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings firstProcess = new GatewaySettings(preferences);
        String firstKey = firstProcess.getSessionCreateIdempotencyKey();

        GatewaySettings restartedProcess = new GatewaySettings(preferences);

        assertEquals(firstKey, restartedProcess.getSessionCreateIdempotencyKey());
    }

    @Test
    public void explicitReloadOrTerminalRecreationRotatesAndPersistsSessionKey() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String original = settings.getSessionCreateIdempotencyKey();
        assertTrue(AgentGatewayClient.shouldRecreateSession("session.closed", "policy"));
        settings.prepareSessionCreate(FINGERPRINT_A);
        settings.acceptSessionCreateResponse(original, SESSION_A, 7L);

        String rotated = settings.rotateSessionCreateIdempotencyKey();

        assertNotEquals(original, rotated);
        assertEquals("", settings.getSessionCreateFingerprint());
        assertEquals("", settings.getSessionCreateRemoteSessionId());
        assertEquals(
                rotated,
                new GatewaySettings(preferences).getSessionCreateIdempotencyKey()
        );
    }

    @Test
    public void failedSynchronousRotationKeepsPreviousSessionKey() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String original = settings.getSessionCreateIdempotencyKey();
        preferences.failNextCommit = true;

        assertThrows(
                IllegalStateException.class,
                settings::rotateSessionCreateIdempotencyKey
        );
        assertEquals(original, settings.getSessionCreateIdempotencyKey());
    }

    @Test
    public void replayedCreateForBoundSessionPreservesDurableLocalCursor() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 3L));
        settings.setCursor(7L);

        GatewaySettings restarted = new GatewaySettings(preferences);
        assertEquals(key, restarted.prepareSessionCreate(FINGERPRINT_A));
        assertTrue(restarted.acceptSessionCreateResponse(key, SESSION_A, 99L));

        assertEquals(7L, restarted.getCursor());
        assertEquals(SESSION_A, restarted.getSessionCreateRemoteSessionId());
    }

    @Test
    public void differentSessionResponseAtomicallyRebindsAndInitializesCursor() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 4L));
        settings.setCursor(8L);

        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_B, 2L));

        assertEquals(SESSION_B, settings.getSessionCreateRemoteSessionId());
        assertEquals(2L, settings.getCursor());
    }

    @Test
    public void acceptedCreatePersistsAResumableRemoteSessionAcrossRestart() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 3L));

        GatewaySettings.RemoteSessionState restored =
                new GatewaySettings(preferences).loadRemoteSessionState();

        assertEquals(SESSION_A, restored.sessionId);
        assertEquals(3L, restored.cursor);
        assertEquals("", restored.activeTurnId);
        assertTrue(!restored.uploadPending);
    }

    @Test
    public void rotatingCreateIdentityAtomicallyClearsRemoteTurnRecovery() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 4L));
        settings.markRemoteTurnInFlight(SESSION_A, TURN_A);

        settings.rotateSessionCreateIdempotencyKey();

        assertEquals("", settings.getSessionCreateRemoteSessionId());
        assertEquals(0L, settings.getCursor());
        assertNull(settings.loadRemoteSessionState());
    }

    @Test
    public void replayRepairsMissingResumeMetadataWithoutRewindingCursor() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 3L));
        settings.setCursor(7L);
        preferences.values.put("remote_session_id", "");
        preferences.values.put("session_gateway_identity", "");

        GatewaySettings restarted = new GatewaySettings(preferences);
        assertTrue(restarted.acceptSessionCreateResponse(key, SESSION_A, 99L));
        GatewaySettings.RemoteSessionState restored = restarted.loadRemoteSessionState();

        assertEquals(SESSION_A, restored.sessionId);
        assertEquals(7L, restored.cursor);
    }

    @Test
    public void rollbackSnapshotRestoresBothCreateAndRemoteSessionMetadata()
            throws Exception {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 5L));
        settings.markRemoteTurnInFlight(SESSION_A, TURN_A);
        JSONObject snapshot = settings.snapshotForRollback();

        settings.rotateSessionCreateIdempotencyKey();
        settings.restore(snapshot);
        GatewaySettings.RemoteSessionState restored = settings.loadRemoteSessionState();

        assertEquals(key, settings.getSessionCreateIdempotencyKey());
        assertEquals(SESSION_A, settings.getSessionCreateRemoteSessionId());
        assertEquals(SESSION_A, restored.sessionId);
        assertEquals(5L, restored.cursor);
        assertEquals(TURN_A, restored.activeTurnId);
        assertTrue(restored.uploadPending);
    }

    @Test
    public void identityChangeAtomicallyRotatesCreateKeyAndClearsRemoteRecovery()
            throws Exception {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 5L));
        settings.markRemoteTurnInFlight(SESSION_A, TURN_A);

        settings.update(new JSONObject().put("gatewayUrl", "https://new.example"));

        assertNotEquals(key, settings.getSessionCreateIdempotencyKey());
        assertEquals("", settings.getSessionCreateFingerprint());
        assertEquals("", settings.getSessionCreateRemoteSessionId());
        assertEquals(0L, settings.getCursor());
        assertNull(settings.loadRemoteSessionState());
    }

    @Test
    public void failedIdentityChangePreservesBothCreateAndRemoteRecovery()
            throws Exception {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        assertTrue(settings.acceptSessionCreateResponse(key, SESSION_A, 5L));
        settings.markRemoteTurnInFlight(SESSION_A, TURN_A);
        preferences.failNextCommit = true;

        assertThrows(
                org.json.JSONException.class,
                () -> settings.update(
                        new JSONObject().put("gatewayUrl", "https://new.example")
                )
        );
        GatewaySettings.RemoteSessionState restored = settings.loadRemoteSessionState();

        assertEquals(key, settings.getSessionCreateIdempotencyKey());
        assertEquals(FINGERPRINT_A, settings.getSessionCreateFingerprint());
        assertEquals(SESSION_A, settings.getSessionCreateRemoteSessionId());
        assertEquals(SESSION_A, restored.sessionId);
        assertEquals(TURN_A, restored.activeTurnId);
        assertTrue(restored.uploadPending);
    }

    @Test
    public void restartWithChangedCreatePayloadRotatesBeforeRequestAndClearsBinding() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings firstProcess = new GatewaySettings(preferences);
        String firstKey = firstProcess.prepareSessionCreate(FINGERPRINT_A);
        firstProcess.acceptSessionCreateResponse(firstKey, SESSION_A, 5L);

        GatewaySettings restarted = new GatewaySettings(preferences);
        String changedPayloadKey = restarted.prepareSessionCreate(FINGERPRINT_B);

        assertNotEquals(firstKey, changedPayloadKey);
        assertEquals(FINGERPRINT_B, restarted.getSessionCreateFingerprint());
        assertEquals("", restarted.getSessionCreateRemoteSessionId());
        assertTrue(restarted.acceptSessionCreateResponse(
                changedPayloadKey,
                SESSION_B,
                1L
        ));
        assertEquals(SESSION_B, restarted.getSessionCreateRemoteSessionId());
        assertEquals(1L, restarted.getCursor());
    }

    @Test
    public void staleCreateResponseCannotOverwriteRotatedSessionState() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String staleKey = settings.prepareSessionCreate(FINGERPRINT_A);
        String currentKey = settings.prepareSessionCreate(FINGERPRINT_B);

        assertTrue(!settings.acceptSessionCreateResponse(staleKey, SESSION_A, 99L));
        assertEquals("", settings.getSessionCreateRemoteSessionId());
        assertEquals(0L, settings.getCursor());
        assertTrue(settings.acceptSessionCreateResponse(currentKey, SESSION_B, 2L));
    }

    @Test
    public void legacyKeyWithoutRequestMetadataIsRotatedDuringMigration() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        String legacyKey = "33333333-3333-4333-8333-333333333333";
        preferences.values.put("session_create_idempotency", legacyKey);

        GatewaySettings migrated = new GatewaySettings(preferences);

        assertNotEquals(legacyKey, migrated.getSessionCreateIdempotencyKey());
        assertEquals("", migrated.getSessionCreateFingerprint());
        assertEquals("", migrated.getSessionCreateRemoteSessionId());
    }

    @Test
    public void unboundCreateConflictAllowsExactlyOneDurableRotation() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String conflictedKey = settings.prepareSessionCreate(FINGERPRINT_A);

        assertTrue(settings.recoverSessionCreateConflict(
                conflictedKey,
                FINGERPRINT_A
        ));
        String recoveredKey = settings.getSessionCreateIdempotencyKey();
        assertNotEquals(conflictedKey, recoveredKey);
        assertEquals(FINGERPRINT_A, settings.getSessionCreateFingerprint());
        assertTrue(!settings.recoverSessionCreateConflict(
                recoveredKey,
                FINGERPRINT_A
        ));
        assertEquals(recoveredKey, settings.getSessionCreateIdempotencyKey());
    }

    @Test
    public void boundCreateConflictNeverRotatesIntoASecondSession() {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        settings.acceptSessionCreateResponse(key, SESSION_A, 4L);

        assertTrue(!settings.recoverSessionCreateConflict(key, FINGERPRINT_A));
        assertEquals(key, settings.getSessionCreateIdempotencyKey());
        assertEquals(SESSION_A, settings.getSessionCreateRemoteSessionId());
        assertEquals(4L, settings.getCursor());
    }

    @Test
    public void failedReloadRotationCanRestoreCompleteSettingsSnapshot()
            throws Exception {
        InMemoryPreferenceStore preferences = new InMemoryPreferenceStore();
        GatewaySettings settings = new GatewaySettings(preferences);
        settings.update(new JSONObject()
                .put("gatewayUrl", "https://old.example")
                .put("enabled", true));
        String key = settings.prepareSessionCreate(FINGERPRINT_A);
        settings.acceptSessionCreateResponse(key, SESSION_A, 6L);
        JSONObject snapshot = settings.snapshotForRollback();
        settings.update(new JSONObject().put("gatewayUrl", "https://new.example"));
        preferences.failNextCommit = true;

        assertThrows(
                IllegalStateException.class,
                settings::rotateSessionCreateIdempotencyKey
        );
        settings.restore(snapshot);

        assertEquals("https://old.example", settings.getGatewayUrl());
        assertEquals(key, settings.getSessionCreateIdempotencyKey());
        assertEquals(FINGERPRINT_A, settings.getSessionCreateFingerprint());
        assertEquals(SESSION_A, settings.getSessionCreateRemoteSessionId());
        assertEquals(6L, settings.getCursor());
    }

    @Test
    public void robotNameLimitsCountUnicodeCodePoints() throws Exception {
        GatewaySettings settings =
                new GatewaySettings(new InMemoryPreferenceStore());
        String emoji = "\uD83E\uDD16";

        settings.update(new JSONObject().put(
                "context",
                new JSONObject()
                        .put("robotName", emoji.repeat(64))
                        .put("language", "zh-TW")
        ));
        assertEquals(64, ProtocolStrings.length(settings.getRobotName()));
        assertThrows(
                org.json.JSONException.class,
                () -> settings.update(new JSONObject().put(
                        "context",
                        new JSONObject()
                                .put("robotName", emoji.repeat(65))
                                .put("language", "zh-TW")
                ))
        );

        settings.update(new JSONObject().put("robotName", emoji.repeat(40)));
        assertEquals(40, ProtocolStrings.length(settings.getRobotName()));
        assertThrows(
                org.json.JSONException.class,
                () -> settings.update(
                        new JSONObject().put("robotName", emoji.repeat(41))
                )
        );
    }

    private static final class InMemoryPreferenceStore implements PreferenceStore {
        final Map<String, Object> values = new HashMap<>();
        boolean failNextCommit;

        @Override public synchronized boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override public synchronized String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override public synchronized boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public synchronized long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        }

        @Override public synchronized Editor edit() {
            Map<String, Object> pending = new HashMap<>();
            return new Editor() {
                @Override public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override public Editor putLong(String key, long value) {
                    pending.put(key, value);
                    return this;
                }

                @Override public boolean commit() {
                    synchronized (InMemoryPreferenceStore.this) {
                        if (failNextCommit) {
                            failNextCommit = false;
                            return false;
                        }
                        values.putAll(pending);
                        return true;
                    }
                }
            };
        }
    }
}
