package com.robot.asus.kira;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.Assert.*;

public class HermesClientTest {
    private static final String KEY = "test-secret-api-key-for-hermes";

    @Test public void everyRouteRetainsTheSelectedProfileAndProxyPrefix() {
        HermesEndpoints endpoints = new HermesEndpoints(HermesEndpoints.DEFAULT_BASE_URL + "/");
        assertEquals("robot", endpoints.profile());
        assertEquals("/hermes-api/p/robot/v1/runs/run_abc/events", endpoints.runEvents("run_abc").encodedPath());
        assertEquals("/hermes-api/p/robot/v1/runs/run_abc/stop", endpoints.runStop("run_abc").encodedPath());
        assertEquals("/hermes-api/p/robot/api/sessions", endpoints.sessions().encodedPath());
        assertEquals("/hermes-api/zenbo/robot/v1/device-channel", endpoints.deviceChannel().encodedPath());
        assertEquals("/hermes-api/zenbo/robot/v1/audio/speech", endpoints.speech().encodedPath());
        assertEquals("/hermes-api/zenbo/robot/v1/audio/transcriptions", endpoints.transcription().encodedPath());
    }

    @Test public void invalidEndpointsCannotSelectDefaultProfileOrSmuggleCredentials() {
        for (String value : new String[]{"https://example.com/v1", "http://example.com/p/robot/v1",
                "https://example.com/agent/v1", "https://key@example.com/p/robot/v1",
                "https://example.com/p/robot/v1?api_key=secret", "https://example.com/p/robot/v1#fragment"}) {
            try { new HermesEndpoints(value); fail(value); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void authorizationNeverPlacesTheKeyInTheUrlOrOldProtocolHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{}"));
            Request request = HermesClient.authorizedRequest(server.url("/p/robot/v1/runs"), KEY, "zenbo-test").get().build();
            try (Response ignored = new OkHttpClient().newCall(request).execute()) {
                RecordedRequest observed = server.takeRequest();
                assertEquals("Bearer " + KEY, observed.getHeader("Authorization"));
                assertEquals("zenbo-test", observed.getHeader("X-Zenbo-Device-Id"));
                assertNull(observed.getHeader("X-Zenbo-Protocol"));
                assertFalse(observed.getPath().contains(KEY));
            }
        }
    }

    @Test public void runsUseActualHermesInputAndSessionFields() throws Exception {
        JSONObject request = HermesClient.runRequest("zenbo_session", "你好", "zh-TW", "Zenbo K");
        assertEquals("zenbo_session", request.getString("session_id"));
        assertFalse(request.has("model"));
        assertEquals("你好", request.getString("input"));
        assertFalse(request.has("metadata"));
        assertFalse(request.has("messages"));
    }

    @Test public void spokenAnswerInstructionsChooseOneSustainedExpressionWithoutGrantingMotion() throws Exception {
        JSONObject request = HermesClient.runRequest("zenbo_session", "今天有什麼新鮮事？", "zh-TW", "Zenbo K");
        String instructions = request.getString("instructions");
        assertTrue(instructions.startsWith("You are Zenbo K, a Zenbo robot. Reply in zh-TW."));
        assertTrue(instructions.contains("proactively call show_emotion once"));
        assertTrue(instructions.contains("without waiting for the user to request an expression"));
        assertTrue(instructions.contains("HAPPY for warm greetings"));
        assertTrue(instructions.contains("EXCITED for positive celebrations"));
        assertTrue(instructions.contains("CURIOUS for questions or explanations"));
        assertTrue(instructions.contains("CONCERNED for empathy or problems"));
        assertTrue(instructions.contains("NEUTRAL when appropriate"));
        assertTrue(instructions.contains("Default to durationMs: 0"));
        assertTrue(instructions.contains("unless the user requests a particular duration"));
        assertTrue(instructions.contains("Honor a requested emotion or NEUTRAL"));
        assertTrue(instructions.contains("when sleep is requested, do not add an automatic expression"));
        assertTrue(instructions.contains("Do not use go_to_sleep for ordinary standby or merely to end a reply"));
        assertTrue(instructions.contains("respect an explicit sleep request"));
        assertTrue(instructions.contains("Avoid repeated show_emotion calls within a turn"));
        assertTrue(instructions.contains("Never invoke physical tools just to animate an expression"));
        assertTrue(instructions.contains("Never claim a physical action succeeded without its tool result"));
        assertTrue(instructions.contains("Keep spoken replies concise"));
        assertEquals(3, request.length());
        assertFalse(request.has("model"));
    }

    @Test public void parsesHermesEventFieldAndMultilineSseWithoutTreatingCommentsAsEvents() throws Exception {
        Buffer buffer = new Buffer().writeUtf8(": keepalive\n\ndata: {\"event\":\"message.delta\",\n"
                + "data: \"run_id\":\"run_test\",\"delta\":\"你好\"}\n\n"
                + "data: {\"event\":\"run.completed\",\"run_id\":\"run_test\",\"output\":\"你好\"}\n\n");
        List<String> names = new ArrayList<>();
        List<JSONObject> events = new ArrayList<>();
        HermesClient.readEvents(buffer, (type, event) -> { names.add(type); events.add(event); });
        assertEquals(java.util.Arrays.asList("message.delta", "run.completed"), names);
        assertEquals("你好", events.get(0).getString("delta"));
        assertEquals("你好", events.get(1).getString("output"));
    }

    @Test(expected = IOException.class) public void malformedSseMustReconcileAuthoritativeRunStatus() throws Exception {
        HermesClient.readEvents(new Buffer().writeUtf8("data: not-json\n\n"), (type, event) -> fail());
    }

    @Test public void remoteErrorBodiesNeverEscapeTheCredentialBoundary() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(502).setBody("Authorization: Bearer " + KEY));
            Capture capture = new Capture();
            HermesClient.executeJson(new OkHttpClient(), new Request.Builder().url(server.url("/error")).build(), capture);
            capture.await();
            assertEquals("GATEWAY_OFFLINE", capture.code);
            assertEquals("Hermes request failed with HTTP 502", capture.message);
            assertFalse(capture.message.contains(KEY));
        }
    }

    @Test public void speechRecoveryReadsOnlyTheBoundedAllowlistedErrorCode() throws Exception {
        String known = "{\"error\":{\"code\":\"device_not_bound\",\"message\":\"" + KEY + "\"}}";
        String[] bodies = {known, known.replace("device_not_bound", "private_provider_error"),
                "not json " + KEY, known + " ".repeat(4096)};
        for (int index = 0; index < bodies.length; index++) {
            try (Response response = new Response.Builder().request(new Request.Builder().url("https://example.com/audio").build())
                    .protocol(okhttp3.Protocol.HTTP_1_1).code(400).message("Bad Request")
                    .header("Content-Type", "application/json")
                    .body(okhttp3.ResponseBody.create(bodies[index], okhttp3.MediaType.get("application/json"))).build()) {
                String code = HermesClient.speechErrorCode(response);
                assertEquals(index == 0 ? "HERMES_DEVICE_NOT_BOUND" : "HERMES_INVALID_REQUEST", code);
                assertFalse(code.contains(KEY));
            }
        }
    }

    @Test public void discoveryChecksProfileCapabilitiesAndAllRequiredPluginTools() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"profile-model\"}]}"));
            server.enqueue(new MockResponse().setBody(capabilities().toString()));
            server.enqueue(new MockResponse().setBody(plugin().toString()));
            Capture capture = new Capture();
            TlsTrust.testCapabilities(new OkHttpClient(), new HermesEndpoints(server.url("/hermes-api/p/robot/v1"), true),
                    "zenbo", KEY, System.currentTimeMillis(), capture);
            capture.await();
            assertNull(capture.code);
            assertTrue(capture.result.getJSONObject("plugin").getBoolean("available"));
            assertFalse(capture.result.getJSONObject("plugin").getJSONObject("speech").getBoolean("sttConfigured"));
            assertEquals("/hermes-api/p/robot/v1/models", server.takeRequest().getPath());
            assertEquals("/hermes-api/p/robot/v1/capabilities", server.takeRequest().getPath());
            assertEquals("/hermes-api/zenbo/robot/v1/capabilities", server.takeRequest().getPath());
        }
    }

    @Test public void emptyModelInventoryFailsWithoutTryingAnotherProfile() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[]}"));
            Capture capture = new Capture();
            TlsTrust.testCapabilities(new OkHttpClient(), new HermesEndpoints(server.url("/p/robot/v1"), true),
                    "zenbo", KEY, 0L, capture);
            capture.await();
            assertEquals("GATEWAY_INCOMPATIBLE", capture.code);
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void nonDurableIdempotencyRejectsUnsafeLostAcceptanceRecovery() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"profile-model\"}]}"));
            JSONObject caps = capabilities();
            caps.getJSONObject("features").getJSONObject("runs_idempotency").put("durable", false);
            server.enqueue(new MockResponse().setBody(caps.toString()));
            Capture capture = new Capture();
            TlsTrust.testCapabilities(new OkHttpClient(), new HermesEndpoints(server.url("/p/robot/v1"), true),
                    "zenbo", KEY, 0L, capture);
            capture.await();
            assertEquals("GATEWAY_INCOMPATIBLE", capture.code);
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void artifactMetadataRejectsOversizeExpiredAndUnsupportedAudio() throws Exception {
        JSONObject valid = new JSONObject().put("artifactId", "00000000-0000-4000-8000-000000000001")
                .put("mimeType", "audio/wav").put("byteLength", 44)
                .put("sha256", "0000000000000000000000000000000000000000000000000000000000000000")
                .put("expiresAt", "2099-01-01T00:00:00.123456+00:00");
        HermesClient.validateArtifact(valid);
        for (JSONObject invalid : new JSONObject[]{new JSONObject(valid.toString()).put("byteLength", 10485761),
                new JSONObject(valid.toString()).put("expiresAt", "2020-01-01T00:00:00Z"),
                new JSONObject(valid.toString()).put("mimeType", "text/html")}) {
            try { HermesClient.validateArtifact(invalid); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void toolResultsUseStoredCorrelationAndStripLocalOnlyFields() throws Exception {
        JSONObject call = new JSONObject().put("callId", "call-authoritative")
                .put("sessionId", "session-authoritative").put("runId", "run-authoritative")
                .put("turnId", "turn-authoritative");
        JSONObject update = new JSONObject().put("status", "failed").put("sessionId", "spoofed")
                .put("updatedAt", "2026-09-17T00:00:00Z")
                .put("error", new JSONObject().put("code", "ROBOT_BUSY").put("message", "Busy").put("retryable", true));
        JSONObject result = HermesClient.toolResultMessage(call, update);
        assertEquals("tool.result", result.getString("type"));
        assertEquals("session-authoritative", result.getString("sessionId"));
        assertEquals("run-authoritative", result.getString("runId"));
        assertEquals("turn-authoritative", result.getString("turnId"));
        assertEquals("call-authoritative", result.getString("callId"));
        assertFalse(result.getJSONObject("error").has("retryable"));
        assertFalse(result.has("output"));
    }

    @Test public void completedRunRetainsPluginSpeechBindingWhileCancellationRevokesIt() {
        assertEquals("completed", HermesClient.terminalDeactivationReason("run.completed"));
        assertEquals("cancelled", HermesClient.terminalDeactivationReason("run.cancelled"));
        assertEquals("failed", HermesClient.terminalDeactivationReason("run.failed"));
    }

    private static JSONObject capabilities() throws Exception {
        return new JSONObject().put("features", new JSONObject().put("run_submission", true)
                .put("run_status", true).put("run_events_sse", true).put("run_stop", true)
                .put("runs_idempotency", new JSONObject().put("supported", true).put("durable", true).put("retention_seconds", 86400)));
    }

    private static JSONObject plugin() throws Exception {
        return new JSONObject().put("pluginVersion", "1.0")
                .put("tools", new JSONArray(java.util.Arrays.asList("get_system_status", "start_robot_following",
                        "stop_robot_following", "look_at_user", "show_emotion", "go_to_sleep")))
                .put("speech", new JSONObject().put("sttConfigured", false).put("ttsConfigured", true));
    }

    private static final class Capture implements HermesTransport.ResultCallback, TlsTrust.CapabilityCallback {
        final CountDownLatch latch = new CountDownLatch(1);
        JSONObject result;
        String code;
        String message;
        @Override public void onSuccess(JSONObject value) { result = value; latch.countDown(); }
        @Override public void onError(String error, String detail) { code = error; message = detail; latch.countDown(); }
        void await() throws Exception { assertTrue("callback timed out", latch.await(5, TimeUnit.SECONDS)); }
    }
}
