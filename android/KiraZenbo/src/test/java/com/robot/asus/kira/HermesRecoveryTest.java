package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class HermesRecoveryTest {
    @Test public void restoredRunSettlesOverRestBeforeBindingAndTransientStatusFailureRecovers() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            GatewaySettings settings = new GatewaySettings(new MemoryPreferences());
            settings.update(new JSONObject().put("enabled", true));
            settings.persistAcceptedRun("api_existing", "run_restored");
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            List<String> frames = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger polls = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<HermesClient> reference = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    order.add(request.getMethod() + " " + path);
                    if (path.endsWith("/models")) return response("{\"data\":[{\"id\":\"configured-profile-model\"}]}");
                    if (path.equals("/p/robot/v1/capabilities")) return response(capabilities());
                    if (path.equals("/zenbo/robot/v1/capabilities")) return response(plugin());
                    if (path.equals("/p/robot/api/sessions/api_existing")) return response("{\"session\":{\"id\":\"api_existing\"}}");
                    if (path.endsWith("/run_restored/stop")) return response("{\"run_id\":\"run_restored\",\"status\":\"stopping\"}");
                    if (path.endsWith("/run_restored")) {
                        if (polls.getAndIncrement() == 0) return new MockResponse().setResponseCode(503);
                        return response("{\"run_id\":\"run_restored\",\"status\":\"completed\",\"output\":\"do not replay\"}");
                    }
                    if (path.equals("/p/robot/v1/runs") && "POST".equals(request.getMethod())) {
                        try { assertFalse(new JSONObject(request.getBody().readUtf8()).has("model")); }
                        catch (Throwable error) { failure.set(error); }
                        return response("{\"run_id\":\"run_next\",\"status\":\"started\"}");
                    }
                    if (path.endsWith("/run_next/events")) return new MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"event\":\"run.completed\",\"run_id\":\"run_next\",\"output\":\"next\"}\n\n");
                    if (path.endsWith("/device-channel")) return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                        @Override public void onMessage(WebSocket socket, String text) {
                            frames.add(text);
                            try {
                                JSONObject message = new JSONObject(text);
                                if ("run.activate".equals(message.optString("type"))) {
                                    message.put("type", "run.active");
                                    socket.send(message.toString());
                                }
                                if ("device.bind".equals(message.optString("type"))) socket.send("{\"type\":\"device.bound\",\"sessionId\":\"api_existing\",\"deviceId\":\"zenbo\"}");
                            } catch (Exception error) { failure.set(error); }
                        }
                    });
                    return new MockResponse().setResponseCode(404);
                }
            });
            HermesClient client = new HermesClient(settings, "ephemeral-test-key-for-hermes", new OkHttpClient(),
                    new HermesEndpoints(server.url("/p/robot/v1"), true), new HermesTransport.Listener() {
                @Override public void onStateChanged(String state, String detail) {
                    // Another thread must be able to acquire the client monitor during callbacks.
                    CountDownLatch inspected = new CountDownLatch(1);
                    Thread inspector = new Thread(() -> { reference.get().getStatus(); inspected.countDown(); });
                    inspector.start();
                    try { if (!inspected.await(2, TimeUnit.SECONDS)) failure.set(new AssertionError("Listener invoked under client lock")); }
                    catch (InterruptedException error) { failure.set(error); }
                    if ("READY".equals(state)) ready.countDown();
                }
                @Override public void onRunEvent(String id, String type, JSONObject payload) {
                    if ("run_restored".equals(id) && "assistant.final".equals(type)) failure.set(new AssertionError("Restored answer replayed"));
                }
                @Override public void onDeviceToolCall(JSONObject call) { failure.set(new AssertionError("Recovered run gained tool authority")); }
            });
            reference.set(client);
            try {
                client.start();
                assertTrue("recovery did not become ready", ready.await(8, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertEquals("READY", client.getStatus().getString("state"));
                assertTrue(order.indexOf("POST /p/robot/v1/runs/run_restored/stop") < order.indexOf("GET /zenbo/robot/v1/device-channel"));
                assertTrue(polls.get() >= 2);
                assertTrue(frames.stream().noneMatch(frame -> frame.contains("run.activate")));
                assertEquals("", settings.loadRemoteSessionState().activeRunId);
                CountDownLatch accepted = new CountDownLatch(1);
                client.submitText("11111111-1111-4111-8111-111111111111", "Next turn", "zh-TW", new HermesTransport.ResultCallback() {
                    @Override public void onSuccess(JSONObject result) { accepted.countDown(); }
                    @Override public void onError(String code, String message) { failure.set(new AssertionError(code)); accepted.countDown(); }
                });
                assertTrue(accepted.await(3, TimeUnit.SECONDS));
                assertNull(failure.get());
            } finally { client.shutdown(); }
        }
    }

    private static MockResponse response(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private static String capabilities() {
        return "{\"features\":{\"run_submission\":true,\"run_status\":true,\"run_events_sse\":true,\"run_stop\":true,"
                + "\"runs_idempotency\":{\"supported\":true,\"durable\":true,\"retention_seconds\":86400}}}";
    }
    private static String plugin() {
        return "{\"pluginVersion\":\"1.0\",\"tools\":[\"get_system_status\",\"start_robot_following\",\"stop_robot_following\",\"look_at_user\",\"show_emotion\",\"go_to_sleep\"],\"speech\":{\"sttConfigured\":true,\"ttsConfigured\":true}}";
    }
}
