package com.robot.asus.kira;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.http.Multimap;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.body.AsyncHttpRequestBody;
import com.koushikdutta.async.http.body.MultipartFormDataBody;
import com.koushikdutta.async.http.body.JSONObjectBody;
import com.koushikdutta.async.http.body.Part;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.LoopbackAsyncHttpServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** One process-local HTTP/WebSocket relay for GeckoView and the native runtime. */
public final class LocalRuntimeServer {
    private static final String TAG = "LocalRuntimeServer";
    private static final long BOOTSTRAP_TTL_MILLIS = 60_000L;
    private static final long SESSION_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int SESSION_MAX_AGE_SECONDS = 24 * 60 * 60;
    private static final String SESSION_COOKIE = "zenbo_local_session";
    private static final String RENDERER_ORIGIN = "http://127.0.0.1:8787";

    private final Context context;
    private final GatewaySettings settings;
    private final DeviceCredentialStore credentialStore;
    private final RemoteSessionCoordinator coordinator;
    private final RobotGateway robotGateway;
    private final DeviceHardware deviceHardware;
    private final LanRemoteServer lanRemote;
    private final LoopbackAsyncHttpServer server = new LoopbackAsyncHttpServer();
    private final Set<WebSocket> clients = Collections.synchronizedSet(new HashSet<>());
    private final LinkedHashMap<String, JSONObject> completedOperations = new LinkedHashMap<>();
    private final Object newSessionLock = new Object();
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile boolean started;
    private volatile String rendererToken;
    private volatile long rendererTokenExpiresAt;
    private volatile String bootstrapSecret;
    private volatile long bootstrapSecretExpiresAt;
    private int failedBootstrapAttempts;
    private long nextBootstrapAttemptAt;

    public LocalRuntimeServer(
            Context context,
            GatewaySettings settings,
            DeviceCredentialStore credentialStore,
            RemoteSessionCoordinator coordinator,
            RobotGateway robotGateway
    ) {
        this(context, settings, credentialStore, coordinator, robotGateway, null);
    }

    public LocalRuntimeServer(Context context, GatewaySettings settings,
            DeviceCredentialStore credentialStore, RemoteSessionCoordinator coordinator,
            RobotGateway robotGateway, DeviceHardware deviceHardware) {
        this.context = context.getApplicationContext();
        this.deviceHardware = deviceHardware;
        this.lanRemote = deviceHardware == null ? null : new LanRemoteServer(this.context, deviceHardware, coordinator);
        this.settings = settings;
        this.credentialStore = credentialStore;
        this.coordinator = coordinator;
        this.robotGateway = robotGateway;
    }

    public synchronized void start(int port) throws java.io.IOException {
        if (started) return;
        registerRoutes();
        server.listenLoopback(port);
        started = true;
        Log.i(TAG, "Local runtime started on http://127.0.0.1:" + port);
    }

    public synchronized void stop() {
        if (lanRemote != null) lanRemote.close();
        if (!started) return;
        server.stop();
        WebSocket[] closing;
        synchronized (clients) {
            closing = clients.toArray(new WebSocket[0]);
            clients.clear();
        }
        // close() can invoke callbacks synchronously; never acquire coordinator under clients.
        for (WebSocket client : closing) client.close();
        rendererToken = null;
        rendererTokenExpiresAt = 0L;
        bootstrapSecret = null;
        bootstrapSecretExpiresAt = 0L;
        started = false;
    }

    public boolean isStarted() {
        return started;
    }

    public synchronized String issueBootstrapSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        bootstrapSecret = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        bootstrapSecretExpiresAt = System.currentTimeMillis() + BOOTSTRAP_TTL_MILLIS;
        return bootstrapSecret;
    }

    public void publish(JSONObject event) {
        String text = event.toString();
        WebSocket[] recipients;
        synchronized (clients) { recipients = clients.toArray(new WebSocket[0]); }
        for (WebSocket client : recipients) {
            try {
                client.send(text);
            } catch (Exception error) {
                Log.w(TAG, "Could not publish local runtime event", error);
            }
        }
    }

    private void registerRoutes() {
        server.get("/$", (request, response) -> {
            if (!requireLoopback(request, response)) return;
            try {
                sendAppAsset("app/index.html", "text/html; charset=utf-8", response);
            } catch (Exception error) {
                sendError(response, 503, "INTERNAL_ERROR", "Web assets are not installed");
            }
        });

        server.get("/health$", (request, response) -> {
            if (!requireLoopback(request, response)) return;
            sendJson(response, 200, json(
                    "status", "ok",
                    "runtimeReady", started,
                    "robotReady", robotGateway.isReady()
            ));
        });

        registerDeviceRoutes();
        registerSessionRoute("/api/v2/bootstrap$");
        registerStatusRoute("/api/v2/status$");
        registerConversationRoute("/api/v2/conversation$");
        registerMultipartTurnRoute("/api/v2/conversation/turns$");
        server.addAction("POST", "/api/v2/conversation/new-session$", (request, response) -> {
            if (!requireSession(request, response)) return;
            String operationKey = requireIdempotencyKey(request, response, "new-session");
            if (operationKey == null) return;
            // Cover both cache lookup and acceptance, including simultaneous retries of one operation.
            synchronized (newSessionLock) {
                if (sendCachedOperation(operationKey, response, 202)) return;
                try {
                    if (request.getBody() == null || !(request.getBody().get() instanceof JSONObject)) {
                        throw new JSONException("Expected an empty JSON object");
                    }
                    requireOnlyKeys(readJson(request));
                    JSONObject accepted = coordinator.startNewSession();
                    cacheOperation(operationKey, accepted);
                    sendJson(response, 202, accepted);
                } catch (RemoteSessionCoordinator.GatewayUnavailableException error) {
                    sendError(response, 503, "GATEWAY_OFFLINE", "裝置正在重新連線，請稍後再開啟新對話。");
                } catch (IllegalStateException error) {
                    sendError(response, 500, "INTERNAL_ERROR", "Could not save the new conversation state");
                } catch (JSONException error) {
                    boolean busy = "TURN_BUSY".equals(error.getMessage());
                    sendError(response, busy ? 409 : 400, busy ? "TURN_BUSY" : "INVALID_REQUEST",
                            busy ? "The current conversation is still busy" : "Expected an empty JSON object");
                }
            }
        }, headers -> new JSONObjectBody());

        server.addAction("PUT", "/api/v2/motion$", (request, response) -> {
            if (!requireSession(request, response)) return;
            JSONObject body = readJson(request);
            try {
                requireOnlyKeys(body, "enabled");
                if (!(body.opt("enabled") instanceof Boolean)) {
                    throw new IllegalArgumentException("enabled must be a boolean");
                }
                sendJson(response, 200, coordinator.setMotionEnabled(body.getBoolean("enabled")));
            } catch (IllegalStateException error) {
                sendError(response, 500, "INTERNAL_ERROR", "Could not save motion preference");
            } catch (Exception error) {
                sendError(response, 400, "INVALID_REQUEST", "enabled must be the only field and a boolean");
            }
        }, headers -> new JSONObjectBody());

        server.get("/api/v2/settings$", (request, response) -> {
            if (!requireSession(request, response)) return;
            sendJson(response, 200, settingsJson());
        });
        server.addAction("PUT", "/api/v2/settings$", this::updateSettings, headers -> new JSONObjectBody());
        server.post("/api/v2/settings/setup$", this::setupSettings);

        server.post("/api/v2/settings/test$", (request, response) -> {
            if (!requireSession(request, response)) return;
            JSONObject body = readJson(request);
            String gatewayUrl = body.optString("gatewayUrl", "").trim();
            String trustMode = body.optString("trustMode", "");
            try {
                requireOnlyKeys(body, "gatewayUrl", "trustMode", "apiKey");
                GatewaySettings.validateGatewayUrl(gatewayUrl);
                if (!(GatewaySettings.SYSTEM_TRUST.equals(trustMode)
                        || GatewaySettings.CONFIRMED_SPKI_PIN.equals(trustMode))) {
                    throw new IllegalArgumentException("trustMode is invalid");
                }
                if (body.has("apiKey")) {
                    String suppliedToken = body.optString("apiKey", "");
                    if (suppliedToken.length() < 16 || suppliedToken.length() > 4096) {
                        throw new IllegalArgumentException("apiKey must contain 16 to 4096 characters");
                    }
                }
            } catch (Exception error) {
                sendError(response, 400, "INVALID_REQUEST", error.getMessage());
                return;
            }

            boolean savedPinConfirmed = GatewaySettings.CONFIRMED_SPKI_PIN.equals(trustMode)
                    && GatewaySettings.CONFIRMED_SPKI_PIN.equals(settings.getTrustMode())
                    && gatewayUrl.equals(settings.getGatewayUrl())
                    && !settings.getCertificatePin().isEmpty();
            if (GatewaySettings.CONFIRMED_SPKI_PIN.equals(trustMode) && !savedPinConfirmed) {
                TlsTrust.probe(gatewayUrl, settings.getDeviceId(), new TlsTrust.ProbeCallback() {
                    @Override public void onSuccess(JSONObject certificate) {
                        sendJson(response, 200, json(
                                "reachable", true,
                                "tlsTrusted", false,
                                "latencyMs", JSONObject.NULL,
                                "protocolVersion", JSONObject.NULL,
                                "confirmationRequired", true,
                                "fingerprint", certificate.optString("certificatePin", ""),
                                "authenticated", false,
                                "capabilitiesReceived", false
                        ));
                    }

                    @Override public void onError(String code, String message) {
                        sendError(response, 502, code, message);
                    }
                });
                return;
            }

            String transientToken = GatewaySettings.SYSTEM_TRUST.equals(trustMode)
                    ? body.optString("apiKey", "")
                    : body.optString("apiKey", "").isEmpty()
                    ? credentialStore.load()
                    : body.optString("apiKey", "");
            if (GatewaySettings.SYSTEM_TRUST.equals(trustMode) && transientToken.isEmpty()) {
                long probeStartedAt = System.currentTimeMillis();
                TlsTrust.probeSystemTrust(gatewayUrl, settings.getDeviceId(), new TlsTrust.ProbeCallback() {
                    @Override public void onSuccess(JSONObject certificate) {
                        sendJson(response, 200, json(
                                "reachable", true,
                                "tlsTrusted", true,
                                "latencyMs", Math.max(0L, System.currentTimeMillis() - probeStartedAt),
                                "protocolVersion", JSONObject.NULL,
                                "confirmationRequired", false,
                                "fingerprint", certificate.optString("certificatePin", ""),
                                "authenticated", false,
                                "capabilitiesReceived", false
                        ));
                    }

                    @Override public void onError(String code, String message) {
                        sendError(response, 502, code, message);
                    }
                });
                return;
            }
            TlsTrust.testCapabilities(
                    gatewayUrl,
                    trustMode,
                    savedPinConfirmed ? settings.getCertificatePin() : "",
                    settings.getDeviceId(),
                    transientToken,
                    new TlsTrust.CapabilityCallback() {
                        @Override public void onSuccess(JSONObject result) { sendJson(response, 200, result); }
                        @Override public void onError(String code, String message) { sendError(response, 502, code, message); }
                    }
            );
        });

        server.post("/api/v2/conversation/cancel$", (request, response) -> {
            if (!requireSession(request, response)) return;
            String operationKey = requireIdempotencyKey(request, response, "cancel");
            if (operationKey == null || sendCachedOperation(operationKey, response, 202)) return;
            JSONObject body = readJson(request);
            String turnId;
            String reason;
            try {
                requireOnlyKeys(body, "turnId", "reason");
                turnId = body.optString("turnId", "");
                reason = body.optString("reason", "");
                if (!turnId.isEmpty()) UUID.fromString(turnId);
                if (!("barge_in".equals(reason)
                        || "user_interaction".equals(reason)
                        || "screen_off".equals(reason)
                        || "sleep".equals(reason))) {
                    throw new IllegalArgumentException("A supported cancellation reason is required");
                }
            } catch (Exception error) {
                sendError(response, 400, "INVALID_REQUEST", error.getMessage());
                return;
            }
            coordinator.cancelActiveTurn(turnId, reason, idempotentJsonResponse(response, 202, operationKey));
        });

        server.get("/api/v2/conversation/audio/([^/]+)$", (request, response) -> {
            if (!requireSession(request, response)) return;
            String artifactId = request.getMatcher().group(1);
            coordinator.downloadAudio(artifactId, new HermesClient.BinaryCallback() {
                @Override public void onSuccess(byte[] bytes, String contentType, String digest, String expiresAt) {
                    response.code(200);
                    response.getHeaders().set("Cache-Control", "no-store");
                    response.getHeaders().set("Digest", digest);
                    response.getHeaders().set("Content-Length", String.valueOf(bytes.length));
                    response.getHeaders().set("Expires", httpDate(expiresAt));
                    response.send(contentType, bytes);
                }
                @Override public void onError(String code, String message) {
                    int status = "AUDIO_NOT_AVAILABLE".equals(code) ? 404
                            : "AUDIO_EXPIRED".equals(code) ? 410
                            : 502;
                    sendError(response, status, code, message);
                }
            });
        });

        server.addAction("PUT", "/api/v2/conversation/tool-calls/([^/]+)$", (request, response) -> {
            if (!requireSession(request, response)) return;
            String callId = request.getMatcher().group(1);
            JSONObject body = readJson(request);
            try {
                UUID parsedCallId = UUID.fromString(callId);
                if (!parsedCallId.toString().equalsIgnoreCase(callId)) {
                    throw new IllegalArgumentException("callId must be a canonical UUID");
                }
                validateToolCallUpdate(body);
            } catch (Exception error) {
                boolean tooLarge = error instanceof ToolOutputTooLargeException;
                sendError(response, tooLarge ? 413 : 400,
                        tooLarge ? "PAYLOAD_TOO_LARGE" : "INVALID_REQUEST", error.getMessage());
                return;
            }
            coordinator.reportToolResult(callId, body, jsonResponse(response));
        }, headers -> new JSONObjectBody());

        server.post("/api/v2/conversation/playback$", (request, response) -> {
            if (!requireSession(request, response)) return;
            String operationKey = requireIdempotencyKey(request, response, "playback");
            if (operationKey == null || sendCachedOperation(operationKey, response, 202)) return;
            JSONObject body = readJson(request);
            try {
                requireOnlyKeys(body, "turnId", "artifactId", "status", "timestamp", "positionMs", "reason");
                UUID.fromString(body.optString("turnId", ""));
                UUID.fromString(body.optString("artifactId", ""));
                String status = body.optString("status", "");
                if (!("started".equals(status) || "completed".equals(status) || "interrupted".equals(status))) {
                    throw new IllegalArgumentException("Playback status is invalid");
                }
                if ("interrupted".equals(status)) {
                    String reason = body.optString("reason", "");
                    if (!("barge_in".equals(reason) || "screen_off".equals(reason)
                            || "playback_error".equals(reason) || "client_cancelled".equals(reason))) {
                        throw new IllegalArgumentException("Interrupted playback requires a supported reason");
                    }
                } else if (body.has("reason")) {
                    throw new IllegalArgumentException("Playback reason is allowed only when interrupted");
                }
                Object timestamp = body.opt("timestamp");
                if (!(timestamp instanceof String) || ((String) timestamp).isEmpty()) {
                    throw new IllegalArgumentException("Playback timestamp is required");
                }
                parseIsoTime((String) timestamp);
                if (body.has("positionMs")) {
                    Object position = body.opt("positionMs");
                    if (!(position instanceof Number)) {
                        throw new IllegalArgumentException("positionMs must be a non-negative integer");
                    }
                    double numericPosition = ((Number) position).doubleValue();
                    if (Double.isNaN(numericPosition)
                            || Double.isInfinite(numericPosition)
                            || numericPosition < 0
                            || numericPosition != Math.rint(numericPosition)) {
                        throw new IllegalArgumentException("positionMs must be a non-negative integer");
                    }
                }
            } catch (Exception error) {
                sendError(response, 400, "INVALID_REQUEST", error.getMessage());
                return;
            }
            coordinator.reportPlayback(body, idempotentJsonResponse(response, 202, operationKey));
        });

        registerWebSocket("/api/v2/events");
        server.get("/(.+)$", (request, response) -> serveAppAsset(request, response));
    }

    private JSONObject deviceStatus() {
        JSONObject state = deviceHardware.status();
        try {
            state.put("motionEnabled", coordinator.isMotionEnabled());
            state.put("remote", lanRemote.status(true));
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        return state;
    }

    static boolean validDeviceAction(String action) {
        return "follow".equals(action) || "stop".equals(action) || "forward".equals(action)
                || "backward".equals(action) || "left".equals(action) || "right".equals(action);
    }

    private void registerDeviceRoutes() {
        if (deviceHardware == null) return;
        server.get("/api/v2/device/status$", (request,response) -> {
            if (requireSession(request,response)) sendJson(response,200,deviceStatus());
        });
        server.addAction("PUT", "/api/v2/device/settings$", (request,response) -> {
            if (!requireSession(request,response)) return;
            JSONObject body = readJson(request);
            try {
                requireOnlyKeys(body,"cameraEnabled","attentionEnabled");
                if (body.length() == 0) throw new JSONException("At least one setting is required");
                for (String field : Arrays.asList("cameraEnabled","attentionEnabled")) {
                    if (body.has(field) && !(body.opt(field) instanceof Boolean)) throw new JSONException("Settings must be boolean");
                }
                if (body.has("attentionEnabled")) deviceHardware.setAttentionEnabled(body.getBoolean("attentionEnabled"));
                if (body.has("cameraEnabled")) {
                    deviceHardware.setCameraEnabled(body.getBoolean("cameraEnabled"), result ->
                            com.koushikdutta.async.AsyncServer.getDefault().post(() -> sendDeviceResult(response,result,true,false)));
                } else sendJson(response,200,deviceStatus());
            } catch (Exception invalid) { sendError(response,400,"INVALID_REQUEST","Invalid device settings"); }
        }, headers -> new BoundedJsonBody());
        server.addAction("PUT", "/api/v2/device/attention$", (request,response) -> {
            if (!requireSession(request,response)) return;
            try {
                JSONObject body = readJson(request); requireOnlyKeys(body,"phase");
                String phase = body.optString("phase","");
                if (!("idle".equals(phase) || "listening".equals(phase) || "speaking".equals(phase))) throw new JSONException("Invalid phase");
                deviceHardware.setInteractionPhase(phase);
                sendJson(response,200,deviceStatus());
            } catch (Exception invalid) { sendError(response,400,"INVALID_REQUEST","Invalid interaction phase"); }
        }, headers -> new BoundedJsonBody());
        server.addAction("POST", "/api/v2/device/action$", (request,response) -> {
            if (!requireSession(request,response)) return;
            try {
                JSONObject body = readJson(request); requireOnlyKeys(body,"action");
                String action = body.optString("action","");
                if (!validDeviceAction(action)) throw new JSONException("Invalid device action");
                if (!"stop".equals(action) && !coordinator.manualDeviceActionAllowed()) {
                    sendError(response,409,"TURN_BUSY","Conversation currently owns the robot"); return;
                }
                deviceHardware.action("follow".equals(action) ? "follow_start" : action,
                        operation -> ("stop".equals(action) || coordinator.manualDeviceActionAllowed()) && runDeviceOperation(operation),
                        result -> com.koushikdutta.async.AsyncServer.getDefault().post(() -> sendDeviceResult(response,result,false,false)));
            } catch (Exception invalid) { sendError(response,400,"INVALID_REQUEST","Invalid device action"); }
        }, headers -> new BoundedJsonBody());
        server.get("/api/v2/device/camera/frame$", (request,response) -> {
            if (requireSession(request,response)) sendDeviceImage(response,"");
        });
        server.addAction("POST", "/api/v2/device/camera/capture$", (request,response) -> {
            if (!requireSession(request,response)) return;
            try {
                if (request.getBody() == null || !(request.getBody().get() instanceof JSONObject)) throw new JSONException("Expected JSON");
                requireOnlyKeys(readJson(request));
            }
            catch (Exception invalid) { sendError(response,400,"INVALID_REQUEST","Expected an empty object"); return; }
            deviceHardware.capture(result -> com.koushikdutta.async.AsyncServer.getDefault().post(() -> sendDeviceResult(response,result,false,true)));
        }, headers -> new BoundedJsonBody());
        server.get("/api/v2/device/camera/([a-fA-F0-9-]{36})$", (request,response) -> {
            if (!requireSession(request,response)) return;
            String artifactId = request.getPath().substring(request.getPath().lastIndexOf('/') + 1);
            try { if (!UUID.fromString(artifactId).toString().equalsIgnoreCase(artifactId)) throw new IllegalArgumentException(); }
            catch (Exception invalid) { sendError(response,400,"INVALID_REQUEST","Invalid camera artifact"); return; }
            sendDeviceImage(response,artifactId);
        });
        server.addAction("PUT", "/api/v2/device/remote$", (request,response) -> {
            if (!requireSession(request,response)) return;
            try {
                JSONObject body = readJson(request); requireOnlyKeys(body,"enabled");
                if (!(body.opt("enabled") instanceof Boolean)) throw new JSONException("enabled must be boolean");
                lanRemote.setEnabled(body.getBoolean("enabled"));
                sendJson(response,200,deviceStatus());
            } catch (JSONException invalid) { sendError(response,400,"INVALID_REQUEST","enabled must be boolean"); }
            catch (Exception unavailable) { sendError(response,503,"LAN_UNAVAILABLE","LAN listener could not start; connect to a private Wi-Fi network"); }
        }, headers -> new BoundedJsonBody());
    }

    private static boolean runDeviceOperation(Runnable operation) { operation.run(); return true; }
    private void sendDeviceImage(AsyncHttpServerResponse response,String artifactId) {
        byte[] image = deviceHardware.cameraJpeg(artifactId);
        if (image == null) { sendError(response,404,"CAMERA_UNAVAILABLE","No fresh camera image is available"); return; }
        secureHeaders(response); response.send("image/jpeg",image);
    }
    private void sendDeviceResult(AsyncHttpServerResponse response,JSONObject result,boolean status,boolean capture) {
        if ("error".equals(result.optString("status"))) {
            JSONObject error = result.optJSONObject("error");
            sendError(response,409,error == null ? "DEVICE_UNAVAILABLE" : error.optString("code","DEVICE_UNAVAILABLE"),
                    error == null ? "Device operation is unavailable" : error.optString("message","Device operation is unavailable"));
            return;
        }
        JSONObject output = result.optJSONObject("result");
        sendJson(response,200,status ? deviceStatus() : capture ? RemoteSessionCoordinator.cameraMetadata(output) : json("accepted",true));
    }

    private synchronized void setupSettings(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (!requireSession(request, response)) return;
        if (settings.isOnboardingComplete()) {
            sendError(response, 409, "ALREADY_CONFIGURED", "Initial setup is already complete");
            return;
        }
        try {
            InitialSetup.configure(settings, credentialStore, readJson(request));
            coordinator.reloadGateway();
            sendJson(response, 200, settingsJson());
        } catch (Exception error) {
            sendError(response, 400, "INVALID_REQUEST", "Initial setup was rejected or could not be saved");
        }
    }

    private void registerSessionRoute(String path) {
        server.post(path, (request, response) -> {
            if (!requireLoopback(request, response)) return;
            if (!requireRendererOrigin(request, response)) return;
            long now = System.currentTimeMillis();
            if (now < nextBootstrapAttemptAt) {
                response.getHeaders().set("Retry-After", String.valueOf(Math.max(1L, (nextBootstrapAttemptAt - now + 999L) / 1000L)));
                sendError(response, 429, "RATE_LIMITED", "Request a fresh renderer bootstrap token and try again later");
                return;
            }
            JSONObject requestBody = readJson(request);
            String suppliedSecret = requestBody.optString("bootstrapToken", "");
            String clientVersion = requestBody.optString("clientVersion", "").trim();
            String expectedSecret = bootstrapSecret;
            long expectedExpiry = bootstrapSecretExpiresAt;
            bootstrapSecret = null;
            bootstrapSecretExpiresAt = 0L;
            boolean requestShapeValid;
            try {
                requireOnlyKeys(requestBody, "clientVersion", "bootstrapToken");
                requestShapeValid = requestBody.opt("clientVersion") instanceof String
                        && requestBody.opt("bootstrapToken") instanceof String
                        && suppliedSecret.length() >= 43
                        && suppliedSecret.length() <= 128
                        && suppliedSecret.matches("[A-Za-z0-9_-]+");
            } catch (JSONException error) {
                requestShapeValid = false;
            }
            if (expectedSecret == null
                    || !requestShapeValid
                    || clientVersion.isEmpty()
                    || ProtocolStrings.length(clientVersion) > 64
                    || now > expectedExpiry
                    || !constantTimeEquals(expectedSecret, suppliedSecret)) {
                failedBootstrapAttempts++;
                nextBootstrapAttemptAt = now + Math.min(30_000L, 1000L << Math.min(failedBootstrapAttempts - 1, 5));
                sendError(response, 400, "INVALID_BOOTSTRAP_TOKEN", "Bootstrap token is invalid, expired, or already used");
                return;
            }
            failedBootstrapAttempts = 0;
            nextBootstrapAttemptAt = 0L;
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            rendererToken = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            rendererTokenExpiresAt = now + SESSION_TTL_MILLIS;
            response.getHeaders().set(
                    "Set-Cookie",
                    SESSION_COOKIE + "=" + rendererToken + "; HttpOnly; SameSite=Strict; Path=/api/v2; Max-Age=" + SESSION_MAX_AGE_SECONDS
            );
            sendJson(response, 200, json(
                    "protocolVersion", "2.0",
                    "expiresAt", isoTime(rendererTokenExpiresAt)
            ));
        });
    }

    private void serveAppAsset(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (!requireLoopback(request, response)) return;
        String path = request.getPath();
        if (path == null || !path.startsWith("/") || path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
            sendError(response, 400, "INVALID_ASSET_PATH", "Asset path is invalid");
            return;
        }
        String assetPath = "app" + path;
        try {
            sendAppAsset(assetPath, contentType(path), response);
        } catch (Exception error) {
            sendError(response, 404, "ASSET_NOT_FOUND", "Asset was not found");
        }
    }

    private void sendAppAsset(String assetPath, String mimeType, AsyncHttpServerResponse response)
            throws java.io.IOException {
        secureHeaders(response);
        response.code(200);
        response.setContentType(mimeType);
        // Ownership passes to the asynchronous transfer; do not close at route return.
        // Chunked framing avoids treating InputStream.available() as the file length.
        AppAssetStream.send(context.getAssets().open(assetPath), response);
    }

    private void registerStatusRoute(String path) {
        server.get(path, (request, response) -> {
            if (!requireSession(request, response)) return;
            JSONObject coordinatorStatus = coordinator.getStatus();
            Object activeSessionId = coordinator.getSessionId();
            String activeTurnId = coordinator.getActiveTurnId();
            sendJson(response, 200, json(
                    "runtimeReady", started,
                    "protocolVersion", "2.0",
                    "lastSequence", coordinator.getLastSequence(),
                    "setupRequired", !settings.isOnboardingComplete(),
                    "gatewayState", coordinator.getGatewayState(),
                    "robotReady", robotGateway.isReady(),
                    "motionEnabled", coordinatorStatus.optBoolean("motionEnabled", false),
                    "battery", coordinatorStatus.optJSONObject("battery"),
                    "turnBusy", coordinatorStatus.optBoolean("turnBusy", false),
                    "activeSessionId", activeSessionId,
                    "activeTurnId", activeTurnId == null || activeTurnId.isEmpty() ? JSONObject.NULL : activeTurnId
            ));
        });
    }

    private void registerConversationRoute(String path) {
        server.get(path, (request, response) -> {
            if (!requireSession(request, response)) return;
            sendJson(response, 200, coordinator.getConversationSnapshot(coordinator.getLastSequence()));
        });
    }

    private void registerMultipartTurnRoute(String path) {
        server.addAction("POST", path, (request, response) -> {
            if (!requireSession(request, response)) return;
            String operationKey = requireIdempotencyKey(request, response, "turn");
            if (operationKey == null || sendCachedOperation(operationKey, response, 202)) return;
            JSONObject input;
            if (request.getBody() instanceof CapturingMultipartBody) {
                CapturingMultipartBody multipart = (CapturingMultipartBody) request.getBody();
                if (multipart.isTooLarge()) {
                    sendError(response, 413, "PAYLOAD_TOO_LARGE", "WAV audio must not exceed 2 MiB");
                    return;
                }
                byte[] audio = multipart.bytes("audio");
                String audioType = multipart.contentType("audio");
                if (audioType == null || !"audio/wav".equalsIgnoreCase(audioType.split(";", 2)[0].trim())) {
                    sendError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "The audio part must use audio/wav");
                    return;
                }
                int declaredDurationMs = parseInt(multipart.string("durationMs"), 0);
                try {
                    WavValidator.validate(audio, declaredDurationMs);
                } catch (IllegalArgumentException error) {
                    sendError(response, 422, "INVALID_AUDIO", error.getMessage());
                    return;
                }
                input = json(
                        "clientTurnId", multipart.string("clientTurnId"),
                        "durationMs", declaredDurationMs,
                        "language", multipart.string("language"),
                        "audioBase64", Base64.encodeToString(audio, Base64.NO_WRAP),
                        "mimeType", "audio/wav"
                );
            } else {
                input = readJson(request);
                try {
                    requireOnlyKeys(input, "clientTurnId", "text", "language");
                } catch (JSONException error) {
                    sendError(response, 400, "INVALID_REQUEST", error.getMessage());
                    return;
                }
            }
            String clientTurnId = input.optString("clientTurnId", "");
            String text = input.optString("text", "");
            String language = input.optString("language", "");
            try {
                UUID.fromString(clientTurnId);
                if (text.isEmpty() && input.optString("audioBase64", "").isEmpty()) {
                    throw new IllegalArgumentException("WAV audio or text is required");
                }
                validateTextTurnLength(text);
                if (!language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) {
                    throw new IllegalArgumentException("language is invalid");
                }
            } catch (Exception error) {
                sendError(response, 400, "INVALID_REQUEST", error.getMessage());
                return;
            }
            try {
                JSONObject accepted = coordinator.submitTurn(input);
                cacheOperation(operationKey, accepted);
                sendJson(response, 202, accepted);
            } catch (RemoteSessionCoordinator.GatewayUnavailableException error) {
                sendError(response, 503, "GATEWAY_OFFLINE", "裝置連線尚未恢復，請稍後再說一次。");
            } catch (JSONException error) {
                sendError(response, "TURN_BUSY".equals(error.getMessage()) ? 409 : 400,
                        "TURN_BUSY".equals(error.getMessage()) ? "TURN_BUSY" : "INVALID_REQUEST", error.getMessage());
            }
        }, headers -> {
            String contentType = headers.get("Content-Type");
            if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
                return new CapturingMultipartBody(contentType);
            }
            return new JSONObjectBody();
        });
    }

    private void registerWebSocket(String path) {
        server.websocket(path, (webSocket, request) -> {
            if (!isLoopback(request)
                    || !RENDERER_ORIGIN.equals(request.getHeaders().get("Origin"))
                    || !validSession(cookieValue(request, SESSION_COOKIE))) {
                webSocket.close();
                return;
            }
            long after;
            try {
                String rawAfter = queryValue(request, "after");
                after = rawAfter == null || rawAfter.isEmpty() ? 0L : Long.parseLong(rawAfter);
            } catch (NumberFormatException error) {
                webSocket.close();
                return;
            }
            if (after < 0L || after > coordinator.getLastSequence()) {
                webSocket.close();
                return;
            }
            webSocket.setClosedCallback(error -> rendererDisconnected(webSocket));
            webSocket.setEndCallback(error -> rendererDisconnected(webSocket));
            // Match the coordinator -> client lock order used by event publication.
            // Subscription and retained-history selection are atomic with native sequencing.
            synchronized (coordinator) {
                JSONArray retained = coordinator.getConversation();
                long earliestRetainedSequence = Long.MAX_VALUE;
                for (int index = 0; index < retained.length(); index++) {
                    JSONObject event = retained.optJSONObject(index);
                    long sequence = event != null ? event.optLong("sequence", 0L) : 0L;
                    if (sequence > 0L) earliestRetainedSequence = Math.min(earliestRetainedSequence, sequence);
                }
                long currentCursor = coordinator.getLastSequence();
                boolean stale = currentCursor > after
                        && (earliestRetainedSequence == Long.MAX_VALUE || after + 1L < earliestRetainedSequence);
                // Recovery controls are broadcast to existing clients before this socket joins.
                JSONArray events = stale ? coordinator.getLocalRecoveryFrames() : retained;
                synchronized (clients) { clients.add(webSocket); }
                for (int index = 0; index < events.length(); index++) {
                    JSONObject event = events.optJSONObject(index);
                    if (event != null && (stale || event.optLong("sequence", 0L) > after)) {
                        webSocket.send(event.toString());
                    }
                }
            }
        });
    }

    private void rendererDisconnected(WebSocket webSocket) {
        // Keep coordinator -> clients lock order, matching publication and socket subscription.
        synchronized (coordinator) {
            if (removeLastRenderer(clients, webSocket)) coordinator.onRendererDisconnected();
        }
    }

    static <T> boolean removeLastRenderer(Set<T> connected, T client) {
        synchronized (connected) {
            // End and close can both fire. A replaced socket must not revoke the new renderer.
            return connected.remove(client) && connected.isEmpty();
        }
    }

    private void updateSettings(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (!requireSession(request, response)) return;
        if (!settings.isOnboardingComplete()) {
            sendError(response, 409, "SETUP_REQUIRED", "Complete initial setup first");
            return;
        }
        JSONObject snapshot = null;
        String previousCredential = null;
        try {
            JSONObject body = readJson(request);
            snapshot = settings.snapshotForRollback();
            previousCredential = credentialStore.load();
            requireOnlyKeys(body, "gatewayUrl", "apiKey", "trustMode",
                    "certificatePin", "confirmedFingerprint", "context");
            requirePairedFields(body, "certificatePin", "confirmedFingerprint");
            if (!body.has("trustMode")) throw new IllegalArgumentException("trustMode is required");
            JSONObject context = body.optJSONObject("context");
            if (context != null) requireOnlyKeys(context, "robotName", "language");
            settings.update(body);
            if (body.has("apiKey")) {
                String token = body.optString("apiKey", "").trim();
                if (token.length() < 16 || token.length() > 4096) {
                    throw new IllegalArgumentException("API key must contain 16 to 4096 characters");
                }
                credentialStore.save(token);
            }
            coordinator.reloadGateway();
            sendJson(response, 200, settingsJson());
        } catch (Exception error) {
            if (snapshot != null) {
                try {
                    settings.restore(snapshot);
                    if (previousCredential == null) credentialStore.clear(); else credentialStore.save(previousCredential);
                } catch (Exception rollbackError) {
                    Log.e(TAG, "Could not fully roll back settings update", rollbackError);
                }
            }
            sendError(response, 400, "INVALID_REQUEST", error.getMessage());
        }
    }

    private JSONObject readJson(AsyncHttpServerRequest request) {
        AsyncHttpRequestBody<?> body = request.getBody();
        if (body != null && body.get() instanceof JSONObject) return (JSONObject) body.get();
        return new JSONObject();
    }

    private boolean requireLoopback(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (isLoopback(request)) return true;
        sendError(response, 403, "FORBIDDEN_ORIGIN", "Local runtime is available only on this device");
        return false;
    }

    private boolean requireSession(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (!requireLoopback(request, response)) return false;
        if (!"GET".equalsIgnoreCase(request.getMethod()) && !requireRendererOrigin(request, response)) return false;
        String token = cookieValue(request, SESSION_COOKIE);
        if (validSession(token)) return true;
        sendError(response, 401, "SESSION_EXPIRED", "Create a renderer session first");
        return false;
    }

    private boolean validSession(String candidate) {
        String expected = rendererToken;
        return expected != null
                && candidate != null
                && System.currentTimeMillis() < rendererTokenExpiresAt
                && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isLoopback(AsyncHttpServerRequest request) {
        if (!(request.getSocket() instanceof AsyncNetworkSocket)) return false;
        InetSocketAddress remote = ((AsyncNetworkSocket) request.getSocket()).getRemoteAddress();
        return remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
    }

    private boolean requireRendererOrigin(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (RENDERER_ORIGIN.equals(request.getHeaders().get("Origin"))) return true;
        sendError(response, 403, "FORBIDDEN_ORIGIN", "Request origin is not the bundled renderer");
        return false;
    }

    private static String queryValue(AsyncHttpServerRequest request, String name) {
        Multimap query = request.getQuery();
        return query != null ? query.getString(name) : null;
    }

    private static String cookieValue(AsyncHttpServerRequest request, String name) {
        String cookieHeader = request.getHeaders().get("Cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return pair[1];
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String candidate) {
        return candidate != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void secureHeaders(AsyncHttpServerResponse response) {
        response.getHeaders().set("Cache-Control", "no-store");
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set("Cross-Origin-Opener-Policy", "same-origin");
        response.getHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
        response.getHeaders().set("Content-Security-Policy", "default-src 'self' data: blob:; connect-src 'self' ws://127.0.0.1:8787; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'");
    }

    private static void sendJson(AsyncHttpServerResponse response, int code, JSONObject body) {
        secureHeaders(response);
        response.code(code);
        response.getHeaders().set("Content-Type", "application/json; charset=utf-8");
        response.send(json(
                "ok", true,
                "requestId", java.util.UUID.randomUUID().toString(),
                "data", body,
                "error", JSONObject.NULL
        ).toString());
    }

    private static void sendError(AsyncHttpServerResponse response, int code, String errorCode, String message) {
        secureHeaders(response);
        response.code(code);
        response.getHeaders().set("Content-Type", "application/json; charset=utf-8");
        String canonicalCode = canonicalErrorCode(errorCode, code);
        String safeMessage = message == null || message.isEmpty() ? canonicalCode : message;
        safeMessage = ProtocolStrings.truncate(safeMessage, 512);
        response.send(json(
                "ok", false,
                "requestId", java.util.UUID.randomUUID().toString(),
                "data", JSONObject.NULL,
                "error", json(
                        "code", canonicalCode,
                        "message", safeMessage,
                        "retryable", isRetryable(canonicalCode)
                )
        ).toString());
    }

    private JSONObject settingsJson() {
        try {
            return settings.toJson(credentialStore.hasCredential());
        } catch (JSONException error) {
            return json("error", "settings_unavailable");
        }
    }

    private static JSONObject json(Object... keyValues) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index + 1 < keyValues.length; index += 2) {
                result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Could not construct JSON", error);
        }
        return result;
    }

    private static void put(JSONObject target, String name, Object value) {
        try {
            target.put(name, value);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not construct JSON", error);
        }
    }

    private static void requireOnlyKeys(JSONObject input, String... allowedKeys) throws JSONException {
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedKeys));
        java.util.Iterator<String> keys = input.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw new JSONException("Unsupported request field: " + key);
        }
    }

    private static void requirePairedFields(JSONObject input, String first, String second) {
        if (input.has(first) != input.has(second)) {
            throw new IllegalArgumentException(first + " and " + second + " must be supplied together");
        }
    }

    private static void validateToolCallUpdate(JSONObject body) throws Exception {
        requireOnlyKeys(body, "status", "updatedAt", "output", "error");
        Object statusValue = body.opt("status");
        Object updatedAtValue = body.opt("updatedAt");
        if (!(statusValue instanceof String)) throw new IllegalArgumentException("Tool status is required");
        if (!(updatedAtValue instanceof String)) throw new IllegalArgumentException("updatedAt is required");
        String status = (String) statusValue;
        parseIsoTime((String) updatedAtValue);

        boolean hasOutput = body.has("output");
        boolean hasError = body.has("error");
        if ("accepted".equals(status)) {
            if (hasOutput || hasError) throw new IllegalArgumentException("Accepted updates cannot include output or error");
            return;
        }
        if ("succeeded".equals(status)) {
            if (!hasOutput || hasError) throw new IllegalArgumentException("Succeeded updates require output and cannot include error");
            if (serializedJsonBytes(body.opt("output")) > 16 * 1024) {
                throw new ToolOutputTooLargeException();
            }
            return;
        }
        if (!("failed".equals(status) || "rejected".equals(status))) {
            throw new IllegalArgumentException("Tool status is invalid");
        }
        if (hasOutput || !hasError) throw new IllegalArgumentException("Failed or rejected updates require error and cannot include output");
        JSONObject error = body.optJSONObject("error");
        if (error == null) throw new IllegalArgumentException("Tool error must be an object");
        requireOnlyKeys(error, "code", "message", "retryable");
        Object codeValue = error.opt("code");
        Object messageValue = error.opt("message");
        Object retryableValue = error.opt("retryable");
        if (!(codeValue instanceof String)
                || !((String) codeValue).matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw new IllegalArgumentException("Tool error code is invalid");
        }
        if (!(messageValue instanceof String)
                || ((String) messageValue).isEmpty()
                || ProtocolStrings.length((String) messageValue) > 512) {
            throw new IllegalArgumentException("Tool error message is invalid");
        }
        if (!(retryableValue instanceof Boolean)) {
            throw new IllegalArgumentException("Tool error retryable must be a boolean");
        }
    }

    private static int serializedJsonBytes(Object value) {
        String encoded;
        if (value == null || value == JSONObject.NULL) encoded = "null";
        else if (value instanceof String) encoded = JSONObject.quote((String) value);
        else encoded = String.valueOf(value);
        return encoded.getBytes(StandardCharsets.UTF_8).length;
    }

    static void validateTextTurnLength(String text) {
        if (ProtocolStrings.length(text) > 16_000) {
            throw new IllegalArgumentException("Text turn exceeds 16000 characters");
        }
    }

    private static String canonicalErrorCode(String value, int status) {
        String normalized = value == null ? "" : value.toUpperCase(java.util.Locale.US).replaceAll("[^A-Z0-9_]", "_");
        Set<String> allowed = new HashSet<>(Arrays.asList(
                "INVALID_REQUEST", "UNAUTHORIZED", "FORBIDDEN_ORIGIN", "RATE_LIMITED", "NOT_FOUND",
                "CONFLICT", "TURN_BUSY", "PAYLOAD_TOO_LARGE", "UNSUPPORTED_MEDIA_TYPE", "GATEWAY_UNCONFIGURED",
                "GATEWAY_AUTH", "GATEWAY_TLS", "GATEWAY_INCOMPATIBLE", "GATEWAY_OFFLINE",
                "SESSION_EXPIRED", "TURN_CANCELLED", "ROBOT_INITIALIZING", "ROBOT_UNAVAILABLE",
                "TOOL_REJECTED", "TIMEOUT", "ARTIFACT_EXPIRED", "INTERNAL_ERROR",
                "INVALID_BOOTSTRAP_TOKEN", "SETUP_REQUIRED", "ALREADY_CONFIGURED",
                "INVALID_SETTINGS", "LAN_UNAVAILABLE", "CAMERA_UNAVAILABLE",
                "CAMERA_DISABLED", "CAMERA_BUSY", "CAMERA_PERMISSION_REQUIRED", "MOTION_DISABLED",
                "APP_NOT_FOREGROUND", "DEVICE_UNAVAILABLE", "ROBOT_BUSY", "PERMISSION_REQUIRED"
        ));
        if (allowed.contains(normalized)) return normalized;
        if (normalized.contains("TLS") || normalized.contains("CERTIFICATE")) return "GATEWAY_TLS";
        if (normalized.contains("AUTH") || normalized.contains("CREDENTIAL")) return "GATEWAY_AUTH";
        if (normalized.contains("INCOMPATIBLE") || normalized.contains("PROTOCOL")) return "GATEWAY_INCOMPATIBLE";
        if (normalized.contains("EXPIRED")) return "ARTIFACT_EXPIRED";
        if (normalized.contains("NOT_AVAILABLE") || status == 404) return "NOT_FOUND";
        if (status == 401) return "UNAUTHORIZED";
        if (status == 403) return "FORBIDDEN_ORIGIN";
        if (status == 409) return "CONFLICT";
        if (status >= 400 && status < 500) return "INVALID_REQUEST";
        if (normalized.contains("GATEWAY") || normalized.startsWith("HTTP_") || status == 502 || status == 503) {
            return "GATEWAY_OFFLINE";
        }
        return "INTERNAL_ERROR";
    }

    private static boolean isRetryable(String code) {
        if ("TURN_BUSY".equals(code)) return true;
        return "RATE_LIMITED".equals(code)
                || "GATEWAY_OFFLINE".equals(code)
                || "ROBOT_INITIALIZING".equals(code)
                || "TIMEOUT".equals(code)
                || "INTERNAL_ERROR".equals(code);
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".onnx")) return "application/octet-stream";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static HermesClient.ResultCallback jsonResponse(AsyncHttpServerResponse response) {
        return new HermesClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) { sendJson(response, 200, result); }
            @Override public void onError(String code, String message) {
                sendError(response, statusForCallbackError(code), code, message);
            }
        };
    }

    private HermesClient.ResultCallback idempotentJsonResponse(
            AsyncHttpServerResponse response,
            int successCode,
            String operationKey
    ) {
        return new HermesClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                cacheOperation(operationKey, result);
                sendJson(response, successCode, result);
            }

            @Override public void onError(String code, String message) {
                sendError(response, statusForCallbackError(code), code, message);
            }
        };
    }

    private static int statusForCallbackError(String code) {
        if (code == null) return 502;
        switch (code) {
            case "INVALID_REQUEST":
            case "INVALID_TOOL_CALL":
            case "INVALID_TOOL_STATUS":
            case "INVALID_PLAYBACK":
            case "INVALID_SETTINGS":
                return 400;
            case "UNAUTHORIZED":
            case "GATEWAY_AUTH":
                return 401;
            case "FORBIDDEN_ORIGIN":
                return 403;
            case "NOT_FOUND":
                return 404;
            case "TURN_BUSY":
            case "CONFLICT":
            case "TOOL_REJECTED":
            case "TURN_CANCELLED":
            case "GATEWAY_INCOMPATIBLE":
            case "SETUP_REQUIRED":
            case "ALREADY_CONFIGURED":
                return 409;
            case "PAYLOAD_TOO_LARGE":
                return 413;
            case "RATE_LIMITED":
                return 429;
            default:
                return 502;
        }
    }

    private String requireIdempotencyKey(
            AsyncHttpServerRequest request,
            AsyncHttpServerResponse response,
            String scope
    ) {
        String value = request.getHeaders().get("Idempotency-Key");
        try {
            UUID.fromString(value);
            return scope + ":" + value;
        } catch (Exception error) {
            sendError(response, 400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be a UUID");
            return null;
        }
    }

    private boolean sendCachedOperation(String operationKey, AsyncHttpServerResponse response, int code) {
        JSONObject cached;
        synchronized (completedOperations) {
            cached = completedOperations.get(operationKey);
        }
        if (cached == null) return false;
        sendJson(response, code, cached);
        return true;
    }

    private void cacheOperation(String operationKey, JSONObject result) {
        synchronized (completedOperations) {
            completedOperations.put(operationKey, result);
            while (completedOperations.size() > 128) {
                String oldest = completedOperations.keySet().iterator().next();
                completedOperations.remove(oldest);
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    static String isoTime(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }

    private static String httpDate(String isoTimestamp) {
        try {
            long timestamp = parseIsoTime(isoTimestamp);
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            return format.format(new Date(timestamp));
        } catch (Exception error) {
            return "Thu, 01 Jan 1970 00:00:00 GMT";
        }
    }

    private static long parseIsoTime(String value) throws Exception {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, java.util.Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = format.parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("timestamp is invalid");
    }

    private static final class ToolOutputTooLargeException extends IllegalArgumentException {
        ToolOutputTooLargeException() {
            super("Tool output exceeds 16 KiB");
        }
    }

    private static final class CapturingMultipartBody extends MultipartFormDataBody {
        private final Map<String, byte[]> parts = new HashMap<>();
        private final Map<String, String> contentTypes = new HashMap<>();
        private Part currentPart;
        private ByteArrayOutputStream currentBytes;
        private boolean tooLarge;

        CapturingMultipartBody(String contentType) {
            super(contentType);
            setMultipartCallback(part -> {
                finishPart();
                currentPart = part;
                currentBytes = new ByteArrayOutputStream();
                setDataCallback((emitter, data) -> {
                    byte[] bytes = data.getAllByteArray();
                    if ((long) currentBytes.size() + bytes.length <= WavValidator.MAX_BYTES) {
                        currentBytes.write(bytes, 0, bytes.length);
                    } else {
                        tooLarge = true;
                    }
                });
            });
        }

        @Override
        public boolean readFullyOnRequest() {
            // The route validates captured parts, so it must wait for the complete multipart body.
            return true;
        }

        @Override
        protected void onBoundaryEnd() {
            finishPart();
            super.onBoundaryEnd();
        }

        byte[] bytes(String name) {
            byte[] value = parts.get(name);
            return value != null ? value : new byte[0];
        }

        String string(String name) {
            return new String(bytes(name), StandardCharsets.UTF_8).trim();
        }

        String contentType(String name) {
            return contentTypes.get(name);
        }

        boolean isTooLarge() {
            return tooLarge;
        }

        private void finishPart() {
            if (currentPart != null && currentBytes != null && currentPart.getName() != null) {
                parts.put(currentPart.getName(), currentBytes.toByteArray());
                contentTypes.put(currentPart.getName(), currentPart.getContentType());
            }
            currentPart = null;
            currentBytes = null;
        }
    }
}
