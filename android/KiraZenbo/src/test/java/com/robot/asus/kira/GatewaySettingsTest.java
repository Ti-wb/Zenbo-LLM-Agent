package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GatewaySettingsTest {
    @Test public void defaultsUseTheExplicitProfileWithoutModelOverride() throws Exception {
        GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
        assertEquals(HermesEndpoints.DEFAULT_BASE_URL, settings.getGatewayUrl());
        assertFalse(settings.isEnabled());
        assertFalse(settings.isMotionEnabled());
        JSONObject exposed = settings.toJson(false);
        assertFalse(exposed.has("model"));
        assertFalse(exposed.has("agentProfile"));
        assertFalse(exposed.getBoolean("hasApiKey"));
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
}
