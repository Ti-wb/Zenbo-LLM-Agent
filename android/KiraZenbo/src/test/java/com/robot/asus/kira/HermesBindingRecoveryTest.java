package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class HermesBindingRecoveryTest {
    @Test public void delayedOldSpeechFailureCannotDisconnectANewConversation() throws Exception {
        CountDownLatch responseArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (chain.request().url().encodedPath().endsWith("/audio/transcriptions")) {
                responseArrived.countDown();
                try { assertTrue(releaseResponse.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new java.io.IOException(error); }
            }
            return response;
        }).build();
        try (Host host = new Host(http)) {
            host.start();
            host.awaitReady();
            host.speech.add(json("{\"error\":{\"code\":\"device_not_bound\"}}").setResponseCode(400));
            Capture old = new Capture();
            host.client.executeSpeech(host.speechRequest(), old);
            assertTrue(responseArrived.await(3, TimeUnit.SECONDS));
            assertEquals(HermesTransport.NewSessionResult.STARTED, host.client.startNewSession());
            host.awaitReady();
            assertNotEquals("api_existing", host.client.getRemoteSessionId());
            releaseResponse.countDown();
            old.await();
            assertEquals("GATEWAY_OFFLINE", old.code);
            assertEquals("READY", host.client.getStatus().getString("state"));
            assertEquals(2, host.bindings.get());
            assertEquals(1, host.speechRequests.get());
            assertNull(host.disconnected.poll(200, TimeUnit.MILLISECONDS));
        } finally { releaseResponse.countDown(); }
    }

    @Test public void newConversationCreatesAndBindsOneFreshSessionWithoutDeletingHistoryOrChangingSettings() throws Exception {
        try (Host host = new Host()) {
            host.settings.setMotionEnabled(true);
            String identity = host.settings.getGatewayIdentity();
            host.start();
            host.awaitReady();
            host.acknowledgeBinding.set(false);
            assertEquals(HermesTransport.NewSessionResult.STARTED, host.client.startNewSession());
            WebSocket fresh = host.unacknowledged.poll(3, TimeUnit.SECONDS);
            assertNotNull(fresh);
            String freshId = host.client.getRemoteSessionId();
            assertNotEquals("api_existing", freshId);
            assertEquals(freshId, host.settings.loadRemoteSessionState().sessionId);
            assertEquals("CONNECTING", host.client.getStatus().getString("state"));
            assertEquals(HermesTransport.NewSessionResult.OFFLINE, host.client.startNewSession());
            Capture speech = new Capture();
            host.client.transcribe(new JSONObject(), speech);
            assertEquals("GATEWAY_OFFLINE", speech.code);
            fresh.send(Host.BOUND); // A stale binding acknowledgement cannot finish this rotation.
            assertNull(host.ready.poll(200, TimeUnit.MILLISECONDS));
            fresh.send(HermesClient.json("type", "device.bound", "sessionId", freshId).toString());
            host.awaitReady();
            assertEquals(identity, host.settings.getGatewayIdentity());
            assertTrue(host.settings.isMotionEnabled());
            assertEquals(1, host.sessionsCreated.get());
            assertEquals(0, host.sessionsDeleted.get());
            assertEquals(0, host.speechRequests.get());
            assertEquals(0, host.runRequests.get());
            assertNull(host.failure.get());
        }
    }

    @Test public void rotationStorageFailureAndPendingSubmissionPreserveTheExistingSession() throws Exception {
        try (Host host = new Host()) {
            host.start();
            host.awaitReady();
            host.preferences.failCommits = true;
            try { host.client.startNewSession(); fail("A failed durable reset must not disconnect the old session"); }
            catch (IllegalStateException expected) { }
            assertEquals("READY", host.client.getStatus().getString("state"));
            assertEquals("api_existing", host.client.getRemoteSessionId());
            assertEquals("api_existing", host.settings.loadRemoteSessionState().sessionId);
            host.preferences.failCommits = false;
            host.settings.persistPendingSubmission(java.util.UUID.randomUUID().toString(),
                    new JSONObject().put("input", "synthetic unsent turn").put("session_id", "api_existing"));
            assertEquals(HermesTransport.NewSessionResult.BUSY, host.client.startNewSession());
            assertNotNull(host.settings.loadPendingSubmission());
            assertEquals(0, host.sessionsCreated.get());
            assertEquals(0, host.sessionsDeleted.get());
        }
    }

    @Test public void delayedOldBindingCannotMakeTheReplacementChannelReady() throws Exception {
        try (Host host = new Host()) {
            host.acknowledgeBinding.set(false);
            host.start();
            WebSocket old = host.unacknowledged.poll(3, TimeUnit.SECONDS);
            assertNotNull(old);
            host.client.reload();
            WebSocket replacement = host.unacknowledged.poll(3, TimeUnit.SECONDS);
            assertNotNull(replacement);
            old.send(Host.BOUND);
            assertNull(host.ready.poll(200, TimeUnit.MILLISECONDS));
            assertEquals("CONNECTING", host.client.getStatus().getString("state"));
            replacement.send(Host.BOUND);
            host.awaitReady();
            assertEquals(2, host.bindings.get());
            assertNull(host.failure.get());
        }
    }

    @Test public void repeatedServerCloseRebindsTheSameSessionAfterTemporaryDowntime() throws Exception {
        try (Host host = new Host()) {
            host.start();
            host.awaitReady();
            for (int restart = 0; restart < 2; restart++) {
                if (restart == 1) host.discoveryFailures.set(1);
                host.socket.get().close(1001, "Server restarting");
                assertEquals("OFFLINE", host.disconnected.poll(3, TimeUnit.SECONDS));
                assertNotEquals("READY", host.client.getStatus().getString("state"));
                Capture blocked = new Capture();
                host.client.transcribe(new JSONObject(), blocked);
                assertEquals("GATEWAY_OFFLINE", blocked.code);
                host.awaitReady();
                assertEquals(restart + 2, host.bindings.get());
                assertEquals("api_existing", host.client.getRemoteSessionId());
            }
            assertEquals(0, host.speechRequests.get());
            assertEquals(0, host.runRequests.get());
            assertNull(host.failure.get());
        }
    }

    @Test public void missingBindingEndsSpeechAndRebindsWithoutReplayingAudio() throws Exception {
        try (Host host = new Host()) {
            host.start();
            host.awaitReady();
            host.speech.add(json("{\"error\":{\"code\":\"device_not_bound\",\"message\":\"private provider detail\"}}")
                    .setResponseCode(400));
            Capture failed = new Capture();
            host.client.executeSpeech(host.speechRequest(), failed);
            failed.await();
            assertEquals("GATEWAY_OFFLINE", failed.code);
            assertFalse(failed.message.contains("private"));
            assertNotEquals("READY", host.client.getStatus().getString("state"));
            host.awaitReady();
            assertEquals(2, host.bindings.get());
            assertEquals(1, host.speechRequests.get());
            assertEquals(0, host.runRequests.get());

            host.speech.add(json("{\"text\":\"new user recording\"}"));
            Capture retried = new Capture();
            host.client.executeSpeech(host.speechRequest(), retried);
            retried.await();
            assertNull(retried.code);
            assertEquals("new user recording", retried.result.getString("text"));
            assertEquals(2, host.speechRequests.get());
            assertNull(host.failure.get());
        }
    }

    private static final class Capture implements HermesTransport.ResultCallback {
        final CountDownLatch done = new CountDownLatch(1);
        JSONObject result;
        String code;
        String message;
        @Override public void onSuccess(JSONObject value) { result = value; done.countDown(); }
        @Override public void onError(String value, String detail) { code = value; message = detail; done.countDown(); }
        void await() throws InterruptedException { assertTrue(done.await(3, TimeUnit.SECONDS)); }
    }

    private static final class Host implements AutoCloseable {
        static final String BOUND = "{\"type\":\"device.bound\",\"sessionId\":\"api_existing\"}";
        final MockWebServer server = new MockWebServer();
        final AtomicReference<WebSocket> socket = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger bindings = new AtomicInteger();
        final AtomicInteger discoveryFailures = new AtomicInteger();
        final AtomicInteger speechRequests = new AtomicInteger();
        final AtomicInteger runRequests = new AtomicInteger();
        final AtomicInteger sessionsCreated = new AtomicInteger();
        final AtomicInteger sessionsDeleted = new AtomicInteger();
        final java.util.Set<String> sessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicBoolean acknowledgeBinding = new AtomicBoolean(true);
        final BlockingQueue<WebSocket> unacknowledged = new LinkedBlockingQueue<>();
        final BlockingQueue<String> ready = new LinkedBlockingQueue<>();
        final BlockingQueue<String> disconnected = new LinkedBlockingQueue<>();
        final BlockingQueue<MockResponse> speech = new LinkedBlockingQueue<>();
        final HermesClient client;
        final MemoryPreferences preferences = new MemoryPreferences();
        final GatewaySettings settings = new GatewaySettings(preferences);

        Host() throws Exception { this(new OkHttpClient()); }
        Host(OkHttpClient http) throws Exception {
            settings.update(new JSONObject().put("enabled", true));
            settings.persistRemoteSessionState("api_existing");
            sessionIds.add("api_existing");
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    if ("DELETE".equals(request.getMethod())) sessionsDeleted.incrementAndGet();
                    if (path.endsWith("/models")) return json("{\"data\":[{\"id\":\"profile-model\"}]}");
                    if (path.equals("/p/robot/v1/capabilities")) {
                        if (discoveryFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                            return new MockResponse().setResponseCode(503);
                        return json("{\"features\":{\"run_submission\":true,\"run_status\":true,\"run_events_sse\":true,"
                                + "\"run_stop\":true,\"runs_idempotency\":{\"supported\":true,\"durable\":true,\"retention_seconds\":86400}}}");
                    }
                    if (path.equals("/zenbo/robot/v1/capabilities")) return json("{\"pluginVersion\":\"1.0\","
                            + "\"tools\":[\"get_system_status\",\"start_robot_following\",\"stop_robot_following\",\"look_at_user\","
                            + "\"show_emotion\",\"go_to_sleep\"],\"speech\":{\"sttConfigured\":true,\"ttsConfigured\":true}}");
                    if (path.equals("/p/robot/api/sessions") && "POST".equals(request.getMethod())) {
                        try {
                            JSONObject body = new JSONObject(request.getBody().readUtf8());
                            String id = body.getString("id");
                            assertFalse(body.has("model"));
                            assertTrue(sessionIds.add(id));
                            sessionsCreated.incrementAndGet();
                            return json(HermesClient.json("session", HermesClient.json("id", id)).toString());
                        } catch (Throwable error) { failure.set(error); return new MockResponse().setResponseCode(500); }
                    }
                    if (path.startsWith("/p/robot/api/sessions/")) {
                        String id = path.substring(path.lastIndexOf('/') + 1);
                        if (sessionIds.contains(id)) return json(HermesClient.json("session", HermesClient.json("id", id)).toString());
                    }
                    if (path.endsWith("/device-channel")) return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                        @Override public void onMessage(WebSocket channel, String text) {
                            try {
                                JSONObject message = new JSONObject(text);
                                assertEquals("device.bind", message.getString("type"));
                                String id = message.getString("sessionId");
                                assertTrue(sessionIds.contains(id));
                                socket.set(channel);
                                bindings.incrementAndGet();
                                if (acknowledgeBinding.get()) channel.send(HermesClient.json("type", "device.bound", "sessionId", id).toString());
                                else unacknowledged.add(channel);
                            } catch (Throwable error) { failure.set(error); }
                        }
                        @Override public void onClosing(WebSocket channel, int code, String reason) { channel.close(1000, null); }
                    });
                    if (path.endsWith("/audio/transcriptions")) {
                        speechRequests.incrementAndGet();
                        MockResponse response = speech.poll();
                        return response == null ? new MockResponse().setResponseCode(500) : response;
                    }
                    if (path.endsWith("/runs")) runRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(404);
                }
            });
            client = new HermesClient(settings, "ephemeral-test-key-for-hermes", http,
                    new HermesEndpoints(server.url("/p/robot/v1"), true), new HermesTransport.Listener() {
                @Override public void onStateChanged(String state, String detail) {
                    if ("READY".equals(state)) ready.add(state);
                    if ("OFFLINE".equals(state)) disconnected.add(state);
                }
                @Override public void onRunEvent(String id, String type, JSONObject payload) { failure.set(new AssertionError("Unexpected run")); }
                @Override public void onDeviceToolCall(JSONObject call) { failure.set(new AssertionError("Unexpected device tool")); }
            });
        }

        void start() { client.start(); }
        void awaitReady() throws InterruptedException {
            assertEquals("READY", ready.poll(10, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
        Request speechRequest() {
            return new Request.Builder().url(server.url("/zenbo/robot/v1/audio/transcriptions"))
                    .post(okhttp3.RequestBody.create(new byte[]{1}, okhttp3.MediaType.get("application/octet-stream"))).build();
        }
        @Override public void close() throws Exception { client.shutdown(); server.close(); }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
