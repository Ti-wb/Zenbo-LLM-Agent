package com.robot.asus.kira;

import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.TimeZone;
import java.util.Locale;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CertificatePinner;
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

/** Native-only Agent Gateway protocol 1.0 client. Provider secrets never enter this process. */
public final class AgentGatewayClient implements GatewayTransport {
    public interface Listener {
        void onGatewayStateChanged(String state, String detail);
        void onGatewayMessage(JSONObject message, EventCommitCallback callback);
    }

    public interface EventCommitCallback {
        void commit();
        void retry();
    }

    public interface ResultCallback {
        void onSuccess(JSONObject result);
        void onError(String code, String message);
    }

    public interface BinaryCallback {
        void onSuccess(byte[] bytes, String contentType, String digest, String expiresAt);
        void onError(String code, String message);
    }

    private static final String TAG = "AgentGatewayClient";
    private static final String PROTOCOL_VERSION = "1.0";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType WAV = MediaType.get("audio/wav");
    private static final long[] RECONNECT_DELAYS = {1_000L, 2_000L, 4_000L, 8_000L, 15_000L};
    private static final int MAX_INPUT_AUDIO_BYTES = 2 * 1024 * 1024;
    private static final int MAX_OUTPUT_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int MAX_DEFERRED_EVENT_FRAMES = 128;

    private final GatewaySettings settings;
    private final DeviceCredentialStore credentialStore;
    private final JSONObject toolManifest;
    private final Listener listener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SecureRandom random = new SecureRandom();

    private OkHttpClient httpClient;
    private HttpUrl apiBaseUrl;
    private WebSocket webSocket;
    private ScheduledFuture<?> reconnectTask;
    private boolean running;
    private int generation;
    private int failures;
    private String remoteSessionId;
    private boolean capabilitiesChecked;
    private String state = "OFFLINE";
    private String detail = "";
    private final Deque<String> deferredEventFrames = new ArrayDeque<>();
    private boolean eventCommitPending;

    public AgentGatewayClient(
            GatewaySettings settings,
            DeviceCredentialStore credentialStore,
            JSONObject toolManifest,
            Listener listener
    ) {
        this.settings = settings;
        this.credentialStore = credentialStore;
        this.toolManifest = toolManifest;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        GatewaySettings.RemoteSessionState restored = settings.loadRemoteSessionState();
        if (restored != null && shouldResumePersistedSession(restored.activeTurnId)) {
            remoteSessionId = restored.sessionId;
        } else {
            if (restored != null) {
                try {
                    // A persisted active turn is intentionally not resumed. Rotate the durable
                    // create key as well as clearing the session state so session creation cannot
                    // replay the abandoned session and its uncertain turn.
                    settings.rotateSessionCreateIdempotencyKey();
                } catch (IllegalStateException error) {
                    remoteSessionId = null;
                    updateState("DEGRADED", "Could not persist Gateway session identity");
                    return;
                }
            }
            remoteSessionId = null;
        }
        connect(++generation);
    }

    @Override
    public void prepareReload() {
        settings.rotateSessionCreateIdempotencyKey();
    }

    public synchronized void reload() {
        if (!running) return;
        generation++;
        remoteSessionId = null;
        capabilitiesChecked = false;
        cancelReconnect();
        if (webSocket != null) webSocket.cancel();
        webSocket = null;
        resetEventDelivery();
        failures = 0;
        connect(generation);
    }

    public synchronized void shutdown() {
        running = false;
        generation++;
        remoteSessionId = null;
        cancelReconnect();
        if (webSocket != null) webSocket.close(1000, "service stopped");
        webSocket = null;
        resetEventDelivery();
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
            httpClient.connectionPool().evictAll();
        }
        httpClient = null;
            updateState("OFFLINE", "");
        scheduler.shutdownNow();
    }

    public synchronized String getRemoteSessionId() {
        return remoteSessionId;
    }

    public synchronized JSONObject getStatus() {
        return json(
                "state", state,
                "detail", detail,
                "failures", failures,
                "connected", "READY".equals(state),
                "remoteSessionId", remoteSessionId != null ? remoteSessionId : JSONObject.NULL
        );
    }

    public void uploadTurn(
            String expectedSessionId,
            JSONObject input,
            ResultCallback callback
    ) {
        try {
            String clientTurnId = input.optString("clientTurnId", input.optString("turnId", UUID.randomUUID().toString()));
            String text = input.optString("text", "").trim();
            HttpUrl url = expectedSessionUrl(expectedSessionId, "turns");
            if (!text.isEmpty()) {
                JSONObject jsonBody = json(
                        "clientTurnId", clientTurnId,
                        "text", text,
                        "language", input.optString("language", Locale.getDefault().toLanguageTag())
                );
                Request request = authorizedRequest(url)
                        .post(RequestBody.create(jsonBody.toString(), JSON))
                        .header("Idempotency-Key", clientTurnId)
                        .build();
                executeTurnUpload(
                        request,
                        expectedSessionId,
                        clientTurnId,
                        callback
                );
                return;
            }
            String encodedAudio = input.optString("audioBase64", "");
            byte[] audio = Base64.decode(encodedAudio, Base64.DEFAULT);
            int durationMs = input.optInt("durationMs", 0);
            if (audio.length == 0 || audio.length > MAX_INPUT_AUDIO_BYTES) {
                callback.onError("INVALID_AUDIO", "WAV audio must be between 1 byte and 2 MiB");
                return;
            }
            if (durationMs < 1 || durationMs > 30_000) {
                callback.onError("INVALID_DURATION", "durationMs must be between 1 and 30000");
                return;
            }
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("clientTurnId", clientTurnId)
                    .addFormDataPart("durationMs", String.valueOf(durationMs))
                    .addFormDataPart("language", input.optString("language", Locale.getDefault().toLanguageTag()))
                    .addFormDataPart("audio", clientTurnId + ".wav", RequestBody.create(audio, WAV))
                    .build();
            Request request = authorizedRequest(url)
                    .post(body)
                    .header("Idempotency-Key", clientTurnId)
                    .build();
            executeTurnUpload(
                    request,
                    expectedSessionId,
                    clientTurnId,
                    callback
            );
        } catch (Exception error) {
            callback.onError("GATEWAY_UNAVAILABLE", error.getMessage());
        }
    }

    public void cancelTurn(
            String expectedSessionId,
            String turnId,
            String reason,
            ResultCallback callback
    ) {
        if (!isAllowedCancelReason(reason)) {
            callback.onError(
                    "INVALID_CANCEL_REASON",
                    "reason must be client_request, superseded, or timeout"
            );
            return;
        }
        try {
            HttpUrl url = expectedSessionUrl(
                    expectedSessionId,
                    "turns",
                    turnId,
                    "cancel"
            );
            RequestBody body = RequestBody.create(cancelRequestBody(reason).toString(), JSON);
            executeJson(authorizedRequest(url)
                    .post(body)
                    .header("Idempotency-Key", stableIdempotency(
                            "cancel",
                            expectedSessionId,
                            turnId,
                            reason
                    ))
                    .build(), expectedSessionId, callback);
        } catch (Exception error) {
            callback.onError("GATEWAY_UNAVAILABLE", error.getMessage());
        }
    }

    public void reportToolResult(
            String expectedSessionId,
            String callId,
            JSONObject update,
            ResultCallback callback
    ) {
        try {
            HttpUrl url = expectedSessionUrl(
                    expectedSessionId,
                    "tool-calls",
                    callId
            );
            executeJson(
                    authorizedRequest(url)
                            .put(RequestBody.create(update.toString(), JSON))
                            .build(),
                    expectedSessionId,
                    callback
            );
        } catch (Exception error) {
            callback.onError("GATEWAY_UNAVAILABLE", error.getMessage());
        }
    }

    public void reportPlayback(
            String expectedSessionId,
            JSONObject update,
            ResultCallback callback
    ) {
        try {
            HttpUrl url = expectedSessionUrl(
                    expectedSessionId,
                    "playback"
            );
            executeOptionalJson(authorizedRequest(url)
                    .post(RequestBody.create(update.toString(), JSON))
                    .header("Idempotency-Key", stableIdempotency(
                            "playback",
                            expectedSessionId,
                            update.optString("turnId", ""),
                            update.optString("artifactId", ""),
                            update.optString("status", "")
                    ))
                    .build(), expectedSessionId, callback);
        } catch (Exception error) {
            callback.onError("GATEWAY_UNAVAILABLE", error.getMessage());
        }
    }

    public void downloadAudio(
            String expectedSessionId,
            String artifactId,
            JSONObject expected,
            BinaryCallback callback
    ) {
        try {
            String expectedType = expected.getString("mimeType");
            int expectedLength = expected.getInt("byteLength");
            String expectedSha256 = expected.getString("sha256");
            String expectedExpiresAt = expected.getString("expiresAt");
            try {
                AudioArtifactValidator.validateMetadata(
                        expectedType,
                        expectedLength,
                        parseTimestamp(expectedExpiresAt),
                        System.currentTimeMillis()
                );
            } catch (IllegalStateException error) {
                callback.onError("AUDIO_EXPIRED", "Audio artifact has expired");
                return;
            } catch (IllegalArgumentException error) {
                callback.onError("INVALID_AUDIO", error.getMessage());
                return;
            }
            Request request = authorizedRequest(expectedSessionUrl(
                    expectedSessionId,
                    "audio",
                    artifactId
            )).get().build();
            requireHttpClient().newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    callback.onError("DOWNLOAD_FAILED", error.getMessage());
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    try (Response closeable = response) {
                        ResponseBody body = closeable.body();
                        if (!closeable.isSuccessful() || body == null) {
                            callback.onError("DOWNLOAD_FAILED", "Gateway returned HTTP " + closeable.code());
                            return;
                        }
                        long length = body.contentLength();
                        String actualType = body.contentType() != null ? body.contentType().toString().split(";", 2)[0] : "";
                        if (length != expectedLength) {
                            callback.onError("INVALID_AUDIO", "Audio artifact length is invalid");
                            return;
                        }
                        byte[] bytes = body.bytes();
                        try {
                            AudioArtifactValidator.validatePayload(
                                    bytes,
                                    actualType,
                                    expectedType,
                                    expectedLength,
                                    expectedSha256
                            );
                        } catch (IllegalArgumentException error) {
                            callback.onError("INVALID_AUDIO", error.getMessage());
                            return;
                        }
                        callback.onSuccess(
                                bytes,
                                actualType,
                                sha256DigestHeader(bytes),
                                expectedExpiresAt
                        );
                    }
                }
            });
        } catch (Exception error) {
            callback.onError("GATEWAY_UNAVAILABLE", error.getMessage());
        }
    }

    private synchronized void connect(int expectedGeneration) {
        if (!running || expectedGeneration != generation) return;
        if (!settings.isEnabled()) {
            updateState("UNCONFIGURED", "Gateway connection is disabled");
            return;
        }
        String token = credentialStore.load();
        if (token == null || token.isEmpty()) {
            updateState("AUTH_ERROR", "Device credential is missing or unavailable");
            return;
        }
        try {
            apiBaseUrl = apiBaseUrl(settings.getGatewayUrl());
            httpClient = buildClient(apiBaseUrl.host());
        } catch (Exception error) {
            updateState("DEGRADED", error.getMessage());
            return;
        }
        if (!capabilitiesChecked) fetchCapabilities(expectedGeneration);
        else if (remoteSessionId == null) createRemoteSession(expectedGeneration);
        else openEventStream(expectedGeneration);
    }

    private void fetchCapabilities(int expectedGeneration) {
        updateState("CONNECTING", "Validating Gateway capabilities");
        Request request = authorizedRequest(apiBaseUrl.newBuilder().addPathSegment("capabilities").build()).get().build();
        requireHttpClient().newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { handleHttpFailure(expectedGeneration, error, null); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    if (!isGenerationCurrent(expectedGeneration)) return;
                    if (closeable.code() == 401 || closeable.code() == 403) { updateState("AUTH_ERROR", "Gateway rejected the device credential"); return; }
                    if (closeable.code() == 426) { updateState("INCOMPATIBLE", "Gateway protocol version is incompatible"); return; }
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful() || body == null) { handleHttpFailure(expectedGeneration, null, closeable); return; }
                    try {
                        validateCapabilities(new JSONObject(body.string()));
                        synchronized (AgentGatewayClient.this) { capabilitiesChecked = true; }
                        if (getRemoteSessionId() == null) createRemoteSession(expectedGeneration);
                        else openEventStream(expectedGeneration);
                    } catch (Exception error) {
                        updateState("INCOMPATIBLE", "Gateway capabilities response is invalid");
                    }
                }
            }
        });
    }

    private void createRemoteSession(int expectedGeneration) {
        updateState("CONNECTING", "Creating remote session");
        JSONObject client = json(
                "appVersion", BuildConfig.VERSION_NAME,
                "platform", "android",
                "robotModel", "zenbo-k",
                "osVersion", Build.VERSION.RELEASE,
                "locale", Locale.getDefault().toLanguageTag()
        );
        JSONObject requestJson = json(
                "client", client,
                "agentProfile", settings.getAgentProfile(),
                "context", json("robotName", settings.getRobotName(), "language", settings.getLanguage()),
                "toolManifest", toolManifest
        );
        String requestKey;
        String requestFingerprint = sessionCreateFingerprint(requestJson);
        try {
            requestKey = settings.prepareSessionCreate(requestFingerprint);
        } catch (RuntimeException error) {
            updateState("DEGRADED", "Could not persist Gateway session identity");
            return;
        }
        Request request = authorizedRequest(apiBaseUrl.newBuilder().addPathSegment("sessions").build())
                .post(RequestBody.create(requestJson.toString(), JSON))
                .header("Idempotency-Key", requestKey)
                .build();
        requireHttpClient().newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                handleHttpFailure(expectedGeneration, error, null);
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    if (!isGenerationCurrent(expectedGeneration)) return;
                    if (closeable.code() == 401 || closeable.code() == 403) {
                        updateState("AUTH_ERROR", "Gateway rejected the device credential");
                        return;
                    }
                    if (closeable.code() == 426) {
                        updateState("INCOMPATIBLE", "Gateway protocol version is incompatible");
                        return;
                    }
                    if (closeable.code() == 409) {
                        try {
                            if (settings.recoverSessionCreateConflict(
                                    requestKey,
                                    requestFingerprint
                            )) {
                                createRemoteSession(expectedGeneration);
                            } else {
                                updateState(
                                        "DEGRADED",
                                        "Gateway rejected the session create identity"
                                );
                            }
                        } catch (RuntimeException error) {
                            updateState(
                                    "DEGRADED",
                                    "Could not persist Gateway session identity"
                            );
                        }
                        return;
                    }
                    ResponseBody body = closeable.body();
                    if (closeable.code() != 201 || body == null) {
                        handleHttpFailure(expectedGeneration, null, closeable);
                        return;
                    }
                    try {
                        JSONObject session = new JSONObject(body.string());
                        validateSessionResponse(session);
                        String id = session.getString("sessionId");
                        long lastSequence = session.getLong("lastSequence");
                        if (!settings.acceptSessionCreateResponse(
                                requestKey,
                                id,
                                lastSequence
                        )) {
                            return;
                        }
                        synchronized (AgentGatewayClient.this) {
                            if (!isGenerationCurrent(expectedGeneration)) return;
                            remoteSessionId = id;
                        }
                        openEventStream(expectedGeneration);
                    } catch (JSONException error) {
                        handleHttpFailure(expectedGeneration, error, null);
                    } catch (RuntimeException error) {
                        updateState("DEGRADED", "Could not persist Gateway session identity");
                    }
                }
            }
        });
    }

    private synchronized void openEventStream(int expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration) || remoteSessionId == null) return;
        resetEventDelivery();
        HttpUrl.Builder url = sessionUrl("events").newBuilder();
        long cursor = settings.getCursor();
        if (cursor > 0) url.addQueryParameter("after", String.valueOf(cursor));
        Request request = authorizedRequest(url.build()).get().build();
        updateState("CONNECTING", apiBaseUrl.host());
        webSocket = requireHttpClient().newWebSocket(request, new GatewayWebSocketListener(expectedGeneration));
    }

    private final class GatewayWebSocketListener extends WebSocketListener {
        private final int socketGeneration;
        GatewayWebSocketListener(int socketGeneration) { this.socketGeneration = socketGeneration; }

        @Override public void onOpen(WebSocket socket, Response response) {
            if (!isCurrent(socket, socketGeneration)) { socket.close(1000, "superseded"); return; }
            synchronized (AgentGatewayClient.this) { failures = 0; }
            updateState("READY", "Authenticated WSS session established");
        }

        @Override public void onMessage(WebSocket socket, String text) {
            enqueueGatewayFrame(socket, socketGeneration, text);
        }

        @Override public void onClosed(WebSocket socket, int code, String reason) {
            handleDisconnect(socket, socketGeneration, "closed:" + code, null, null);
        }

        @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
            handleDisconnect(socket, socketGeneration, error.getClass().getSimpleName(), error, response);
        }
    }

    private void enqueueGatewayFrame(WebSocket socket, int socketGeneration, String text) {
        synchronized (this) {
            if (!isCurrent(socket, socketGeneration)) return;
            if (eventCommitPending) {
                if (deferredEventFrames.size() >= MAX_DEFERRED_EVENT_FRAMES) {
                    resetEventDelivery();
                    socket.close(1011, "event handoff backlog");
                } else {
                    deferredEventFrames.addLast(text);
                }
                return;
            }
            eventCommitPending = true;
        }
        processGatewayFrame(socket, socketGeneration, text);
    }

    private void processGatewayFrame(WebSocket socket, int socketGeneration, String text) {
        try {
            JSONObject message = new JSONObject(text);
            validateEnvelope(message);
            AtomicBoolean completed = new AtomicBoolean();
            listener.onGatewayMessage(message, new EventCommitCallback() {
                @Override public void commit() {
                    if (completed.compareAndSet(false, true)) {
                        completeGatewayFrame(socket, socketGeneration, message);
                    }
                }

                @Override public void retry() {
                    if (completed.compareAndSet(false, true)) {
                        retryGatewayFrame(socket, socketGeneration);
                    }
                }
            });
        } catch (Exception error) {
            Log.w(TAG, "Gateway protocol frame rejected: " + error.getClass().getSimpleName());
            retryGatewayFrame(socket, socketGeneration);
        }
    }

    private void completeGatewayFrame(WebSocket socket, int socketGeneration, JSONObject message) {
        String next = null;
        boolean terminal = false;
        try {
            synchronized (this) {
                if (!isCurrent(socket, socketGeneration) || !eventCommitPending) return;
                String type = message.getString("type");
                if ("session.snapshot".equals(type)) {
                    JSONObject snapshot = message.getJSONObject("data");
                    settings.replaceRemoteSessionSnapshot(
                            remoteSessionId,
                            snapshot.getLong("lastSequence"),
                            snapshot.isNull("activeTurnId")
                                    ? ""
                                    : snapshot.optString("activeTurnId", "")
                    );
                } else if (!"session.ready".equals(type)) {
                    settings.commitRemoteEvent(
                            remoteSessionId,
                            message.getLong("sequence"),
                            type,
                            message.isNull("turnId") ? "" : message.optString("turnId", "")
                    );
                }
                terminal = "session.expired".equals(type) || "session.closed".equals(type);
                eventCommitPending = false;
                if (!terminal && !deferredEventFrames.isEmpty()) {
                    next = deferredEventFrames.removeFirst();
                    eventCommitPending = true;
                } else if (terminal) {
                    deferredEventFrames.clear();
                }
            }
            if (terminal) {
                handleTerminalSessionEvent(socket, socketGeneration, message);
            } else if (next != null) {
                processGatewayFrame(socket, socketGeneration, next);
            }
        } catch (Exception error) {
            retryGatewayFrame(socket, socketGeneration);
        }
    }

    private void retryGatewayFrame(WebSocket socket, int socketGeneration) {
        synchronized (this) {
            if (!isCurrent(socket, socketGeneration)) return;
            resetEventDelivery();
        }
        socket.close(1011, "event handoff retry");
    }

    private void resetEventDelivery() {
        eventCommitPending = false;
        deferredEventFrames.clear();
    }

    private void validateEnvelope(JSONObject message) throws JSONException {
        if (message.length() != 8) throw new JSONException("unexpected envelope fields");
        if (!PROTOCOL_VERSION.equals(message.getString("protocolVersion"))) throw new JSONException("protocol mismatch");
        if (!remoteSessionId.equals(message.getString("sessionId"))) throw new JSONException("session mismatch");
        UUID.fromString(message.getString("eventId"));
        try { parseTimestamp(message.getString("timestamp")); }
        catch (Exception error) { throw new JSONException("timestamp is invalid"); }
        JSONObject data = message.getJSONObject("data");
        String type = message.getString("type");
        if (!EVENT_TYPES.contains(type)) throw new JSONException("unknown event type");
        validateEventData(type, data);
        boolean sessionScoped = type.startsWith("session.");
        if (sessionScoped != message.isNull("turnId")) throw new JSONException("turnId scope mismatch");
        if (!sessionScoped) UUID.fromString(message.getString("turnId"));
        long sequence = message.getLong("sequence");
        if ("session.ready".equals(type)) {
            if (sequence != settings.getCursor()) throw new JSONException("ready cursor mismatch");
            if (data.getLong("resumedAfter") != sequence) throw new JSONException("ready resumedAfter mismatch");
        } else if ("session.snapshot".equals(type)) {
            if (sequence != message.getJSONObject("data").getLong("lastSequence")) throw new JSONException("snapshot cursor mismatch");
        } else if (sequence != settings.getCursor() + 1L) {
            throw new JSONException("event sequence gap");
        }
    }

    private static void validateEventData(String type, JSONObject data) throws JSONException {
        switch (type) {
            case "session.ready":
                requireKeys(data, new String[]{"resumedAfter", "gatewayTime"}, new String[0]);
                if (data.getLong("resumedAfter") < 0L) throw new JSONException("resumedAfter is invalid");
                requireTimestamp(data.getString("gatewayTime"));
                return;
            case "session.snapshot":
                requireKeys(data, new String[]{"state", "lastSequence"}, new String[]{"activeTurnId"});
                if (!("active".equals(data.getString("state")) || "closing".equals(data.getString("state")))) {
                    throw new JSONException("snapshot state is invalid");
                }
                if (data.getLong("lastSequence") < 0L) throw new JSONException("snapshot sequence is invalid");
                if (data.has("activeTurnId") && !data.isNull("activeTurnId")) UUID.fromString(data.getString("activeTurnId"));
                return;
            case "turn.accepted":
            case "agent.thinking":
            case "turn.completed":
                requireKeys(data, new String[0], new String[0]);
                return;
            case "stt.final":
                requireKeys(data, new String[]{"text", "language"}, new String[0]);
                requireLength(data.getString("text"), 1, 16_000, "transcript");
                requireLength(data.getString("language"), 2, 35, "language");
                return;
            case "agent.text.final":
                requireKeys(data, new String[]{"text"}, new String[0]);
                requireLength(data.getString("text"), 1, 16_000, "agent text");
                return;
            case "tts.ready":
                requireKeys(data, new String[]{"artifactId", "mimeType", "byteLength", "sha256", "expiresAt"}, new String[0]);
                UUID.fromString(data.getString("artifactId"));
                String mimeType = data.getString("mimeType");
                if (!("audio/mpeg".equals(mimeType) || "audio/wav".equals(mimeType))) throw new JSONException("audio MIME is invalid");
                int byteLength = data.getInt("byteLength");
                if (byteLength < 1 || byteLength > MAX_OUTPUT_AUDIO_BYTES) throw new JSONException("audio length is invalid");
                if (!data.getString("sha256").matches("[a-f0-9]{64}")) throw new JSONException("audio digest is invalid");
                requireTimestamp(data.getString("expiresAt"));
                return;
            case "tool.call":
                requireKeys(data, new String[]{"callId", "toolName", "toolVersion", "arguments", "timeoutMs", "deadlineAt"}, new String[0]);
                UUID.fromString(data.getString("callId"));
                if (!TOOL_NAMES.contains(data.getString("toolName"))) throw new JSONException("tool name is invalid");
                if (!data.getString("toolVersion").matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new JSONException("tool version is invalid");
                data.getJSONObject("arguments");
                int timeoutMs = data.getInt("timeoutMs");
                if (timeoutMs < 100 || timeoutMs > 15_000) throw new JSONException("tool timeout is invalid");
                requireTimestamp(data.getString("deadlineAt"));
                return;
            case "turn.error":
                requireKeys(data, new String[]{"error"}, new String[0]);
                validateError(data.getJSONObject("error"));
                return;
            case "turn.cancelled":
                requireKeys(data, new String[]{"reason"}, new String[0]);
                String cancelReason = data.getString("reason");
                if (!("client_request".equals(cancelReason) || "superseded".equals(cancelReason) || "timeout".equals(cancelReason))) {
                    throw new JSONException("cancel reason is invalid");
                }
                return;
            case "session.expired":
                requireKeys(data, new String[]{"reason", "expiredAt"}, new String[0]);
                String expiryReason = data.getString("reason");
                if (!("idle_timeout".equals(expiryReason) || "credential_revoked".equals(expiryReason) || "server_policy".equals(expiryReason))) {
                    throw new JSONException("expiry reason is invalid");
                }
                requireTimestamp(data.getString("expiredAt"));
                return;
            case "session.closed":
                requireKeys(data, new String[]{"reason"}, new String[0]);
                String closeReason = data.getString("reason");
                if (!("client_request".equals(closeReason) || "expired".equals(closeReason)
                        || "replaced".equals(closeReason) || "policy".equals(closeReason))) {
                    throw new JSONException("close reason is invalid");
                }
                return;
            default:
                throw new JSONException("unknown event type");
        }
    }

    private static void validateError(JSONObject error) throws JSONException {
        requireKeys(error, new String[]{"code", "message", "retryable"}, new String[0]);
        if (!error.getString("code").matches("[A-Z][A-Z0-9_]{1,63}")) throw new JSONException("error code is invalid");
        requireLength(error.getString("message"), 1, 512, "error message");
        if (!(error.get("retryable") instanceof Boolean)) throw new JSONException("retryable is invalid");
    }

    private static void requireKeys(JSONObject data, String[] required, String[] optional) throws JSONException {
        Set<String> allowed = new HashSet<>();
        allowed.addAll(Arrays.asList(required));
        allowed.addAll(Arrays.asList(optional));
        for (String key : required) if (!data.has(key)) throw new JSONException(key + " is required");
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new JSONException("unexpected event data field");
    }

    static void requireLength(String value, int minimum, int maximum, String name) throws JSONException {
        int length = ProtocolStrings.length(value);
        if (length < minimum || length > maximum) throw new JSONException(name + " length is invalid");
    }

    private static void requireTimestamp(String value) throws JSONException {
        try { parseTimestamp(value); }
        catch (Exception error) { throw new JSONException("timestamp is invalid"); }
    }

    private synchronized void handleDisconnect(WebSocket socket, int socketGeneration, String reason, Throwable error, Response response) {
        if (!isCurrent(socket, socketGeneration)) return;
        webSocket = null;
        resetEventDelivery();
        if (response != null && (response.code() == 401 || response.code() == 403)) {
            updateState("AUTH_ERROR", "Gateway rejected the device credential");
            return;
        }
        if (response != null && shouldResetRemoteSession(response.code())) {
            if (!resetRemoteSessionForRecovery()) return;
        }
        if (response != null && response.code() == 426) { updateState("INCOMPATIBLE", "Gateway protocol version is incompatible"); return; }
        if (error instanceof SSLPeerUnverifiedException || error instanceof SSLHandshakeException) {
            updateState("TLS_ERROR", "TLS identity verification failed; check certificate pin and device clock");
            return;
        }
        scheduleReconnect(socketGeneration, reason);
    }

    private synchronized boolean resetRemoteSessionForRecovery() {
        try {
            settings.rotateSessionCreateIdempotencyKey();
        } catch (IllegalStateException error) {
            remoteSessionId = null;
            updateState("DEGRADED", "Could not persist Gateway session identity");
            return false;
        }
        remoteSessionId = null;
        return true;
    }

    private void handleTerminalSessionEvent(
            WebSocket socket,
            int socketGeneration,
            JSONObject message
    ) throws JSONException {
        String type = message.getString("type");
        String reason = message.getJSONObject("data").getString("reason");
        synchronized (this) {
            if (!isCurrent(socket, socketGeneration)) return;
            remoteSessionId = null;
            webSocket = null;
        }
        socket.close(1000, type);
        if (shouldRecreateSession(type, reason)) {
            try {
                settings.rotateSessionCreateIdempotencyKey();
            } catch (IllegalStateException error) {
                updateState("DEGRADED", "Could not persist Gateway session identity");
                return;
            }
            scheduleReconnect(socketGeneration, type + ":" + reason);
        } else {
            try {
                settings.clearRemoteSessionState();
            } catch (IllegalStateException error) {
                updateState("DEGRADED", "Could not persist Gateway session identity");
                return;
            }
            updateState("AUTH_ERROR", "Gateway revoked the device credential");
        }
    }

    private synchronized void handleHttpFailure(int expectedGeneration, Throwable error, Response response) {
        if (!isGenerationCurrent(expectedGeneration)) return;
        if (error instanceof SSLPeerUnverifiedException || error instanceof SSLHandshakeException) {
            updateState("TLS_ERROR", "TLS identity verification failed; check certificate pin and device clock");
            return;
        }
        scheduleReconnect(expectedGeneration, response != null ? "http:" + response.code() : error != null ? error.getClass().getSimpleName() : "request_failed");
    }

    private synchronized void scheduleReconnect(int expectedGeneration, String reason) {
        failures++;
        updateState("DEGRADED", reason);
        long base = RECONNECT_DELAYS[Math.min(failures - 1, RECONNECT_DELAYS.length - 1)];
        long delay = Math.min(15_000L, base + nonNegativeModulo(random.nextLong(), Math.max(1L, base / 4L)));
        reconnectTask = scheduler.schedule(() -> connect(expectedGeneration), delay, TimeUnit.MILLISECONDS);
    }

    private void executeTurnUpload(
            Request request,
            String expectedSessionId,
            String clientTurnId,
            ResultCallback callback
    ) {
        settings.markRemoteTurnInFlight(expectedSessionId, clientTurnId);
        try {
            executeJson(request, expectedSessionId, new ResultCallback() {
                @Override public void onSuccess(JSONObject result) {
                    String remoteTurnId = result.optString("turnId", "");
                    try {
                        UUID.fromString(remoteTurnId);
                        if (!expectedSessionId.equals(result.optString("sessionId", ""))
                                || !clientTurnId.equals(result.optString("clientTurnId", ""))
                                || !settings.replaceRemoteTurnMarker(
                                        expectedSessionId,
                                        clientTurnId,
                                        remoteTurnId
                                )) {
                            throw new IllegalStateException(
                                    "Turn response does not match the pending upload"
                            );
                        }
                        callback.onSuccess(result);
                    } catch (Exception error) {
                        abandonUncertainRemoteSession(expectedSessionId);
                        callback.onError(
                                "GATEWAY_OFFLINE",
                                "Gateway returned an invalid turn response"
                        );
                    }
                }

                @Override public void onError(String code, String message) {
                    // A failed response cannot prove the Gateway did not accept the request.
                    // Abandon the entire session so the unknown turn cannot surface or replay.
                    abandonUncertainRemoteSession(expectedSessionId);
                    callback.onError(code, message);
                }
            });
        } catch (RuntimeException error) {
            // The request was not handed to OkHttp, so this marker is safe to clear.
            settings.clearRemoteTurnMarker(expectedSessionId, clientTurnId);
            throw error;
        }
    }

    private void abandonUncertainRemoteSession(String expectedSessionId) {
        WebSocket abandonedSocket;
        int reconnectGeneration;
        boolean canReconnect;
        synchronized (this) {
            if (!running
                    || !isSameRemoteSession(expectedSessionId, remoteSessionId)) {
                return;
            }
            try {
                settings.rotateSessionCreateIdempotencyKey();
                canReconnect = true;
            } catch (IllegalStateException error) {
                canReconnect = false;
            }
            generation++;
            reconnectGeneration = generation;
            remoteSessionId = null;
            cancelReconnect();
            abandonedSocket = webSocket;
            webSocket = null;
            resetEventDelivery();
        }
        if (abandonedSocket != null) abandonedSocket.cancel();
        if (!canReconnect) {
            updateState("DEGRADED", "Could not persist Gateway session identity");
            return;
        }
        connect(reconnectGeneration);
    }

    static boolean isSameRemoteSession(String expectedSessionId, String activeSessionId) {
        return expectedSessionId != null && expectedSessionId.equals(activeSessionId);
    }

    private void executeJson(
            Request request,
            String expectedSessionId,
            ResultCallback callback
    ) {
        executeJsonResponse(request, expectedSessionId, callback, false);
    }

    private void executeOptionalJson(
            Request request,
            String expectedSessionId,
            ResultCallback callback
    ) {
        executeJsonResponse(request, expectedSessionId, callback, true);
    }

    private void executeJsonResponse(
            Request request,
            String expectedSessionId,
            ResultCallback callback,
            boolean allowEmptySuccess
    ) {
        final int requestGeneration;
        synchronized (this) {
            requestGeneration = generation;
        }
        requireHttpClient().newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                if (error instanceof SSLPeerUnverifiedException || error instanceof SSLHandshakeException) {
                    updateStateForRequest(
                            requestGeneration,
                            expectedSessionId,
                            "TLS_ERROR",
                            "TLS identity verification failed"
                    );
                    callback.onError("GATEWAY_TLS", "Gateway TLS verification failed");
                } else {
                    updateStateForRequest(
                            requestGeneration,
                            expectedSessionId,
                            "DEGRADED",
                            "Gateway request failed"
                    );
                    callback.onError("GATEWAY_OFFLINE", "Gateway request failed");
                }
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    ResponseBody body = closeable.body();
                    if (!closeable.isSuccessful()) {
                        if (closeable.code() == 401 || closeable.code() == 403) {
                            updateStateForRequest(
                                    requestGeneration,
                                    expectedSessionId,
                                    "AUTH_ERROR",
                                    "Gateway rejected the device credential"
                            );
                            callback.onError(gatewayErrorForHttpStatus(closeable.code()), "Gateway rejected the device credential");
                        } else if (closeable.code() == 426) {
                            updateStateForRequest(
                                    requestGeneration,
                                    expectedSessionId,
                                    "INCOMPATIBLE",
                                    "Gateway protocol version is incompatible"
                            );
                            callback.onError(gatewayErrorForHttpStatus(closeable.code()), "Gateway protocol version is incompatible");
                        } else if (closeable.code() == 409) {
                            callback.onError(
                                    "CONFLICT",
                                    safeGatewayFailureDetail(closeable.code(), null)
                            );
                        } else {
                            updateStateForRequest(
                                    requestGeneration,
                                    expectedSessionId,
                                    "DEGRADED",
                                    "Gateway returned HTTP " + closeable.code()
                            );
                            callback.onError("GATEWAY_OFFLINE", safeGatewayFailureDetail(closeable.code(), null));
                        }
                        return;
                    }
                    String text = body != null ? body.string() : "";
                    try { callback.onSuccess(parseSuccessBody(text, allowEmptySuccess)); }
                    catch (JSONException error) {
                        callback.onError("GATEWAY_OFFLINE", "Gateway returned an invalid JSON response");
                    }
                }
            }
        });
    }

    private synchronized void updateStateForRequest(
            int requestGeneration,
            String expectedSessionId,
            String newState,
            String newDetail
    ) {
        if (!requestContextIsCurrent(
                requestGeneration,
                generation,
                expectedSessionId,
                remoteSessionId
        )) {
            return;
        }
        updateState(newState, newDetail);
    }

    static boolean requestContextIsCurrent(
            int requestGeneration,
            int currentGeneration,
            String expectedSessionId,
            String currentSessionId
    ) {
        return requestGeneration == currentGeneration
                && expectedSessionId != null
                && expectedSessionId.equals(currentSessionId);
    }

    static JSONObject parseSuccessBody(String body, boolean allowEmpty) throws JSONException {
        String text = body == null ? "" : body.trim();
        if (allowEmpty && text.isEmpty()) return new JSONObject();
        return new JSONObject(text);
    }

    private synchronized Request.Builder authorizedRequest(HttpUrl url) {
        String token = credentialStore.load();
        if (token == null || token.isEmpty()) throw new IllegalStateException("Device credential is unavailable");
        return authorizedRequest(url, token, settings.getDeviceId());
    }

    static Request.Builder authorizedRequest(HttpUrl url, String token, String deviceId) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("X-Zenbo-Device-Id", deviceId)
                .header("X-Zenbo-Protocol", PROTOCOL_VERSION);
    }

    private synchronized HttpUrl sessionUrl(String... segments) {
        if (apiBaseUrl == null || remoteSessionId == null) throw new IllegalStateException("Remote session is unavailable");
        HttpUrl.Builder builder = apiBaseUrl.newBuilder().addPathSegment("sessions").addPathSegment(remoteSessionId);
        for (String segment : segments) builder.addPathSegment(segment);
        return builder.build();
    }

    private synchronized HttpUrl expectedSessionUrl(
            String expectedSessionId,
            String... segments
    ) {
        if (expectedSessionId == null
                || !expectedSessionId.equals(remoteSessionId)) {
            throw new IllegalStateException("Remote session changed");
        }
        return sessionUrl(segments);
    }

    private synchronized OkHttpClient requireHttpClient() {
        if (httpClient == null) throw new IllegalStateException("Gateway client is not configured");
        return httpClient;
    }

    private OkHttpClient buildClient(String host) {
        String pin = settings.getCertificatePin();
        OkHttpClient.Builder builder;
        if (GatewaySettings.CONFIRMED_SPKI_PIN.equals(settings.getTrustMode())) {
            if (pin == null || pin.isEmpty()) throw new IllegalStateException("Confirmed SPKI pin is missing");
            builder = TlsTrust.pinnedBuilder(host, pin);
        } else {
            builder = new OkHttpClient.Builder();
        }
        builder
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        return builder.build();
    }

    private synchronized boolean isCurrent(WebSocket socket, int expectedGeneration) {
        return isGenerationCurrent(expectedGeneration) && webSocket == socket;
    }

    private synchronized boolean isGenerationCurrent(int expectedGeneration) {
        return running && generation == expectedGeneration;
    }

    private synchronized void cancelReconnect() {
        if (reconnectTask != null) reconnectTask.cancel(false);
        reconnectTask = null;
    }

    private synchronized void updateState(String newState, String newDetail) {
        state = newState;
        detail = newDetail != null ? newDetail : "";
        listener.onGatewayStateChanged(state, detail);
    }

    static HttpUrl apiBaseUrl(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.trim().isEmpty()) throw new IllegalArgumentException("Gateway URL is not configured");
        URI uri = URI.create(configuredUrl.trim());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Gateway URL must use HTTPS or WSS");
        }
        String normalized = configuredUrl.trim().replaceFirst("(?i)^wss://", "https://");
        HttpUrl parsed = HttpUrl.parse(normalized);
        if (parsed == null) throw new IllegalArgumentException("Gateway URL is invalid");
        HttpUrl.Builder builder = parsed.newBuilder().query(null).fragment(null);
        if ("/".equals(parsed.encodedPath())) builder.addPathSegments("agent/v1");
        return builder.build();
    }

    private static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index + 1 < pairs.length; index += 2) result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    static String stableIdempotency(String operation, String remoteSessionId, String... values) {
        StringBuilder material = new StringBuilder(operation)
                .append('\n')
                .append(remoteSessionId != null ? remoteSessionId : "");
        for (String value : values) material.append('\n').append(value != null ? value : "");
        return UUID.nameUUIDFromBytes(material.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String sessionCreateFingerprint(JSONObject request) {
        try {
            return sha256(canonicalJson(request).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IllegalArgumentException("Session create request is invalid", error);
        }
    }

    private static String canonicalJson(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) result.append(',');
                String key = keys.get(index);
                result.append(JSONObject.quote(key))
                        .append(':')
                        .append(canonicalJson(object.get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) result.append(',');
                result.append(canonicalJson(array.get(index)));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        return JSONObject.quote(String.valueOf(value));
    }

    static String gatewayErrorForHttpStatus(int status) {
        if (status == 401 || status == 403) return "GATEWAY_AUTH";
        if (status == 426) return "GATEWAY_INCOMPATIBLE";
        return "GATEWAY_OFFLINE";
    }

    static String safeGatewayFailureDetail(int status, String ignoredRemoteBody) {
        return "Gateway request failed with HTTP " + status;
    }

    static long nonNegativeModulo(long value, long divisor) {
        if (divisor <= 0L) throw new IllegalArgumentException("divisor must be positive");
        long remainder = value % divisor;
        return remainder < 0L ? remainder + divisor : remainder;
    }

    static boolean shouldRecreateSession(String type, String reason) {
        return ("session.expired".equals(type) || "session.closed".equals(type))
                && !("session.expired".equals(type) && "credential_revoked".equals(reason));
    }

    static boolean shouldResetRemoteSession(int websocketHttpStatus) {
        return websocketHttpStatus == 404 || websocketHttpStatus == 409;
    }

    static boolean shouldResumePersistedSession(String activeTurnId) {
        return activeTurnId == null || activeTurnId.isEmpty();
    }

    static boolean isAllowedCancelReason(String reason) {
        return "client_request".equals(reason)
                || "superseded".equals(reason)
                || "timeout".equals(reason);
    }

    static JSONObject cancelRequestBody(String reason) {
        if (!isAllowedCancelReason(reason)) {
            throw new IllegalArgumentException("Cancel reason is not allowed");
        }
        return json("reason", reason);
    }

    private static final Set<String> EVENT_TYPES = new HashSet<>(Arrays.asList(
            "session.ready", "session.snapshot", "turn.accepted", "stt.final", "agent.thinking",
            "tool.call", "agent.text.final", "tts.ready", "turn.completed", "turn.error",
            "session.expired", "turn.cancelled", "session.closed"
    ));
    private static final Set<String> TOOL_NAMES = new HashSet<>(Arrays.asList(
            "get_system_status", "start_robot_following", "stop_robot_following",
            "look_at_user", "show_emotion", "go_to_sleep"
    ));

    private void validateCapabilities(JSONObject capabilities) throws JSONException {
        if (!PROTOCOL_VERSION.equals(capabilities.getString("protocolVersion"))) throw new JSONException("Protocol 1.0 is required");
        JSONObject input = capabilities.getJSONObject("audioInput");
        if (input.getInt("maxBytes") != MAX_INPUT_AUDIO_BYTES || input.getInt("maxDurationMs") != 30_000
                || !"audio/wav".equals(input.getJSONArray("contentTypes").getString(0))) {
            throw new JSONException("Gateway WAV input limits are incompatible");
        }
        JSONObject output = capabilities.getJSONObject("audioOutput");
        if (output.getInt("maxBytes") != MAX_OUTPUT_AUDIO_BYTES) throw new JSONException("Gateway audio output limit is incompatible");
        JSONArray outputTypes = output.getJSONArray("contentTypes");
        boolean supportedOutput = false;
        for (int index = 0; index < outputTypes.length(); index++) {
            String value = outputTypes.getString(index);
            if ("audio/mpeg".equals(value) || "audio/wav".equals(value)) supportedOutput = true;
        }
        if (!supportedOutput) throw new JSONException("Gateway audio output type is incompatible");
        JSONArray advertisedEvents = capabilities.getJSONArray("eventTypes");
        Set<String> advertisedEventSet = new HashSet<>();
        for (int index = 0; index < advertisedEvents.length(); index++) {
            advertisedEventSet.add(advertisedEvents.getString(index));
        }
        if (!advertisedEventSet.containsAll(EVENT_TYPES)) throw new JSONException("Gateway event surface is incomplete");
        JSONArray profiles = capabilities.getJSONArray("agentProfiles");
        boolean profileFound = false;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            if (settings.getAgentProfile().equals(profile.getString("id"))) {
                JSONArray languages = profile.getJSONArray("languages");
                for (int languageIndex = 0; languageIndex < languages.length(); languageIndex++) {
                    if (settings.getLanguage().equalsIgnoreCase(languages.getString(languageIndex))) profileFound = true;
                }
            }
        }
        if (!profileFound) throw new JSONException("Configured agent profile or language is unavailable");
        JSONObject rawAudioRetention = capabilities.getJSONObject("retentionPolicy").getJSONObject("rawAudio");
        if (rawAudioRetention.getBoolean("retained") || rawAudioRetention.getInt("maxAgeSeconds") != 0) {
            throw new JSONException("Gateway raw-audio retention policy is incompatible");
        }
        capabilities.getJSONObject("retentionPolicy").getJSONObject("transcript");
    }

    private void validateSessionResponse(JSONObject session) throws JSONException {
        requireKeys(session, new String[]{
                "sessionId", "deviceId", "protocolVersion", "state",
                "createdAt", "expiresAt", "lastSequence"
        }, new String[0]);
        UUID.fromString(session.getString("sessionId"));
        if (!settings.getDeviceId().equals(session.getString("deviceId"))) {
            throw new JSONException("Session device identity does not match");
        }
        if (!PROTOCOL_VERSION.equals(session.getString("protocolVersion"))) {
            throw new JSONException("Session protocol is incompatible");
        }
        if (!"active".equals(session.getString("state"))) {
            throw new JSONException("Created session is not active");
        }
        requireTimestamp(session.getString("createdAt"));
        requireTimestamp(session.getString("expiresAt"));
        if (session.getLong("lastSequence") < 0L) {
            throw new JSONException("Session cursor is invalid");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String sha256DigestHeader(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha-256=" + Base64.encodeToString(digest, Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static long parseTimestamp(String value) throws ParseException {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        ParseException last = null;
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = format.parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException error) {
                last = error;
            }
        }
        throw last != null ? last : new ParseException("Invalid timestamp", 0);
    }
}
