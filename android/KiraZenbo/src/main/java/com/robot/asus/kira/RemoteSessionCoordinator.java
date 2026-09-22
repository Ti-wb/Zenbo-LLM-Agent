package com.robot.asus.kira;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns Local Runtime v2 state. Hermes run/SSE frames are never forwarded to the Web UI. */
public final class RemoteSessionCoordinator implements HermesTransport.Listener {
    public interface LocalPublisher { void publish(JSONObject message); }
    static final class GatewayUnavailableException extends JSONException {
        GatewayUnavailableException() { super("Hermes device binding is reconnecting"); }
    }
    private static final int MAX_CONVERSATION_EVENTS = 100;
    private static final long TERMINAL_TOOL_RETENTION_MS = 5 * 60_000L;
    private final RobotOperations robotGateway;
    private final HermesTransport gatewayClient;
    private final GatewaySettings settings;
    private final ScheduledExecutorService scheduler;
    private final String sessionId = UUID.randomUUID().toString();
    private final Deque<JSONObject> conversation = new ArrayDeque<>();
    private final Map<String, ToolCall> tools = new LinkedHashMap<>();
    private final AtomicReference<String> physicalToolOwner = new AtomicReference<>();
    private final TurnAuthority turnAuthority = new TurnAuthority();
    private LocalPublisher localPublisher;
    interface HardwareLifecycle {
        void setMotionAllowed(boolean allowed);
        void stop();
        void suspend();
    }
    private HardwareLifecycle deviceHardware;
    private final Deque<JSONObject> cameraCaptures = new ArrayDeque<>();
    private long sequence;
    private long generation;
    private boolean stopped;
    private boolean announcedReady;
    private boolean motionEnabled;
    private boolean newSessionPending;
    private BatteryState battery = BatteryState.UNKNOWN;
    private String connectionState = "OFFLINE";
    private String transcript = "";
    private String assistantText = "";
    private Turn active;

    private static final class Turn {
        final String id;
        final long generation;
        final String language;
        final Map<String, JSONObject> artifacts = new LinkedHashMap<>();
        final Set<String> playbackStarted = new HashSet<>();
        final Set<String> playbackTerminal = new HashSet<>();
        String runId;
        String state = "UPLOADING";
        boolean submitting;
        boolean cancelled;
        boolean remoteTerminal;
        boolean localTerminal;
        boolean synthesisStarted;
        boolean pollScheduled;
        Turn(String id, long generation, String language) {
            this.id = id; this.generation = generation; this.language = language;
        }
    }

    private static final class ToolCall {
        final String id;
        final String name;
        final Turn turn;
        final long deadline;
        JSONObject terminal;
        long terminalAt;
        boolean dispatched;
        boolean deliveryAcknowledged;
        boolean deliveryAbandoned;
        boolean deliveryScheduled;
        int deliveryAttempts;
        boolean deliveryInFlight;
        final List<HermesTransport.ResultCallback> deliveryWaiters = new ArrayList<>();
        ScheduledFuture<?> timeout;
        ToolCall(String id, String name, Turn turn, long deadline) {
            this.id = id; this.name = name; this.turn = turn; this.deadline = deadline;
        }
    }

    public RemoteSessionCoordinator(GatewaySettings settings, DeviceCredentialStore credentials,
                                    RobotGateway robotGateway) {
        this.settings = settings;
        this.motionEnabled = settings.isMotionEnabled();
        this.robotGateway = robotGateway;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.gatewayClient = new HermesClient(settings, credentials, robotGateway.getToolManifest(), this);
    }

    RemoteSessionCoordinator(HermesTransport transport, RobotOperations robotGateway,
                             ScheduledExecutorService scheduler, GatewaySettings settings) {
        this.gatewayClient = transport; this.robotGateway = robotGateway; this.scheduler = scheduler;
        this.settings = settings;
        this.motionEnabled = settings.isMotionEnabled();
    }

    public synchronized void setLocalPublisher(LocalPublisher publisher) { localPublisher = publisher; }
    public synchronized void setDeviceHardware(DeviceHardware hardware) {
        setHardwareLifecycle(hardware == null ? null : new HardwareLifecycle() {
            @Override public void setMotionAllowed(boolean allowed) { hardware.setMotionAllowed(allowed); }
            @Override public void stop() { hardware.stop(); }
            @Override public void suspend() { hardware.suspend(); }
        });
    }
    synchronized void setHardwareLifecycle(HardwareLifecycle hardware) {
        deviceHardware = hardware;
        if (hardware != null) hardware.setMotionAllowed(motionEnabled);
    }
    synchronized void onRendererDisconnected() {
        if (stopped) return;
        cancelActiveTurn("", "screen_off", NO_OP_CALLBACK);
        if (deviceHardware != null) deviceHardware.suspend();
    }
    synchronized boolean manualDeviceActionAllowed() {
        return !stopped && active == null && !newSessionPending && physicalToolOwner.get() == null;
    }
    public void start() { gatewayClient.start(); }
    public synchronized void reloadGateway() {
        cancelActiveTurn("", "user_interaction", NO_OP_CALLBACK);
        // A replacement credential must not make an uncertain old run executable locally.
        generation++;
        active = null;
        announcedReady = false;
        newSessionPending = false;
        gatewayClient.reload();
    }
    public synchronized void stop() {
        cancelActiveTurn("", "screen_off", NO_OP_CALLBACK);
        stopped = true;
        if (deviceHardware != null) deviceHardware.suspend();
        for (ToolCall tool : tools.values()) releaseCameraBytes(tool);
        generation++;
        gatewayClient.shutdown();
        scheduler.shutdownNow();
    }
    public String getSessionId() { return sessionId; }
    public synchronized long getLastSequence() { return sequence; }
    public synchronized String getActiveTurnId() {
        return active == null || active.localTerminal ? null : active.id;
    }
    public synchronized String getGatewayState() { return GatewayStateMapper.normalize(connectionState); }
    public synchronized boolean isMotionEnabled() { return motionEnabled; }
    synchronized void updateBattery(BatteryState reading) {
        if (stopped) return;
        battery = reading;
        publishRobotState();
    }

    public synchronized JSONObject startNewSession() throws JSONException {
        if (active != null || newSessionPending) throw new JSONException("TURN_BUSY");
        if (stopped || !"READY".equals(getGatewayState())) throw new GatewayUnavailableException();
        HermesTransport.NewSessionResult result = gatewayClient.startNewSession();
        if (result == HermesTransport.NewSessionResult.BUSY) throw new JSONException("TURN_BUSY");
        if (result != HermesTransport.NewSessionResult.STARTED) throw new GatewayUnavailableException();
        newSessionPending = true;
        generation++;
        transcript = "";
        assistantText = "";
        conversation.clear();
        cameraCaptures.clear();
        onStateChanged("CONNECTING", "");
        JSONObject snapshot = getConversationSnapshot(sequence);
        snapshot.put("lastSequence", sequence + 1);
        emit("session.snapshot", null, snapshot);
        return snapshot;
    }
    public synchronized JSONObject setMotionEnabled(boolean enabled) {
        if (enabled) {
            // Never grant hardware authority before its durable preference is saved.
            settings.setMotionEnabled(true);
            motionEnabled = true;
            if (deviceHardware != null) deviceHardware.setMotionAllowed(true);
            publishRobotState();
        } else {
            motionEnabled = false;
            if (deviceHardware != null) deviceHardware.setMotionAllowed(false);
            boolean motionPending = robotGateway.isMoving();
            for (ToolCall tool : new ArrayList<>(tools.values())) {
                if (requiresMotionPermission(tool.name) && tool.terminal == null) {
                    motionPending = true;
                    terminalTool(tool, motionDisabledResult());
                }
            }
            if (motionPending) robotGateway.emergencyStop();
            try { settings.setMotionEnabled(false); }
            finally { publishRobotState(); }
        }
        return json("motionEnabled", motionEnabled, "moving", robotGateway.isMoving());
    }
    public synchronized JSONArray getConversation() {
        JSONArray result = new JSONArray();
        for (JSONObject event : conversation) result.put(event);
        return result;
    }
    public synchronized JSONArray getLocalRecoveryFrames() {
        boolean interrupted = active != null && !active.localTerminal;
        if (interrupted) cancelActiveTurn("", "user_interaction", NO_OP_CALLBACK);
        JSONArray frames = new JSONArray();
        frames.put(emit("local.gateway.state", null, json("state", getGatewayState(),
                "detail", interrupted ? "畫面重新連線，上一輪已停止，請重新提問。" : "")));
        frames.put(emit("local.robot.state", null,
                json("ready", robotGateway.isReady(), "moving", robotGateway.isMoving(), "motionEnabled", motionEnabled,
                        "battery", battery.toJson())));
        return frames;
    }
    public synchronized JSONObject getStatus() {
        return json("sessionId", sessionId, "gateway", gatewayClient.getStatus(),
                "robotReady", robotGateway.isReady(), "motionEnabled", motionEnabled, "lastSequence", sequence,
                "conversationEvents", conversation.size(), "turnBusy", active != null || newSessionPending,
                "battery", battery.toJson());
    }
    public synchronized JSONObject getConversationSnapshot(long ignored) {
        return json("sessionId", sessionId, "activeTurnId", nullable(getActiveTurnId()),
                "turnState", active == null || active.localTerminal ? "IDLE" : active.state,
                "lastSequence", sequence, "transcript", transcript, "assistantText", assistantText,
                "cameraCaptures", new JSONArray(cameraCaptures));
    }

    public synchronized JSONObject submitTurn(JSONObject input) throws JSONException {
        if (active != null || newSessionPending) throw new JSONException("TURN_BUSY");
        if (stopped || !"READY".equals(getGatewayState())) throw new GatewayUnavailableException();
        String id = input.getString("clientTurnId");
        if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new JSONException("Invalid clientTurnId");
        Turn turn = new Turn(id, ++generation, input.optString("language", "zh-TW"));
        active = turn;
        transcript = input.optString("text", "").trim();
        assistantText = "";
        emit("turn.accepted", turn.id, json());
        if (!transcript.isEmpty()) {
            submitText(turn, transcript);
        } else {
            gatewayClient.transcribe(input, new HermesTransport.ResultCallback() {
                @Override public void onSuccess(JSONObject result) {
                    synchronized (RemoteSessionCoordinator.this) {
                        if (!current(turn) || turn.cancelled) return;
                        String text = result.optString("text", "").trim();
                        if (text.isEmpty() || ProtocolStrings.length(text) > 16_000) {
                            failTurn(turn, "INVALID_TRANSCRIPT", "Speech recognition returned no usable text");
                            return;
                        }
                        transcript = text;
                        emit("stt.final", turn.id, json("text", text, "language", turn.language));
                        submitText(turn, text);
                    }
                }
                @Override public void onError(String code, String message) {
                    synchronized (RemoteSessionCoordinator.this) {
                        if (current(turn) && !turn.cancelled) failTurn(turn, code,
                                "GATEWAY_OFFLINE".equals(code)
                                        ? "裝置連線已中斷，請在連線恢復後再說一次。" : "Speech recognition failed");
                    }
                }
            });
        }
        return json("sessionId", sessionId, "turnId", id, "clientTurnId", id,
                "state", "accepted", "acceptedAt", isoNow());
    }

    private void submitText(Turn turn, String text) {
        turn.submitting = true;
        turn.state = "THINKING";
        emit("agent.thinking", turn.id, json());
        gatewayClient.submitText(turn.id, text, turn.language, new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                synchronized (RemoteSessionCoordinator.this) {
                    String runId = result.optString("runId", result.optString("run_id", result.optString("id", "")));
                    if (!current(turn)) {
                        if (!runId.isEmpty()) gatewayClient.stopRun(runId, NO_OP_CALLBACK);
                        return;
                    }
                    turn.submitting = false;
                    if (runId.isEmpty()) {
                        failTurn(turn, "INVALID_RUN", "Hermes did not return a run identifier");
                        return;
                    }
                    turn.runId = runId;
                    if (turn.cancelled) requestRemoteStop(turn);
                    else handleRunStatus(turn, result);
                }
            }
            @Override public void onError(String code, String message) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!current(turn)) return;
                    // Submission ambiguity cannot be turned into permission for another run.
                    // The client resolves idempotent submission before declaring a final error.
                    turn.submitting = false;
                    if (turn.cancelled) { active = null; return; }
                    failTurn(turn, code, "Hermes run submission failed");
                }
            }
        });
    }

    @Override public synchronized void onStateChanged(String state, String detail) {
        if (stopped) return;
        if (newSessionPending && "READY".equals(state)) {
            // A previously queued READY callback must not complete the new-session barrier.
            if (!"READY".equals(gatewayClient.getStatus().optString("state"))) return;
            newSessionPending = false;
        }
        connectionState = GatewayStateMapper.normalize(state);
        if (deviceHardware != null && ("OFFLINE".equals(connectionState) || "ERROR".equals(connectionState))) {
            deviceHardware.stop();
        }
        emit("local.gateway.state", null, json("state", connectionState,
                "detail", safeDetail(detail)));
        if ("READY".equals(connectionState) && !announcedReady) {
            long previous = sequence;
            announcedReady = true;
            emit("session.ready", null, json("resumedAfter", previous, "gatewayTime", isoNow()));
        }
        if ("READY".equals(connectionState) && active != null && active.runId != null
                && !active.remoteTerminal) recoverRun(active);
    }

    @Override public synchronized void onRunEvent(String runId, String type, JSONObject payload) {
        Turn turn = active;
        if (turn == null || turn.runId == null || !turn.runId.equals(runId) || !current(turn)) return;
        if ("stream.disconnected".equals(type) || "run.disconnected".equals(type)) {
            recoverRun(turn);
            return;
        }
        if ("run.completed".equals(type)) {
            if (!turn.cancelled) captureText(payload);
            remoteTerminal(turn, "completed");
            return;
        }
        if ("run.failed".equals(type) || "run.cancelled".equals(type)
                || "run.canceled".equals(type)) {
            remoteTerminal(turn, type.endsWith("failed") ? "failed" : "cancelled");
            return;
        }
        if (turn.cancelled || turn.remoteTerminal) return;
        if ("assistant.delta".equals(type)) {
            String delta = payload.optString("text", "");
            if (ProtocolStrings.length(assistantText) + ProtocolStrings.length(delta) <= 16_000) assistantText += delta;
        } else if ("assistant.final".equals(type)) {
            captureText(payload);
        } else if ("run.running".equals(type) || "run.started".equals(type)) {
            turn.state = "THINKING";
        }
    }

    private void captureText(JSONObject payload) {
        String text = payload.optString("text", payload.optString("output_text", payload.optString("output", ""))).trim();
        if (!text.isEmpty()) assistantText = ProtocolStrings.truncate(text, 16_000);
    }
    private void handleRunStatus(Turn turn, JSONObject result) {
        String status = result.optString("status", result.optString("state", ""));
        if ("completed".equals(status) || "succeeded".equals(status)) {
            captureText(result);
            remoteTerminal(turn, "completed");
        } else if ("failed".equals(status) || "cancelled".equals(status) || "canceled".equals(status)) {
            remoteTerminal(turn, "failed".equals(status) ? "failed" : "cancelled");
        }
    }
    private void remoteTerminal(Turn turn, String status) {
        if (!current(turn) || turn.remoteTerminal) return;
        turn.remoteTerminal = true;
        reconcileToolDelivery(turn);
        if (turn.cancelled || turn.localTerminal) { active = null; return; }
        if (!"completed".equals(status)) {
            failTurn(turn, "failed".equals(status) ? "RUN_FAILED" : "TURN_CANCELLED",
                    "failed".equals(status) ? "Hermes run failed" : "Hermes run was cancelled");
            return;
        }
        if (assistantText.isEmpty()) {
            completeTurn(turn);
            return;
        }
        emit("agent.text.final", turn.id, json("text", assistantText));
        turn.synthesisStarted = true;
        turn.state = "THINKING";
        gatewayClient.synthesize(turn.runId, assistantText, turn.language, new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!current(turn) || turn.cancelled) return;
                    try {
                        JSONArray segments = result.getJSONArray("segments");
                        if (segments.length() == 0 || segments.length() > 64) throw new JSONException("Invalid audio segment count");
                        JSONArray publicArtifacts = new JSONArray();
                        long totalBytes = 0;
                        for (int index = 0; index < segments.length(); index++) {
                            JSONObject source = segments.getJSONObject(index);
                            String artifactId = source.getString("artifactId");
                            if (!UUID.fromString(artifactId).toString().equalsIgnoreCase(artifactId)
                                    || turn.artifacts.containsKey(artifactId)) throw new JSONException("Invalid artifact identity");
                            String mime = source.getString("mimeType");
                            int size = source.getInt("byteLength");
                            String sha = source.getString("sha256");
                            String expiry = source.getString("expiresAt");
                            AudioArtifactValidator.validateMetadata(mime, size, parseDeadline(expiry), System.currentTimeMillis());
                            if (!sha.matches("[a-f0-9]{64}")) throw new JSONException("Invalid audio digest");
                            totalBytes += size;
                            if (totalBytes > 10L * 1024 * 1024) throw new JSONException("Audio playlist is too large");
                            turn.artifacts.put(artifactId, source);
                            publicArtifacts.put(json("artifactId", artifactId, "mimeType", mime,
                                    "byteLength", size, "sha256", sha, "expiresAt", expiry));
                        }
                        emit("tts.ready", turn.id, json("artifacts", publicArtifacts));
                    } catch (Exception error) {
                        failTurn(turn, "INVALID_AUDIO", "Hermes speech artifacts are invalid");
                    }
                }
            }
            @Override public void onError(String code, String message) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (current(turn) && !turn.cancelled) failTurn(turn, code, "Speech synthesis failed");
                }
            }
        });
    }

    public synchronized void cancelActiveTurn(String expectedTurnId, String reason,
                                               HermesTransport.ResultCallback callback) {
        boolean robotStopped = robotGateway.emergencyStop();
        // Native cancellation must also revoke passive attention and queued camera callbacks.
        // Otherwise a later SDK voice event can restart head motion after a head press or cancel.
        if (deviceHardware != null) deviceHardware.stop();
        physicalToolOwner.set(null);
        Turn turn = active;
        if (turn == null || (!expectedTurnId.isEmpty() && !expectedTurnId.equals(turn.id))) {
            publishRobotState();
            callback.onSuccess(json("remoteCancelled", false, "robotStopped", robotStopped));
            return;
        }
        if (!turn.cancelled) {
            turn.cancelled = true;
            turnAuthority.revoke(turn.id);
            turn.artifacts.clear();
            terminalizeTools(turn, "TURN_CANCELLED");
            if (!turn.localTerminal) {
                turn.localTerminal = true;
                emit("turn.cancelled", turn.id, json("reason", "client_request"));
            }
        }
        publishRobotState();
        // Local cancellation is acknowledged immediately; the run still owns the next-turn gate.
        callback.onSuccess(json("remoteCancelled", turn.remoteTerminal, "robotStopped", robotStopped));
        if (turn.remoteTerminal || (turn.runId == null && !turn.submitting)) {
            active = null;
        } else if (turn.runId != null) requestRemoteStop(turn);
    }

    private void requestRemoteStop(Turn turn) {
        if (!current(turn) || turn.remoteTerminal || turn.runId == null) return;
        gatewayClient.stopRun(turn.runId, new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!current(turn)) return;
                    handleRunStatus(turn, result);
                    if (current(turn) && !turn.remoteTerminal) recoverRun(turn);
                }
            }
            @Override public void onError(String code, String message) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (current(turn)) scheduleRecovery(turn);
                }
            }
        });
    }
    private void recoverRun(Turn turn) {
        if (!current(turn) || turn.runId == null || turn.remoteTerminal) return;
        gatewayClient.getRun(turn.runId, new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!current(turn)) return;
                    handleRunStatus(turn, result);
                    if (current(turn) && !turn.remoteTerminal) scheduleRecovery(turn);
                }
            }
            @Override public void onError(String code, String message) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (current(turn)) scheduleRecovery(turn);
                }
            }
        });
    }
    private void scheduleRecovery(Turn turn) {
        if (turn.pollScheduled || stopped || !current(turn)) return;
        turn.pollScheduled = true;
        scheduler.schedule(() -> {
            synchronized (RemoteSessionCoordinator.this) {
                turn.pollScheduled = false;
                if (!current(turn)) return;
                if (turn.cancelled) requestRemoteStop(turn); else recoverRun(turn);
            }
        }, 2, TimeUnit.SECONDS);
    }

    public synchronized void downloadAudio(String artifactId, HermesTransport.BinaryCallback callback) {
        Turn turn = active;
        JSONObject expected = turn == null ? null : turn.artifacts.get(artifactId);
        if (turn == null || turn.cancelled || expected == null || turn.playbackTerminal.contains(artifactId)) {
            callback.onError("AUDIO_NOT_AVAILABLE", "Audio artifact is not available for this turn");
            return;
        }
        gatewayClient.downloadAudio(expected, new HermesTransport.BinaryCallback() {
            @Override public void onSuccess(byte[] bytes, String contentType, String digest, String expiresAt) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!current(turn) || turn.cancelled) {
                        callback.onError("TURN_CANCELLED", "Audio turn was cancelled");
                        return;
                    }
                    try {
                        AudioArtifactValidator.validateMetadata(expected.getString("mimeType"), expected.getInt("byteLength"),
                                parseDeadline(expected.getString("expiresAt")), System.currentTimeMillis());
                        AudioArtifactValidator.validatePayload(bytes, contentType, expected.getString("mimeType"),
                                expected.getInt("byteLength"), expected.getString("sha256"));
                        callback.onSuccess(bytes, contentType, digest, expected.getString("expiresAt"));
                    } catch (Exception error) {
                        callback.onError("INVALID_AUDIO", "Speech artifact verification failed");
                    }
                }
            }
            @Override public void onError(String code, String message) { callback.onError(code, message); }
        });
    }

    public synchronized void reportPlayback(JSONObject update, HermesTransport.ResultCallback callback) {
        Turn turn = active;
        String artifactId = update.optString("artifactId", "");
        String status = update.optString("status", "");
        if (turn == null || !turn.id.equals(update.optString("turnId", ""))
                || !turn.artifacts.containsKey(artifactId) || turn.cancelled) {
            callback.onError("CONFLICT", "Playback is not correlated with the active turn");
            return;
        }
        if (turn.playbackTerminal.contains(artifactId)) {
            callback.onSuccess(json("artifactId", artifactId, "status", status));
            return;
        }
        if ("started".equals(status)) {
            for (String preceding : turn.artifacts.keySet()) {
                if (preceding.equals(artifactId)) break;
                if (!turn.playbackTerminal.contains(preceding)) {
                    callback.onError("CONFLICT", "Audio segments must play in order");
                    return;
                }
            }
            turn.playbackStarted.add(artifactId);
            turn.state = "SPEAKING";
        } else if ("completed".equals(status)) {
            if (!turn.playbackStarted.contains(artifactId)) {
                callback.onError("CONFLICT", "Playback must start before completion");
                return;
            }
            turn.playbackTerminal.add(artifactId);
        } else if ("interrupted".equals(status)) {
            callback.onSuccess(json("artifactId", artifactId, "status", status));
            cancelActiveTurn(turn.id, "user_interaction", NO_OP_CALLBACK);
            return;
        } else {
            callback.onError("INVALID_PLAYBACK", "Unsupported playback state");
            return;
        }
        callback.onSuccess(json("artifactId", artifactId, "status", status));
        if (turn.playbackTerminal.size() == turn.artifacts.size()) completeTurn(turn);
    }

    @Override public synchronized void onDeviceToolCall(JSONObject message) {
        cleanupTools();
        JSONObject call = message.optJSONObject("data");
        if (call == null) call = message;
        String callId = call.optString("callId", "");
        String name = call.optString("toolName", "");
        String runId = call.optString("runId", message.optString("runId", ""));
        ToolCall prior = tools.get(callId);
        if (prior != null) {
            if (prior.terminal != null) deliverTerminal(prior);
            return;
        }
        Turn turn = active;
        JSONObject args = call.optJSONObject("arguments");
        long deadline;
        try {
            if (!UUID.fromString(callId).toString().equalsIgnoreCase(callId)) throw new IllegalArgumentException("Invalid call identifier");
            if (turn == null || turn.cancelled || turn.remoteTerminal || turn.runId == null || !turn.runId.equals(runId))
                throw new IllegalArgumentException("Tool is not for the active run");
            if (!robotGateway.getAllowedTools().contains(name) || !"1.0.0".equals(call.optString("toolVersion", "")))
                throw new IllegalArgumentException("Unsupported device tool");
            if ("capture_camera".equals(name)) {
                for (ToolCall pending : tools.values()) {
                    JSONObject output = pending.terminal == null ? null : pending.terminal.optJSONObject("output");
                    if ("capture_camera".equals(pending.name) && !pending.deliveryAcknowledged && !pending.deliveryAbandoned
                            && (pending.terminal == null || (output != null && output.has("imageBase64")))) {
                        throw new IllegalArgumentException("A camera result is still awaiting acknowledgement");
                    }
                }
            }
            String owner = call.optString("owner", robotGateway.isNativeTool(name) ? "native" : "web");
            if (!owner.equals(robotGateway.isNativeTool(name) ? "native" : "web")) throw new IllegalArgumentException("Wrong tool owner");
            if (args == null) throw new IllegalArgumentException("Tool arguments are required");
            validateArguments(name, args);
            int timeoutMs = ToolManifestSpec.timeoutMs(name);
            Object declaredTimeout = call.get("timeoutMs");
            if (!(declaredTimeout instanceof Number) || ((Number) declaredTimeout).doubleValue() != timeoutMs)
                throw new IllegalArgumentException("Invalid timeout");
            deadline = Math.min(parseDeadline(call.getString("deadlineAt")), System.currentTimeMillis() + timeoutMs);
            if (deadline <= System.currentTimeMillis()) throw new IllegalArgumentException("Expired tool call");
            if ((motionEnabled || !requiresMotionPermission(name))
                    && robotGateway.isPhysicalTool(name) && !tryClaimPhysicalTool(physicalToolOwner, callId))
                throw new IllegalArgumentException("Another physical tool is active");
        } catch (Exception error) {
            if (!callId.isEmpty()) gatewayClient.reportToolResult(callId,
                    toolUpdate("rejected", null, toolError("TOOL_REJECTED", "Device rejected an invalid or stale tool call")), NO_OP_CALLBACK);
            return;
        }
        if ("go_to_sleep".equals(name)) {
            if (deviceHardware != null) deviceHardware.suspend();
            // Stop hardware immediately, but keep this web tool alive until its succeeded ACK.
            for (ToolCall physical : new ArrayList<>(tools.values())) {
                if (robotGateway.isPhysicalTool(physical.name) && physical.terminal == null)
                    terminalTool(physical, toolUpdate("failed", null, toolError("TURN_CANCELLED", "Sleep stopped robot motion")));
            }
            robotGateway.emergencyStop();
            physicalToolOwner.set(null);
            publishRobotState();
        }
        ToolCall tool = new ToolCall(callId, name, turn, deadline);
        tools.put(callId, tool);
        if (!motionEnabled && requiresMotionPermission(name)) {
            terminalTool(tool, motionDisabledResult());
            return;
        }
        final JSONObject arguments = args;
        gatewayClient.reportToolResult(callId, toolUpdate("accepted", null, null), new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!canExecute(tool)) {
                        terminalTool(tool, toolUpdate("failed", null, toolError("TURN_CANCELLED", "Tool authority expired")));
                        return;
                    }
                    tool.dispatched = true;
                    tool.timeout = scheduler.schedule(() -> {
                        synchronized (RemoteSessionCoordinator.this) {
                            if (tool.terminal != null) return;
                            if (robotGateway.isPhysicalTool(tool.name)) robotGateway.emergencyStop();
                            terminalTool(tool, toolUpdate("failed", null, toolError("TIMEOUT", "Tool execution deadline expired")));
                            publishRobotState();
                        }
                    }, Math.max(1, tool.deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (!robotGateway.isNativeTool(tool.name)) {
                        tool.turn.state = "AWAITING_TOOL";
                        emit("tool.call", tool.turn.id, json("callId", tool.id, "toolName", tool.name,
                                "toolVersion", "1.0.0", "arguments", arguments,
                                "timeoutMs", (int) Math.max(100, tool.deadline - System.currentTimeMillis()),
                                "deadlineAt", isoTime(tool.deadline)));
                        return;
                    }
                    robotGateway.execute(tool.id, tool.name, arguments, action -> {
                        synchronized (RemoteSessionCoordinator.this) {
                            return (motionEnabled || !requiresMotionPermission(tool.name))
                                    && canExecute(tool) && turnAuthority.runIfAuthorized(tool.turn.id, action);
                        }
                    }, result -> {
                        synchronized (RemoteSessionCoordinator.this) {
                            if (tool.terminal != null) return;
                            if (!canExecute(tool)) {
                                terminalTool(tool, toolUpdate("failed", null, toolError("TURN_CANCELLED", "Tool authority expired")));
                                return;
                            }
                            boolean success = !"error".equals(result.optString("status", ""));
                            JSONObject output = result.optJSONObject("result");
                            if (success && "capture_camera".equals(tool.name)) {
                                JSONObject metadata = cameraMetadata(output);
                                metadata.remove("accepted");
                                cameraCaptures.addLast(metadata);
                                while (cameraCaptures.size() > 4) cameraCaptures.removeFirst();
                                emit("camera.captured", tool.turn.id, metadata);
                            }
                            terminalTool(tool, toolUpdate(success ? "succeeded" : "failed",
                                    success ? output : null,
                                    success ? null : nativeToolError(result.optJSONObject("error"))));
                            publishRobotState();
                        }
                    });
                }
            }
            @Override public void onError(String code, String detail) {
                synchronized (RemoteSessionCoordinator.this) {
                    terminalTool(tool, toolUpdate("failed", null, toolError("DELIVERY_FAILED", "Tool acceptance was not acknowledged")));
                }
            }
        });
    }

    public synchronized void reportToolResult(String callId, JSONObject update, HermesTransport.ResultCallback callback) {
        ToolCall tool = tools.get(callId);
        if (tool == null || robotGateway.isNativeTool(tool.name)) {
            callback.onError("TOOL_REJECTED", "No web tool is pending for this call");
            return;
        }
        if (tool.terminal != null) {
            if (!tool.terminal.toString().equals(update.toString())) {
                callback.onError("CONFLICT", "Terminal tool result is immutable");
                return;
            }
            if (tool.deliveryAcknowledged) callback.onSuccess(tool.terminal);
            else if (tool.deliveryAbandoned) callback.onError("TURN_CANCELLED", "The run ended before tool acknowledgement was confirmed");
            else { tool.deliveryWaiters.add(callback); deliverTerminal(tool); }
            return;
        }
        if (!canExecute(tool) || !tool.dispatched) {
            callback.onError("TOOL_REJECTED", "Tool authority expired");
            return;
        }
        String status = update.optString("status", "");
        if ("accepted".equals(status)) { callback.onSuccess(update); return; }
        if (!("succeeded".equals(status) || "failed".equals(status) || "rejected".equals(status))) {
            callback.onError("INVALID_TOOL_STATUS", "Unsupported tool result status");
            return;
        }
        tool.deliveryWaiters.add(callback);
        terminalTool(tool, update);
        if (current(tool.turn) && !tool.turn.cancelled) tool.turn.state = "THINKING";
    }
    private boolean canExecute(ToolCall tool) {
        return !stopped && tool.terminal == null && current(tool.turn) && !tool.turn.cancelled
                && !tool.turn.remoteTerminal && !turnAuthority.isRevoked(tool.turn.id)
                && System.currentTimeMillis() < tool.deadline;
    }
    private void terminalTool(ToolCall tool, JSONObject terminal) {
        if (tool.terminal != null) return;
        tool.terminal = terminal;
        tool.terminalAt = System.currentTimeMillis();
        if (tool.timeout != null) tool.timeout.cancel(false);
        releasePhysicalTool(physicalToolOwner, tool.id);
        if ("capture_camera".equals(tool.name)) {
            scheduler.schedule(() -> {
                synchronized (RemoteSessionCoordinator.this) {
                    if (!tool.deliveryAcknowledged && !tool.deliveryAbandoned) abandonToolDelivery(tool);
                }
            }, TERMINAL_TOOL_RETENTION_MS, TimeUnit.MILLISECONDS);
        }
        deliverTerminal(tool);
    }
    private void deliverTerminal(ToolCall tool) {
        if (stopped || tool.deliveryAcknowledged || tool.deliveryAbandoned || tool.deliveryInFlight || tool.terminal == null) return;
        if (tool.turn.remoteTerminal) { abandonToolDelivery(tool); return; }
        tool.deliveryInFlight = true;
        gatewayClient.reportToolResult(tool.id, tool.terminal, new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                synchronized (RemoteSessionCoordinator.this) {
                    tool.deliveryInFlight = false;
                    if (tool.deliveryAbandoned) return;
                    tool.deliveryAcknowledged = true;
                    releaseCameraBytes(tool);
                    List<HermesTransport.ResultCallback> callbacks = new ArrayList<>(tool.deliveryWaiters);
                    tool.deliveryWaiters.clear();
                    for (HermesTransport.ResultCallback callback : callbacks) callback.onSuccess(tool.terminal);
                }
            }
            @Override public void onError(String code, String message) {
                synchronized (RemoteSessionCoordinator.this) {
                    tool.deliveryInFlight = false;
                    if (tool.deliveryAbandoned) return;
                    List<HermesTransport.ResultCallback> callbacks = new ArrayList<>(tool.deliveryWaiters);
                    tool.deliveryWaiters.clear();
                    for (HermesTransport.ResultCallback callback : callbacks)
                        callback.onError("GATEWAY_OFFLINE", "Device tool result is awaiting acknowledgement");
                    if (tool.deliveryScheduled || stopped || tool.deliveryAcknowledged
                            || System.currentTimeMillis() - tool.terminalAt >= TERMINAL_TOOL_RETENTION_MS) return;
                    tool.deliveryScheduled = true;
                    long delay = Math.min(15_000L, 1000L << Math.min(tool.deliveryAttempts++, 4));
                    scheduler.schedule(() -> {
                        synchronized (RemoteSessionCoordinator.this) {
                            tool.deliveryScheduled = false;
                            deliverTerminal(tool);
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                }
            }
        });
    }
    private void reconcileToolDelivery(Turn turn) {
        // A terminal run cannot consume another tool result. Lost ACKs remain uncertain;
        // never replay the action or let an obsolete delivery keep the next turn busy.
        for (ToolCall tool : new ArrayList<>(tools.values())) {
            if (tool.turn != turn) continue;
            if (tool.terminal == null) {
                if (robotGateway.isPhysicalTool(tool.name)) robotGateway.emergencyStop();
                tool.terminal = toolUpdate("failed", null, toolError("TURN_CANCELLED", "Run ended before tool completion"));
                tool.terminalAt = System.currentTimeMillis();
                if (tool.timeout != null) tool.timeout.cancel(false);
                releasePhysicalTool(physicalToolOwner, tool.id);
            }
            if (!tool.deliveryAcknowledged) abandonToolDelivery(tool);
        }
    }
    private static void releaseCameraBytes(ToolCall tool) {
        if (!"capture_camera".equals(tool.name) || tool.terminal == null) return;
        JSONObject output = tool.terminal.optJSONObject("output");
        if (output != null) output.remove("imageBase64");
    }
    private void abandonToolDelivery(ToolCall tool) {
        tool.deliveryAbandoned = true;
        releaseCameraBytes(tool);
        tool.deliveryScheduled = false;
        List<HermesTransport.ResultCallback> callbacks = new ArrayList<>(tool.deliveryWaiters);
        tool.deliveryWaiters.clear();
        for (HermesTransport.ResultCallback callback : callbacks)
            callback.onError("TURN_CANCELLED", "The run ended before tool acknowledgement was confirmed");
    }
    private void terminalizeTools(Turn turn, String code) {
        for (ToolCall tool : new ArrayList<>(tools.values())) {
            if (tool.turn == turn && tool.terminal == null)
                terminalTool(tool, toolUpdate("failed", null, toolError(code, "Turn authority ended")));
        }
    }
    private void cleanupTools() {
        long cutoff = System.currentTimeMillis() - TERMINAL_TOOL_RETENTION_MS;
        Iterator<ToolCall> iterator = tools.values().iterator();
        while (iterator.hasNext()) {
            ToolCall tool = iterator.next();
            if (tool.terminal != null && tool.terminalAt < cutoff) iterator.remove();
        }
    }

    public synchronized void publishRobotEvent(String type, JSONObject data) {
        if ("ScreenOff".equals(type)) {
            cancelActiveTurn("", "screen_off", NO_OP_CALLBACK);
            emit("local.screen.state", null, json("state", "OFF"));
        } else if ("ScreenOn".equals(type)) {
            emit("local.screen.state", null, json("state", "ON"));
        } else if ("HeadPress".equals(type)) {
            cancelActiveTurn("", "user_interaction", NO_OP_CALLBACK);
            emit("local.interaction", null, json("kind", "HEAD_PRESS"));
        } else if ("initComplete".equals(type) || "onStateChange".equals(type)) {
            publishRobotState();
        } else if ("robotUnavailable".equals(type) || "DeviceShutdown".equals(type)) {
            emit("local.robot.state", null, json("ready", false, "moving", false, "motionEnabled", motionEnabled,
                    "battery", battery.toJson()));
        }
    }
    private void publishRobotState() {
        emit("local.robot.state", null, json("ready", robotGateway.isReady(), "moving", robotGateway.isMoving(),
                "motionEnabled", motionEnabled, "battery", battery.toJson()));
    }
    private static boolean requiresMotionPermission(String name) {
        return "start_robot_following".equals(name) || "look_at_user".equals(name) || "move_robot".equals(name);
    }
    private static JSONObject motionDisabledResult() {
        return toolUpdate("rejected", null, toolError("MOTION_DISABLED", "Robot motion is disabled on this device"));
    }
    private boolean current(Turn turn) { return !stopped && active == turn && turn.generation == generation; }
    private void completeTurn(Turn turn) {
        if (!current(turn) || turn.localTerminal) return;
        turn.localTerminal = true;
        terminalizeTools(turn, "TURN_CANCELLED");
        emit("turn.completed", turn.id, json());
        active = null;
    }
    private void failTurn(Turn turn, String code, String message) {
        if (!current(turn) || turn.localTerminal) return;
        turn.localTerminal = true;
        turn.cancelled = true;
        turnAuthority.revoke(turn.id);
        turn.artifacts.clear();
        terminalizeTools(turn, "TURN_CANCELLED");
        robotGateway.emergencyStop();
        if (deviceHardware != null) deviceHardware.stop();
        emit("turn.error", turn.id, json("error", json("code", safeCode(code), "message", message,
                "retryable", "GATEWAY_OFFLINE".equals(code))));
        if (turn.runId != null && !turn.remoteTerminal) requestRemoteStop(turn);
        else if (!turn.submitting) active = null;
    }
    private JSONObject emit(String type, String turnId, JSONObject data) {
        JSONObject envelope = json("protocolVersion", "2.0", "eventId", UUID.randomUUID().toString(),
                "sequence", ++sequence, "type", type, "timestamp", isoNow(), "data", data);
        if (!type.startsWith("local.")) {
            try { envelope.put("sessionId", sessionId).put("turnId", nullable(turnId)); }
            catch (JSONException error) { throw new IllegalStateException(error); }
        }
        conversation.addLast(envelope);
        while (conversation.size() > MAX_CONVERSATION_EVENTS) conversation.removeFirst();
        if (localPublisher != null) localPublisher.publish(envelope);
        return envelope;
    }
    private static Object nullable(String value) { return value == null ? JSONObject.NULL : value; }
    private static String safeDetail(String value) {
        // State details come from a transport, never expose a remote response body or request URL.
        return value == null || value.isEmpty() ? "" : "Hermes connection state changed";
    }
    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{1,63}") ? value : "GATEWAY_OFFLINE";
    }
    private static JSONObject toolError(String code, String message) {
        return json("code", code, "message", message, "retryable", false);
    }
    static JSONObject nativeToolError(JSONObject detail) {
        String code = detail == null ? "" : detail.optString("code", "");
        if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) code = "EXECUTION_FAILED";
        String message = detail == null ? "" : detail.optString("message", "");
        if (message.isEmpty()) message = "Robot could not execute the tool";
        return toolError(code, ProtocolStrings.truncate(message, 512));
    }
    private static JSONObject toolUpdate(String status, JSONObject output, JSONObject error) {
        JSONObject value = json("status", status, "updatedAt", isoNow());
        try {
            if (output != null) value.put("output", output);
            if (error != null) value.put("error", error);
        } catch (JSONException ignored) { }
        return value;
    }
    private static String isoNow() { return isoTime(System.currentTimeMillis()); }
    private static String isoTime(long value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(value));
    }
    private static long parseDeadline(String value) throws Exception {
        // Share the API 23 parser, including Python microseconds and UTC +00:00.
        return HermesClient.parseTimestampMs(value);
    }
    private static JSONObject json(Object... values) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return result;
    }
    private static final HermesTransport.ResultCallback NO_OP_CALLBACK = new HermesTransport.ResultCallback() {
        @Override public void onSuccess(JSONObject result) { }
        @Override public void onError(String code, String message) { }
    };
    static boolean shouldResetRecoveryForSnapshot(String current, String next) { return next == null || !next.equals(current); }
    static boolean isTurnTerminalEvent(String type) { return "turn.completed".equals(type) || "turn.error".equals(type) || "turn.cancelled".equals(type); }
    static boolean isSessionTerminalEvent(String type) { return "session.expired".equals(type) || "session.closed".equals(type); }
    static boolean isCurrentUploadCallback(long callbackGeneration, long currentGeneration, String callbackTurn, String uploadingTurn) {
        return callbackGeneration == currentGeneration && callbackTurn != null && callbackTurn.equals(uploadingTurn);
    }
    static boolean tryClaimPhysicalTool(AtomicReference<String> owner, String callId) {
        return callId != null && !callId.isEmpty() && owner.compareAndSet(null, callId);
    }
    static void releasePhysicalTool(AtomicReference<String> owner, String callId) {
        if (callId == null || callId.isEmpty()) return;
        while (true) {
            String current = owner.get();
            if (!callId.equals(current) || owner.compareAndSet(current, null)) return;
        }
    }

    private static void validateArguments(String name, JSONObject arguments) {
        Set<String> allowed = new HashSet<>();
        if ("start_robot_following".equals(name)) {
            allowed.add("enablePreview");
            allowed.add("largePreview");
            requireBooleanIfPresent(arguments, "enablePreview");
            requireBooleanIfPresent(arguments, "largePreview");
        } else if ("move_robot".equals(name)) {
            allowed.add("direction");
            String direction = arguments.optString("direction", "");
            if (!("forward".equals(direction) || "backward".equals(direction)
                    || "left".equals(direction) || "right".equals(direction))) {
                throw new IllegalArgumentException("move_robot.direction is invalid");
            }
        } else if ("look_at_user".equals(name)) {
            allowed.add("doa");
        } else if ("show_emotion".equals(name)) {
            allowed.add("emotion");
            allowed.add("durationMs");
        }
        java.util.Iterator<String> keys = arguments.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw new IllegalArgumentException(name + "." + key + " is not allowed");
        }
        if ("look_at_user".equals(name)) {
            if (!(arguments.opt("doa") instanceof Number)) throw new IllegalArgumentException("look_at_user.doa is required");
            double doa = arguments.optDouble("doa", Double.NaN);
            if (Double.isNaN(doa) || doa < -180 || doa > 180) throw new IllegalArgumentException("look_at_user.doa is invalid");
        }
        if ("show_emotion".equals(name)) {
            String emotion = arguments.optString("emotion", "");
            if (!("NEUTRAL".equals(emotion) || "HAPPY".equals(emotion) || "CURIOUS".equals(emotion)
                    || "CONCERNED".equals(emotion) || "EXCITED".equals(emotion))) {
                throw new IllegalArgumentException("show_emotion.emotion is invalid");
            }
            if (arguments.has("durationMs")) {
                Object duration = arguments.opt("durationMs");
                if (!(duration instanceof Number)
                        || ((Number) duration).doubleValue() != ((Number) duration).intValue()
                        || ((Number) duration).intValue() < 0
                        || ((Number) duration).intValue() > 30_000) {
                    throw new IllegalArgumentException("show_emotion.durationMs is invalid");
                }
            }
        }
    }

    /** Only explicitly allowed snapshot metadata may enter renderer events or recovery state. */
    static JSONObject cameraMetadata(JSONObject output) {
        JSONObject metadata = new JSONObject();
        if (output == null) return metadata;
        for (String key : Arrays.asList("accepted", "artifactId", "mimeType", "byteLength", "sha256", "width", "height", "capturedAt")) {
            if (output.has(key)) {
                try { metadata.put(key, output.get(key)); }
                catch (JSONException invalid) { throw new IllegalArgumentException("Invalid camera metadata"); }
            }
        }
        return metadata;
    }

    private static void requireBooleanIfPresent(JSONObject arguments, String name) {
        if (arguments.has(name) && !(arguments.opt(name) instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
    }
}
