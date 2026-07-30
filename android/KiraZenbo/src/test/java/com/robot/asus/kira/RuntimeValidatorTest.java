package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeValidatorTest {
    @Test
    public void validatesPcm16Mono16kWavAndDeclaredDuration() {
        byte[] wav = wav(16_000, 1, 16, 1_000);
        WavValidator.validate(wav, 1_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsStereoWav() {
        WavValidator.validate(wav(16_000, 2, 16, 1_000), 1_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedDeclaredWavDuration() {
        WavValidator.validate(wav(16_000, 1, 16, 1_000), 2_000);
    }

    @Test
    public void validatesAudioMimeLengthDigestAndExpiry() {
        byte[] bytes = "verified audio".getBytes(StandardCharsets.UTF_8);
        AudioArtifactValidator.validateMetadata("audio/mpeg", bytes.length, 20_000L, 10_000L);
        AudioArtifactValidator.validatePayload(
                bytes,
                "audio/mpeg",
                "audio/mpeg",
                bytes.length,
                AudioArtifactValidator.sha256(bytes)
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAudioDigestMismatch() {
        byte[] bytes = "audio".getBytes(StandardCharsets.UTF_8);
        AudioArtifactValidator.validatePayload(bytes, "audio/wav", "audio/wav", bytes.length,
                "0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsExpiredAudioMetadata() {
        AudioArtifactValidator.validateMetadata("audio/wav", 100, 10_000L, 10_000L);
    }

    @Test
    public void idempotencyKeysAreStableAndOperationScoped() {
        String first = AgentGatewayClient.stableIdempotency("playback", "session", "turn", "artifact", "started");
        String retry = AgentGatewayClient.stableIdempotency("playback", "session", "turn", "artifact", "started");
        String completed = AgentGatewayClient.stableIdempotency("playback", "session", "turn", "artifact", "completed");
        assertEquals(first, retry);
        assertNotEquals(first, completed);
    }

    @Test
    public void gatewayStateNeverLeaksAnUnknownRuntimeState() {
        assertEquals("DEGRADED", GatewayStateMapper.normalize("DEGRADED"));
        assertEquals("OFFLINE", GatewayStateMapper.normalize("RECONNECTING"));
        assertEquals("OFFLINE", GatewayStateMapper.normalize("ERROR"));
    }

    @Test
    public void terminalSessionEventsRecreateExceptForRevokedCredentials() {
        assertTrue(AgentGatewayClient.shouldRecreateSession("session.closed", "policy"));
        assertTrue(AgentGatewayClient.shouldRecreateSession("session.expired", "idle_timeout"));
        assertFalse(AgentGatewayClient.shouldRecreateSession("session.expired", "credential_revoked"));
    }

    @Test
    public void missingSessionAndCursorAheadResponsesResetDurableSessionRecovery() {
        assertTrue(AgentGatewayClient.shouldResetRemoteSession(404));
        assertTrue(AgentGatewayClient.shouldResetRemoteSession(409));
        assertFalse(AgentGatewayClient.shouldResetRemoteSession(401));
        assertFalse(AgentGatewayClient.shouldResetRemoteSession(426));
        assertFalse(AgentGatewayClient.shouldResetRemoteSession(500));
    }

    @Test
    public void restartResumesOnlySessionsWithoutAnInFlightTurnMarker() {
        assertTrue(AgentGatewayClient.shouldResumePersistedSession(""));
        assertTrue(AgentGatewayClient.shouldResumePersistedSession(null));
        assertFalse(AgentGatewayClient.shouldResumePersistedSession(
                "44444444-4444-4444-8444-444444444444"
        ));
    }

    @Test
    public void authoritativeSnapshotCannotEraseAPreAcceptanceUploadMarker() {
        assertTrue(GatewaySettings.shouldPreservePendingUploadMarker(true, ""));
        assertTrue(GatewaySettings.shouldPreservePendingUploadMarker(true, null));
        assertFalse(GatewaySettings.shouldPreservePendingUploadMarker(
                true,
                "44444444-4444-4444-8444-444444444444"
        ));
        assertFalse(GatewaySettings.shouldPreservePendingUploadMarker(false, ""));
    }

    @Test
    public void uncertainUploadAbandonsOnlyTheSessionThatIssuedIt() {
        String uncertainSession = "55555555-5555-4555-8555-555555555555";
        String replacementSession = "66666666-6666-4666-8666-666666666666";
        assertTrue(AgentGatewayClient.isSameRemoteSession(
                uncertainSession,
                uncertainSession
        ));
        assertFalse(AgentGatewayClient.isSameRemoteSession(
                uncertainSession,
                replacementSession
        ));
        assertFalse(AgentGatewayClient.isSameRemoteSession(null, replacementSession));
    }

    @Test
    public void terminalEventClearsOnlyItsCorrelatedActiveTurnMarker() {
        assertTrue(GatewaySettings.shouldClearActiveTurn(
                "44444444-4444-4444-8444-444444444444",
                "44444444-4444-4444-8444-444444444444"
        ));
        assertFalse(GatewaySettings.shouldClearActiveTurn(
                "55555555-5555-4555-8555-555555555555",
                "44444444-4444-4444-8444-444444444444"
        ));
        assertFalse(GatewaySettings.shouldClearActiveTurn(
                "",
                "44444444-4444-4444-8444-444444444444"
        ));
    }

    @Test
    public void playbackAllowsAnEmptySuccessfulResponseWithoutWeakeningStrictJsonEndpoints()
            throws Exception {
        assertEquals(0, AgentGatewayClient.parseSuccessBody("", true).length());
        assertEquals(0, AgentGatewayClient.parseSuccessBody("  ", true).length());
        assertEquals(0, AgentGatewayClient.parseSuccessBody("{}", false).length());
    }

    @Test(expected = org.json.JSONException.class)
    public void strictJsonEndpointStillRejectsAnEmptySuccessfulResponse() throws Exception {
        AgentGatewayClient.parseSuccessBody("", false);
    }

    @Test
    public void cancelRequestUsesOnlyTheValidatedWireReason() throws Exception {
        assertTrue(AgentGatewayClient.isAllowedCancelReason("client_request"));
        assertTrue(AgentGatewayClient.isAllowedCancelReason("superseded"));
        assertTrue(AgentGatewayClient.isAllowedCancelReason("timeout"));
        assertFalse(AgentGatewayClient.isAllowedCancelReason("barge_in"));
        assertEquals(
                "superseded",
                AgentGatewayClient.cancelRequestBody("superseded").getString("reason")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void cancelRequestBodyRejectsUnknownReason() {
        AgentGatewayClient.cancelRequestBody("barge_in");
    }

    @Test
    public void durableSessionIdentityChangesWithGatewayTrustAndAgentContext() {
        String identity = GatewaySettings.gatewayIdentity(
                "https://gateway.lan/agent/v1",
                GatewaySettings.SYSTEM_TRUST,
                "",
                "device-1",
                "default",
                "Zenbo K",
                "zh-TW"
        );
        assertEquals(identity, GatewaySettings.gatewayIdentity(
                "https://gateway.lan/agent/v1",
                GatewaySettings.SYSTEM_TRUST,
                "",
                "device-1",
                "default",
                "Zenbo K",
                "zh-TW"
        ));
        assertNotEquals(identity, GatewaySettings.gatewayIdentity(
                "https://other-gateway.lan/agent/v1",
                GatewaySettings.SYSTEM_TRUST,
                "",
                "device-1",
                "default",
                "Zenbo K",
                "zh-TW"
        ));
        assertNotEquals(identity, GatewaySettings.gatewayIdentity(
                "https://gateway.lan/agent/v1",
                GatewaySettings.CONFIRMED_SPKI_PIN,
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "device-1",
                "hermes",
                "Zenbo K",
                "zh-TW"
        ));
    }

    @Test
    public void toolCallCannotDispatchBeforeRemoteAcceptanceOrAfterTerminal() {
        ToolCallLifecycle lifecycle = new ToolCallLifecycle();
        String callId = "11111111-1111-4111-8111-111111111111";
        assertTrue(lifecycle.beginAcceptance(callId));
        assertFalse(lifecycle.isDispatched(callId));
        assertTrue(lifecycle.markAccepted(callId));
        assertTrue(lifecycle.isDispatched(callId));
        lifecycle.markTerminal(callId);
        assertFalse(lifecycle.isDispatched(callId));
        assertFalse(lifecycle.beginAcceptance(callId));
    }

    @Test
    public void sessionBoundaryTerminalizesAcceptingAndDispatchedToolCalls() {
        ToolCallLifecycle lifecycle = new ToolCallLifecycle();
        String accepting = "11111111-1111-4111-8111-111111111111";
        String dispatched = "22222222-2222-4222-8222-222222222222";
        String alreadyTerminal = "33333333-3333-4333-8333-333333333333";
        assertTrue(lifecycle.beginAcceptance(accepting));
        assertTrue(lifecycle.beginAcceptance(dispatched));
        assertTrue(lifecycle.markAccepted(dispatched));
        assertTrue(lifecycle.beginAcceptance(alreadyTerminal));
        lifecycle.markTerminal(alreadyTerminal);

        assertEquals(
                new HashSet<>(Arrays.asList(accepting, dispatched)),
                lifecycle.terminateActiveCalls()
        );
        assertFalse(lifecycle.markAccepted(accepting));
        assertFalse(lifecycle.isDispatched(dispatched));
        assertFalse(lifecycle.beginAcceptance(accepting));
        assertFalse(lifecycle.beginAcceptance(dispatched));
        assertFalse(lifecycle.beginAcceptance(alreadyTerminal));
        assertTrue(lifecycle.terminateActiveCalls().isEmpty());
    }

    @Test
    public void authoritativeSnapshotClearsRecoveryWhenItHasNoActiveTurn() {
        String active = "44444444-4444-4444-8444-444444444444";
        assertTrue(RemoteSessionCoordinator.shouldResetRecoveryForSnapshot(active, null));
        assertTrue(RemoteSessionCoordinator.shouldResetRecoveryForSnapshot(null, null));
        assertTrue(RemoteSessionCoordinator.shouldResetRecoveryForSnapshot(
                active,
                "55555555-5555-4555-8555-555555555555"
        ));
        assertFalse(RemoteSessionCoordinator.shouldResetRecoveryForSnapshot(active, active));
    }

    @Test
    public void terminalEventClassificationCoversTurnAndSessionBoundaries() {
        assertTrue(RemoteSessionCoordinator.isTurnTerminalEvent("turn.completed"));
        assertTrue(RemoteSessionCoordinator.isTurnTerminalEvent("turn.error"));
        assertTrue(RemoteSessionCoordinator.isTurnTerminalEvent("turn.cancelled"));
        assertFalse(RemoteSessionCoordinator.isTurnTerminalEvent("tool.call"));
        assertTrue(RemoteSessionCoordinator.isSessionTerminalEvent("session.expired"));
        assertTrue(RemoteSessionCoordinator.isSessionTerminalEvent("session.closed"));
        assertFalse(RemoteSessionCoordinator.isSessionTerminalEvent("turn.completed"));
    }

    @Test
    public void uploadCallbacksRequireBothGenerationAndClientTurnCorrelation() {
        String clientTurnId = "66666666-6666-4666-8666-666666666666";
        assertTrue(RemoteSessionCoordinator.isCurrentUploadCallback(
                7L,
                7L,
                clientTurnId,
                clientTurnId
        ));
        assertFalse(RemoteSessionCoordinator.isCurrentUploadCallback(
                6L,
                7L,
                clientTurnId,
                clientTurnId
        ));
        assertFalse(RemoteSessionCoordinator.isCurrentUploadCallback(
                7L,
                7L,
                clientTurnId,
                "77777777-7777-4777-8777-777777777777"
        ));
        assertFalse(RemoteSessionCoordinator.isCurrentUploadCallback(
                7L,
                7L,
                null,
                clientTurnId
        ));
    }

    @Test
    public void stalePhysicalToolReleaseCannotClearANewerOwner() {
        AtomicReference<String> owner = new AtomicReference<>();
        String firstCall = "88888888-8888-4888-8888-888888888888";
        String secondCall = "99999999-9999-4999-8999-999999999999";
        assertTrue(RemoteSessionCoordinator.tryClaimPhysicalTool(owner, firstCall));
        assertFalse(RemoteSessionCoordinator.tryClaimPhysicalTool(owner, secondCall));
        RemoteSessionCoordinator.releasePhysicalTool(owner, secondCall);
        assertEquals(firstCall, owner.get());
        RemoteSessionCoordinator.releasePhysicalTool(owner, firstCall);
        assertEquals(null, owner.get());
        assertTrue(RemoteSessionCoordinator.tryClaimPhysicalTool(owner, secondCall));
    }

    @Test
    public void cancelledTurnCannotRegainPhysicalExecutionAuthority() {
        TurnAuthority authority = new TurnAuthority();
        String sessionId = "11111111-1111-4111-8111-111111111111";
        long epoch = 7L;
        String turnId = "22222222-2222-4222-8222-222222222222";
        final boolean[] executed = {false};
        assertTrue(authority.runIfAuthorized(
                sessionId,
                epoch,
                turnId,
                () -> executed[0] = true
        ));
        assertTrue(executed[0]);
        authority.revoke(sessionId, epoch, turnId);
        executed[0] = false;
        assertFalse(authority.runIfAuthorized(
                sessionId,
                epoch,
                turnId,
                () -> executed[0] = true
        ));
        assertFalse(executed[0]);
        assertTrue(authority.isRevoked(sessionId, epoch, turnId));
        assertFalse(authority.isRevoked(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                epoch,
                turnId
        ));
        assertFalse(authority.isRevoked(sessionId, epoch + 1L, turnId));
        authority.clearSession(sessionId, epoch);
        assertFalse(authority.isRevoked(sessionId, epoch, turnId));
        authority.revoke(sessionId, epoch, turnId);
        authority.forget(sessionId, epoch, turnId);
        assertFalse(authority.isRevoked(sessionId, epoch, turnId));
    }

    @Test
    public void cancellationAfterQueueingStillBlocksTheActualSideEffect() {
        TurnAuthority authority = new TurnAuthority();
        String sessionId = "11111111-1111-4111-8111-111111111111";
        long epoch = 9L;
        String turnId = "33333333-3333-4333-8333-333333333333";
        final boolean[] sideEffect = {false};
        Runnable queuedMainThreadWork = () -> GuardedExecution.runIfAllowed(
                action -> authority.runIfAuthorized(
                        sessionId,
                        epoch,
                        turnId,
                        action
                ),
                () -> sideEffect[0] = true
        );
        authority.revoke(sessionId, epoch, turnId);
        queuedMainThreadWork.run();
        assertFalse(sideEffect[0]);
    }

    @Test
    public void toolJournalEntryValidationFailsClosedOnInconsistentState()
            throws Exception {
        ToolCallJournal.Entry dispatched = new ToolCallJournal.Entry(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "33333333-3333-4333-8333-333333333333",
                "web",
                "show_emotion",
                10L,
                null,
                0L,
                ToolCallJournal.DeliveryState.NONE
        );
        assertTrue(SharedPreferencesToolCallJournal.isValidEntry(dispatched));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                dispatched.withDeliveryState(
                        ToolCallJournal.DeliveryState.PENDING
                )
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                new ToolCallJournal.Entry(
                        dispatched.sessionId,
                        dispatched.callId,
                        dispatched.turnId,
                        dispatched.owner,
                        dispatched.name,
                        dispatched.dispatchedAt,
                        new JSONObject(),
                        0L,
                        ToolCallJournal.DeliveryState.PENDING
                )
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                new ToolCallJournal.Entry(
                        "not-a-uuid",
                        dispatched.callId,
                        dispatched.turnId,
                        "unknown",
                        "",
                        -1L,
                        null,
                        0L,
                        ToolCallJournal.DeliveryState.NONE
                )
        ));
        JSONObject succeeded = new JSONObject()
                .put("status", "succeeded")
                .put("updatedAt", "2026-07-24T12:34:56.789Z")
                .put("output", new JSONObject()
                        .put("ok", true)
                        .put("emotion", "HAPPY")
                        .put("durationMs", 100));
        JSONObject failed = new JSONObject()
                .put("status", "failed")
                .put("updatedAt", "2026-07-24T12:34:56Z")
                .put("error", new JSONObject()
                        .put("code", "TIMEOUT")
                        .put("message", "deadline exceeded")
                        .put("retryable", false));
        assertTrue(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, succeeded)
        ));
        assertTrue(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, failed)
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject())
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject()
                        .put("status", "accepted")
                        .put("updatedAt", "2026-07-24T12:34:56.789Z"))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject(succeeded.toString())
                        .put("updatedAt", "not-a-timestamp"))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject(succeeded.toString())
                        .put("output", new JSONObject().put("ok", true)))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject(succeeded.toString())
                        .put("output", new JSONObject()
                                .put("ok", true)
                                .put("emotion", "HAPPY")
                                .put("durationMs", "100")))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject(succeeded.toString())
                        .put("output", new JSONObject()
                                .put("ok", true)
                                .put("emotion", "HAPPY")
                                .put("durationMs", 100)
                                .put("unexpected", true)))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject(failed.toString())
                        .put("unexpected", true))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                terminalEntry(dispatched, new JSONObject()
                        .put("status", "failed")
                        .put("updatedAt", "2026-07-24T12:34:56.789Z")
                        .put("error", new JSONObject()
                                .put("code", "bad")
                                .put("message", "")
                                .put("retryable", "false")))
        ));
        assertFalse(SharedPreferencesToolCallJournal.isValidEntry(
                new ToolCallJournal.Entry(
                        dispatched.sessionId,
                        dispatched.callId,
                        dispatched.turnId,
                        "native",
                        dispatched.name,
                        dispatched.dispatchedAt,
                        succeeded,
                        20L,
                        ToolCallJournal.DeliveryState.PENDING
                )
        ));
    }

    @Test
    public void reconnectJitterModuloIsApi23SafeAndNonNegative() {
        assertEquals(0L, AgentGatewayClient.nonNegativeModulo(Long.MIN_VALUE, 4L));
        assertEquals(3L, AgentGatewayClient.nonNegativeModulo(-1L, 4L));
        assertEquals(1L, AgentGatewayClient.nonNegativeModulo(5L, 4L));
    }

    @Test
    public void nativeToolManifestHasExactOwnersEffectsSchemasAndTimeouts() {
        List<ToolManifestSpec.Definition> tools = ToolManifestSpec.definitions();
        assertEquals(6, tools.size());
        Set<String> names = new HashSet<>();
        for (ToolManifestSpec.Definition tool : tools) {
            String name = tool.name;
            names.add(name);
            boolean webOwned = "show_emotion".equals(name) || "go_to_sleep".equals(name);
            assertEquals(webOwned ? "web" : "native", tool.owner);
            assertEquals(webOwned ? "ui" : "get_system_status".equals(name) ? "none" : "physical",
                    tool.sideEffect);
            assertEquals(5_000, tool.timeoutMs);
            assertEquals(!"start_robot_following".equals(name), tool.idempotent);
        }

        assertEquals(new HashSet<>(Arrays.asList(
                "get_system_status", "start_robot_following", "stop_robot_following",
                "look_at_user", "show_emotion", "go_to_sleep"
        )), names);

        assertEquals(set(), propertyNames(findTool(tools, "get_system_status").inputProperties));
        assertEquals(set("enablePreview", "largePreview"),
                propertyNames(findTool(tools, "start_robot_following").inputProperties));
        assertEquals(set(), propertyNames(findTool(tools, "stop_robot_following").inputProperties));
        assertEquals(set("doa"), propertyNames(findTool(tools, "look_at_user").inputProperties));
        assertEquals(set("emotion", "durationMs"),
                propertyNames(findTool(tools, "show_emotion").inputProperties));
        assertEquals(set(), propertyNames(findTool(tools, "go_to_sleep").inputProperties));

        assertEquals(set("accepted", "robotReady", "moving", "androidSdk", "robotModel"),
                propertyNames(findTool(tools, "get_system_status").resultProperties));
        assertEquals(set("accepted"),
                propertyNames(findTool(tools, "start_robot_following").resultProperties));
        assertEquals(set("accepted"),
                propertyNames(findTool(tools, "stop_robot_following").resultProperties));
        assertEquals(set("accepted"),
                propertyNames(findTool(tools, "look_at_user").resultProperties));
        assertEquals(set("ok", "emotion", "durationMs"),
                propertyNames(findTool(tools, "show_emotion").resultProperties));
        assertEquals(set("ok", "sleeping"),
                propertyNames(findTool(tools, "go_to_sleep").resultProperties));

        assertEquals(set("doa"), set(findTool(tools, "look_at_user").requiredInputs.toArray(new String[0])));
        assertEquals(set("emotion"), set(findTool(tools, "show_emotion").requiredInputs.toArray(new String[0])));
        ToolManifestSpec.Property emotionInput =
                findProperty(findTool(tools, "show_emotion").inputProperties, "emotion");
        ToolManifestSpec.Property durationInput =
                findProperty(findTool(tools, "show_emotion").inputProperties, "durationMs");
        ToolManifestSpec.Property emotionResult =
                findProperty(findTool(tools, "show_emotion").resultProperties, "emotion");
        ToolManifestSpec.Property durationResult =
                findProperty(findTool(tools, "show_emotion").resultProperties, "durationMs");
        List<String> emotions = Arrays.asList(
                "NEUTRAL", "HAPPY", "CURIOUS", "CONCERNED", "EXCITED"
        );
        assertEquals(emotions, emotionInput.allowedValues);
        assertEquals(emotions, emotionResult.allowedValues);
        assertEquals(0, durationInput.minimum.intValue());
        assertEquals(30_000, durationInput.maximum.intValue());
        assertEquals(0, durationResult.minimum.intValue());
        assertEquals(30_000, durationResult.maximum.intValue());
        for (ToolManifestSpec.Definition tool : tools) {
            assertEquals(propertyNames(tool.resultProperties), new HashSet<>(tool.requiredResults));
        }
    }

    private static ToolManifestSpec.Definition findTool(
            List<ToolManifestSpec.Definition> tools,
            String name
    ) {
        for (ToolManifestSpec.Definition tool : tools) if (name.equals(tool.name)) return tool;
        throw new AssertionError("Missing tool: " + name);
    }

    private static ToolCallJournal.Entry terminalEntry(
            ToolCallJournal.Entry dispatched,
            JSONObject update
    ) {
        return new ToolCallJournal.Entry(
                dispatched.sessionId,
                dispatched.callId,
                dispatched.turnId,
                dispatched.owner,
                dispatched.name,
                dispatched.dispatchedAt,
                update,
                20L,
                ToolCallJournal.DeliveryState.PENDING
        );
    }

    private static Set<String> propertyNames(List<ToolManifestSpec.Property> properties) {
        Set<String> names = new HashSet<>();
        for (ToolManifestSpec.Property property : properties) names.add(property.name);
        return names;
    }

    private static ToolManifestSpec.Property findProperty(
            List<ToolManifestSpec.Property> properties,
            String name
    ) {
        for (ToolManifestSpec.Property property : properties) {
            if (name.equals(property.name)) return property;
        }
        throw new AssertionError("Missing property: " + name);
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static byte[] wav(int sampleRate, int channels, int bitsPerSample, int durationMs) {
        int dataSize = sampleRate * channels * (bitsPerSample / 8) * durationMs / 1_000;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * (bitsPerSample / 8));
        buffer.putShort((short) (channels * (bitsPerSample / 8)));
        buffer.putShort((short) bitsPerSample);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        return buffer.array();
    }
}
