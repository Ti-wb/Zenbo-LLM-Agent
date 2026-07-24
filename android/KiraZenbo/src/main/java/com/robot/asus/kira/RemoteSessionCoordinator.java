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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Coordinates local renderer turns, remote Gateway events, and correlated robot tool calls. */
public final class RemoteSessionCoordinator implements AgentGatewayClient.Listener {
    public interface LocalPublisher {
        void publish(JSONObject message);
    }

    private static final int MAX_CONVERSATION_EVENTS = 100;
    private static final long TERMINAL_TOOL_RETENTION_MILLIS = 5L * 60L * 1000L;
    private static final long MAX_TOOL_RESULT_RETRY_MILLIS = 15_000L;

    private final RobotOperations robotGateway;
    private final GatewayTransport gatewayClient;
    private final ToolCallJournal toolJournal;
    private final Deque<JSONObject> conversation = new ArrayDeque<>();
    private final Set<String> cancelRequestedTurns = new HashSet<>();
    private final Map<String, PendingTurnCancel> pendingTurnCancels =
            new HashMap<>();
    private final LinkedHashMap<String, TerminalToolRecord> terminalToolRecords = new LinkedHashMap<>();
    private final Map<String, AudioArtifactLease> audioArtifacts = new HashMap<>();
    private final Map<String, AudioArtifactLease> playbackArtifacts = new HashMap<>();
    private final Map<String, PendingWebTool> pendingWebTools = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> toolTimeouts = new HashMap<>();
    private final Map<String, ToolContext> liveToolContexts = new HashMap<>();
    private final ToolCallLifecycle toolCallLifecycle = new ToolCallLifecycle();
    private final TurnAuthority turnAuthority = new TurnAuthority();
    private final ReentrantLock webPublicationFence = new ReentrantLock(true);
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;
    private volatile LocalPublisher localPublisher;
    private volatile String sessionId = UUID.randomUUID().toString();
    private volatile String activeTurnId;
    private volatile String activeRemoteSessionId;
    private volatile String reconciledRemoteSessionId;
    private volatile long reconciledSessionEpoch = -1L;
    private volatile boolean awaitingTurnAcceptance;
    private volatile String turnState = "IDLE";
    private volatile String transcript = "";
    private volatile String assistantText = "";
    private UploadContext activeUpload;
    private PendingUploadCancel pendingUploadCancel;
    private boolean physicalToolExecuting;
    private long sessionEpoch;
    private volatile Runnable beforeWebToolRegistrationHook;
    private volatile Runnable beforeGatewayMessageFenceHook;
    private volatile Runnable afterLiveDispatchJournalHook;
    private volatile Runnable beforeToolTerminalFenceHook;

    private static final class PendingWebTool {
        final ToolContext context;
        AgentGatewayClient.EventCommitCallback eventCallback;

        PendingWebTool(
                ToolContext context,
                AgentGatewayClient.EventCommitCallback eventCallback
        ) {
            this.context = context;
            this.eventCallback = eventCallback;
        }
    }

    private static final class AudioArtifactLease {
        final String sessionId;
        final long epoch;
        final String turnId;
        final JSONObject metadata;

        AudioArtifactLease(
                String sessionId,
                long epoch,
                String turnId,
                JSONObject metadata
        ) {
            this.sessionId = sessionId;
            this.epoch = epoch;
            this.turnId = turnId;
            this.metadata = metadata;
        }
    }

    private static final class ToolContext {
        final String sessionId;
        final String callId;
        final String turnId;
        final String owner;
        final String name;
        final long epoch;
        final long dispatchedAt;

        ToolContext(
                String sessionId,
                String callId,
                String turnId,
                String owner,
                String name,
                long epoch,
                long dispatchedAt
        ) {
            this.sessionId = sessionId;
            this.callId = callId;
            this.turnId = turnId;
            this.owner = owner;
            this.name = name;
            this.epoch = epoch;
            this.dispatchedAt = dispatchedAt;
        }

        ToolCallJournal.Entry journalEntry() {
            return new ToolCallJournal.Entry(
                    sessionId,
                    callId,
                    turnId,
                    owner,
                    name,
                    dispatchedAt,
                    null,
                    0L,
                    ToolCallJournal.DeliveryState.NONE
            );
        }
    }

    private static final class TerminalToolRecord {
        final ToolContext context;
        final JSONObject result;
        final long completedAt;
        final List<AgentGatewayClient.ResultCallback> waiters = new ArrayList<>();
        ScheduledFuture<?> retryTask;
        boolean acknowledged;
        boolean deliveryAbandoned;
        boolean deliveryInFlight;
        int deliveryFailures;

        TerminalToolRecord(
                ToolContext context,
                JSONObject result,
                long completedAt,
                ToolCallJournal.DeliveryState deliveryState
        ) {
            this.context = context;
            this.result = result;
            this.completedAt = completedAt;
            this.acknowledged =
                    deliveryState == ToolCallJournal.DeliveryState.ACKNOWLEDGED;
            this.deliveryAbandoned =
                    deliveryState == ToolCallJournal.DeliveryState.ABANDONED;
        }
    }

    private static final class PendingUploadCancel {
        final UploadContext upload;
        final String reason;
        final List<AgentGatewayClient.ResultCallback> callbacks = new ArrayList<>();
        boolean robotStopped;

        PendingUploadCancel(
                UploadContext upload,
                String reason,
                boolean robotStopped,
                AgentGatewayClient.ResultCallback callback
        ) {
            this.upload = upload;
            this.reason = reason;
            this.robotStopped = robotStopped;
            callbacks.add(callback);
        }
    }

    private static final class PendingTurnCancel {
        final List<AgentGatewayClient.ResultCallback> callbacks =
                new ArrayList<>();
        boolean robotStopped;

        PendingTurnCancel(
                boolean robotStopped,
                AgentGatewayClient.ResultCallback callback
        ) {
            this.robotStopped = robotStopped;
            callbacks.add(callback);
        }
    }

    private static final class UploadContext {
        final String clientTurnId;
        final String sessionId;
        final long epoch;

        UploadContext(String clientTurnId, String sessionId, long epoch) {
            this.clientTurnId = clientTurnId;
            this.sessionId = sessionId;
            this.epoch = epoch;
        }
    }

    private enum DispatchResult {
        STARTED,
        SETTLED,
        REJECTED
    }

    public RemoteSessionCoordinator(
            GatewaySettings settings,
            DeviceCredentialStore credentialStore,
            RobotGateway robotGateway,
            ToolCallJournal toolJournal
    ) {
        this.robotGateway = robotGateway;
        this.gatewayClient = new AgentGatewayClient(settings, credentialStore, robotGateway.getToolManifest(), this);
        this.toolJournal = toolJournal;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.clock = System::currentTimeMillis;
    }

    RemoteSessionCoordinator(
            RobotOperations robotGateway,
            GatewayTransport gatewayClient,
            ToolCallJournal toolJournal,
            ScheduledExecutorService scheduler,
            LongSupplier clock
    ) {
        this.robotGateway = robotGateway;
        this.gatewayClient = gatewayClient;
        this.toolJournal = toolJournal;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public void setLocalPublisher(LocalPublisher publisher) {
        localPublisher = publisher;
    }

    public void start() {
        gatewayClient.start();
    }

    public void reloadGateway() {
        // Durable identity rotation is the only failure-prone preflight. Do it before revoking
        // turns or abandoning tool deliveries so callers can roll settings back without losing
        // the still-live session.
        gatewayClient.prepareReload();
        webPublicationFence.lock();
        try {
            String oldSessionId = fenceSession();
            releaseUncommittedToolEvents(true);
            if (oldSessionId != null) {
                abandonJournalSession(oldSessionId, "SESSION_RELOADED");
            }
            gatewayClient.reload();
        } finally {
            webPublicationFence.unlock();
        }
    }

    public void stop() {
        webPublicationFence.lock();
        try {
            fenceSession();
            releaseUncommittedToolEvents(false);
            gatewayClient.shutdown();
        } finally {
            webPublicationFence.unlock();
        }
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
        String remoteSessionId = activeRemoteSessionId;
        if (remoteSessionId == null
                || remoteSessionId.isEmpty()
                || !remoteSessionId.equals(reconciledRemoteSessionId)
                || sessionEpoch != reconciledSessionEpoch) {
            throw new JSONException("Gateway session is not ready");
        }
        UploadContext upload = new UploadContext(turnId, remoteSessionId, sessionEpoch);
        awaitingTurnAcceptance = true;
        activeUpload = upload;
        turnState = "UPLOADING";
        transcript = input.optString("text", "");
        assistantText = "";
        gatewayClient.uploadTurn(
                upload.sessionId,
                input,
                new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                String remoteTurnId = result.optString("turnId", "");
                PendingUploadCancel pending;
                synchronized (RemoteSessionCoordinator.this) {
                    if (!isUploadCurrentLocked(upload)) return;
                    pending = claimPendingUploadCancelLocked(upload, remoteTurnId);
                    activeUpload = null;
                    awaitingTurnAcceptance = false;
                    if (pending == null && !turnAuthority.isRevoked(
                            upload.sessionId,
                            upload.epoch,
                            remoteTurnId
                    )) {
                        activeTurnId = remoteTurnId;
                    }
                }
                if (pending != null) sendPendingUploadCancel(remoteTurnId, pending);
            }
            @Override public void onError(String code, String message) {
                PendingUploadCancel pending;
                synchronized (RemoteSessionCoordinator.this) {
                    if (!isUploadCurrentLocked(upload)) return;
                    awaitingTurnAcceptance = false;
                    activeUpload = null;
                    activeTurnId = null;
                    turnState = "ERROR";
                    pending = pendingUploadCancel != null
                            && pendingUploadCancel.upload == upload
                            ? pendingUploadCancel
                            : null;
                    pendingUploadCancel = null;
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
        Runnable hook = beforeGatewayMessageFenceHook;
        if (hook != null) hook.run();
        webPublicationFence.lock();
        try {
            processGatewayMessage(message, eventCallback);
        } finally {
            webPublicationFence.unlock();
        }
    }

    private void processGatewayMessage(
            JSONObject message,
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        String type = message.optString("type");
        String turnId = message.optString("turnId", "");
        String messageSessionId = message.optString(
                "sessionId",
                gatewayClient.getRemoteSessionId()
        );
        if (!messageSessionId.isEmpty()
                && !bindRemoteSession(
                messageSessionId,
                "session.ready".equals(type)
        )) {
            eventCallback.retry();
            return;
        }
        long messageEpoch;
        synchronized (this) {
            if (!messageSessionId.equals(activeRemoteSessionId)) {
                eventCallback.retry();
                return;
            }
            messageEpoch = sessionEpoch;
        }
        if ("session.ready".equals(type)
                && !recoverJournalForSession(messageSessionId, messageEpoch)) {
            eventCallback.retry();
            return;
        }
        if ("turn.accepted".equals(type)) {
            PendingUploadCancel pending;
            synchronized (this) {
                pending = claimPendingUploadCancelForTurnLocked(
                        messageSessionId,
                        messageEpoch,
                        turnId
                );
            }
            if (pending != null) {
                sendPendingUploadCancel(turnId, pending);
            } else if (!turnAuthority.isRevoked(
                    messageSessionId,
                    messageEpoch,
                    turnId
            )) {
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
            if (snapshotTurnId != null && turnAuthority.isRevoked(
                    messageSessionId,
                    messageEpoch,
                    snapshotTurnId
            )) {
                clearTurnRecoveryState();
                activeTurnId = null;
                turnState = "IDLE";
                retryRevokedTurnCancel(
                        messageSessionId,
                        messageEpoch,
                        snapshotTurnId
                );
            } else {
                if (snapshotTurnId != null && !snapshotTurnId.equals(activeTurnId)) clearTurnRecoveryState();
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
        if ("turn.completed".equals(type) || "turn.error".equals(type) || "turn.cancelled".equals(type)) {
            String terminalCode = "turn.cancelled".equals(type)
                    ? "TURN_CANCELLED"
                    : "turn.error".equals(type) ? "TURN_ERROR" : "TURN_COMPLETED";
            boolean terminalSettled;
            webPublicationFence.lock();
            try {
                synchronized (this) {
                    if (!isSessionLeaseCurrent(
                            messageSessionId,
                            messageEpoch
                    )) {
                        eventCallback.retry();
                        return;
                    }
                    turnAuthority.revoke(
                            messageSessionId,
                            messageEpoch,
                            turnId
                    );
                }
                boolean webSettled = terminalizePendingWebTools(
                        messageSessionId,
                        messageEpoch,
                        turnId,
                        terminalCode,
                        "Gateway turn reached a terminal state",
                        false
                );
                boolean nativeSettled = terminalizeDispatchedNativeTools(
                        messageSessionId,
                        messageEpoch,
                        turnId,
                        terminalCode,
                        "Gateway turn reached a terminal state",
                        false
                );
                terminalSettled = webSettled && nativeSettled;
            } finally {
                webPublicationFence.unlock();
            }
            if (terminalSettled) {
                synchronized (this) {
                    if (!isSessionLeaseCurrent(
                            messageSessionId,
                            messageEpoch
                    )) {
                        terminalSettled = false;
                    } else if (turnId.equals(activeTurnId)) {
                        activeTurnId = null;
                        turnState = "turn.error".equals(type)
                                ? "ERROR"
                                : "IDLE";
                    }
                    if (terminalSettled) {
                        turnAuthority.forget(
                                messageSessionId,
                                messageEpoch,
                                turnId
                        );
                        synchronized (cancelRequestedTurns) {
                            cancelRequestedTurns.remove(turnLeaseKey(
                                    messageSessionId,
                                    messageEpoch,
                                    turnId
                            ));
                        }
                    }
                }
            }
            if (!terminalSettled) {
                eventCallback.retry();
                return;
            }
        }
        if ("tts.ready".equals(type) && currentTurnEvent) {
            JSONObject audio = message.optJSONObject("data");
            if (audio != null && !audio.optString("artifactId", "").isEmpty()) {
                String artifactId = audio.optString("artifactId");
                AudioArtifactLease artifact = new AudioArtifactLease(
                        messageSessionId,
                        messageEpoch,
                        turnId,
                        audio
                );
                synchronized (audioArtifacts) {
                    audioArtifacts.put(artifactId, artifact);
                }
                synchronized (playbackArtifacts) {
                    playbackArtifacts.put(artifactId, artifact);
                }
            }
        }
        if ("session.expired".equals(type) || "session.closed".equals(type)) {
            String endingSessionId = messageSessionId.isEmpty()
                    ? currentRemoteSessionId()
                    : messageSessionId;
            fenceSession();
            abandonToolDeliveriesForTerminalSession(type, endingSessionId);
            activeTurnId = null;
            awaitingTurnAcceptance = false;
            turnState = "IDLE";
            clearTurnRecoveryState();
        }
        if ("tool.call".equals(type)) {
            JSONObject toolData = message.optJSONObject("data");
            String callId = toolData != null ? toolData.optString("callId", "") : "";
            if (!callId.isEmpty() && isTerminalTool(messageSessionId, callId)) {
                eventCallback.commit();
                deliverTerminalToolResult(messageSessionId, callId, null);
                return;
            }
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
        synchronized (playbackArtifacts) { playbackArtifacts.clear(); }
    }

    private void clearTurnRecoveryState() {
        clearTurnArtifacts();
        transcript = "";
        assistantText = "";
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
        String toolSessionId = message.optString("sessionId", currentRemoteSessionId());
        ToolContext toolContext = new ToolContext(
                toolSessionId,
                callId,
                toolTurnId,
                robotGateway.isNativeTool(name) ? "native" : "web",
                name,
                currentSessionEpoch(),
                clock.getAsLong()
        );
        JSONObject arguments = data.optJSONObject("arguments");
        cleanupTerminalCalls();
        if (isTerminalTool(toolSessionId, callId)) {
            eventCallback.commit();
            deliverTerminalToolResult(toolSessionId, callId, null);
            return;
        }
        PendingWebTool pendingReplay;
        synchronized (pendingWebTools) {
            pendingReplay = pendingWebTools.get(callId);
        }
        if (pendingReplay != null) {
            if (!publishPendingWebToolReplay(
                    pendingReplay,
                    eventCallback,
                    message
            )) {
                settleFailedToolDispatch(
                        pendingReplay.context,
                        eventCallback
                );
            }
            return;
        }
        if (isTerminalTool(toolSessionId, callId)) {
            eventCallback.commit();
            return;
        }
        String lifecycleId = terminalRecordKey(toolSessionId, callId);
        if (!toolCallLifecycle.beginAcceptance(lifecycleId)) {
            if (toolCallLifecycle.isDispatched(lifecycleId)) eventCallback.commit(); else eventCallback.retry();
            return;
        }
        try {
            UUID.fromString(callId);
            UUID.fromString(toolSessionId);
            if (turnAuthority.isRevoked(
                    toolSessionId,
                    toolContext.epoch,
                    toolTurnId
            )) throw new IllegalArgumentException("Turn authority was cancelled");
            if (!robotGateway.getAllowedTools().contains(name)) throw new IllegalArgumentException("Tool is not allowlisted");
            if (!"1.0.0".equals(data.optString("toolVersion", ""))) throw new IllegalArgumentException("Tool version is incompatible");
            int timeoutMs = data.optInt("timeoutMs", 0);
            if (timeoutMs < 100 || timeoutMs > 15_000) throw new IllegalArgumentException("Tool timeoutMs is invalid");
            if (parseDeadline(data.optString("deadlineAt", ""))
                    <= clock.getAsLong()) {
                toolCallLifecycle.failAcceptance(lifecycleId);
                expireToolBeforeDispatch(toolContext, eventCallback);
                return;
            }
            validateArguments(name, arguments != null ? arguments : new JSONObject());
            if (robotGateway.isPhysicalTool(name)) {
                synchronized (this) {
                    if (!message.optString("turnId", "").equals(activeTurnId)) throw new IllegalArgumentException("Physical tool is not for the active user turn");
                    if (physicalToolExecuting) throw new IllegalArgumentException("Another physical tool is already executing");
                    physicalToolExecuting = true;
                }
            }
        } catch (Exception error) {
            toolCallLifecycle.failAcceptance(lifecycleId);
            rejectToolCall(message, "TOOL_REJECTED", error.getMessage(), eventCallback);
            return;
        }

        JSONObject accepted = toolUpdate("accepted", null, null);
        gatewayClient.reportToolResult(
                toolSessionId,
                callId,
                accepted,
                new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                if (!SharedPreferencesToolCallJournal.isValidToolUpdate(
                        toolContext.owner,
                        toolContext.name,
                        result,
                        true
                )) {
                    settleAcceptedToolConflict(
                            toolContext,
                            lifecycleId,
                            "Gateway returned an invalid tool acceptance",
                            eventCallback
                    );
                    if (robotGateway.isPhysicalTool(name)) {
                        releasePhysicalTool();
                    }
                    return;
                }
                if (!"accepted".equals(result.optString("status"))) {
                    settleAcceptedToolTerminal(
                            toolContext,
                            lifecycleId,
                            result,
                            eventCallback
                    );
                    if (robotGateway.isPhysicalTool(name)) {
                        releasePhysicalTool();
                    }
                    return;
                }
                if (!activateLiveDispatch(toolContext, lifecycleId)) {
                    if (robotGateway.isPhysicalTool(name)) releasePhysicalTool();
                    settleFailedToolDispatch(toolContext, eventCallback);
                    return;
                }
                if (!isToolContextCurrent(toolContext)
                        || turnAuthority.isRevoked(
                        toolContext.sessionId,
                        toolContext.epoch,
                        toolTurnId
                )) {
                    if (robotGateway.isPhysicalTool(name)) releasePhysicalTool();
                    rejectToolCall(message, "TURN_CANCELLED", "Turn authority was cancelled", eventCallback);
                    return;
                }
                if (!robotGateway.isNativeTool(name)) {
                    Runnable hook = beforeWebToolRegistrationHook;
                    if (hook != null) hook.run();
                    DispatchResult dispatchResult = registerAndPublishWebTool(
                            toolContext,
                            eventCallback,
                            message,
                            data.optInt("timeoutMs", 5_000),
                            data.optString("deadlineAt", "")
                    );
                    if (dispatchResult == DispatchResult.REJECTED) {
                        settleFailedToolDispatch(toolContext, eventCallback);
                    }
                    return;
                }
                DispatchResult dispatchResult = publishAndStartNativeTool(
                        toolContext,
                        message,
                        arguments,
                        eventCallback,
                        data.optInt("timeoutMs", 5_000),
                        data.optString("deadlineAt", "")
                );
                if (dispatchResult != DispatchResult.STARTED
                        && robotGateway.isPhysicalTool(name)) {
                    releasePhysicalTool();
                }
                if (dispatchResult == DispatchResult.REJECTED) {
                    settleFailedToolDispatch(toolContext, eventCallback);
                }
            }
            @Override public void onError(String code, String detail) {
                if ("CONFLICT".equals(code)) {
                    settleAcceptedToolConflict(
                            toolContext,
                            lifecycleId,
                            detail,
                            eventCallback
                    );
                    if (robotGateway.isPhysicalTool(name)) {
                        releasePhysicalTool();
                    }
                    return;
                }
                toolCallLifecycle.failAcceptance(lifecycleId);
                if (robotGateway.isPhysicalTool(name)) releasePhysicalTool();
                eventCallback.retry();
            }
        });
    }

    private DispatchResult publishAndStartNativeTool(
            ToolContext context,
            JSONObject message,
            JSONObject arguments,
            AgentGatewayClient.EventCommitCallback eventCallback,
            int requestedTimeoutMs,
            String deadlineAt
    ) {
        webPublicationFence.lock();
        try {
            synchronized (this) {
                if (!isToolContextCurrent(context)
                        || isTerminalTool(
                        context.sessionId,
                        context.callId
                )) {
                    return DispatchResult.REJECTED;
                }
            }
            if (deadlineExpired(deadlineAt)) {
                return expireToolBeforeDispatch(context, eventCallback);
            }
            scheduleToolTimeoutLocked(
                    context,
                    requestedTimeoutMs,
                    deadlineAt
            );
            if (deadlineExpired(deadlineAt)) {
                cancelToolTimeout(context.callId);
                return expireToolBeforeDispatch(context, eventCallback);
            }
            if (isTerminalTool(context.sessionId, context.callId)) {
                eventCallback.commit();
                deliverTerminalToolResult(
                        context.sessionId,
                        context.callId,
                        null
                );
                return DispatchResult.SETTLED;
            }
            remember(message);
            publishLocal(message);
            if (deadlineExpired(deadlineAt)) {
                cancelToolTimeout(context.callId);
                return expireToolBeforeDispatch(context, eventCallback);
            }
            boolean started = runIfToolContextAuthorized(context, () -> {
                executeNativeTool(context, arguments);
                eventCallback.commit();
            });
            return started
                    ? DispatchResult.STARTED
                    : DispatchResult.REJECTED;
        } finally {
            webPublicationFence.unlock();
        }
    }

    private void executeNativeTool(ToolContext toolContext, JSONObject arguments) {
        robotGateway.execute(toolContext.callId, toolContext.name, arguments,
                action -> runIfToolContextAuthorized(toolContext, action), result -> {
            try {
                boolean success = !"error".equals(result.optString("status"));
                JSONObject error = result.optJSONObject("error");
                JSONObject normalizedError = error == null ? null : new JSONObject()
                        .put("code", error.optString("code", "EXECUTION_FAILED").toUpperCase(Locale.US))
                        .put("message", error.optString("message", "Tool execution failed"))
                        .put("retryable", false);
                JSONObject update = toolUpdate(success ? "succeeded" : "failed", result.optJSONObject("result"), normalizedError);
                Runnable hook = beforeToolTerminalFenceHook;
                if (hook != null) hook.run();
                webPublicationFence.lock();
                try {
                    if (!isToolContextCurrent(toolContext)
                            || isTerminalTool(
                            toolContext.sessionId,
                            toolContext.callId
                    )) {
                        return;
                    }
                    if (!captureTerminal(toolContext, update)) return;
                    cancelToolTimeout(toolContext.callId);
                } finally {
                    webPublicationFence.unlock();
                }
                deliverTerminalToolResult(
                        toolContext.sessionId,
                        toolContext.callId,
                        null
                );
            } catch (JSONException ignored) {
            } finally {
                if (robotGateway.isPhysicalTool(toolContext.name)) releasePhysicalTool();
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
        String name = data != null ? data.optString("toolName", "") : "";
        ToolContext context = new ToolContext(
                message.optString("sessionId", currentRemoteSessionId()),
                callId,
                message.optString("turnId", ""),
                robotGateway.isNativeTool(name) ? "native" : "web",
                name,
                currentSessionEpoch(),
                clock.getAsLong()
        );
        if (!captureTerminal(context, update)) {
            eventCallback.retry();
            return;
        }
        eventCallback.commit();
        cancelToolTimeout(callId);
        deliverTerminalToolResult(context.sessionId, callId, null);
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
        String current;
        String expectedSessionId;
        long expectedEpoch;
        boolean pendingUploadWasCancelled = false;
        boolean stalePendingUploadCancel = false;
        boolean staleActiveTurnCancel = false;
        webPublicationFence.lock();
        try {
            synchronized (this) {
                current = activeTurnId;
                expectedSessionId = activeRemoteSessionId;
                expectedEpoch = sessionEpoch;
                if ((current == null || current.isEmpty())
                        && awaitingTurnAcceptance
                        && activeUpload != null) {
                    boolean targetsUpload = requestedTurnId == null
                            || requestedTurnId.isEmpty()
                            || requestedTurnId.equals(
                            activeUpload.clientTurnId
                    );
                    if (!targetsUpload) {
                        stalePendingUploadCancel = true;
                    } else {
                        boolean robotStopped = robotGateway.emergencyStop();
                        physicalToolExecuting = false;
                        if (pendingUploadCancel == null) {
                            pendingUploadCancel = new PendingUploadCancel(
                                    activeUpload,
                                    reason,
                                    robotStopped,
                                    callback
                            );
                        } else {
                            pendingUploadCancel.robotStopped |= robotStopped;
                            pendingUploadCancel.callbacks.add(callback);
                        }
                        pendingUploadWasCancelled = true;
                    }
                } else {
                    boolean targetsCurrent = current != null && !current.isEmpty()
                            && (requestedTurnId == null
                            || requestedTurnId.isEmpty()
                            || requestedTurnId.equals(current));
                    if (targetsCurrent) {
                        turnAuthority.revoke(
                                expectedSessionId,
                                expectedEpoch,
                                current
                        );
                    } else if (requestedTurnId != null
                            && !requestedTurnId.isEmpty()) {
                        staleActiveTurnCancel = true;
                    }
                }
            }
            boolean targetsCurrent = current != null && !current.isEmpty()
                    && (requestedTurnId == null
                    || requestedTurnId.isEmpty()
                    || requestedTurnId.equals(current));
            if (targetsCurrent && !pendingUploadWasCancelled) {
                terminalizePendingWebTools(
                        expectedSessionId,
                        expectedEpoch,
                        current,
                        "TURN_CANCELLED",
                        "Turn authority was cancelled",
                        true
                );
                terminalizeDispatchedNativeTools(
                        expectedSessionId,
                        expectedEpoch,
                        current,
                        "TURN_CANCELLED",
                        "Turn authority was cancelled",
                        true
                );
            }
        } finally {
            webPublicationFence.unlock();
        }
        if (stalePendingUploadCancel || staleActiveTurnCancel) {
            callback.onSuccess(json(
                    "remoteCancelled", false,
                    "robotStopped", false
            ));
            return;
        }
        if (pendingUploadWasCancelled) {
            publishRobotState();
            return;
        }
        boolean robotStopped = robotGateway.emergencyStop();
        releasePhysicalTool();
        publishRobotState();
        if (current == null || current.isEmpty()
                || (requestedTurnId != null && !requestedTurnId.isEmpty() && !requestedTurnId.equals(current))) {
            callback.onSuccess(json("remoteCancelled", false, "robotStopped", robotStopped));
            return;
        }
        String cancelKey = turnLeaseKey(expectedSessionId, expectedEpoch, current);
        PendingTurnCancel pendingCancel;
        synchronized (pendingTurnCancels) {
            PendingTurnCancel existing = pendingTurnCancels.get(cancelKey);
            if (existing != null) {
                existing.robotStopped |= robotStopped;
                existing.callbacks.add(callback);
                return;
            }
            synchronized (cancelRequestedTurns) {
                if (cancelRequestedTurns.contains(cancelKey)) {
                    callback.onSuccess(json(
                            "remoteCancelled", true,
                            "robotStopped", robotStopped
                    ));
                    return;
                }
                cancelRequestedTurns.add(cancelKey);
            }
            pendingCancel = new PendingTurnCancel(robotStopped, callback);
            pendingTurnCancels.put(cancelKey, pendingCancel);
        }
        gatewayClient.cancelTurn(
                expectedSessionId,
                current,
                reason,
                new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) {
                completeActiveTurnCancel(
                        cancelKey,
                        pendingCancel,
                        true,
                        null,
                        null
                );
            }

            @Override public void onError(String code, String message) {
                synchronized (cancelRequestedTurns) {
                    cancelRequestedTurns.remove(cancelKey);
                }
                if (isSessionLeaseCurrent(expectedSessionId, expectedEpoch)) {
                    turnState = "ERROR";
                    publishGatewayFailure(
                            code,
                            "Gateway turn cancellation failed"
                    );
                }
                completeActiveTurnCancel(
                        cancelKey,
                        pendingCancel,
                        false,
                        code,
                        message
                );
            }
        });
    }

    private void completeActiveTurnCancel(
            String cancelKey,
            PendingTurnCancel expected,
            boolean remoteCancelled,
            String errorCode,
            String errorMessage
    ) {
        List<AgentGatewayClient.ResultCallback> callbacks;
        boolean robotStopped;
        synchronized (pendingTurnCancels) {
            if (pendingTurnCancels.get(cancelKey) != expected) return;
            pendingTurnCancels.remove(cancelKey);
            callbacks = new ArrayList<>(expected.callbacks);
            robotStopped = expected.robotStopped;
        }
        for (AgentGatewayClient.ResultCallback callback : callbacks) {
            if (errorCode == null) {
                callback.onSuccess(json(
                        "remoteCancelled", remoteCancelled,
                        "robotStopped", robotStopped
                ));
            } else {
                callback.onError(errorCode, errorMessage);
            }
        }
    }

    private boolean isUploadCurrentLocked(UploadContext upload) {
        return upload != null
                && activeUpload == upload
                && upload.epoch == sessionEpoch
                && upload.sessionId.equals(activeRemoteSessionId);
    }

    private PendingUploadCancel claimPendingUploadCancelLocked(
            UploadContext upload,
            String remoteTurnId
    ) {
        if (!isUploadCurrentLocked(upload)
                || pendingUploadCancel == null
                || pendingUploadCancel.upload != upload
                || remoteTurnId == null
                || remoteTurnId.isEmpty()) {
            return null;
        }
        PendingUploadCancel pending = pendingUploadCancel;
        pendingUploadCancel = null;
        activeUpload = null;
        awaitingTurnAcceptance = false;
        activeTurnId = null;
        turnState = "IDLE";
        turnAuthority.revoke(upload.sessionId, upload.epoch, remoteTurnId);
        synchronized (cancelRequestedTurns) {
            cancelRequestedTurns.add(
                    turnLeaseKey(upload.sessionId, upload.epoch, remoteTurnId)
            );
        }
        return pending;
    }

    private PendingUploadCancel claimPendingUploadCancelForTurnLocked(
            String sessionId,
            long epoch,
            String remoteTurnId
    ) {
        UploadContext upload = activeUpload;
        if (upload == null
                || upload.epoch != epoch
                || !upload.sessionId.equals(sessionId)) {
            return null;
        }
        return claimPendingUploadCancelLocked(upload, remoteTurnId);
    }

    private void sendPendingUploadCancel(String remoteTurnId, PendingUploadCancel pending) {
        String cancelKey = turnLeaseKey(
                pending.upload.sessionId,
                pending.upload.epoch,
                remoteTurnId
        );
        gatewayClient.cancelTurn(
                pending.upload.sessionId,
                remoteTurnId,
                pending.reason,
                new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) {
                completePendingCancel(pending, true, null, null);
            }

            @Override public void onError(String code, String message) {
                if (isSessionLeaseCurrent(
                        pending.upload.sessionId,
                        pending.upload.epoch
                )) {
                    turnState = "ERROR";
                }
                synchronized (cancelRequestedTurns) {
                    cancelRequestedTurns.remove(cancelKey);
                }
                publishGatewayFailure(code, "Gateway turn cancellation failed");
                completePendingCancel(pending, false, code, message);
            }
        });
    }

    private void retryRevokedTurnCancel(
            String expectedSessionId,
            long expectedEpoch,
            String remoteTurnId
    ) {
        String cancelKey = turnLeaseKey(
                expectedSessionId,
                expectedEpoch,
                remoteTurnId
        );
        synchronized (cancelRequestedTurns) {
            if (cancelRequestedTurns.contains(cancelKey)) return;
            cancelRequestedTurns.add(cancelKey);
        }
        gatewayClient.cancelTurn(
                expectedSessionId,
                remoteTurnId,
                "client_request",
                new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject ignored) { }

            @Override public void onError(String code, String message) {
                synchronized (cancelRequestedTurns) {
                    cancelRequestedTurns.remove(cancelKey);
                }
                if (isSessionLeaseCurrent(expectedSessionId, expectedEpoch)) {
                    turnState = "ERROR";
                }
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
        boolean terminalStatus = "succeeded".equals(status)
                || "failed".equals(status)
                || "rejected".equals(status);
        if (!"accepted".equals(status) && !terminalStatus) {
            callback.onError("TOOL_REJECTED", "Tool status is invalid");
            return;
        }
        if (terminalStatus) {
            Runnable hook = beforeToolTerminalFenceHook;
            if (hook != null) hook.run();
        }
        webPublicationFence.lock();
        try {
            if (isTerminalTool(callId)) {
                deliverTerminalToolResult(callId, callback);
                return;
            }
            PendingWebTool pending;
            synchronized (pendingWebTools) {
                pending = pendingWebTools.get(callId);
            }
            if (pending == null) {
                callback.onError(
                        "TOOL_REJECTED",
                        "Tool call is not pending for the renderer"
                );
                return;
            }
            if ("accepted".equals(status)) {
                callback.onSuccess(json(
                        "callId", callId,
                        "status", "accepted"
                ));
                return;
            }
            if (!SharedPreferencesToolCallJournal.isValidToolUpdate(
                    pending.context.owner,
                    pending.context.name,
                    update,
                    false
            )) {
                callback.onError(
                        "TOOL_REJECTED",
                        "Tool result does not match the fixed manifest"
                );
                return;
            }
            if (!isToolContextCurrent(pending.context)) {
                if (isTerminalTool(
                        pending.context.sessionId,
                        callId
                )) {
                    deliverTerminalToolResult(
                            pending.context.sessionId,
                            callId,
                            callback
                    );
                } else {
                    callback.onError(
                            "TURN_CANCELLED",
                            "Turn authority ended before the tool result was accepted"
                    );
                }
                return;
            }
            if (!captureTerminal(pending.context, update)) {
                callback.onError(
                        "JOURNAL_WRITE_FAILED",
                        "Could not persist terminal tool result"
                );
                return;
            }
            cancelToolTimeout(callId);
            synchronized (pendingWebTools) {
                if (pendingWebTools.get(callId) == pending) {
                    pendingWebTools.remove(callId);
                }
            }
            pending.eventCallback.commit();
            deliverTerminalToolResult(
                    pending.context.sessionId,
                    callId,
                    callback
            );
        } finally {
            webPublicationFence.unlock();
        }
    }

    public void reportPlayback(JSONObject update, AgentGatewayClient.ResultCallback callback) {
        String artifactId = update.optString("artifactId", "");
        String turnId = update.optString("turnId", "");
        String status = update.optString("status", "");
        AudioArtifactLease artifact;
        webPublicationFence.lock();
        try {
            synchronized (playbackArtifacts) {
                artifact = playbackArtifacts.get(artifactId);
            }
            if (artifact == null || !artifact.turnId.equals(turnId)) {
                callback.onError(
                        "CONFLICT",
                        "Playback artifact is not correlated with this turn"
                );
                return;
            }
            if (!isSessionLeaseCurrent(artifact.sessionId, artifact.epoch)) {
                callback.onError(
                        "SESSION_EXPIRED",
                        "Playback artifact belongs to a replaced Gateway session"
                );
                return;
            }
            if ("started".equals(status)
                    && (activeTurnId == null
                    || artifact.turnId.equals(activeTurnId))) {
                turnState = "SPEAKING";
            }
            gatewayClient.reportPlayback(
                    artifact.sessionId,
                    update,
                    new AgentGatewayClient.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                webPublicationFence.lock();
                try {
                    AudioArtifactLease current;
                    synchronized (playbackArtifacts) {
                        current = playbackArtifacts.get(artifactId);
                    }
                    if (!isSessionLeaseCurrent(
                            artifact.sessionId,
                            artifact.epoch
                    ) || current != artifact) {
                        callback.onError(
                                "SESSION_EXPIRED",
                                "Gateway session changed before playback was recorded"
                        );
                        return;
                    }
                    if ("completed".equals(status)
                            || "interrupted".equals(status)) {
                        synchronized (playbackArtifacts) {
                            if (playbackArtifacts.get(artifactId) == artifact) {
                                playbackArtifacts.remove(artifactId);
                            }
                        }
                        if (activeTurnId == null
                                || artifact.turnId.equals(activeTurnId)) {
                            turnState = "IDLE";
                        }
                    }
                    callback.onSuccess(json(
                            "turnId", turnId,
                            "artifactId", artifactId,
                            "status", status
                    ));
                } finally {
                    webPublicationFence.unlock();
                }
            }

            @Override public void onError(String code, String message) {
                webPublicationFence.lock();
                try {
                    if (!isSessionLeaseCurrent(
                            artifact.sessionId,
                            artifact.epoch
                    )) {
                        callback.onError(
                                "SESSION_EXPIRED",
                                "Gateway session changed before playback was recorded"
                        );
                        return;
                    }
                    callback.onError(code, message);
                } finally {
                    webPublicationFence.unlock();
                }
            }
                    }
            );
        } finally {
            webPublicationFence.unlock();
        }
    }

    public void downloadAudio(String artifactId, AgentGatewayClient.BinaryCallback callback) {
        AudioArtifactLease artifact;
        webPublicationFence.lock();
        try {
            synchronized (audioArtifacts) {
                artifact = audioArtifacts.get(artifactId);
                if (artifact != null
                        && isSessionLeaseCurrent(
                        artifact.sessionId,
                        artifact.epoch
                )) {
                    audioArtifacts.remove(artifactId);
                }
            }
            if (artifact == null) {
                callback.onError(
                        "AUDIO_NOT_AVAILABLE",
                        "Audio artifact is unknown or already consumed"
                );
                return;
            }
            if (!isSessionLeaseCurrent(artifact.sessionId, artifact.epoch)) {
                callback.onError(
                        "SESSION_EXPIRED",
                        "Audio artifact belongs to a replaced Gateway session"
                );
                return;
            }
            gatewayClient.downloadAudio(
                    artifact.sessionId,
                    artifactId,
                    artifact.metadata,
                    new AgentGatewayClient.BinaryCallback() {
                        @Override public void onSuccess(
                                byte[] bytes,
                                String contentType,
                                String digest,
                                String expiresAt
                        ) {
                            webPublicationFence.lock();
                            try {
                                if (!isSessionLeaseCurrent(
                                        artifact.sessionId,
                                        artifact.epoch
                                )) {
                                    callback.onError(
                                            "SESSION_EXPIRED",
                                            "Gateway session changed before audio download completed"
                                    );
                                    return;
                                }
                                callback.onSuccess(
                                        bytes,
                                        contentType,
                                        digest,
                                        expiresAt
                                );
                            } finally {
                                webPublicationFence.unlock();
                            }
                        }

                        @Override public void onError(
                                String code,
                                String message
                        ) {
                            webPublicationFence.lock();
                            try {
                                if (!isSessionLeaseCurrent(
                                        artifact.sessionId,
                                        artifact.epoch
                                )) {
                                    callback.onError(
                                            "SESSION_EXPIRED",
                                            "Gateway session changed before audio download completed"
                                    );
                                    return;
                                }
                                callback.onError(code, message);
                            } finally {
                                webPublicationFence.unlock();
                            }
                        }
                    }
            );
        } finally {
            webPublicationFence.unlock();
        }
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

    private synchronized void releasePhysicalTool() {
        physicalToolExecuting = false;
    }

    private static String terminalRecordKey(String remoteSessionId, String callId) {
        return (remoteSessionId != null ? remoteSessionId : "") + "\n" + callId;
    }

    private static String turnLeaseKey(
            String remoteSessionId,
            long epoch,
            String turnId
    ) {
        return (remoteSessionId != null ? remoteSessionId : "")
                + "\n" + epoch + "\n" + (turnId != null ? turnId : "");
    }

    private boolean isTerminalTool(String callId) {
        return isTerminalTool(currentRemoteSessionId(), callId);
    }

    private boolean isTerminalTool(String remoteSessionId, String callId) {
        synchronized (terminalToolRecords) {
            return terminalToolRecords.containsKey(
                    terminalRecordKey(remoteSessionId, callId)
            );
        }
    }

    private void deliverTerminalToolResult(
            String callId,
            AgentGatewayClient.ResultCallback callback
    ) {
        deliverTerminalToolResult(currentRemoteSessionId(), callId, callback);
    }

    private void deliverTerminalToolResult(
            String remoteSessionId,
            String callId,
            AgentGatewayClient.ResultCallback callback
    ) {
        TerminalToolRecord record;
        boolean acknowledged;
        boolean abandoned;
        synchronized (terminalToolRecords) {
            record = terminalToolRecords.get(
                    terminalRecordKey(remoteSessionId, callId)
            );
            if (record == null) {
                if (callback != null) {
                    callback.onError("TOOL_REJECTED", "Terminal tool result is no longer retained");
                }
                return;
            }
            if (!record.context.sessionId.equals(currentRemoteSessionId())) {
                toolJournal.markAbandoned(record.context.sessionId, callId);
                record.deliveryAbandoned = true;
                if (callback != null) {
                    callback.onError("SESSION_EXPIRED", "Tool result belongs to a different session");
                }
                return;
            }
            acknowledged = record.acknowledged;
            abandoned = record.deliveryAbandoned;
            if (abandoned) {
                if (callback != null) {
                    callback.onError("SESSION_EXPIRED", "Tool result belongs to a terminal session");
                }
                return;
            }
            if (!acknowledged && callback != null) record.waiters.add(callback);
            if (acknowledged || record.deliveryInFlight) {
                if (!acknowledged) return;
            } else {
                record.deliveryInFlight = true;
            }
        }
        if (acknowledged) {
            if (callback != null) callback.onSuccess(terminalToolResponse(callId, record.result));
            return;
        }

        try {
            gatewayClient.reportToolResult(
                    record.context.sessionId,
                    callId,
                    record.result,
                    new AgentGatewayClient.ResultCallback() {
                @Override public void onSuccess(JSONObject result) {
                    if (!SharedPreferencesToolCallJournal.isValidToolUpdate(
                            record.context.owner,
                            record.context.name,
                            result,
                            false
                    ) || !terminalUpdatesEquivalent(
                            record.result,
                            result
                    )) {
                        failTerminalToolDelivery(
                                callId,
                                record,
                                "CONFLICT",
                                "Gateway acknowledged a different terminal tool result"
                        );
                        return;
                    }
                    completeTerminalToolDelivery(callId, record);
                }

                @Override public void onError(String code, String message) {
                    failTerminalToolDelivery(callId, record, code, message);
                }
            });
        } catch (RuntimeException error) {
            failTerminalToolDelivery(
                    callId,
                    record,
                    "GATEWAY_UNAVAILABLE",
                    error.getMessage() != null ? error.getMessage() : "Gateway tool-result upload failed"
            );
        }
    }

    private void completeTerminalToolDelivery(String callId, TerminalToolRecord deliveredRecord) {
        List<AgentGatewayClient.ResultCallback> waiters;
        ScheduledFuture<?> retryTask;
        synchronized (terminalToolRecords) {
            TerminalToolRecord record = terminalToolRecords.get(terminalRecordKey(
                    deliveredRecord.context.sessionId,
                    callId
            ));
            if (record != deliveredRecord || record.acknowledged || record.deliveryAbandoned) return;
            if (!toolJournal.markAcknowledged(record.context.sessionId, callId)) {
                record.deliveryInFlight = false;
                failTerminalToolDelivery(
                        callId,
                        record,
                        "JOURNAL_WRITE_FAILED",
                        "Could not persist tool-result acknowledgement"
                );
                return;
            }
            record.acknowledged = true;
            record.deliveryInFlight = false;
            waiters = new ArrayList<>(record.waiters);
            record.waiters.clear();
            retryTask = record.retryTask;
            record.retryTask = null;
        }
        if (retryTask != null) retryTask.cancel(false);
        JSONObject response = terminalToolResponse(callId, deliveredRecord.result);
        for (AgentGatewayClient.ResultCallback waiter : waiters) waiter.onSuccess(response);
    }

    private static boolean terminalUpdatesEquivalent(
            JSONObject expected,
            JSONObject actual
    ) {
        String expectedStatus = expected.optString("status", "");
        if (!expectedStatus.equals(actual.optString("status", ""))) {
            return false;
        }
        if ("succeeded".equals(expectedStatus)) {
            return jsonValuesEquivalent(
                    expected.opt("output"),
                    actual.opt("output")
            );
        }
        return jsonValuesEquivalent(
                expected.opt("error"),
                actual.opt("error")
        );
    }

    private static boolean jsonValuesEquivalent(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null
                || left == JSONObject.NULL
                || right == JSONObject.NULL) {
            return left == JSONObject.NULL && right == JSONObject.NULL;
        }
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject leftObject = (JSONObject) left;
            JSONObject rightObject = (JSONObject) right;
            if (leftObject.length() != rightObject.length()) return false;
            java.util.Iterator<String> keys = leftObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!rightObject.has(key)
                        || !jsonValuesEquivalent(
                        leftObject.opt(key),
                        rightObject.opt(key)
                )) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray leftArray = (JSONArray) left;
            JSONArray rightArray = (JSONArray) right;
            if (leftArray.length() != rightArray.length()) return false;
            for (int index = 0; index < leftArray.length(); index++) {
                if (!jsonValuesEquivalent(
                        leftArray.opt(index),
                        rightArray.opt(index)
                )) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Number && right instanceof Number) {
            double leftNumber = ((Number) left).doubleValue();
            double rightNumber = ((Number) right).doubleValue();
            return !Double.isNaN(leftNumber)
                    && !Double.isNaN(rightNumber)
                    && leftNumber == rightNumber;
        }
        return left.equals(right);
    }

    private void failTerminalToolDelivery(
            String callId,
            TerminalToolRecord failedRecord,
            String code,
            String message
    ) {
        if ("CONFLICT".equals(code)
                && completeConflictedTerminalToolDelivery(callId, failedRecord)) {
            return;
        }
        List<AgentGatewayClient.ResultCallback> waiters;
        long retryDelay;
        synchronized (terminalToolRecords) {
            TerminalToolRecord record = terminalToolRecords.get(terminalRecordKey(
                    failedRecord.context.sessionId,
                    callId
            ));
            if (record != failedRecord || record.acknowledged || record.deliveryAbandoned) return;
            record.deliveryInFlight = false;
            record.deliveryFailures++;
            waiters = new ArrayList<>(record.waiters);
            record.waiters.clear();
            int shift = Math.min(record.deliveryFailures - 1, 4);
            retryDelay = Math.min(MAX_TOOL_RESULT_RETRY_MILLIS, 1_000L << shift);
            if (clock.getAsLong() - record.completedAt >= TERMINAL_TOOL_RETENTION_MILLIS) {
                if (record.retryTask != null) record.retryTask.cancel(false);
                record.retryTask = null;
                toolJournal.markAbandoned(record.context.sessionId, callId);
                record.deliveryAbandoned = true;
            } else if (record.retryTask == null && !scheduler.isShutdown()) {
                try {
                    record.retryTask = scheduler.schedule(() -> {
                        synchronized (terminalToolRecords) {
                            TerminalToolRecord current = terminalToolRecords.get(
                                    terminalRecordKey(record.context.sessionId, callId)
                            );
                            if (current != record
                                    || current.acknowledged
                                    || current.deliveryAbandoned) {
                                return;
                            }
                            current.retryTask = null;
                        }
                        deliverTerminalToolResult(
                                record.context.sessionId,
                                callId,
                                null
                        );
                    }, retryDelay, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {
                    // Process shutdown owns no authority to abandon a durable result.
                    // A future coordinator instance will hydrate and resend PENDING.
                    record.retryTask = null;
                }
            } else if (scheduler.isShutdown()) {
                if (record.retryTask != null) record.retryTask.cancel(false);
                record.retryTask = null;
            }
        }
        String safeCode = code == null || code.isEmpty() ? "GATEWAY_UNAVAILABLE" : code;
        String safeMessage = message == null || message.isEmpty()
                ? "Gateway tool-result upload failed"
                : message;
        for (AgentGatewayClient.ResultCallback waiter : waiters) {
            waiter.onError(safeCode, safeMessage);
        }
    }

    private boolean completeConflictedTerminalToolDelivery(
            String callId,
            TerminalToolRecord deliveredRecord
    ) {
        List<AgentGatewayClient.ResultCallback> waiters;
        ScheduledFuture<?> retryTask;
        synchronized (terminalToolRecords) {
            TerminalToolRecord record = terminalToolRecords.get(terminalRecordKey(
                    deliveredRecord.context.sessionId,
                    callId
            ));
            if (record != deliveredRecord
                    || record.acknowledged
                    || record.deliveryAbandoned) {
                return true;
            }
            if (!toolJournal.markAbandoned(record.context.sessionId, callId)) {
                return false;
            }
            record.deliveryAbandoned = true;
            record.deliveryInFlight = false;
            waiters = new ArrayList<>(record.waiters);
            record.waiters.clear();
            retryTask = record.retryTask;
            record.retryTask = null;
        }
        if (retryTask != null) retryTask.cancel(false);
        for (AgentGatewayClient.ResultCallback waiter : waiters) {
            waiter.onError(
                    "CONFLICT",
                    "Gateway already retained a different terminal tool result"
            );
        }
        return true;
    }

    private static JSONObject terminalToolResponse(String callId, JSONObject result) {
        return json(
                "callId", callId,
                "status", result != null ? result.optString("status", "") : ""
        );
    }

    private boolean terminalizeDispatchedNativeTools(
            String expectedSessionId,
            long expectedEpoch,
            String turnId,
            String code,
            String message,
            boolean deliverResult
    ) {
        if (turnId == null || turnId.isEmpty()) return true;
        if (!isSessionLeaseCurrent(expectedSessionId, expectedEpoch)) return false;
        List<ToolCallJournal.Entry> entries = toolJournal.loadSession(expectedSessionId);
        if (entries == null) return false;

        boolean settled = true;
        boolean physicalToolFound = false;
        for (ToolCallJournal.Entry entry : entries) {
            if (!turnId.equals(entry.turnId) || !"native".equals(entry.owner)) continue;
            if (!isSessionLeaseCurrent(expectedSessionId, expectedEpoch)) return false;
            ToolContext context = contextFromJournal(entry, expectedEpoch);

            ToolCallJournal.Entry terminalEntry = entry;
            if (!entry.isTerminal()) {
                physicalToolFound |= robotGateway.isPhysicalTool(entry.name);
                JSONObject error = json(
                        "code", code,
                        "message", message,
                        "retryable", false
                );
                if (!captureTerminal(
                        context,
                        toolUpdate("failed", null, error)
                )) {
                    settled = false;
                    continue;
                }
                terminalEntry = toolJournal.get(expectedSessionId, entry.callId);
                if (terminalEntry == null || !terminalEntry.isTerminal()) {
                    settled = false;
                    continue;
                }
            } else {
                hydrateTerminalRecord(entry);
                removeLiveToolContext(context);
            }
            cancelToolTimeout(entry.callId);

            if (terminalEntry.deliveryState
                    == ToolCallJournal.DeliveryState.ACKNOWLEDGED
                    || terminalEntry.deliveryState
                    == ToolCallJournal.DeliveryState.ABANDONED) {
                continue;
            }
            if (deliverResult) {
                deliverTerminalToolResult(expectedSessionId, entry.callId, null);
            } else if (!abandonTerminalToolRecord(context, code, message)) {
                settled = false;
            }
        }
        if (physicalToolFound) {
            robotGateway.emergencyStop();
            releasePhysicalTool();
            publishRobotState();
        }
        return settled;
    }

    private boolean abandonTerminalToolRecord(
            ToolContext context,
            String code,
            String message
    ) {
        if (!toolJournal.markAbandoned(context.sessionId, context.callId)) {
            return false;
        }
        List<AgentGatewayClient.ResultCallback> waiters = new ArrayList<>();
        ScheduledFuture<?> retryTask = null;
        synchronized (terminalToolRecords) {
            TerminalToolRecord record = terminalToolRecords.get(terminalRecordKey(
                    context.sessionId,
                    context.callId
            ));
            if (record != null && !record.acknowledged) {
                record.deliveryAbandoned = true;
                record.deliveryInFlight = false;
                retryTask = record.retryTask;
                record.retryTask = null;
                waiters.addAll(record.waiters);
                record.waiters.clear();
            }
        }
        if (retryTask != null) retryTask.cancel(false);
        for (AgentGatewayClient.ResultCallback waiter : waiters) {
            waiter.onError(code, message);
        }
        return true;
    }

    private boolean terminalizePendingWebTools(
            String expectedSessionId,
            long expectedEpoch,
            String turnId,
            String code,
            String message,
            boolean deliverResult
    ) {
        if (!webPublicationFence.isHeldByCurrentThread()) {
            throw new IllegalStateException("Web publication fence is not held");
        }
        if (!isSessionLeaseCurrent(expectedSessionId, expectedEpoch)) return false;
        List<ToolCallJournal.Entry> entries =
                toolJournal.loadSession(expectedSessionId);
        if (entries == null) return false;
        Map<String, PendingWebTool> matching = new HashMap<>();
        synchronized (pendingWebTools) {
            for (Map.Entry<String, PendingWebTool> entry : pendingWebTools.entrySet()) {
                ToolContext context = entry.getValue().context;
                if (turnId.equals(context.turnId)
                        && expectedSessionId.equals(context.sessionId)
                        && expectedEpoch == context.epoch) {
                    matching.put(entry.getKey(), entry.getValue());
                }
            }
        }
        boolean settled = true;
        Set<String> settledCallIds = new HashSet<>();
        for (ToolCallJournal.Entry entry : entries) {
            if (!turnId.equals(entry.turnId) || !"web".equals(entry.owner)) continue;
            if (!isSessionLeaseCurrent(expectedSessionId, expectedEpoch)) return false;
            String callId = entry.callId;
            ToolContext context = contextFromJournal(entry, expectedEpoch);
            if (entry.deliveryState
                    == ToolCallJournal.DeliveryState.ACKNOWLEDGED
                    || entry.deliveryState
                    == ToolCallJournal.DeliveryState.ABANDONED) {
                cancelToolTimeout(entry.callId);
                hydrateTerminalRecord(entry);
                removeLiveToolContext(context);
                settledCallIds.add(callId);
                continue;
            }
            if (!entry.isTerminal()) {
                JSONObject error = json(
                        "code", code,
                        "message", message,
                        "retryable", false
                );
                if (!captureTerminal(
                        context,
                        toolUpdate("failed", null, error)
                )) {
                    settled = false;
                    continue;
                }
            } else {
                hydrateTerminalRecord(entry);
                removeLiveToolContext(context);
            }
            cancelToolTimeout(entry.callId);
            if (!deliverResult
                    && !abandonTerminalToolRecord(context, code, message)) {
                settled = false;
                continue;
            }
            if (deliverResult) {
                deliverTerminalToolResult(context.sessionId, callId, null);
            }
            settledCallIds.add(callId);
        }
        for (Map.Entry<String, PendingWebTool> entry : matching.entrySet()) {
            if (!settledCallIds.contains(entry.getKey())) {
                settled = false;
                continue;
            }
            synchronized (pendingWebTools) {
                if (pendingWebTools.get(entry.getKey()) == entry.getValue()) {
                    pendingWebTools.remove(entry.getKey());
                }
            }
            entry.getValue().eventCallback.commit();
        }
        return settled;
    }

    private void releaseUncommittedToolEvents(boolean abandonDeliveries) {
        Map<String, PendingWebTool> pending;
        synchronized (pendingWebTools) {
            pending = new HashMap<>(pendingWebTools);
            pendingWebTools.clear();
        }
        for (Map.Entry<String, PendingWebTool> entry : pending.entrySet()) {
            cancelToolTimeout(entry.getKey());
            entry.getValue().eventCallback.retry();
        }
        stopTerminalToolDeliveries(
                abandonDeliveries,
                "GATEWAY_OFFLINE",
                "Gateway connection ended before tool-result delivery"
        );
    }

    private void abandonToolDeliveriesForTerminalSession(
            String terminalType,
            String endingSessionId
    ) {
        Map<String, PendingWebTool> pending;
        synchronized (pendingWebTools) {
            pending = new HashMap<>(pendingWebTools);
            pendingWebTools.clear();
        }
        String errorCode = "SESSION_EXPIRED";
        for (Map.Entry<String, PendingWebTool> entry : pending.entrySet()) {
            cancelToolTimeout(entry.getKey());
            JSONObject error = json(
                    "code", errorCode,
                    "message", "Gateway session ended before the renderer completed the tool",
                    "retryable", false
            );
            if (captureTerminal(entry.getValue().context, toolUpdate("failed", null, error))) {
                entry.getValue().eventCallback.commit();
            } else {
                entry.getValue().eventCallback.retry();
            }
        }

        if (endingSessionId != null && !endingSessionId.isEmpty()) {
            abandonJournalSession(endingSessionId, errorCode);
        }
        stopTerminalToolDeliveries(
                true,
                errorCode,
                "Gateway session ended before tool-result delivery"
        );
    }

    private void stopTerminalToolDeliveries(
            boolean abandonDeliveries,
            String errorCode,
            String message
    ) {
        List<AgentGatewayClient.ResultCallback> waiters = new ArrayList<>();
        synchronized (terminalToolRecords) {
            for (TerminalToolRecord record : terminalToolRecords.values()) {
                if (record.retryTask != null) {
                    record.retryTask.cancel(false);
                    record.retryTask = null;
                }
                record.deliveryInFlight = false;
                if (abandonDeliveries && !record.acknowledged) {
                    toolJournal.markAbandoned(
                            record.context.sessionId,
                            record.context.callId
                    );
                    record.deliveryAbandoned = true;
                }
                waiters.addAll(record.waiters);
                record.waiters.clear();
            }
        }
        for (AgentGatewayClient.ResultCallback waiter : waiters) {
            waiter.onError(errorCode, message);
        }
    }

    private boolean captureTerminal(ToolContext context, JSONObject terminalResult) {
        if (context.sessionId == null || context.sessionId.isEmpty()) return false;
        ToolCallJournal.Entry base = toolJournal.get(context.sessionId, context.callId);
        if (base == null) base = context.journalEntry();
        long terminalAt = clock.getAsLong();
        if (!toolJournal.recordTerminal(base, terminalResult, terminalAt)) return false;
        ToolCallJournal.Entry persisted = toolJournal.get(context.sessionId, context.callId);
        if (persisted == null || !persisted.isTerminal()) return false;
        hydrateTerminalRecord(persisted);
        removeLiveToolContext(context);
        return true;
    }

    private void hydrateTerminalRecord(ToolCallJournal.Entry entry) {
        String recordKey = terminalRecordKey(entry.sessionId, entry.callId);
        toolCallLifecycle.markTerminal(recordKey);
        synchronized (terminalToolRecords) {
            TerminalToolRecord existing = terminalToolRecords.get(recordKey);
            if (existing != null) return;
            terminalToolRecords.put(
                    recordKey,
                    new TerminalToolRecord(
                            contextFromJournal(entry),
                            entry.terminalResult,
                            entry.terminalAt,
                            entry.deliveryState
                    )
            );
        }
    }

    private ToolContext contextFromJournal(ToolCallJournal.Entry entry) {
        return contextFromJournal(entry, currentSessionEpoch());
    }

    private ToolContext contextFromJournal(
            ToolCallJournal.Entry entry,
            long expectedEpoch
    ) {
        return new ToolContext(
                entry.sessionId,
                entry.callId,
                entry.turnId,
                entry.owner,
                entry.name,
                expectedEpoch,
                entry.dispatchedAt
        );
    }

    private synchronized long currentSessionEpoch() {
        return sessionEpoch;
    }

    private String currentRemoteSessionId() {
        String current = activeRemoteSessionId;
        if (current != null && !current.isEmpty()) return current;
        String gatewaySessionId = gatewayClient.getRemoteSessionId();
        return gatewaySessionId != null ? gatewaySessionId : "";
    }

    private boolean bindRemoteSession(
            String remoteSessionId,
            boolean allowReplacement
    ) {
        if (remoteSessionId == null || remoteSessionId.isEmpty()) return false;
        webPublicationFence.lock();
        try {
            String transportSessionId = gatewayClient.getRemoteSessionId();
            if (!remoteSessionId.equals(transportSessionId)) return false;
            String previous;
            synchronized (this) {
                previous = activeRemoteSessionId;
                if (previous != null
                        && !previous.isEmpty()
                        && !previous.equals(remoteSessionId)
                        && !allowReplacement) {
                    return false;
                }
            }
            if (previous != null
                    && !previous.isEmpty()
                    && !previous.equals(remoteSessionId)) {
                String fencedSession = fenceSession();
                releaseUncommittedToolEvents(true);
                if (fencedSession != null && !fencedSession.isEmpty()) {
                    abandonJournalSession(fencedSession, "SESSION_REPLACED");
                }
            }
            synchronized (this) {
                if (activeRemoteSessionId == null
                        || activeRemoteSessionId.isEmpty()) {
                    activeRemoteSessionId = remoteSessionId;
                    reconciledRemoteSessionId = null;
                    reconciledSessionEpoch = -1L;
                } else if (!activeRemoteSessionId.equals(remoteSessionId)) {
                    return false;
                }
                if (remoteSessionId.equals(reconciledRemoteSessionId)
                        && sessionEpoch == reconciledSessionEpoch) {
                    return true;
                }
            }
            if (!reconcileStaleJournalSessions(remoteSessionId)) return false;
            synchronized (this) {
                if (!remoteSessionId.equals(activeRemoteSessionId)) return false;
                if (!remoteSessionId.equals(
                        gatewayClient.getRemoteSessionId()
                )) {
                    return false;
                }
            }
            return true;
        } finally {
            webPublicationFence.unlock();
        }
    }

    private synchronized boolean isToolContextCurrent(ToolContext context) {
        return isToolLeaseCurrent(context)
                && !turnAuthority.isRevoked(
                context.sessionId,
                context.epoch,
                context.turnId
        );
    }

    private synchronized boolean isToolLeaseCurrent(ToolContext context) {
        return context != null
                && context.epoch == sessionEpoch
                && context.sessionId.equals(activeRemoteSessionId)
                && context.turnId.equals(activeTurnId);
    }

    private synchronized boolean isSessionLeaseCurrent(
            String expectedSessionId,
            long expectedEpoch
    ) {
        return expectedEpoch == sessionEpoch
                && expectedSessionId != null
                && expectedSessionId.equals(activeRemoteSessionId);
    }

    /**
     * Holds the epoch/session check through the point where a queued main-thread action starts.
     * Session fencing uses the same monitor, so an old action can never begin after the fence.
     */
    private boolean runIfToolContextAuthorized(
            ToolContext context,
            Runnable action
    ) {
        webPublicationFence.lock();
        try {
            synchronized (this) {
                if (!isToolContextCurrent(context)
                        || isTerminalTool(
                        context.sessionId,
                        context.callId
                )) {
                    return false;
                }
                return turnAuthority.runIfAuthorized(
                        context.sessionId,
                        context.epoch,
                        context.turnId,
                        action
                );
            }
        } finally {
            webPublicationFence.unlock();
        }
    }

    private boolean activateLiveDispatch(
            ToolContext context,
            String lifecycleId
    ) {
        webPublicationFence.lock();
        try {
            synchronized (this) {
                if (!isToolContextCurrent(context)
                        || isTerminalTool(
                        context.sessionId,
                        context.callId
                )) {
                    toolCallLifecycle.failAcceptance(lifecycleId);
                    return false;
                }
                liveToolContexts.put(lifecycleId, context);
                if (!toolJournal.recordDispatched(context.journalEntry())) {
                    liveToolContexts.remove(lifecycleId);
                    toolCallLifecycle.failAcceptance(lifecycleId);
                    return false;
                }
            }
            Runnable hook = afterLiveDispatchJournalHook;
            if (hook != null) hook.run();
            if (!toolCallLifecycle.markAccepted(lifecycleId)) {
                removeLiveToolContext(context);
                return false;
            }
            return true;
        } finally {
            webPublicationFence.unlock();
        }
    }

    private DispatchResult registerAndPublishWebTool(
            ToolContext context,
            AgentGatewayClient.EventCommitCallback eventCallback,
            JSONObject message,
            int requestedTimeoutMs,
            String deadlineAt
    ) {
        webPublicationFence.lock();
        try {
            synchronized (this) {
                if (!isToolContextCurrent(context)
                        || isTerminalTool(
                        context.sessionId,
                        context.callId
                )) {
                    return DispatchResult.REJECTED;
                }
            }
            if (deadlineExpired(deadlineAt)) {
                return expireToolBeforeDispatch(context, eventCallback);
            }
            scheduleToolTimeoutLocked(
                    context,
                    requestedTimeoutMs,
                    deadlineAt
            );
            if (deadlineExpired(deadlineAt)) {
                cancelToolTimeout(context.callId);
                return expireToolBeforeDispatch(context, eventCallback);
            }
            if (isTerminalTool(context.sessionId, context.callId)) {
                eventCallback.commit();
                deliverTerminalToolResult(
                        context.sessionId,
                        context.callId,
                        null
                );
                return DispatchResult.SETTLED;
            }
            synchronized (this) {
                if (!isToolContextCurrent(context)
                        || isTerminalTool(
                        context.sessionId,
                        context.callId
                )) {
                    return DispatchResult.REJECTED;
                }
                synchronized (pendingWebTools) {
                    pendingWebTools.put(
                            context.callId,
                            new PendingWebTool(context, eventCallback)
                    );
                }
            }
            remember(message);
            publishLocal(message);
            return DispatchResult.STARTED;
        } finally {
            webPublicationFence.unlock();
        }
    }

    private boolean publishPendingWebToolReplay(
            PendingWebTool expectedPending,
            AgentGatewayClient.EventCommitCallback eventCallback,
            JSONObject message
    ) {
        webPublicationFence.lock();
        try {
            synchronized (this) {
                if (!isToolContextCurrent(expectedPending.context)
                        || isTerminalTool(
                        expectedPending.context.sessionId,
                        expectedPending.context.callId
                )) {
                    return false;
                }
                synchronized (pendingWebTools) {
                    PendingWebTool pending = pendingWebTools.get(
                            expectedPending.context.callId
                    );
                    if (pending != expectedPending) return false;
                    pending.eventCallback = eventCallback;
                }
            }
            remember(message);
            publishLocal(message);
            return true;
        } finally {
            webPublicationFence.unlock();
        }
    }

    private void settleFailedToolDispatch(
            ToolContext context,
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        synchronized (pendingWebTools) {
            PendingWebTool pending = pendingWebTools.get(context.callId);
            if (pending != null && pending.context == context) {
                pendingWebTools.remove(context.callId);
            }
        }

        ToolCallJournal.Entry entry =
                toolJournal.get(context.sessionId, context.callId);
        if (entry != null && !entry.isTerminal()) {
            JSONObject error = json(
                    "code", "TURN_CANCELLED",
                    "message", "Turn authority ended before device tool dispatch completed",
                    "retryable", false
            );
            if (!captureTerminal(
                    context,
                    toolUpdate("failed", null, error)
            )) {
                resetUndispatchedTool(context);
                eventCallback.retry();
                return;
            }
            entry = toolJournal.get(context.sessionId, context.callId);
        }
        if (entry == null || !entry.isTerminal()) {
            resetUndispatchedTool(context);
            eventCallback.retry();
            return;
        }
        cancelToolTimeout(context.callId);
        if (isSessionLeaseCurrent(context.sessionId, context.epoch)) {
            if (entry.deliveryState == ToolCallJournal.DeliveryState.ABANDONED
                    || entry.deliveryState
                    == ToolCallJournal.DeliveryState.ACKNOWLEDGED) {
                eventCallback.commit();
                return;
            }
            eventCallback.commit();
            deliverTerminalToolResult(
                    context.sessionId,
                    context.callId,
                    null
            );
        } else {
            toolJournal.markAbandoned(
                    context.sessionId,
                    context.callId,
                    clock.getAsLong()
            );
            eventCallback.retry();
        }
    }

    private void settleAcceptedToolConflict(
            ToolContext context,
            String lifecycleId,
            String detail,
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        webPublicationFence.lock();
        try {
            if (!isToolContextCurrent(context)) {
                toolCallLifecycle.failAcceptance(lifecycleId);
                eventCallback.retry();
                return;
            }
            JSONObject error = json(
                    "code", "CONFLICT",
                    "message", detail == null || detail.isEmpty()
                            ? "Gateway no longer accepts this tool call"
                            : detail,
                    "retryable", false
            );
            if (!captureTerminal(
                    context,
                    toolUpdate("rejected", null, error)
            ) || !abandonTerminalToolRecord(
                    context,
                    "CONFLICT",
                    error.optString("message")
            )) {
                toolCallLifecycle.failAcceptance(lifecycleId);
                resetUndispatchedTool(context);
                eventCallback.retry();
                return;
            }
            eventCallback.commit();
        } finally {
            webPublicationFence.unlock();
        }
    }

    private void settleAcceptedToolTerminal(
            ToolContext context,
            String lifecycleId,
            JSONObject terminalResult,
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        webPublicationFence.lock();
        try {
            if (!isToolContextCurrent(context)) {
                toolCallLifecycle.failAcceptance(lifecycleId);
                eventCallback.retry();
                return;
            }
            if (!captureTerminal(context, terminalResult)) {
                toolCallLifecycle.failAcceptance(lifecycleId);
                eventCallback.retry();
                return;
            }
            TerminalToolRecord record;
            synchronized (terminalToolRecords) {
                record = terminalToolRecords.get(terminalRecordKey(
                        context.sessionId,
                        context.callId
                ));
            }
            if (record == null) {
                eventCallback.retry();
                return;
            }
            completeTerminalToolDelivery(context.callId, record);
            eventCallback.commit();
        } finally {
            webPublicationFence.unlock();
        }
    }

    void setBeforeWebToolRegistrationHookForTest(Runnable hook) {
        beforeWebToolRegistrationHook = hook;
    }

    void setBeforeGatewayMessageFenceHookForTest(Runnable hook) {
        beforeGatewayMessageFenceHook = hook;
    }

    void setAfterLiveDispatchJournalHookForTest(Runnable hook) {
        afterLiveDispatchJournalHook = hook;
    }

    void setBeforeToolTerminalFenceHookForTest(Runnable hook) {
        beforeToolTerminalFenceHook = hook;
    }

    private void resetUndispatchedTool(ToolContext context) {
        removeLiveToolContext(context);
        toolCallLifecycle.failDispatch(terminalRecordKey(
                context.sessionId,
                context.callId
        ));
    }

    private synchronized void removeLiveToolContext(ToolContext context) {
        String key = terminalRecordKey(context.sessionId, context.callId);
        ToolContext live = liveToolContexts.get(key);
        if (live != null && live.epoch == context.epoch) {
            liveToolContexts.remove(key);
        }
    }

    private boolean isLiveJournalEntryLocked(ToolCallJournal.Entry entry) {
        ToolContext live = liveToolContexts.get(
                terminalRecordKey(entry.sessionId, entry.callId)
        );
        return live != null
                && live.epoch == sessionEpoch
                && live.sessionId.equals(activeRemoteSessionId)
                && live.turnId.equals(activeTurnId)
                && live.turnId.equals(entry.turnId)
                && live.owner.equals(entry.owner)
                && live.name.equals(entry.name)
                && !turnAuthority.isRevoked(
                live.sessionId,
                live.epoch,
                live.turnId
        );
    }

    /**
     * Invalidates every callback and queued action from the current remote session.
     * Durable journal disposition is deliberately handled by the caller: process shutdown
     * retains pending records for recovery, while reload/terminal-session paths abandon them.
     */
    private String fenceSession() {
        String previousSessionId;
        String previousTurnId;
        long previousEpoch;
        PendingUploadCancel detachedPendingCancel;
        List<PendingTurnCancel> detachedTurnCancels;
        webPublicationFence.lock();
        try {
            synchronized (this) {
                previousSessionId = activeRemoteSessionId;
                if (previousSessionId == null || previousSessionId.isEmpty()) {
                    previousSessionId = gatewayClient.getRemoteSessionId();
                }
                previousTurnId = activeTurnId;
                previousEpoch = sessionEpoch;
                detachedPendingCancel = pendingUploadCancel;
                pendingUploadCancel = null;
                activeUpload = null;
                if (previousTurnId != null && !previousTurnId.isEmpty()) {
                    turnAuthority.revoke(
                            previousSessionId,
                            previousEpoch,
                            previousTurnId
                    );
                }
                sessionEpoch++;
                activeRemoteSessionId = null;
                reconciledRemoteSessionId = null;
                reconciledSessionEpoch = -1L;
                activeTurnId = null;
                awaitingTurnAcceptance = false;
                turnState = "IDLE";
                physicalToolExecuting = false;
                liveToolContexts.clear();
            }
            synchronized (pendingTurnCancels) {
                detachedTurnCancels =
                        new ArrayList<>(pendingTurnCancels.values());
                pendingTurnCancels.clear();
            }
        } finally {
            webPublicationFence.unlock();
        }
        turnAuthority.clearSession(previousSessionId, previousEpoch);
        synchronized (cancelRequestedTurns) {
            cancelRequestedTurns.clear();
        }
        if (detachedPendingCancel != null) {
            completePendingCancel(
                    detachedPendingCancel,
                    false,
                    "SESSION_EXPIRED",
                    "Gateway session changed before turn cancellation completed"
            );
        }
        for (PendingTurnCancel pending : detachedTurnCancels) {
            for (AgentGatewayClient.ResultCallback callback
                    : pending.callbacks) {
                callback.onError(
                        "SESSION_EXPIRED",
                        "Gateway session changed before turn cancellation completed"
                );
            }
        }
        cancelAllToolTimeouts();
        robotGateway.emergencyStop();
        publishRobotState();
        return previousSessionId;
    }

    private void cancelAllToolTimeouts() {
        List<ScheduledFuture<?>> tasks;
        synchronized (toolTimeouts) {
            tasks = new ArrayList<>(toolTimeouts.values());
            toolTimeouts.clear();
        }
        for (ScheduledFuture<?> task : tasks) {
            if (task != null) task.cancel(false);
        }
    }

    private boolean recoverJournalForSession(
            String remoteSessionId,
            long expectedEpoch
    ) {
        webPublicationFence.lock();
        try {
            return recoverJournalForSessionLocked(
                    remoteSessionId,
                    expectedEpoch
            );
        } finally {
            webPublicationFence.unlock();
        }
    }

    private boolean recoverJournalForSessionLocked(
            String remoteSessionId,
            long expectedEpoch
    ) {
        if (remoteSessionId == null || remoteSessionId.isEmpty()) return false;
        if (!isSessionLeaseCurrent(remoteSessionId, expectedEpoch)
                || !reconcileStaleJournalSessions(remoteSessionId)) {
            return false;
        }
        List<ToolCallJournal.Entry> terminalEntries = new ArrayList<>();
        List<String> pendingDeliveries = new ArrayList<>();
        synchronized (this) {
            if (!isSessionLeaseCurrent(remoteSessionId, expectedEpoch)) return false;
            List<ToolCallJournal.Entry> entries = toolJournal.loadSession(remoteSessionId);
            if (entries == null) return false;
            for (ToolCallJournal.Entry entry : entries) {
                ToolCallJournal.Entry recovered = entry;
                if (!entry.isTerminal()) {
                    if (isLiveJournalEntryLocked(entry)) continue;
                    JSONObject error = json(
                            "code", "EXECUTION_UNCERTAIN",
                            "message", "The app stopped after dispatch; the tool will not be executed again",
                            "retryable", false
                    );
                    if (!toolJournal.recordTerminal(
                            entry,
                            toolUpdate("failed", null, error),
                            clock.getAsLong()
                    )) {
                        return false;
                    }
                    recovered = toolJournal.get(remoteSessionId, entry.callId);
                    if (recovered == null || !recovered.isTerminal()) return false;
                }
                terminalEntries.add(recovered);
                if (recovered.deliveryState == ToolCallJournal.DeliveryState.PENDING) {
                    pendingDeliveries.add(recovered.callId);
                }
            }
        }

        for (ToolCallJournal.Entry entry : terminalEntries) {
            hydrateTerminalRecord(entry);
        }
        cleanupTerminalCalls();
        for (String callId : pendingDeliveries) {
            deliverTerminalToolResult(remoteSessionId, callId, null);
        }
        synchronized (this) {
            if (!isSessionLeaseCurrent(remoteSessionId, expectedEpoch)
                    || !remoteSessionId.equals(
                    gatewayClient.getRemoteSessionId()
            )) {
                return false;
            }
            reconciledRemoteSessionId = remoteSessionId;
            reconciledSessionEpoch = expectedEpoch;
        }
        return true;
    }

    private boolean reconcileStaleJournalSessions(String currentSessionId) {
        if (currentSessionId == null || currentSessionId.isEmpty()) return false;
        List<ToolCallJournal.Entry> entries = toolJournal.loadAll();
        if (entries == null) return false;
        long reconciledAt = clock.getAsLong();
        for (ToolCallJournal.Entry entry : entries) {
            if (currentSessionId.equals(entry.sessionId)) continue;
            ToolCallJournal.Entry reconciled = entry;
            if (!entry.isTerminal()) {
                JSONObject error = json(
                        "code", "STALE_SESSION",
                        "message", "Tool execution belongs to a replaced Gateway session",
                        "retryable", false
                );
                if (!toolJournal.recordTerminal(
                        entry,
                        toolUpdate("failed", null, error),
                        reconciledAt
                )) {
                    return false;
                }
                reconciled = toolJournal.get(entry.sessionId, entry.callId);
                if (reconciled == null || !reconciled.isTerminal()) return false;
            }
            if (reconciled.deliveryState != ToolCallJournal.DeliveryState.ACKNOWLEDGED
                    && reconciled.deliveryState != ToolCallJournal.DeliveryState.ABANDONED
                    && !toolJournal.markAbandoned(
                    reconciled.sessionId,
                    reconciled.callId,
                    reconciledAt
            )) {
                return false;
            }
        }
        return toolJournal.prune(
                reconciledAt,
                TERMINAL_TOOL_RETENTION_MILLIS
        );
    }

    private boolean abandonJournalSession(String remoteSessionId, String reasonCode) {
        List<ToolCallJournal.Entry> entries = toolJournal.loadSession(remoteSessionId);
        if (entries == null) return false;
        boolean complete = true;
        long abandonedAt = clock.getAsLong();
        for (ToolCallJournal.Entry entry : entries) {
            ToolCallJournal.Entry abandoned = entry;
            if (!entry.isTerminal()) {
                JSONObject error = json(
                        "code", reasonCode,
                        "message", "Gateway session ended after the tool was dispatched",
                        "retryable", false
                );
                if (!toolJournal.recordTerminal(
                        entry,
                        toolUpdate("failed", null, error),
                        abandonedAt
                )) {
                    complete = false;
                    continue;
                }
                abandoned = toolJournal.get(remoteSessionId, entry.callId);
                if (abandoned == null || !abandoned.isTerminal()) {
                    complete = false;
                    continue;
                }
            }
            hydrateTerminalRecord(abandoned);
            if (abandoned.deliveryState != ToolCallJournal.DeliveryState.ACKNOWLEDGED
                    && !toolJournal.markAbandoned(
                    remoteSessionId,
                    abandoned.callId,
                    abandonedAt
            )) {
                complete = false;
                continue;
            }
            synchronized (terminalToolRecords) {
                TerminalToolRecord record = terminalToolRecords.get(terminalRecordKey(
                        remoteSessionId,
                        abandoned.callId
                ));
                if (record != null && record.context.sessionId.equals(remoteSessionId)) {
                    record.deliveryAbandoned =
                            abandoned.deliveryState != ToolCallJournal.DeliveryState.ACKNOWLEDGED;
                    record.deliveryInFlight = false;
                    if (record.retryTask != null) {
                        record.retryTask.cancel(false);
                        record.retryTask = null;
                    }
                }
            }
        }
        toolJournal.prune(clock.getAsLong(), TERMINAL_TOOL_RETENTION_MILLIS);
        return complete;
    }

    int retainedTerminalToolCount() {
        cleanupTerminalCalls();
        synchronized (terminalToolRecords) {
            return terminalToolRecords.size();
        }
    }

    private boolean deadlineExpired(String deadlineAt) {
        try {
            return parseDeadline(deadlineAt) <= clock.getAsLong();
        } catch (Exception error) {
            return true;
        }
    }

    private DispatchResult expireToolBeforeDispatch(
            ToolContext context,
            AgentGatewayClient.EventCommitCallback eventCallback
    ) {
        if (!webPublicationFence.isHeldByCurrentThread()) {
            throw new IllegalStateException("Tool dispatch fence is not held");
        }
        JSONObject error = json(
                "code", "TIMEOUT",
                "message", "Tool execution deadline expired before dispatch",
                "retryable", false
        );
        if (!captureTerminal(
                context,
                toolUpdate("failed", null, error)
        )) {
            resetUndispatchedTool(context);
            eventCallback.retry();
            return DispatchResult.SETTLED;
        }
        eventCallback.commit();
        deliverTerminalToolResult(context.sessionId, context.callId, null);
        return DispatchResult.SETTLED;
    }

    private void scheduleToolTimeoutLocked(
            ToolContext context,
            int requestedTimeoutMs,
            String deadlineAt
    ) {
        if (!webPublicationFence.isHeldByCurrentThread()) {
            throw new IllegalStateException("Tool dispatch fence is not held");
        }
        long delay;
        try {
            long deadlineDelay = Math.max(1L, parseDeadline(deadlineAt) - clock.getAsLong());
            delay = Math.min(Math.min(5_000L, requestedTimeoutMs), deadlineDelay);
        } catch (Exception error) {
            delay = 1L;
        }
        ScheduledFuture<?> task = scheduler.schedule(
                () -> runToolTimeout(context),
                delay,
                TimeUnit.MILLISECONDS
        );
        synchronized (toolTimeouts) {
            if (!task.isDone()) {
                toolTimeouts.put(context.callId, task);
            }
        }
    }

    private void runToolTimeout(ToolContext context) {
        boolean persistenceRetryRequired = false;
        webPublicationFence.lock();
        try {
            if (!isToolLeaseCurrent(context)
                    || isTerminalTool(context.sessionId, context.callId)) {
                return;
            }
            PendingWebTool pending;
            synchronized (pendingWebTools) {
                pending = pendingWebTools.get(context.callId);
                if (pending != null && pending.context != context) pending = null;
            }
            if (robotGateway.isPhysicalTool(context.name)) {
                robotGateway.emergencyStop();
                releasePhysicalTool();
                publishRobotState();
            }
            JSONObject error = json(
                    "code", "TIMEOUT",
                    "message", "Tool execution exceeded its deadline",
                    "retryable", false
            );
            JSONObject update = toolUpdate("failed", null, error);
            if (!captureTerminal(context, update)) {
                persistenceRetryRequired = true;
            } else {
                if (pending != null) {
                    synchronized (pendingWebTools) {
                        if (pendingWebTools.get(context.callId) == pending) {
                            pendingWebTools.remove(context.callId);
                        }
                    }
                }
                if (pending != null) pending.eventCallback.commit();
                deliverTerminalToolResult(
                        context.sessionId,
                        context.callId,
                        null
                );
            }
        } finally {
            synchronized (toolTimeouts) {
                toolTimeouts.remove(context.callId);
            }
            webPublicationFence.unlock();
        }
        if (persistenceRetryRequired) {
            scheduleToolTimeoutPersistenceRetry(context);
        }
    }

    private void scheduleToolTimeoutPersistenceRetry(ToolContext context) {
        webPublicationFence.lock();
        try {
            if (!isToolLeaseCurrent(context)
                    || isTerminalTool(
                    context.sessionId,
                    context.callId
            ) || scheduler.isShutdown()) {
                return;
            }
            ScheduledFuture<?> task;
            try {
                task = scheduler.schedule(
                        () -> runToolTimeout(context),
                        1_000L,
                        TimeUnit.MILLISECONDS
                );
            } catch (RejectedExecutionException ignored) {
                return;
            }
            synchronized (toolTimeouts) {
                if (!task.isDone()) {
                    toolTimeouts.put(context.callId, task);
                }
            }
        } finally {
            webPublicationFence.unlock();
        }
    }

    private void cancelToolTimeout(String callId) {
        ScheduledFuture<?> task;
        synchronized (toolTimeouts) { task = toolTimeouts.remove(callId); }
        if (task != null) task.cancel(false);
    }

    private void cleanupTerminalCalls() {
        long now = clock.getAsLong();
        toolJournal.prune(now, TERMINAL_TOOL_RETENTION_MILLIS);
        synchronized (terminalToolRecords) {
            long cutoff = now - TERMINAL_TOOL_RETENTION_MILLIS;
            java.util.Iterator<Map.Entry<String, TerminalToolRecord>> iterator =
                    terminalToolRecords.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, TerminalToolRecord> entry = iterator.next();
                TerminalToolRecord record = entry.getValue();
                if ((record.acknowledged || record.deliveryAbandoned)
                        && record.completedAt < cutoff) {
                    if (record.retryTask != null) record.retryTask.cancel(false);
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
