package com.robot.asus.kira;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.BufferedSource;

/** Hermes Runs/SSE transport and the in-process Zenbo plugin. All credentials stay native. */
public final class HermesClient implements HermesTransport {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private final GatewaySettings settings;
    private final DeviceCredentialStore credentials;
    private final Listener listener;
    private final String testApiKey;
    private final OkHttpClient testHttp;
    private final HermesEndpoints testEndpoints;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, String> runTurns = new java.util.LinkedHashMap<>();
    private final Map<String, JSONObject> deviceCalls = new HashMap<>();
    private final Map<String, ResultCallback> pendingAcks = new HashMap<>();
    private final Set<String> terminalRuns = new java.util.LinkedHashSet<>();
    private final Set<String> revokedRuns = new java.util.LinkedHashSet<>();
    private OkHttpClient http;
    private HermesEndpoints endpoints;
    private WebSocket deviceSocket;
    private Call eventCall;
    private volatile boolean running;
    private volatile boolean deviceBound;
    private volatile int generation;
    private volatile String state = "OFFLINE";
    private volatile String detail = "";
    private volatile String sessionId;
    private volatile String activeRunId;
    private volatile String activeTurnId;
    private boolean submitting;

    public HermesClient(GatewaySettings settings, DeviceCredentialStore credentials,
                        JSONObject manifest, Listener listener) {
        this.settings = settings;
        this.credentials = credentials;
        this.listener = listener;
        this.testApiKey = null;
        this.testHttp = null;
        this.testEndpoints = null;
    }

    // In-process transport tests inject HTTP and an ephemeral key; production always uses Keystore + HTTPS.
    HermesClient(GatewaySettings settings, String apiKey, OkHttpClient http,
                 HermesEndpoints endpoints, Listener listener) {
        this.settings = settings;
        this.credentials = null;
        this.listener = listener;
        this.testApiKey = apiKey;
        this.testHttp = http;
        this.testEndpoints = endpoints;
    }

    private String apiKey() { return credentials == null ? testApiKey : credentials.load(); }

    @Override public void start() {
        final int epoch;
        synchronized (this) {
            if (running) return;
            running = true;
            epoch = ++generation;
        }
        connect(epoch);
    }

    @Override public void reload() {
        final int epoch;
        synchronized (this) {
            epoch = ++generation;
            sessionId = null;
            activeRunId = null;
            activeTurnId = null;
            runTurns.clear();
            deviceCalls.clear();
            terminalRuns.clear();
            revokedRuns.clear();
            submitting = false;
        }
        closeTransport();
        if (isCurrent(epoch)) connect(epoch);
    }

    @Override public void shutdown() {
        synchronized (this) { running = false; generation++; }
        closeTransport();
        scheduler.shutdownNow();
        updateState("OFFLINE", "");
    }

    private void closeTransport() {
        final java.util.List<ResultCallback> callbacks;
        final Call oldEvent;
        final WebSocket oldSocket;
        final OkHttpClient oldHttp;
        synchronized (this) {
            oldEvent = eventCall;
            eventCall = null;
            oldSocket = deviceSocket;
            deviceSocket = null;
            deviceBound = false;
            oldHttp = http;
            callbacks = new java.util.ArrayList<>(pendingAcks.values());
            pendingAcks.clear();
        }
        if (oldEvent != null) oldEvent.cancel();
        if (oldSocket != null) oldSocket.cancel();
        if (oldHttp != null) {
            oldHttp.dispatcher().cancelAll();
            oldHttp.connectionPool().evictAll();
        }
        for (ResultCallback callback : callbacks) callback.onError("GATEWAY_OFFLINE", "Hermes device channel closed");
    }

    @Override public synchronized String getRemoteSessionId() { return sessionId; }

    @Override public synchronized JSONObject getStatus() {
        return json("state", state, "detail", detail, "connected", "READY".equals(state),
                "remoteSessionId", sessionId, "activeRunId", activeRunId,
                "profile", endpoints == null ? null : endpoints.profile());
    }

    private void connect(int epoch) {
        if (!isCurrent(epoch)) return;
        if (!settings.isEnabled() || apiKey() == null) {
            updateState("UNCONFIGURED", "Configure the Hermes API key");
            return;
        }
        try {
            endpoints = testEndpoints != null ? testEndpoints : new HermesEndpoints(settings.getGatewayUrl());
            OkHttpClient.Builder builder = GatewaySettings.CONFIRMED_SPKI_PIN.equals(settings.getTrustMode())
                    ? TlsTrust.pinnedBuilder(endpoints.base().host(), settings.getCertificatePin())
                    : new OkHttpClient.Builder();
            if (testHttp != null) builder = testHttp.newBuilder();
            http = builder.followRedirects(false).followSslRedirects(false)
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(45, TimeUnit.SECONDS).build();
            updateState("CONNECTING", "Checking Hermes profile and Zenbo plugin");
            TlsTrust.testCapabilities(http, endpoints, settings.getDeviceId(), apiKey(), System.currentTimeMillis(),
                    new TlsTrust.CapabilityCallback() {
                        @Override public void onSuccess(JSONObject result) {
                            if (isCurrent(epoch)) establishSession(epoch);
                        }
                        @Override public void onError(String code, String message) {
                            if (isCurrent(epoch)) connectionFailure(epoch, code);
                        }
                    });
        } catch (Exception error) {
            updateState("INCOMPATIBLE", "Invalid Hermes configuration");
        }
    }

    private void establishSession(int epoch) {
        GatewaySettings.RemoteSessionState restored = settings.loadRemoteSessionState();
        if (restored != null) {
            sessionId = restored.sessionId;
            activeRunId = restored.activeRunId.isEmpty() ? null : restored.activeRunId;
            executeJson(request(sessionUrl(sessionId)).get().build(), new ResultCallback() {
                @Override public void onSuccess(JSONObject result) {
                    if (!isCurrent(epoch)) return;
                    if (settings.loadPendingSubmission() != null) recoverPending(epoch);
                    else if (activeRunId != null) {
                        updateState("CONNECTING", "Reconciling the previous Hermes run");
                        stopRun(activeRunId, NO_OP);
                    } else openDeviceChannel(epoch);
                }
                @Override public void onError(String code, String message) {
                    if (!isCurrent(epoch)) return;
                    if ("HERMES_NOT_FOUND".equals(code) && activeRunId == null) {
                        settings.clearRemoteSessionState();
                        sessionId = null;
                        createSession(epoch);
                    } else connectionFailure(epoch, code);
                }
            });
        } else createSession(epoch);
    }

    private HttpUrl sessionUrl(String id) {
        return endpoints.sessions().newBuilder().addPathSegment(HermesEndpoints.requireId(id)).build();
    }

    private void createSession(int epoch) {
        final String createdId = "zenbo_" + UUID.randomUUID().toString().replace("-", "");
        JSONObject body = json("id", createdId, "source", "api_server");
        executeJson(request(endpoints.sessions()).post(jsonBody(body)).build(), new ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                if (!isCurrent(epoch)) return;
                JSONObject session = result.optJSONObject("session");
                if (session == null || !createdId.equals(session.optString("id"))) {
                    connectionFailure(epoch, "GATEWAY_INCOMPATIBLE");
                    return;
                }
                sessionId = createdId;
                settings.persistRemoteSessionState(createdId);
                openDeviceChannel(epoch);
            }
            @Override public void onError(String code, String message) {
                if (isCurrent(epoch)) connectionFailure(epoch, code);
            }
        });
    }

    private void openDeviceChannel(int epoch) {
        if (!isCurrent(epoch)) return;
        deviceSocket = http.newWebSocket(request(endpoints.deviceChannel()).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                if (!isCurrent(epoch)) { socket.cancel(); return; }
                deviceSocket = socket;
                sendControl(json("type", "device.bind", "sessionId", sessionId), "device.bound", new ResultCallback() {
                    @Override public void onSuccess(JSONObject result) {
                        if (!isCurrent(epoch)) return;
                        deviceBound = true;
                        updateState("READY", "");
                    }
                    @Override public void onError(String code, String message) { connectionFailure(epoch, code); }
                });
            }
            @Override public void onMessage(WebSocket socket, String text) {
                if (!isCurrent(epoch) || text.length() > MAX_JSON_BYTES) return;
                try { handleDeviceMessage(new JSONObject(text)); }
                catch (Exception error) { connectionFailure(epoch, "GATEWAY_INCOMPATIBLE"); }
            }
            @Override public void onClosed(WebSocket socket, int code, String reason) {
                if (isCurrent(epoch)) channelLost(epoch);
            }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (isCurrent(epoch)) channelLost(epoch);
            }
        });
    }

    private void channelLost(int epoch) {
        // Reconcile the persisted run over REST before attempting to rebind the device.
        connectionFailure(epoch, "GATEWAY_OFFLINE");
    }

    private void connectionFailure(int epoch, String code) {
        if (!isCurrent(epoch)) return;
        String failureState = "GATEWAY_AUTH".equals(code) ? "AUTH_ERROR"
                : "GATEWAY_TLS".equals(code) ? "TLS_ERROR"
                : "GATEWAY_INCOMPATIBLE".equals(code) ? "INCOMPATIBLE" : "OFFLINE";
        updateState(failureState, code);
        if ("GATEWAY_AUTH".equals(code) || "GATEWAY_TLS".equals(code)
                || "GATEWAY_INCOMPATIBLE".equals(code)) return;
        final int reconnectEpoch;
        synchronized (this) {
            if (!isCurrent(epoch)) return;
            reconnectEpoch = ++generation;
        }
        closeTransport();
        scheduler.schedule(() -> connect(reconnectEpoch), 3, TimeUnit.SECONDS);
    }

    @Override public void submitText(String turnId, String text, String language, ResultCallback callback) {
        if (text == null || text.trim().isEmpty() || text.length() > 32_768) {
            callback.onError("INVALID_TEXT", "Text must contain 1 to 32768 characters"); return;
        }
        try { UUID.fromString(turnId); } catch (Exception error) {
            callback.onError("INVALID_TURN", "A UUID client turn ID is required"); return;
        }
        final int epoch;
        final String currentSession;
        final boolean ready;
        synchronized (this) {
            ready = running && "READY".equals(state) && activeRunId == null && !submitting;
            epoch = generation;
            currentSession = sessionId;
            if (ready) { submitting = true; activeTurnId = turnId; }
        }
        if (!ready) {
            callback.onError("GATEWAY_UNAVAILABLE", "Hermes is not ready for a new run"); return;
        }
        JSONObject body = runRequest(currentSession, text, language, settings.getRobotName());
        try { settings.persistPendingSubmission(turnId, body); }
        catch (Exception error) {
            submitting = false;
            callback.onError("HERMES_STORAGE", "Could not safely save the pending turn");
            return;
        }
        Request submit = request(endpoints.runs()).header("Idempotency-Key", turnId)
                .header("X-Hermes-Session-Key", currentSession).post(jsonBody(body)).build();
        executeJson(submit, new ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                if (!isCurrent(epoch)) return;
                String id = result.optString("run_id", "");
                try { HermesEndpoints.requireId(id); }
                catch (Exception error) { onError("GATEWAY_INCOMPATIBLE", "Hermes returned no run ID"); return; }
                synchronized (HermesClient.this) {
                    submitting = false;
                    activeRunId = id;
                    runTurns.put(id, turnId);
                    if (runTurns.size() > 128) runTurns.remove(runTurns.keySet().iterator().next());
                    settings.persistAcceptedRun(currentSession, id);
                }
                callback.onSuccess(json("runId", id, "sessionId", currentSession,
                        "status", result.optString("status", "started")));
                if (!isCurrent(epoch) || !id.equals(activeRunId) || isRevoked(id)) return;
                sendControl(json("type", "run.activate", "sessionId", currentSession,
                        "runId", id, "turnId", turnId), "run.active", new ResultCallback() {
                    @Override public void onSuccess(JSONObject ignored) { openEvents(id, epoch); }
                    @Override public void onError(String code, String message) {
                        if (!isCurrent(epoch)) return;
                        stopRun(id, NO_OP);
                    }
                });
            }
            @Override public void onError(String code, String message) {
                if (!isCurrent(epoch)) return;
                synchronized (HermesClient.this) { submitting = false; activeTurnId = null; }
                if ("GATEWAY_AUTH".equals(code) || "HERMES_INVALID_REQUEST".equals(code)
                        || "HERMES_CONFLICT".equals(code) || "HERMES_BUSY".equals(code)) {
                    settings.clearPendingSubmission();
                }
                boolean uncertain = settings.loadPendingSubmission() != null;
                if (uncertain) updateState("DEGRADED", "HERMES_RECOVERY_PENDING");
                callback.onError(uncertain ? "HERMES_RECOVERY_PENDING" : code, message);
                if (uncertain) connectionFailure(epoch, "GATEWAY_OFFLINE");
            }
        });
    }

    private void recoverPending(int epoch) {
        JSONObject pending = settings.loadPendingSubmission();
        JSONObject body = pending == null ? null : pending.optJSONObject("body");
        long age = pending == null ? Long.MAX_VALUE : System.currentTimeMillis() - pending.optLong("createdAt", 0L);
        if (body == null || age < 0L || age >= TimeUnit.HOURS.toMillis(23)
                || !sessionId.equals(body.optString("session_id"))) {
            updateState("DEGRADED", "HERMES_RECOVERY_EXPIRED: pending turn requires operator review");
            return;
        }
        String turnId = pending.optString("turnId", "");
        updateState("CONNECTING", "Recovering an uncertain Hermes submission");
        executeJson(request(endpoints.runs()).header("Idempotency-Key", turnId)
                .header("X-Hermes-Session-Key", sessionId).post(jsonBody(body)).build(), new ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                if (!isCurrent(epoch)) return;
                String runId = result.optString("run_id", "");
                try {
                    HermesEndpoints.requireId(runId);
                    activeRunId = runId;
                    settings.persistAcceptedRun(sessionId, runId);
                    stopRun(runId, NO_OP);
                } catch (Exception error) { updateState("DEGRADED", "HERMES_RECOVERY_INVALID"); }
            }
            @Override public void onError(String code, String message) {
                if (isCurrent(epoch)) {
                    updateState("DEGRADED", "HERMES_RECOVERY_BLOCKED: " + code);
                    if ("GATEWAY_OFFLINE".equals(code)) scheduler.schedule(() -> recoverPending(epoch), 3, TimeUnit.SECONDS);
                }
            }
        });
    }

    static JSONObject runRequest(String sessionId, String text, String language, String robotName) {
        return json("input", text, "session_id", sessionId,
                "instructions", "You are " + robotName + ", a Zenbo robot. Reply in " + language
                        + ". Use the six Zenbo tools when a device action or expression is needed. "
                        + "Never claim a physical action succeeded without its tool result. Keep spoken replies concise.");
    }

    private void openEvents(String runId, int epoch) {
        if (!isCurrent(epoch)) return;
        Request request = request(endpoints.runEvents(runId)).header("Accept", "text/event-stream").build();
        eventCall = http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build().newCall(request);
        eventCall.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                if (isCurrent(epoch)) pollRun(runId, epoch);
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (closeable.isSuccessful() && closeable.body() != null
                            && closeable.header("Content-Type", "").startsWith("text/event-stream")) {
                        readEvents(closeable.body().source(), (type, payload) -> {
                            if (isCurrent(epoch)) handleRunEvent(runId, type, payload);
                        });
                    }
                } catch (Exception ignored) {
                    // Hermes owns run lifetime; ending the SSE connection never acknowledges a stop.
                }
                if (isCurrent(epoch) && !isTerminal(runId)) pollRun(runId, epoch);
            }
        });
    }

    interface EventConsumer { void onEvent(String type, JSONObject payload); }

    static void readEvents(BufferedSource source, EventConsumer consumer) throws IOException {
        StringBuilder data = new StringBuilder();
        String event = "message";
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict(MAX_JSON_BYTES);
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    try {
                        JSONObject object = new JSONObject(data.toString());
                        consumer.onEvent(object.optString("event", object.optString("type", event)), object);
                    } catch (org.json.JSONException error) {
                        throw new IOException("Invalid Hermes event");
                    }
                }
                data.setLength(0); event = "message";
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
                if (data.length() > MAX_JSON_BYTES) throw new IOException("Hermes event is too large");
            } else if (line.startsWith("event:")) event = line.substring(6).trim();
        }
    }

    private void handleRunEvent(String runId, String type, JSONObject payload) {
        if (isTerminal(runId) || !runId.equals(payload.optString("run_id", runId))) return;
        if ("message.delta".equals(type) || "assistant.delta".equals(type)) {
            listener.onRunEvent(runId, "assistant.delta", json("text", payload.optString("delta", "")));
        } else if ("run.completed".equals(type)) {
            finishRun(runId, "run.completed", payload);
        } else if ("run.cancelled".equals(type)) {
            finishRun(runId, type, payload);
        } else if ("run.failed".equals(type) || "run.interrupted".equals(type)) {
            finishRun(runId, "run.failed", json("code", "HERMES_RUN_FAILED", "message", "Hermes run failed"));
        } else if ("approval.request".equals(type)) {
            // The robot UI has no approval workflow: fail closed and settle the actual server run.
            stopRun(runId, NO_OP);
        }
    }

    private synchronized boolean isTerminal(String runId) { return terminalRuns.contains(runId); }
    private synchronized boolean isRevoked(String runId) { return revokedRuns.contains(runId); }

    private void finishRun(String runId, String type, JSONObject payload) {
        synchronized (this) {
            if (!terminalRuns.add(runId)) return;
            if (terminalRuns.size() > 128) terminalRuns.remove(terminalRuns.iterator().next());
            deactivate(runId, terminalDeactivationReason(type));
            if (runId.equals(activeRunId)) {
                activeRunId = null;
                activeTurnId = null;
                settings.persistRemoteSessionState(sessionId);
            }
        }
        if ("run.completed".equals(type) && !isRevoked(runId)) {
            listener.onRunEvent(runId, "assistant.final", json("text", payload.optString("output", "")));
        }
        listener.onRunEvent(runId, type, payload);
        if (deviceSocket == null && running) openDeviceChannel(generation);
        else if (deviceBound && running) updateState("READY", "");
    }

    private void pollRun(String runId, int epoch) {
        if (!isCurrent(epoch) || isTerminal(runId)) return;
        getRun(runId, new ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                if (!isCurrent(epoch) || isTerminal(runId)) return;
                String status = result.optString("status", "");
                if ("completed".equals(status) || "cancelled".equals(status)
                        || "failed".equals(status) || "interrupted".equals(status)) {
                    handleRunEvent(runId, "run." + status, result);
                } else {
                    if ("waiting_for_approval".equals(status)) stopRun(runId, NO_OP);
                    listener.onRunEvent(runId, "run.running", json("status", status));
                    scheduler.schedule(() -> pollRun(runId, epoch), 1, TimeUnit.SECONDS);
                }
            }
            @Override public void onError(String code, String message) {
                if (!isCurrent(epoch) || isTerminal(runId)) return;
                // Unknown state stays blocked; no new run or physical action is admitted.
                updateState("DEGRADED", "Could not reconcile Hermes run: " + code);
                scheduler.schedule(() -> pollRun(runId, epoch), 3, TimeUnit.SECONDS);
            }
        });
    }

    @Override public void getRun(String runId, ResultCallback callback) {
        try { executeJson(request(endpoints.run(runId)).get().build(), callback); }
        catch (Exception error) { callback.onError("GATEWAY_UNAVAILABLE", "Hermes run is unavailable"); }
    }

    @Override public void stopRun(String runId, ResultCallback callback) {
        final int epoch = generation;
        synchronized (this) {
            revokedRuns.add(runId);
            if (revokedRuns.size() > 128) revokedRuns.remove(revokedRuns.iterator().next());
        }
        deactivate(runId, "cancelled");
        try {
            executeJson(request(endpoints.runStop(runId)).post(jsonBody(new JSONObject())).build(), new ResultCallback() {
                @Override public void onSuccess(JSONObject result) {
                    callback.onSuccess(result);
                    if (isCurrent(epoch)) pollRun(runId, epoch);
                }
                @Override public void onError(String code, String message) {
                    callback.onError(code, message);
                    if (isCurrent(epoch)) pollRun(runId, epoch);
                }
            });
        } catch (Exception error) { callback.onError("GATEWAY_UNAVAILABLE", "Could not request Hermes stop"); }
    }

    private void deactivate(String runId, String reason) {
        final String turnId;
        final WebSocket socket;
        synchronized (this) { turnId = runTurns.get(runId); socket = deviceSocket; }
        if (socket != null && turnId != null) {
            socket.send(json("type", "run.deactivate", "sessionId", sessionId,
                    "runId", runId, "turnId", turnId, "reason", reason).toString());
        }
    }

    @Override public void transcribe(JSONObject input, ResultCallback callback) {
        try {
            byte[] audio = Base64.decode(input.getString("audioBase64"), Base64.DEFAULT);
            int duration = input.getInt("durationMs");
            if (audio.length == 0 || audio.length > 2 * 1024 * 1024 || duration < 1 || duration > 30_000) {
                callback.onError("INVALID_AUDIO", "Recording exceeds the supported bounds"); return;
            }
            String turnId = input.optString("clientTurnId", input.optString("turnId", ""));
            UUID.fromString(turnId);
            MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("audio", "recording.wav", RequestBody.create(audio, MediaType.get("audio/wav")))
                    .addFormDataPart("language", input.optString("language", settings.getLanguage()))
                    .addFormDataPart("sessionId", sessionId).addFormDataPart("turnId", turnId)
                    .addFormDataPart("durationMs", String.valueOf(duration)).build();
            executeJson(request(endpoints.transcription()).post(body).build(), callback);
        } catch (Exception error) { callback.onError("INVALID_AUDIO", "Recording is invalid"); }
    }

    @Override public void synthesize(String runId, String text, String language, ResultCallback callback) {
        String turnId;
        synchronized (this) { turnId = runTurns.get(runId); }
        if (turnId == null || text == null || text.isEmpty()) {
            callback.onError("INVALID_TURN", "No spoken text is available for this turn"); return;
        }
        JSONObject body = json("text", text, "language", language, "sessionId", sessionId,
                "runId", runId, "turnId", turnId);
        executeJson(request(endpoints.speech()).post(jsonBody(body)).build(), new ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                JSONArray artifacts = result.optJSONArray("artifacts");
                try {
                    if (artifacts == null || artifacts.length() == 0 || artifacts.length() > 32) {
                        throw new IllegalArgumentException("No audio artifacts");
                    }
                    for (int index = 0; index < artifacts.length(); index++) validateArtifact(artifacts.getJSONObject(index));
                    callback.onSuccess(json("segments", artifacts));
                } catch (Exception error) { callback.onError("INVALID_ARTIFACT", "Hermes audio metadata is invalid"); }
            }
            @Override public void onError(String code, String message) { callback.onError(code, message); }
        });
    }

    static void validateArtifact(JSONObject metadata) throws Exception {
        UUID.fromString(metadata.getString("artifactId"));
        String mime = metadata.getString("mimeType");
        if (!("audio/wav".equals(mime) || "audio/mpeg".equals(mime))) throw new IllegalArgumentException("Unsupported audio type");
        long size = metadata.getLong("byteLength");
        if (size < 1 || size > MAX_AUDIO_BYTES || !metadata.getString("sha256").matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid audio size or digest");
        }
        if (parseTimestampMs(metadata.getString("expiresAt")) <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Audio artifact expired");
        }
    }

    @Override public void downloadAudio(JSONObject metadata, BinaryCallback callback) {
        try {
            validateArtifact(metadata);
            Request request = request(endpoints.artifact(metadata.getString("artifactId"))).get().build();
            http.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    callback.onError(networkErrorCode(error), "Audio download failed");
                }
                @Override public void onResponse(Call call, Response response) {
                    try (Response closeable = response) {
                        if (!closeable.isSuccessful() || closeable.body() == null) {
                            callback.onError(gatewayErrorForHttpStatus(closeable.code()), safeGatewayFailureDetail(closeable.code())); return;
                        }
                        String mime = closeable.header("Content-Type", "").split(";", 2)[0].trim();
                        long length = metadata.getLong("byteLength");
                        if (!metadata.getString("mimeType").equals(mime) || closeable.body().contentLength() != length) {
                            throw new IOException("Audio type or length mismatch");
                        }
                        byte[] bytes = readBounded(closeable.body(), MAX_AUDIO_BYTES);
                        byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(bytes);
                        String digest = hex(actualHash);
                        if (!("sha-256=" + okio.ByteString.of(actualHash).base64()).equals(closeable.header("Digest"))) {
                            throw new IOException("Audio digest header mismatch");
                        }
                        if (bytes.length != length || !MessageDigest.isEqual(
                                digest.getBytes(StandardCharsets.US_ASCII), metadata.getString("sha256").getBytes(StandardCharsets.US_ASCII))) {
                            throw new IOException("Audio digest mismatch");
                        }
                        callback.onSuccess(bytes, mime, digest, metadata.getString("expiresAt"));
                    } catch (Exception error) { callback.onError("INVALID_ARTIFACT", "Audio integrity verification failed"); }
                }
            });
        } catch (Exception error) { callback.onError("INVALID_ARTIFACT", "Audio metadata is invalid or expired"); }
    }

    private void handleDeviceMessage(JSONObject message) {
        String type = message.optString("type", "");
        if ("tool.call".equals(type)) {
            final boolean admitted;
            synchronized (this) {
                admitted = sessionId != null && sessionId.equals(message.optString("sessionId"))
                        && activeRunId != null && activeRunId.equals(message.optString("runId"))
                        && activeTurnId != null && activeTurnId.equals(message.optString("turnId"))
                        && !terminalRuns.contains(activeRunId) && !revokedRuns.contains(activeRunId);
                if (admitted) {
                    String callId = message.optString("callId", "");
                    if (!deviceCalls.containsKey(callId)) deviceCalls.put(callId, message);
                }
            }
            if (admitted) listener.onDeviceToolCall(message);
            return;
        }
        if ("error".equals(type)) {
            final java.util.List<ResultCallback> callbacks;
            synchronized (this) {
                callbacks = new java.util.ArrayList<>(pendingAcks.values());
                pendingAcks.clear();
            }
            for (ResultCallback callback : callbacks) callback.onError("HERMES_DEVICE_REJECTED", "Hermes rejected a device request");
            return;
        }
        final ResultCallback callback;
        synchronized (this) {
            if (("device.bound".equals(type) || "run.active".equals(type))
                    && !message.optString("sessionId").equals(sessionId)) return;
            if ("run.active".equals(type) && (activeRunId == null || !activeRunId.equals(message.optString("runId"))
                    || activeTurnId == null || !activeTurnId.equals(message.optString("turnId")))) return;
            String key = "tool.ack".equals(type) ? type + ":" + message.optString("callId") : type;
            callback = pendingAcks.remove(key);
        }
        if (callback != null) callback.onSuccess(message);
    }

    private void sendControl(JSONObject message, String ackKey, ResultCallback callback) {
        final WebSocket socket;
        final boolean occupied;
        synchronized (this) {
            socket = deviceSocket;
            occupied = pendingAcks.containsKey(ackKey);
            if (socket != null && !occupied) pendingAcks.put(ackKey, callback);
        }
        if (socket == null || occupied) {
            callback.onError("GATEWAY_OFFLINE", "Hermes device channel is unavailable"); return;
        }
        if (!socket.send(message.toString())) {
            synchronized (this) { pendingAcks.remove(ackKey); }
            callback.onError("GATEWAY_OFFLINE", "Hermes device channel is unavailable"); return;
        }
        scheduler.schedule(() -> {
            ResultCallback pending;
            synchronized (HermesClient.this) {
                pending = pendingAcks.get(ackKey) == callback ? pendingAcks.remove(ackKey) : null;
            }
            if (pending != null) pending.onError("GATEWAY_TIMEOUT", "Hermes device acknowledgement timed out");
        }, 5, TimeUnit.SECONDS);
    }

    @Override public void reportToolResult(String callId, JSONObject update, ResultCallback callback) {
        try {
            final JSONObject call;
            synchronized (this) { call = deviceCalls.get(callId); }
            if (call == null) {
                callback.onError("UNKNOWN_TOOL_CALL", "No validated device call exists");
                return;
            }
            JSONObject message = toolResultMessage(call, update);
            sendControl(message, "tool.ack:" + callId, new ResultCallback() {
                @Override public void onSuccess(JSONObject result) {
                    if (!"accepted".equals(update.optString("status"))) {
                        synchronized (HermesClient.this) { deviceCalls.remove(callId); }
                    }
                    callback.onSuccess(result);
                }
                @Override public void onError(String code, String message) { callback.onError(code, message); }
            });
        } catch (Exception error) { callback.onError("INVALID_TOOL_RESULT", "Invalid device tool result"); }
    }

    static String terminalDeactivationReason(String type) {
        if ("run.completed".equals(type)) return "completed";
        if ("run.cancelled".equals(type)) return "cancelled";
        return "failed";
    }

    static JSONObject toolResultMessage(JSONObject call, JSONObject update) throws Exception {
        String status = update.getString("status");
        if (!("accepted".equals(status) || "succeeded".equals(status)
                || "failed".equals(status) || "rejected".equals(status))) {
            throw new IllegalArgumentException("Invalid tool result status");
        }
        JSONObject result = json("type", "tool.result", "callId", call.getString("callId"),
                "sessionId", call.getString("sessionId"), "runId", call.getString("runId"),
                "turnId", call.getString("turnId"), "status", status,
                "updatedAt", update.getString("updatedAt"));
        if ("succeeded".equals(status)) result.put("output", update.getJSONObject("output"));
        if ("failed".equals(status) || "rejected".equals(status)) {
            JSONObject error = update.getJSONObject("error");
            result.put("error", json("code", error.getString("code"), "message", error.getString("message")));
        }
        return result;
    }

    @Override public void reportPlayback(JSONObject update, ResultCallback callback) {
        callback.onSuccess(json("recorded", true));
    }

    private Request.Builder request(HttpUrl url) {
        return authorizedRequest(url, apiKey(), settings.getDeviceId());
    }

    static Request.Builder authorizedRequest(HttpUrl url, String key, String deviceId) {
        if (key == null || key.length() < 16 || key.length() > 4096 || !key.matches("[!-~]+")) {
            throw new IllegalArgumentException("Hermes API key is unavailable or invalid");
        }
        return new Request.Builder().url(url).header("Authorization", "Bearer " + key)
                .header("X-Zenbo-Device-Id", deviceId).header("Accept", "application/json");
    }

    private void executeJson(Request request, ResultCallback callback) {
        executeJson(http, request, callback);
    }

    static void executeJson(OkHttpClient client, Request request, ResultCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError(networkErrorCode(error), "Hermes network request failed");
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) {
                        callback.onError(gatewayErrorForHttpStatus(closeable.code()), safeGatewayFailureDetail(closeable.code())); return;
                    }
                    callback.onSuccess(new JSONObject(new String(readBounded(closeable.body(), MAX_JSON_BYTES), StandardCharsets.UTF_8)));
                } catch (Exception error) { callback.onError("GATEWAY_INCOMPATIBLE", "Hermes returned an invalid response"); }
            }
        });
    }

    static byte[] readBounded(ResponseBody body, int limit) throws IOException {
        if (body.contentLength() > limit) throw new IOException("Response exceeds size limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = body.byteStream().read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Response exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static String gatewayErrorForHttpStatus(int status) {
        if (status == 401 || status == 403) return "GATEWAY_AUTH";
        if (status == 400 || status == 422) return "HERMES_INVALID_REQUEST";
        if (status == 404) return "HERMES_NOT_FOUND";
        if (status == 409) return "HERMES_CONFLICT";
        if (status == 429) return "HERMES_BUSY";
        if ((status >= 300 && status < 400) || status == 426) return "GATEWAY_INCOMPATIBLE";
        return "GATEWAY_OFFLINE";
    }

    static String safeGatewayFailureDetail(int status) { return "Hermes request failed with HTTP " + status; }
    static String networkErrorCode(Throwable error) {
        return error instanceof SSLHandshakeException || error instanceof SSLPeerUnverifiedException ? "GATEWAY_TLS" : "GATEWAY_OFFLINE";
    }
    static long parseTimestampMs(String value) throws java.text.ParseException {
        if (value.endsWith("+00:00")) value = value.substring(0, value.length() - 6) + "Z";
        // Hermes Python datetimes may include microseconds; Android Date keeps milliseconds.
        value = value.replaceFirst("(\\.[0-9]{3})[0-9]{1,6}Z$", "$1Z");
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
            parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            parser.setLenient(false);
            java.text.ParsePosition position = new java.text.ParsePosition(0);
            java.util.Date parsed = parser.parse(value, position);
            if (parsed != null && position.getIndex() == value.length()) return parsed.getTime();
        }
        throw new java.text.ParseException("Invalid UTC timestamp", 0);
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return out.toString();
    }
    private static RequestBody jsonBody(JSONObject value) { return RequestBody.create(value.toString(), JSON); }
    static JSONObject json(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1] == null ? JSONObject.NULL : values[i + 1]); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid JSON values", error); }
        return result;
    }
    private synchronized boolean isCurrent(int epoch) { return running && generation == epoch; }
    private void updateState(String next, String reason) {
        synchronized (this) { state = next; detail = reason; }
        listener.onStateChanged(next, reason);
    }
    private static final ResultCallback NO_OP = new ResultCallback() {
        @Override public void onSuccess(JSONObject result) { }
        @Override public void onError(String code, String message) { }
    };
}
