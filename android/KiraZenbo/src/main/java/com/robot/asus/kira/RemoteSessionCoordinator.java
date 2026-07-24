package com.robot.asus.kira;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates local renderer turns, remote Gateway events, and correlated robot tool calls. */
public final class RemoteSessionCoordinator implements AgentGatewayClient.Listener {
    public interface LocalPublisher {
        void publish(JSONObject message);
    }

    private static final int MAX_CONVERSATION_EVENTS = 100;

    private final RobotGateway robotGateway;
    private final AgentGatewayClient gatewayClient;
    private final Deque<JSONObject> conversation = new ArrayDeque<>();
    private final Set<String> terminalToolCalls = new HashSet<>();
    private final Set<String> cancelRequestedTurns = new HashSet<>();
    private final LinkedHashMap<String, Long> terminalToolCallTimes = new LinkedHashMap<>();
    private final Map<String, JSONObject> terminalToolResults = new HashMap<>();
    private final Map<String, JSONObject> audioArtifacts = new HashMap<>();
    private final Map<String, String> playbackArtifactTurns = new HashMap<>();
    private final Map<String, String> pendingWebToolTurns = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> toolTimeouts = new HashMap<>();
    private final ToolCallLifecycle toolCallLifecycle = new ToolCallLifecycle();
    private final TurnAuthority turnAuthority = new TurnAuthority();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile LocalPublisher localPublisher;
    private volatile String sessionId = UUID.randomUUID().toString();
    private volatile String activeTurnId;
    private volatile boolean awaitingTurnAcceptance;
    private volatile String turnState = "IDLE";
    private volatile String transcript = "";
    private volatile String assistantText = "";
    private String uploadingClientTurnId;
    private long uploadGeneration;
    private PendingUploadCancel pendingUploadCancel;
    private final AtomicReference<String> physicalToolOwner = new AtomicReference<>();

    private static final class PendingUploadCancel {
        final String reason;
        final List<AgentGatewayClient.ResultCallback> callbacks = new ArrayList<>();
        boolean robotStopped;

        PendingUploadCancel(String reason, boolean robotStopped, AgentGatewayClient.ResultCallback callback) {
            this.reason = reason;
            this.robotStopped = robotStopped;
            callbacks.add(callback);
        }
    }

    public RemoteSessionCoordinator(
            GatewaySettings settings,
            DeviceCredentialStore credentialStore,
            RobotGateway robotGateway
    ) {
        this.robotGateway = robotGateway;
        this.gatewayClient = new AgentGatewayClient(settings, credentialStore, robotGateway.getToolManifest(), this);
    }

    public void setLocalPublisher(LocalPublisher publisher) {
        localPublisher = publisher;
    }

    public void start() {
        gatewayClient.start();
    }

    public synchronized void reloadGateway() {
        PendingUploadCancel pending = abortLocalTurnAndTools("IDLE", true);
        try {
            gatewayClient.reload();
        } finally {
            if (pending != null) completePendingCancel(pending, false, null, null);
        }
    }

    public void stop() {
        gatewayClient.shutdown();
        scheduler.shutdownNow();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActiveTurnId() {
        return activeTurnId;
    }

    public String getGatewayState() {
        return GatewayStateMapper.normalize(gatewayClient.getStatus().optString("state", "OFFLINE"));
    }

    public synchronized JSONArray getConversation() {
        JSONArray result = new JSONArray();
        for (JSONObject event : conversation) result.put(event);
        return result;
    }

    public JSONArray getLocalRecoveryFrames() {
        JSONArray frames = new JSONArray();
        frames.put(localEnvelope("local.gateway.state", json("state", getGatewayState())));
        frames.put(localEnvelope(
                "local.robot.state",
                json("ready", robotGateway.isReady(), "moving", robotGateway.isMoving())
        ));
        return frames;
    }

    public JSONObject getStatus() {
        JSONObject result = new JSONObject();
        try {
            result.put("sessionId", sessionId);
            result.put("gateway", gatewayClient.getStatus());
            result.put("robotReady", robotGateway.isReady());
            result.put("conversationEvents", conversation.size());
            result.put("remoteSessionId", gatewayClient.getRemoteSessionId());
        } catch (JSONException ignored) {
        }
        return result;
    }

    public JSONObject getConversationSnapshot(long lastSequence) {
        String remoteSessionId = gatewayClient.getRemoteSessionId();
        return json(
                "sessionId", remoteSessionId == null ? JSONObject.NULL : remoteSessionId,
                "activeTurnId", activeTurnId == null ? JSONObject.NULL : activeTurnId,
                "turnState", turnState,
                "lastSequence", lastSequence,
                "transcript", transcript,
                "assistantText", assistantText
        );
    }

    public synchronized JSONObject submitTurn(JSONObject input) throws JSONException {
        if (awaitingTurnAcceptance || (activeTurnId != null && !activeTurnId.isEmpty())) {
            throw new JSONException("A turn is already active");
        }
        String turnId = input.optString("clientTurnId", input.optString("turnId", UUID.randomUUID().toString()));
        long callbackGeneration = ++uploadGeneration;
        awaitingTurnAcceptance = true;
        uploadingClientTurnId = turnId;
        turnState = "UPLOADING";
        transcript = input.optString("text", "");
        assistantText = "";
        gatewayClient.uploadTurn(input, new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                String remoteTurnId = result.optString("turnId", "");
                PendingUploadCancel pending;
                synchronized (RemoteSessionCoordinator.this) {
                    if (!isCurrentUploadCallback(
                            callbackGeneration,
                            uploadGeneration,
                            turnId,
                            uploadingClientTurnId
                    )) {
                        return;
                    }
                    pending = claimPendingUploadCancel(remoteTurnId);
                    if (pending == null) {
                        invalidateUploadCallbackLocked();
                        uploadingClientTurnId = null;
                        awaitingTurnAcceptance = false;
                        if (!turnAuthority.isRevoked(remoteTurnId)) {
                            activeTurnId = remoteTurnId;
                        }
                    }
                }
                if (pending != null) sendPendingUploadCancel(remoteTurnId, pending);
            }
            @Override public void onError(String code, String message) {
                PendingUploadCancel pending;
                synchronized (RemoteSessionCoordinator.this) {
                    if (!isCurrentUploadCallback(
                            callbackGeneration,
                            uploadGeneration,
                            turnId,
                            uploadingClientTurnId
                    )) {
                        return;
                    }
                    pending = abortLocalTurnAndTools("ERROR", true);
                }
                if (pending != null) completePendingCancel(pending, false, null, null);
                publishGatewayFailure(code, "Gateway turn upload failed");
            }
        });
        return new JSONObject().put("accepted", true).put("clientTurnId", turnId);
    }

    @Override
    public void onGatewayStateChanged(String state, String detail) {
        String normalized = GatewayStateMapper.normalize(state);
        publishLocal(localEnvelope("local.gateway.state", json("state", normalized)));
    }

    @Override
    public void onGatewayMessage(JSONObject message, AgentGatewayClient.EventCommitCallback eventCallback) {
        String type = message.optString("type");
        String turnId = message.optString("turnId", "");
        if ("turn.accepted".equals(type)) {
            PendingUploadCancel pending = claimPendingUploadCancel(turnId);
            if (pending != null) {
                sendPendingUploadCancel(turnId, pending);
            } else if (!turnAuthority.isRevoked(turnId)) {
                if (!turnId.equals(activeTurnId)) clearTurnRecoveryState();
                activeTurnId = turnId;
                awaitingTurnAcceptance = false;
            }
        }
        if ("session.snapshot".equals(type)) {
            JSONObject snapshot = message.optJSONObject("data");
            String snapshotTurnId = snapshot == null || snapshot.isNull("activeTurnId")
                    ? null
                    : snapshot.optString("activeTurnId", null);
            if (snapshotTurnId != null && turnAuthority.isRevoked(snapshotTurnId)) {
                clearTurnRecoveryState();
                activeTurnId = null;
                turnState = "IDLE";
                retryRevokedTurnCancel(snapshotTurnId);
            } else {
                if (shouldResetRecoveryForSnapshot(activeTurnId, snapshotTurnId)) {
                    clearTurnRecoveryState();
                }
                activeTurnId = snapshotTurnId;
                turnState = activeTurnId == null ? "IDLE" : "THINKING";
            }
        }
        boolean currentTurnEvent = !turnId.isEmpty() && turnId.equals(activeTurnId);
        if ("turn.accepted".equals(type) && currentTurnEvent) turnState = "TRANSCRIBING";
        if ("stt.final".equals(type) && currentTurnEvent) {
            JSONObject data = message.optJSONObject("data");
            transcript = data != null ? data.optString("text", "") : "";
            turnState = "THINKING";
        }
        if ("agent.thinking".equals(type) && currentTurnEvent) turnState = "THINKING";
        if ("tool.call".equals(type) && currentTurnEvent) turnState = "AWAITING_TOOL";
        if ("agent.text.final".equals(type) && currentTurnEvent) {
            JSONObject data = message.optJSONObject("data");
            assistantText = data != null ? data.optString("text", "") : "";
            turnState = "SYNTHESIZING";
        }
        if ("tts.ready".equals(type) && currentTurnEvent) turnState = "SYNTHESIZING";
        if (isTurnTerminalEvent(type)) {
            if (currentTurnEvent) {
                PendingUploadCancel pending = abortLocalTurnAndTools(
                        "turn.error".equals(type) ? "ERROR" : "IDLE",
                        false
                );
                if (pending != null) completePendingCancel(pending, false, null, null);
            }
            synchronized (cancelRequestedTurns) { cancelRequestedTurns.remove(turnId); }
        }
        if ("tts.ready".equals(type) && currentTurnEvent) {
            JSONObject audio = message.optJSONObject("data");
            if (audio != null && !audio.optString("artifactId", "").isEmpty()) {
                synchronized (audioArtifacts) { audioArtifacts.put(audio.optString("artifactId"), audio); }
                synchronized (playbackArtifactTurns) {
                    playbackArtifactTurns.put(audio.optString("artifactId"), turnId);
                }
            }
        }
        if (isSessionTerminalEvent(type)) {
            PendingUploadCancel pending = abortLocalTurnAndTools("IDLE", true);
            if (pending != null) completePendingCancel(pending, false, null, null);
        }
        if ("tool.call".equals(type)) {
            if (!currentTurnEvent) {
                remember(message);
                publishLocal(message);
                rejectToolCall(message, "TOOL_REJECTED",
                        "Tool call does not belong to the active turn", eventCallback);
                return;
            }
            handleToolCall(message, eventCallback);
            return;
        }
        remember(message);
        publishLocal(message);
        eventCallback.commit();
    }

    private void clearTurnArtifacts() {
        synchronized (audioArtifacts) { audioArtifacts.clear(); }
        synchronized (playbackArtifactTurns) { playbackArtifactTurns.clear(); }
    }

    private void clearTurnRecoveryState() {
        clearTurnArtifacts();
        transcript = "";
        assistantText = "";
    }

    static boolean shouldResetRecoveryForSnapshot(
            String currentTurnId,
            String snapshotTurnId
    ) {
        return snapshotTurnId == null
                || !snapshotTurnId.equals(currentTurnId);
    }

    static boolean isTurnTerminalEvent(String type) {
        return "turn.completed".equals(type)
                || "turn.error".equals(type)
                || "turn.cancelled".equals(type);
    }

    static boolean isSessionTerminalEvent(String type) {
        return "session.expired".equals(type) || "session.closed".equals(type);
    }

    static boolean isCurrentUploadCallback(
            long callbackGeneration,
            long currentGeneration,
            String callbackClientTurnId,
            String uploadingClientTurnId
    ) {
        return callbackGeneration == currentGeneration
                && callbackClientTurnId != null
                && callbackClientTurnId.equals(uploadingClientTurnId);
    }

    private void invalidateUploadCallbackLocked() {
        uploadGeneration++;
    }

    private PendingUploadCancel abortLocalTurnAndTools(
            String nextTurnState,
            boolean clearRecovery
    ) {
        PendingUploadCancel pending;
        synchronized (this) {
            turnAuthority.revoke(activeTurnId);
            activeTurnId = null;
            awaitingTurnAcceptance = false;
            uploadingClientTurnId = null;
            invalidateUploadCallbackLocked();
            pending = pendingUploadCancel;
            pendingUploadCancel = null;
            turnState = nextTurnState;
        }
        synchronized (cancelRequestedTurns) {
            cancelRequestedTurns.clear();
        }
        abandonPendingToolWork();
        boolean robotStopped = robotGateway.emergencyStop();
        releaseAnyPhysicalTool();
        if (clearRecovery) {
            clearTurnRecoveryState();
        } else {
            clearTurnArtifacts();
        }
        publishRobotState();
        if (pending != null) pending.robotStopped |= robotStopped;
        return pending;
    }

    private void abandonPendingToolWork() {
        Set<String> abandonedCallIds = toolCallLifecycle.terminateActiveCalls();
        synchronized (pendingWebToolTurns) {
            abandonedCallIds.addAll(pendingWebToolTurns.keySet());
            pendingWebToolTurns.clear();
        }
        synchronized (toolTimeouts) {
            abandonedCallIds.addAll(toolTimeouts.keySet());
            for (ScheduledFuture<?> task : toolTimeouts.values()) {
                task.cancel(false);
            }
            toolTimeouts.clear();
        }
        if (abandonedCallIds.isEmpty()) return;
        JSONObject error = json(
                "code", "TURN_TERMINATED",
                "message", "Turn authority ended before tool completion",
                "retryable", false
        );
        JSONObject terminal = toolUpdate("rejected", null, error);
        for (String callId : abandonedCallIds) markTerminal(callId, terminal);
    }

    private void handleToolCall(JSONObject message, AgentGatewayClient.EventCommitCallback eventCallback) {
        JSONObject data = message.optJSONObject("data");
        if (data == null) {
            rejectToolCall(message, "TOOL_REJECTED", "Tool call data is missing", eventCallback);
            return;
        }
        String callId = data.optString("callId", message.optString("callId", ""));
        String name = data.optString("toolName", "");
        String toolTurnId = message.optString("turnId", "");
        JSONObject arguments = data.optJSONObject("arguments");
        cleanupTerminalCalls();
        JSONObject priorTerminal;
        synchronized (terminalToolCallTimes) {
            priorTerminal = terminalToolResults.get(callId);
        }
        if (priorTerminal != null) {
            boolean reported = turnAuthority.runIfAuthorized(
                    toolTurnId,
                    () -> gatewayClient.reportToolResult(
                            callId,
                            priorTerminal,
                            eventResultCallback(eventCallback)
                    )
            );
            if (!reported) eventCallback.commit();
            return;
        }
        if (!toolCallLifecycle.beginAcceptance(callId)) {
            if (toolCallLifecycle.isDispatched(callId)) eventCallback.commit(); else eventCallback.retry();
            return;
        }
        try {
            UUID.fromString(callId);
            if (turnAuthority.isRevoked(toolTurnId)) throw new IllegalArgumentException("Turn authority was cancelled");
            if (!robotGateway.getAllowedTools().contains(name)) throw new IllegalArgumentException("Tool is not allowlisted");
            if (!"1.0.0".equals(data.optString("toolVersion", ""))) throw new IllegalArgumentException("Tool version is incompatible");
            int timeoutMs = data.optInt("timeoutMs", 0);
            if (timeoutMs < 100 || timeoutMs > 15_000) throw new IllegalArgumentException("Tool timeoutMs is invalid");
            if (parseDeadline(data.optString("deadlineAt", "")) <= System.currentTimeMillis()) throw new IllegalArgumentException("Tool call deadline has expired");
            validateArguments(name, arguments != null ? arguments : new JSONObject());
            if (robotGateway.isPhysicalTool(name)) {
                synchronized (this) {
                    if (!message.optString("turnId", "").equals(activeTurnId)) throw new IllegalArgumentException("Physical tool is not for the active user turn");
                }
                if (!tryClaimPhysicalTool(physicalToolOwner, callId)) {
                    throw new IllegalArgumentException("Another physical tool is already executing");
                }
            }
        } catch (Exception error) {
            toolCallLifecycle.failAcceptance(callId);
            rejectToolCall(message, "TOOL_REJECTED", error.getMessage(), eventCallback);
            return;
        }

        JSONObject accepted = toolUpdate("accepted", null, null);
        boolean acceptanceReported = turnAuthority.runIfAuthorized(toolTurnId, () ->
                gatewayClient.reportToolResult(callId, accepted, new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) {
                if (!toolCallLifecycle.markAccepted(callId)) return;
                if (turnAuthority.isRevoked(toolTurnId)) {
                    if (robotGateway.isPhysicalTool(name)) releasePhysicalTool(callId);
                    rejectToolCall(message, "TURN_CANCELLED", "Turn authority was cancelled", eventCallback);
                    return;
                }
                scheduleToolTimeout(
                        callId,
                        name,
                        toolTurnId,
                        data.optInt("timeoutMs", 5_000),
                        data.optString("deadlineAt", "")
                );
                if (!robotGateway.isNativeTool(name)) {
                    synchronized (pendingWebToolTurns) {
                        pendingWebToolTurns.put(callId, toolTurnId);
                    }
                    remember(message);
                    publishLocal(message);
                    eventCallback.commit();
                    return;
                }
                remember(message);
                publishLocal(message);
                boolean executionStarted = turnAuthority.runIfAuthorized(
                        toolTurnId,
                        () -> executeNativeTool(callId, name, arguments, toolTurnId)
                );
                if (executionStarted) eventCallback.commit();
                if (!executionStarted) {
                    if (robotGateway.isPhysicalTool(name)) releasePhysicalTool(callId);
                    rejectToolCall(message, "TURN_CANCELLED", "Turn authority was cancelled", eventCallback);
                }
            }
            @Override public void onError(String code, String detail) {
                toolCallLifecycle.failAcceptance(callId);
                if (robotGateway.isPhysicalTool(name)) releasePhysicalTool(callId);
                eventCallback.retry();
            }
        }));
        if (!acceptanceReported) {
            if (robotGateway.isPhysicalTool(name)) releasePhysicalTool(callId);
            JSONObject terminated = toolUpdate(
                    "rejected",
                    null,
                    json(
                            "code", "TURN_TERMINATED",
                            "message", "Turn authority ended before tool acceptance",
                            "retryable", false
                    )
            );
            markTerminal(callId, terminated);
            eventCallback.commit();
        }
    }

    private void executeNativeTool(String callId, String name, JSONObject arguments, String toolTurnId) {
        robotGateway.execute(callId, name, arguments,
                action -> turnAuthority.runIfAuthorized(toolTurnId, action), result -> {
            try {
                turnAuthority.runIfAuthorized(toolTurnId, () -> {
                    synchronized (terminalToolCallTimes) {
                        if (terminalToolCalls.contains(callId)) return;
                    }
                    try {
                        boolean success = !"error".equals(result.optString("status"));
                        JSONObject error = result.optJSONObject("error");
                        JSONObject normalizedError = error == null ? null : new JSONObject()
                                .put("code", error.optString("code", "EXECUTION_FAILED").toUpperCase(Locale.US))
                                .put("message", error.optString("message", "Tool execution failed"))
                                .put("retryable", false);
                        JSONObject update = toolUpdate(
                                success ? "succeeded" : "failed",
                                result.optJSONObject("result"),
                                normalizedError
                        );
                        cancelToolTimeout(callId);
                        markTerminal(callId, update);
                        gatewayClient.reportToolResult(callId, update, NO_OP_CALLBACK);
                    } catch (JSONException ignored) {
                    }
                });
            } finally {
                if (robotGateway.isPhysicalTool(name)) releasePhysicalTool(callId);
            }
                });
    }

    private void rejectToolCall(
            JSONObject message,
            String code,
            String detail,
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        JSONObject data = message.optJSONObject("data");
        String callId = data != null ? data.optString("callId", "") : "";
        if (callId.isEmpty()) {
            eventCallback.retry();
            return;
        }
        JSONObject error = new JSONObject();
        try {
            error.put("code", code).put("message", detail != null ? detail : code).put("retryable", false);
        } catch (JSONException ignored) { }
        JSONObject update = toolUpdate("rejected", null, error);
        markTerminal(callId, update);
        cancelToolTimeout(callId);
        String toolTurnId = message.optString("turnId", "");
        boolean reported = turnAuthority.runIfAuthorized(
                toolTurnId,
                () -> gatewayClient.reportToolResult(
                        callId,
                        update,
                        eventResultCallback(eventCallback)
                )
        );
        if (!reported) eventCallback.commit();
    }

    private static AgentGatewayClient.ResultCallback eventResultCallback(
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        return new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) { eventCallback.commit(); }
            @Override public void onError(String code, String message) { eventCallback.retry(); }
        };
    }

    public void publishRobotEvent(String type, JSONObject data) {
        if ("ScreenOff".equals(type)) {
            cancelActiveTurn("", "screen_off", NO_OP_CALLBACK);
            publishLocal(localEnvelope("local.screen.state", json("state", "OFF")));
            return;
        }
        if ("ScreenOn".equals(type)) {
            publishLocal(localEnvelope("local.screen.state", json("state", "ON")));
            return;
        }
        if ("HeadPress".equals(type)) {
            cancelActiveTurn("", "user_interaction", NO_OP_CALLBACK);
            publishLocal(localEnvelope("local.interaction", json("kind", "HEAD_PRESS")));
            return;
        }
        if ("initComplete".equals(type) || "onStateChange".equals(type)) {
            publishRobotState();
            return;
        }
        if ("robotUnavailable".equals(type) || "DeviceShutdown".equals(type)) {
            publishLocal(localEnvelope("local.robot.state", json("ready", false, "moving", false)));
        }
    }

    private void publishRobotState() {
        publishLocal(localEnvelope(
                "local.robot.state",
                json("ready", robotGateway.isReady(), "moving", robotGateway.isMoving())
        ));
    }

    private synchronized void remember(JSONObject event) {
        conversation.addLast(event);
        while (conversation.size() > MAX_CONVERSATION_EVENTS) conversation.removeFirst();
    }

    private void publishLocal(JSONObject event) {
        LocalPublisher publisher = localPublisher;
        if (publisher != null) publisher.publish(event);
    }

    public void cancelActiveTurn(
            String requestedTurnId,
            String reason,
            AgentGatewayClient.ResultCallback callback
    ) {
        String remoteReason = AgentGatewayClient.isAllowedCancelReason(reason)
                ? reason
                : "client_request";
        String current;
        synchronized (this) {
            current = activeTurnId;
            if ((current == null || current.isEmpty()) && awaitingTurnAcceptance) {
                boolean robotStopped = robotGateway.emergencyStop();
                releaseAnyPhysicalTool();
                if (pendingUploadCancel == null) {
                    pendingUploadCancel = new PendingUploadCancel(
                            remoteReason,
                            robotStopped,
                            callback
                    );
                } else {
                    pendingUploadCancel.robotStopped |= robotStopped;
                    pendingUploadCancel.callbacks.add(callback);
                }
                publishRobotState();
                return;
            }
        }
        boolean targetsCurrent = current != null && !current.isEmpty()
                && (requestedTurnId == null || requestedTurnId.isEmpty() || requestedTurnId.equals(current));
        if (targetsCurrent) turnAuthority.revoke(current);
        boolean robotStopped = robotGateway.emergencyStop();
        releaseAnyPhysicalTool();
        publishRobotState();
        if (current == null || current.isEmpty()
                || (requestedTurnId != null && !requestedTurnId.isEmpty() && !requestedTurnId.equals(current))) {
            callback.onSuccess(json("remoteCancelled", false, "robotStopped", robotStopped));
            return;
        }
        synchronized (cancelRequestedTurns) {
            if (cancelRequestedTurns.contains(current)) {
                callback.onSuccess(json("remoteCancelled", true, "robotStopped", robotStopped));
                return;
            }
            cancelRequestedTurns.add(current);
        }
        gatewayClient.cancelTurn(current, remoteReason, new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) {
                callback.onSuccess(json("remoteCancelled", true, "robotStopped", robotStopped));
            }

            @Override public void onError(String code, String message) {
                synchronized (cancelRequestedTurns) { cancelRequestedTurns.remove(current); }
                turnState = "ERROR";
                publishGatewayFailure(code, "Gateway turn cancellation failed");
                callback.onError(code, message);
            }
        });
    }

    private synchronized PendingUploadCancel claimPendingUploadCancel(String remoteTurnId) {
        if (pendingUploadCancel == null || remoteTurnId == null || remoteTurnId.isEmpty()) return null;
        PendingUploadCancel pending = pendingUploadCancel;
        pendingUploadCancel = null;
        uploadingClientTurnId = null;
        invalidateUploadCallbackLocked();
        awaitingTurnAcceptance = false;
        activeTurnId = null;
        turnState = "IDLE";
        turnAuthority.revoke(remoteTurnId);
        synchronized (cancelRequestedTurns) { cancelRequestedTurns.add(remoteTurnId); }
        return pending;
    }

    private void sendPendingUploadCancel(String remoteTurnId, PendingUploadCancel pending) {
        gatewayClient.cancelTurn(remoteTurnId, pending.reason, new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) {
                completePendingCancel(pending, true, null, null);
            }

            @Override public void onError(String code, String message) {
                turnState = "ERROR";
                synchronized (cancelRequestedTurns) { cancelRequestedTurns.remove(remoteTurnId); }
                publishGatewayFailure(code, "Gateway turn cancellation failed");
                completePendingCancel(pending, false, code, message);
            }
        });
    }

    private void retryRevokedTurnCancel(String remoteTurnId) {
        synchronized (cancelRequestedTurns) {
            cancelRequestedTurns.add(remoteTurnId);
        }
        gatewayClient.cancelTurn(remoteTurnId, "client_request", new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) { }

            @Override public void onError(String code, String message) {
                synchronized (cancelRequestedTurns) { cancelRequestedTurns.remove(remoteTurnId); }
                turnState = "ERROR";
                publishGatewayFailure(code, "Gateway turn cancellation failed");
            }
        });
    }

    private static void completePendingCancel(
            PendingUploadCancel pending,
            boolean remoteCancelled,
            String errorCode,
            String errorMessage
    ) {
        for (AgentGatewayClient.ResultCallback callback : pending.callbacks) {
            if (errorCode == null) {
                callback.onSuccess(json(
                        "remoteCancelled", remoteCancelled,
                        "robotStopped", pending.robotStopped
                ));
            } else {
                callback.onError(errorCode, errorMessage);
            }
        }
    }

    private void publishGatewayFailure(String code, String detail) {
        String errorCode = canonicalGatewayErrorCode(code);
        publishLocal(localEnvelope("local.gateway.state", json(
                "state", getGatewayState(),
                "detail", detail,
                "errorCode", errorCode
        )));
    }

    private static String canonicalGatewayErrorCode(String code) {
        if ("GATEWAY_AUTH".equals(code)) return "GATEWAY_AUTH";
        if ("GATEWAY_TLS".equals(code)) return "GATEWAY_TLS";
        if ("GATEWAY_INCOMPATIBLE".equals(code)) return "GATEWAY_INCOMPATIBLE";
        if ("GATEWAY_UNCONFIGURED".equals(code)) return "GATEWAY_UNCONFIGURED";
        return "GATEWAY_OFFLINE";
    }

    public void reportToolResult(String callId, JSONObject update, AgentGatewayClient.ResultCallback callback) {
        String status = update.optString("status", "");
        synchronized (terminalToolCallTimes) {
            if (terminalToolCalls.contains(callId)) {
                JSONObject terminal = terminalToolResults.get(callId);
                callback.onSuccess(json(
                        "callId", callId,
                        "status", terminal != null ? terminal.optString("status", status) : status
                ));
                return;
            }
        }
        String toolTurnId;
        synchronized (pendingWebToolTurns) {
            toolTurnId = pendingWebToolTurns.get(callId);
        }
        if (toolTurnId == null) {
            callback.onError("TOOL_REJECTED", "Tool call is not pending for the renderer");
            return;
        }
        if ("accepted".equals(status)) {
            callback.onSuccess(json("callId", callId, "status", "accepted"));
            return;
        }
        if (!("succeeded".equals(status) || "failed".equals(status) || "rejected".equals(status))) {
            callback.onError("TOOL_REJECTED", "Tool status is invalid");
            return;
        }
        final boolean[] claimed = {false};
        boolean authorized = turnAuthority.runIfAuthorized(toolTurnId, () -> {
            synchronized (pendingWebToolTurns) {
                if (!toolTurnId.equals(pendingWebToolTurns.get(callId))) return;
                pendingWebToolTurns.remove(callId);
            }
            claimed[0] = true;
            cancelToolTimeout(callId);
            markTerminal(callId, update);
            gatewayClient.reportToolResult(callId, update, new AgentGatewayClient.ResultCallback() {
                @Override public void onSuccess(JSONObject ignored) {
                    callback.onSuccess(json("callId", callId, "status", status));
                }

                @Override public void onError(String code, String message) {
                    callback.onError(code, message);
                }
            });
        });
        if (authorized && claimed[0]) return;
        synchronized (terminalToolCallTimes) {
            JSONObject terminal = terminalToolResults.get(callId);
            if (terminal != null) {
                callback.onSuccess(json(
                        "callId", callId,
                        "status", terminal.optString("status", "rejected")
                ));
                return;
            }
        }
        callback.onError("TOOL_REJECTED", "Tool turn is no longer active");
    }

    public void reportPlayback(JSONObject update, AgentGatewayClient.ResultCallback callback) {
        String artifactId = update.optString("artifactId", "");
        String turnId = update.optString("turnId", "");
        String expectedTurn;
        synchronized (playbackArtifactTurns) { expectedTurn = playbackArtifactTurns.get(artifactId); }
        if (expectedTurn == null || !expectedTurn.equals(turnId)) {
            callback.onError("CONFLICT", "Playback artifact is not correlated with this turn");
            return;
        }
        String status = update.optString("status", "");
        if ("started".equals(status)) turnState = "SPEAKING";
        gatewayClient.reportPlayback(update, new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                if ("completed".equals(status) || "interrupted".equals(status)) {
                    synchronized (playbackArtifactTurns) { playbackArtifactTurns.remove(artifactId); }
                    turnState = "IDLE";
                }
                callback.onSuccess(json("turnId", turnId, "artifactId", artifactId, "status", status));
            }

            @Override public void onError(String code, String message) {
                callback.onError(code, message);
            }
        });
    }

    public void downloadAudio(String artifactId, AgentGatewayClient.BinaryCallback callback) {
        JSONObject expected;
        synchronized (audioArtifacts) { expected = audioArtifacts.remove(artifactId); }
        if (expected == null) {
            callback.onError("AUDIO_NOT_AVAILABLE", "Audio artifact is unknown or already consumed");
            return;
        }
        gatewayClient.downloadAudio(artifactId, expected, callback);
    }

    private static final AgentGatewayClient.ResultCallback NO_OP_CALLBACK = new AgentGatewayClient.ResultCallback() {
        @Override public void onSuccess(JSONObject result) { }
        @Override public void onError(String code, String message) { }
    };

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static JSONObject localEnvelope(String type, JSONObject data) {
        return json(
                "protocolVersion", "1.0",
                "eventId", UUID.randomUUID().toString(),
                "type", type,
                "timestamp", isoNow(),
                "data", data
        );
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

    private static JSONObject toolUpdate(String status, JSONObject output, JSONObject error) {
        JSONObject update = new JSONObject();
        try {
            update.put("status", status).put("updatedAt", isoNow());
            if (output != null) update.put("output", output);
            if (error != null) update.put("error", error);
        } catch (JSONException ignored) { }
        return update;
    }

    private void releasePhysicalTool(String callId) {
        releasePhysicalTool(physicalToolOwner, callId);
    }

    static boolean tryClaimPhysicalTool(
            AtomicReference<String> ownerReference,
            String callId
    ) {
        return callId != null
                && !callId.isEmpty()
                && ownerReference.compareAndSet(null, callId);
    }

    static void releasePhysicalTool(
            AtomicReference<String> ownerReference,
            String callId
    ) {
        if (callId == null || callId.isEmpty()) return;
        while (true) {
            String owner = ownerReference.get();
            if (!callId.equals(owner) || ownerReference.compareAndSet(owner, null)) return;
        }
    }

    private void releaseAnyPhysicalTool() {
        physicalToolOwner.set(null);
    }

    private void markTerminal(String callId, JSONObject terminalResult) {
        synchronized (terminalToolCallTimes) {
            toolCallLifecycle.markTerminal(callId);
            terminalToolCalls.add(callId);
            if (terminalResult != null) terminalToolResults.put(callId, terminalResult);
            terminalToolCallTimes.put(callId, System.currentTimeMillis());
            while (terminalToolCallTimes.size() > 128) {
                String oldest = terminalToolCallTimes.keySet().iterator().next();
                terminalToolCallTimes.remove(oldest);
                terminalToolCalls.remove(oldest);
                terminalToolResults.remove(oldest);
                toolCallLifecycle.forget(oldest);
            }
        }
    }

    private void scheduleToolTimeout(
            String callId,
            String name,
            String toolTurnId,
            int requestedTimeoutMs,
            String deadlineAt
    ) {
        long delay;
        try {
            long deadlineDelay = Math.max(1L, parseDeadline(deadlineAt) - System.currentTimeMillis());
            delay = Math.min(Math.min(5_000L, requestedTimeoutMs), deadlineDelay);
        } catch (Exception error) {
            delay = 1L;
        }
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            boolean authorized = turnAuthority.runIfAuthorized(toolTurnId, () -> {
                try {
                    synchronized (terminalToolCallTimes) {
                        if (terminalToolCalls.contains(callId)) return;
                    }
                    synchronized (pendingWebToolTurns) {
                        pendingWebToolTurns.remove(callId);
                    }
                    if (robotGateway.isPhysicalTool(name)) {
                        robotGateway.emergencyStop();
                        releasePhysicalTool(callId);
                        publishRobotState();
                    }
                    JSONObject error = json(
                            "code", "TIMEOUT",
                            "message", "Tool execution exceeded its deadline",
                            "retryable", false
                    );
                    JSONObject update = toolUpdate("failed", null, error);
                    markTerminal(callId, update);
                    gatewayClient.reportToolResult(callId, update, NO_OP_CALLBACK);
                } finally {
                    synchronized (toolTimeouts) {
                        toolTimeouts.remove(callId);
                    }
                }
            });
            if (!authorized) {
                synchronized (toolTimeouts) {
                    toolTimeouts.remove(callId);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
        synchronized (toolTimeouts) { toolTimeouts.put(callId, task); }
    }

    private void cancelToolTimeout(String callId) {
        ScheduledFuture<?> task;
        synchronized (toolTimeouts) { task = toolTimeouts.remove(callId); }
        if (task != null) task.cancel(false);
    }

    private void cleanupTerminalCalls() {
        synchronized (terminalToolCallTimes) {
            long cutoff = System.currentTimeMillis() - 5L * 60L * 1000L;
            java.util.Iterator<Map.Entry<String, Long>> iterator = terminalToolCallTimes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (entry.getValue() < cutoff) {
                    terminalToolCalls.remove(entry.getKey());
                    terminalToolResults.remove(entry.getKey());
                    toolCallLifecycle.forget(entry.getKey());
                    iterator.remove();
                }
            }
        }
    }

    private static long parseDeadline(String value) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date parsed = format.parse(value);
        if (parsed == null) throw new IllegalArgumentException("deadlineAt is invalid");
        return parsed.getTime();
    }

    private static void validateArguments(String name, JSONObject arguments) {
        Set<String> allowed = new HashSet<>();
        if ("start_robot_following".equals(name)) {
            allowed.add("enablePreview");
            allowed.add("largePreview");
            requireBooleanIfPresent(arguments, "enablePreview");
            requireBooleanIfPresent(arguments, "largePreview");
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

    private static void requireBooleanIfPresent(JSONObject arguments, String name) {
        if (arguments.has(name) && !(arguments.opt(name) instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
    }
}
