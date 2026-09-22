package com.robot.asus.kira;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RemoteSessionCoordinatorTest {
    private FakeTransport transport;
    private FakeRobot robot;
    private ScheduledExecutorService scheduler;
    private RemoteSessionCoordinator coordinator;
    private List<JSONObject> events;
    private MemoryPreferences preferences;

    @Before public void setUp() {
        transport = new FakeTransport();
        robot = new FakeRobot();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        preferences = new MemoryPreferences();
        coordinator = new RemoteSessionCoordinator(transport, robot, scheduler,
                new GatewaySettings(preferences));
        events = new ArrayList<>();
        coordinator.setLocalPublisher(events::add);
        coordinator.onStateChanged("READY", "ready");
    }
    @After public void tearDown() { scheduler.shutdownNow(); }

    @Test public void nativeOwnsEveryEventSequenceAndDoesNotExposeHermesRunIds() throws Exception {
        String turn = startText();
        coordinator.onRunEvent("run-remote", "assistant.final", object("text", "Hello"));
        coordinator.onRunEvent("run-remote", "run.completed", object());
        long previous = 0;
        for (JSONObject event : events) {
            assertEquals("2.0", event.getString("protocolVersion"));
            assertEquals(++previous, event.getLong("sequence"));
            assertFalse(event.toString().contains("run-remote"));
            if (event.getString("type").startsWith("local.")) {
                assertFalse(event.has("sessionId"));
                assertFalse(event.has("turnId"));
            } else {
                assertEquals(coordinator.getSessionId(), event.getString("sessionId"));
            }
        }
        assertEquals(turn, coordinator.getActiveTurnId());
    }

    @Test public void voiceRecognitionIsCompletedBeforeTextRunSubmission() throws Exception {
        transport.deferTranscription = true;
        String turn = UUID.randomUUID().toString();
        coordinator.submitTurn(object("clientTurnId", turn, "audioBase64", "fixture", "language", "zh-TW"));
        assertEquals(0, transport.submissions);
        transport.transcription.onSuccess(object("text", "你好", "language", "zh-TW"));
        assertEquals(1, transport.submissions);
        assertEquals("你好", transport.submittedText);
        assertEquals(1, count("stt.final"));
    }

    @Test public void newSessionResetsSnapshotWithoutChangingLocalIdentityOrReleasingTheBindingGate() throws Exception {
        String oldTurn = startText();
        finishRun();
        for (int i = 0; i < transport.artifacts.length(); i++) {
            playback(oldTurn, transport.artifacts.getJSONObject(i).getString("artifactId"), "started");
            playback(oldTurn, transport.artifacts.getJSONObject(i).getString("artifactId"), "completed");
        }
        String localSession = coordinator.getSessionId();
        long before = coordinator.getLastSequence();
        JSONObject accepted = coordinator.startNewSession();
        assertEquals(localSession, accepted.getString("sessionId"));
        assertEquals(before + 2, accepted.getLong("lastSequence"));
        assertEquals("", accepted.getString("transcript"));
        assertEquals("", accepted.getString("assistantText"));
        assertTrue(accepted.isNull("activeTurnId"));
        assertEquals("CONNECTING", coordinator.getGatewayState());
        assertTrue(coordinator.getStatus().getBoolean("turnBusy"));
        JSONObject event = latest("session.snapshot");
        assertEquals(event.getLong("sequence"), event.getJSONObject("data").getLong("lastSequence"));
        assertEquals(2, coordinator.getConversation().length());
        coordinator.onStateChanged("READY", "old queued state");
        assertEquals("CONNECTING", coordinator.getGatewayState());
        try { coordinator.startNewSession(); fail("Rotation must be single-flight"); }
        catch (JSONException expected) { assertEquals("TURN_BUSY", expected.getMessage()); }
        assertBusy();
        assertEquals(1, transport.rotations);
        transport.state = "READY";
        coordinator.onStateChanged("READY", "");
        assertFalse(coordinator.getStatus().getBoolean("turnBusy"));
        startText();
        assertEquals(2, transport.submissions);
    }

    @Test public void rejectedNewSessionPreservesConversationIncludingAnUncertainCancelledRun() throws Exception {
        String turn = startText();
        JSONObject before = coordinator.getConversationSnapshot(0);
        try { coordinator.startNewSession(); fail("An active turn must not be discarded"); }
        catch (JSONException expected) { assertEquals("TURN_BUSY", expected.getMessage()); }
        assertEquals(before.toString(), coordinator.getConversationSnapshot(0).toString());
        coordinator.cancelActiveTurn(turn, "user_interaction", new Capture());
        assertNull(coordinator.getActiveTurnId());
        assertTrue(coordinator.getStatus().getBoolean("turnBusy"));
        try { coordinator.startNewSession(); fail("Remote uncertainty must remain gated"); }
        catch (JSONException expected) { assertEquals("TURN_BUSY", expected.getMessage()); }
        assertEquals(0, transport.rotations);
    }

    @Test public void batteryStatusAndEveryRobotStateIncludeTheSameNullableReading() throws Exception {
        assertTrue(coordinator.getStatus().getJSONObject("battery").isNull("percentage"));
        coordinator.updateBattery(BatteryState.fromReading(73, 100, 2, true));
        assertEquals(73, coordinator.getStatus().getJSONObject("battery").getInt("percentage"));
        assertEquals(73, latest("local.robot.state").getJSONObject("data").getJSONObject("battery").getInt("percentage"));
        coordinator.publishRobotEvent("robotUnavailable", object());
        assertEquals(73, latest("local.robot.state").getJSONObject("data").getJSONObject("battery").getInt("percentage"));
        JSONArray recovery = coordinator.getLocalRecoveryFrames();
        assertEquals(73, recovery.getJSONObject(1).getJSONObject("data").getJSONObject("battery").getInt("percentage"));
    }

    @Test public void cancelledTranscriptionCannotCreateAnOldRun() throws Exception {
        transport.deferTranscription = true;
        String old = UUID.randomUUID().toString();
        coordinator.submitTurn(object("clientTurnId", old, "audioBase64", "fixture", "language", "zh-TW"));
        HermesTransport.ResultCallback late = transport.transcription;
        coordinator.cancelActiveTurn(old, "screen_off", new Capture());
        late.onSuccess(object("text", "late"));
        assertEquals(0, transport.submissions);
        assertNull(coordinator.getActiveTurnId());
        startText();
        assertEquals(1, transport.submissions);
    }

    @Test public void lostSpeechBindingReleasesTheTurnForANewUserRecording() throws Exception {
        transport.deferTranscription = true;
        coordinator.submitTurn(object("clientTurnId", UUID.randomUUID().toString(), "audioBase64", "fixture"));
        coordinator.onStateChanged("OFFLINE", "GATEWAY_OFFLINE");
        transport.transcription.onError("GATEWAY_OFFLINE", "private remote detail");
        JSONObject error = latest("turn.error").getJSONObject("data").getJSONObject("error");
        assertEquals("GATEWAY_OFFLINE", error.getString("code"));
        assertTrue(error.getBoolean("retryable"));
        assertFalse(error.getString("message").contains("private"));
        assertNull(coordinator.getActiveTurnId());
        assertEquals(0, transport.submissions);
        coordinator.onStateChanged("READY", "");
        coordinator.submitTurn(object("clientTurnId", UUID.randomUUID().toString(), "audioBase64", "new recording"));
        transport.transcription.onSuccess(object("text", "new user utterance"));
        assertEquals(1, transport.submissions);
    }

    @Test public void disconnectedSubmissionIsRejectedBeforeCreatingAnyTurn() throws Exception {
        coordinator.onStateChanged("OFFLINE", "GATEWAY_OFFLINE");
        long before = coordinator.getLastSequence();
        try {
            startText();
            fail("A disconnected gateway must reject an unaccepted turn");
        } catch (RemoteSessionCoordinator.GatewayUnavailableException expected) { }
        assertNull(coordinator.getActiveTurnId());
        assertEquals(before, coordinator.getLastSequence());
        assertEquals(0, count("turn.accepted"));
        assertEquals(0, transport.submissions);
        coordinator.onStateChanged("READY", "");
        startText();
        assertEquals(1, transport.submissions);
    }

    @Test public void speechAndAssistantTextLimitsPreserveUnicodeCodePoints() throws Exception {
        String emoji = "\uD83E\uDD16";
        String valid = emoji.repeat(16_000);
        transport.deferTranscription = true;
        coordinator.submitTurn(object("clientTurnId", UUID.randomUUID().toString(),
                "audioBase64", "fixture", "language", "zh-TW"));
        transport.transcription.onSuccess(object("text", valid));
        assertEquals(valid, transport.submittedText);
        coordinator.onRunEvent("run-remote", "assistant.delta", object("text", valid));
        assertEquals(valid, coordinator.getConversationSnapshot(0).getString("assistantText"));
        String finalText = "a".repeat(15_999) + emoji;
        coordinator.onRunEvent("run-remote", "assistant.final", object("text", finalText + "z"));
        assertEquals(finalText, coordinator.getConversationSnapshot(0).getString("assistantText"));
    }

    @Test public void cancellationDuringSubmissionWaitsForRemoteRunTerminalBeforeNextTurn() throws Exception {
        transport.deferSubmission = true;
        String turn = startText();
        Capture cancel = new Capture();
        coordinator.cancelActiveTurn(turn, "user_interaction", cancel);
        assertNotNull(cancel.result);
        assertEquals(1, count("turn.cancelled"));
        assertBusy();
        transport.submission.onSuccess(object("runId", "run-remote", "status", "started"));
        assertEquals(1, transport.stops);
        assertBusy();
        coordinator.onRunEvent("run-remote", "run.cancelled", object());
        transport.deferSubmission = false;
        startText();
        assertEquals(2, transport.submissions);
    }

    @Test public void multipartSpeechKeepsTurnUntilEverySegmentFinishesPlayback() throws Exception {
        String turn = startText();
        finishRun();
        assertEquals(1, count("tts.ready"));
        assertEquals(0, count("turn.completed"));
        String first = transport.artifacts.getJSONObject(0).getString("artifactId");
        String second = transport.artifacts.getJSONObject(1).getString("artifactId");
        playback(turn, first, "started");
        playback(turn, first, "completed");
        assertEquals(0, count("turn.completed"));
        assertEquals(turn, coordinator.getActiveTurnId());
        playback(turn, second, "started");
        playback(turn, second, "completed");
        assertEquals(1, count("turn.completed"));
        assertNull(coordinator.getActiveTurnId());
    }

    @Test public void outOfOrderPlaylistStartIsRejected() throws Exception {
        String turn = startText();
        finishRun();
        Capture capture = new Capture();
        coordinator.reportPlayback(object("turnId", turn,
                "artifactId", transport.artifacts.getJSONObject(1).getString("artifactId"), "status", "started"), capture);
        assertEquals("CONFLICT", capture.code);
    }

    @Test public void oldRunFinalCannotSpeakIntoReplacementTurn() throws Exception {
        String old = startText();
        coordinator.cancelActiveTurn(old, "user_interaction", new Capture());
        coordinator.onRunEvent("run-remote", "run.cancelled", object());
        transport.runId = "new-run";
        startText();
        coordinator.onRunEvent("run-remote", "assistant.final", object("text", "stale"));
        coordinator.onRunEvent("run-remote", "run.completed", object());
        assertEquals(0, transport.syntheses);
    }

    @Test public void queuedPhysicalActionLosesAuthorityImmediatelyOnCancellation() throws Exception {
        coordinator.setMotionEnabled(true);
        robot.queue = true;
        String turn = startText();
        coordinator.onDeviceToolCall(tool("look_at_user", object("doa", 10)));
        assertNotNull(robot.pendingGuard);
        coordinator.cancelActiveTurn(turn, "screen_off", new Capture());
        assertFalse(robot.pendingGuard.runIfAllowed(() -> robot.physicalEffects++));
        assertEquals(0, robot.physicalEffects);
        assertTrue(robot.stops > 0);
    }

    @Test public void duplicateAcknowledgedPhysicalCallDoesNotRepeatActionOrTerminal() throws Exception {
        coordinator.setMotionEnabled(true);
        startText();
        JSONObject call = tool("look_at_user", object("doa", 10));
        coordinator.onDeviceToolCall(call);
        coordinator.onDeviceToolCall(call);
        assertEquals(1, robot.executions);
        assertEquals(1, robot.physicalEffects);
        assertEquals(2, transport.toolUpdates.size()); // accepted and one acknowledged terminal
        assertEquals("succeeded", transport.toolUpdates.get(1).getString("status"));
    }

    @Test public void motionDefaultsOffAndRejectsOnlyMotionWithoutFailingConversation() throws Exception {
        assertFalse(coordinator.getStatus().getBoolean("motionEnabled"));
        coordinator.setMotionEnabled(false);
        assertEquals(0, robot.stops); // An idle toggle must not trigger vendor hardware UI.
        String turn = startText();
        coordinator.onDeviceToolCall(tool("look_at_user", object("doa", 10)));
        coordinator.onDeviceToolCall(tool("start_robot_following", object()));
        assertEquals(0, robot.executions);
        for (JSONObject update : transport.toolUpdates) {
            assertEquals("rejected", update.getString("status"));
            assertEquals("MOTION_DISABLED", update.getJSONObject("error").getString("code"));
        }
        coordinator.onDeviceToolCall(tool("stop_robot_following", object()));
        coordinator.onDeviceToolCall(tool("get_system_status", object()));
        assertEquals(2, robot.executions);
        coordinator.onDeviceToolCall(tool("show_emotion", object("emotion", "HAPPY", "durationMs", 0)));
        assertEquals(1, count("tool.call"));
        assertEquals(turn, coordinator.getActiveTurnId());
        assertEquals(0, count("turn.error"));
        assertFalse(latest("local.robot.state").getJSONObject("data").getBoolean("motionEnabled"));
    }

    @Test public void disablingMotionRevokesQueuedActionEvenWhenReenabled() throws Exception {
        coordinator.setMotionEnabled(true);
        robot.queue = true;
        String turn = startText();
        coordinator.onDeviceToolCall(tool("look_at_user", object("doa", 10)));
        GuardedExecution.Guard queued = robot.pendingGuard;
        assertNotNull(queued);
        coordinator.setMotionEnabled(false);
        coordinator.setMotionEnabled(true);
        assertFalse(queued.runIfAllowed(() -> robot.physicalEffects++));
        assertEquals(0, robot.physicalEffects);
        assertEquals(1, robot.stops);
        assertEquals("MOTION_DISABLED", transport.toolUpdates.get(1).getJSONObject("error").getString("code"));
        assertEquals(turn, coordinator.getActiveTurnId());
        assertEquals(0, count("turn.cancelled"));
    }

    @Test public void disablingMotionStopsAnAlreadyRunningAction() throws Exception {
        coordinator.setMotionEnabled(true);
        startText();
        coordinator.onDeviceToolCall(tool("start_robot_following", object()));
        assertTrue(robot.isMoving());
        JSONObject result = coordinator.setMotionEnabled(false);
        assertFalse(result.getBoolean("motionEnabled"));
        assertFalse(result.getBoolean("moving"));
        assertEquals(1, robot.stops);
    }

    @Test public void persistenceFailureNeverGrantsOrRestoresMotionAuthority() throws Exception {
        preferences.failCommits = true;
        try { coordinator.setMotionEnabled(true); fail("Saving enabled must fail"); }
        catch (IllegalStateException expected) { }
        assertFalse(coordinator.isMotionEnabled());
        preferences.failCommits = false;
        coordinator.setMotionEnabled(true);
        preferences.failCommits = true;
        try { coordinator.setMotionEnabled(false); fail("Saving disabled must report failure"); }
        catch (IllegalStateException expected) { }
        assertFalse(coordinator.isMotionEnabled());
        assertFalse(latest("local.robot.state").getJSONObject("data").getBoolean("motionEnabled"));
        assertEquals(0, robot.stops);
    }

    @Test public void sleepStopsHardwareImmediatelyButLocalTerminalReplyWaitsForRemoteAck() throws Exception {
        startText();
        transport.deferToolTerminal = true;
        JSONObject call = tool("go_to_sleep", object());
        coordinator.onDeviceToolCall(call);
        assertTrue(robot.stops > 0);
        assertEquals(1, count("tool.call"));
        Capture result = new Capture();
        JSONObject update = object("status", "succeeded", "updatedAt", future(), "output", object("ok", true));
        coordinator.reportToolResult(call.getString("callId"), update, result);
        assertNull(result.result);
        assertNull(result.code);
        transport.toolTerminal.onSuccess(update);
        assertNotNull(result.result);
        assertNull(result.code);
        assertEquals(0, count("turn.cancelled"));
    }

    @Test public void runTerminalAbandonsUnacknowledgedResultWithoutReplayingActionOrBlockingNextTurn() throws Exception {
        String turn = startText();
        transport.deferToolTerminal = true;
        JSONObject call = tool("show_emotion", object("emotion", "HAPPY", "durationMs", 0));
        coordinator.onDeviceToolCall(call);
        Capture result = new Capture();
        JSONObject update = object("status", "succeeded", "updatedAt", future(), "output", object("ok", true));
        coordinator.reportToolResult(call.getString("callId"), update, result);
        HermesTransport.ResultCallback lateAck = transport.toolTerminal;
        coordinator.cancelActiveTurn(turn, "user_interaction", new Capture());
        assertBusy();
        coordinator.onRunEvent("run-remote", "run.cancelled", object());
        assertEquals("TURN_CANCELLED", result.code);
        assertNull(result.result);
        lateAck.onSuccess(update);
        assertNull(result.result); // A late ACK does not rewrite the resolved uncertainty.
        int sent = transport.toolUpdates.size();
        coordinator.onDeviceToolCall(call);
        assertEquals(sent, transport.toolUpdates.size());
        transport.runId = "replacement-run";
        startText();
        assertEquals(2, transport.submissions);
    }

    @Test public void wrongOwnerAndStaleRunToolsNeverReachHardware() throws Exception {
        startText();
        JSONObject wrongOwner = tool("look_at_user", object("doa", 10)).put("owner", "web");
        coordinator.onDeviceToolCall(wrongOwner);
        JSONObject stale = tool("look_at_user", object("doa", 10)).put("runId", "old-run");
        coordinator.onDeviceToolCall(stale);
        assertEquals(0, robot.executions);
        assertEquals("rejected", transport.toolUpdates.get(0).getString("status"));
        assertEquals("rejected", transport.toolUpdates.get(1).getString("status"));
    }

    @Test public void pythonMicrosecondUtcToolDeadlineIsAcceptedOnApi23Parser() throws Exception {
        startText();
        JSONObject call = tool("show_emotion", object("emotion", "HAPPY", "durationMs", 0));
        call.put("deadlineAt", future().replace("Z", "123+00:00"));
        coordinator.onDeviceToolCall(call);
        assertEquals(1, count("tool.call"));
        assertEquals("accepted", transport.toolUpdates.get(0).getString("status"));
    }

    @Test public void followingDeadlineIncludesAttentionStopAvoidanceAndAcquisition() throws Exception {
        assertScheduledToolDeadline("start_robot_following", object(), 2_000 + 1_500 + 3_000);
    }

    @Test public void moveDeadlineIncludesAttentionStopAvoidanceAndMovement() throws Exception {
        assertScheduledToolDeadline("move_robot", object("direction", "forward"), 2_000 + 1_500 + 2_000);
    }

    private void assertScheduledToolDeadline(String name, JSONObject arguments, int nativeBudgetMs) throws Exception {
        RecordingScheduler recording = useRecordingScheduler();
        coordinator.setMotionEnabled(true);
        startText();
        robot.queue = true;
        recording.delays.clear();
        coordinator.onDeviceToolCall(tool(name, arguments));
        assertEquals(1, robot.executions);
        assertEquals(1, recording.delays.size());
        long delay = recording.delays.get(0);
        assertTrue("Full Native chain must fit within the tool deadline", delay > nativeBudgetMs);
        assertTrue(delay <= ToolManifestSpec.timeoutMs(name));
        assertEquals(nativeBudgetMs + 1_000, ToolManifestSpec.timeoutMs(name));
    }

    @Test public void toolDeadlineHonorsAnEarlierRemoteExpiry() throws Exception {
        RecordingScheduler recording = useRecordingScheduler();
        coordinator.setMotionEnabled(true);
        startText();
        robot.queue = true;
        JSONObject call = tool("start_robot_following", object());
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        call.put("deadlineAt", format.format(new Date(System.currentTimeMillis() + 2_000)));
        recording.delays.clear();
        coordinator.onDeviceToolCall(call);
        assertEquals(1, robot.executions);
        assertEquals(1, recording.delays.size());
        assertTrue(recording.delays.get(0) > 0);
        assertTrue(recording.delays.get(0) <= 2_000);
    }

    @Test public void deviceToolRejectsTimeoutsDeclaredForAnotherTool() throws Exception {
        coordinator.setMotionEnabled(true);
        startText();
        coordinator.onDeviceToolCall(tool("start_robot_following", object()).put("timeoutMs", 5_000));
        coordinator.onDeviceToolCall(tool("move_robot", object("direction", "forward")).put("timeoutMs", 7_500));
        coordinator.onDeviceToolCall(tool("stop_robot_following", object()).put("timeoutMs", 6_500));
        coordinator.onDeviceToolCall(tool("start_robot_following", object()).put("timeoutMs", "7500"));
        coordinator.onDeviceToolCall(tool("move_robot", object("direction", "forward")).put("timeoutMs", 6_500.5));
        assertEquals(0, robot.executions);
        assertEquals(5, transport.toolUpdates.size());
        for (JSONObject update : transport.toolUpdates) assertEquals("rejected", update.getString("status"));
    }

    private RecordingScheduler useRecordingScheduler() {
        scheduler.shutdownNow();
        RecordingScheduler recording = new RecordingScheduler();
        scheduler = recording;
        coordinator = new RemoteSessionCoordinator(transport, robot, scheduler, new GatewaySettings(preferences));
        coordinator.setLocalPublisher(events::add);
        coordinator.onStateChanged("READY", "ready");
        return recording;
    }

    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
        final List<Long> delays = new ArrayList<>();
        RecordingScheduler() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delays.add(unit.toMillis(delay));
            return super.schedule(command, delay, unit);
        }
    }

    @Test public void webEmotionToolHasLocalTurnIdentityAndOneTerminalResult() throws Exception {
        String turn = startText();
        JSONObject call = tool("show_emotion", object("emotion", "HAPPY", "durationMs", 0));
        coordinator.onDeviceToolCall(call);
        JSONObject event = latest("tool.call");
        assertEquals(turn, event.getString("turnId"));
        Capture capture = new Capture();
        coordinator.reportToolResult(call.getString("callId"), object("status", "succeeded", "updatedAt", future(),
                "output", object("ok", true, "emotion", "HAPPY", "durationMs", 0)), capture);
        assertNotNull(capture.result);
        assertEquals(0, robot.executions);
    }

    @Test public void cancelledAudioDownloadCannotDeliverLateBytesToRenderer() throws Exception {
        String turn = startText();
        finishRun();
        transport.deferDownload = true;
        BinaryCapture capture = new BinaryCapture();
        coordinator.downloadAudio(transport.artifacts.getJSONObject(0).getString("artifactId"), capture);
        coordinator.cancelActiveTurn(turn, "user_interaction", new Capture());
        transport.download.onSuccess(FakeTransport.AUDIO, "audio/wav", "fixture", future());
        assertEquals("TURN_CANCELLED", capture.code);
        assertNull(capture.bytes);
    }

    @Test public void artifactDigestMismatchIsRejectedBeforeRendererDelivery() throws Exception {
        startText();
        finishRun();
        transport.downloadBytes = "corrupt".getBytes(StandardCharsets.UTF_8);
        BinaryCapture capture = new BinaryCapture();
        coordinator.downloadAudio(transport.artifacts.getJSONObject(0).getString("artifactId"), capture);
        assertEquals("INVALID_AUDIO", capture.code);
        assertNull(capture.bytes);
    }

    @Test public void lostStreamUsesRunStatusInsteadOfReplayingOldRemoteCursor() throws Exception {
        startText();
        transport.runStatus = "completed";
        coordinator.onRunEvent("run-remote", "stream.disconnected", object());
        assertEquals(1, transport.statusReads);
        assertEquals(1, transport.syntheses);
        assertEquals("Recovered response", latest("agent.text.final").getJSONObject("data").getString("text"));
    }

    private String startText() throws Exception {
        String turn = UUID.randomUUID().toString();
        coordinator.submitTurn(object("clientTurnId", turn, "text", "hello", "language", "zh-TW"));
        return turn;
    }
    private void finishRun() {
        coordinator.onRunEvent("run-remote", "assistant.final", object("text", "Hello. Another sentence."));
        coordinator.onRunEvent("run-remote", "run.completed", object());
    }
    private void assertBusy() throws Exception {
        try { startText(); fail("A second run must wait for the old run to terminate"); }
        catch (JSONException expected) { assertEquals("TURN_BUSY", expected.getMessage()); }
    }
    private void playback(String turn, String artifact, String state) {
        Capture capture = new Capture();
        coordinator.reportPlayback(object("turnId", turn, "artifactId", artifact, "status", state), capture);
        assertNull(capture.code);
        assertNotNull(capture.result);
    }
    @Test public void cameraOutputReachesHermesButOnlyMetadataReachesRendererAndSnapshot() throws Exception {
        startText();
        robot.nextOutput = object("accepted",true,"artifactId",UUID.randomUUID().toString(),"mimeType","image/jpeg",
                "byteLength",3,"sha256", "a".repeat(64),"width",1,"height",1,"capturedAt",future(),"imageBase64","/9j/");
        JSONObject call = tool("capture_camera",object());
        coordinator.onDeviceToolCall(call);
        assertEquals(1,count("camera.captured"));
        assertFalse("ACK releases the transport image", robot.nextOutput.has("imageBase64"));
        assertEquals("/9j/",transport.toolUpdates.get(transport.toolUpdates.size()-1).getJSONObject("output").getString("imageBase64"));
        assertFalse(coordinator.getConversation().toString().contains("imageBase64"));
        assertFalse(coordinator.getConversationSnapshot(0).toString().contains("imageBase64"));
        assertEquals(1,coordinator.getConversationSnapshot(0).getJSONArray("cameraCaptures").length());
        coordinator.onDeviceToolCall(call);
        assertEquals(1,count("camera.captured"));
    }
    @Test public void onlyOneUnacknowledgedCameraPayloadMayBeRetained() throws Exception {
        startText(); transport.deferToolTerminal = true;
        robot.nextOutput = object("accepted",true,"artifactId",UUID.randomUUID().toString(),"imageBase64","/9j/");
        coordinator.onDeviceToolCall(tool("capture_camera",object()));
        assertEquals(1,robot.executions);
        HermesTransport.ResultCallback firstDelivery = transport.toolTerminal;
        coordinator.onDeviceToolCall(tool("capture_camera",object()));
        assertEquals(1,robot.executions);
        firstDelivery.onSuccess(object());
        coordinator.onDeviceToolCall(tool("capture_camera",object()));
        assertEquals(2,robot.executions);
    }
    @Test public void movementRequiresNativePermissionAndStrictDirectionAndManualControlWaitsForTurn() throws Exception {
        assertTrue(coordinator.manualDeviceActionAllowed());
        startText(); assertFalse(coordinator.manualDeviceActionAllowed());
        coordinator.onDeviceToolCall(tool("move_robot",object("direction","forward")));
        assertEquals(0,robot.physicalEffects);
        coordinator.setMotionEnabled(true);
        coordinator.onDeviceToolCall(tool("move_robot",object("direction","diagonal")));
        assertEquals(0,robot.physicalEffects);
        coordinator.onDeviceToolCall(tool("move_robot",object("direction","forward","distance",100)));
        assertEquals(0,robot.physicalEffects);
        coordinator.onDeviceToolCall(tool("move_robot",object("direction","forward")));
        assertEquals(1,robot.physicalEffects);
    }
    @Test public void cancelledQueuedCaptureCannotPublishImageOrExecute() throws Exception {
        startText(); robot.queue = true;
        coordinator.onDeviceToolCall(tool("capture_camera",object()));
        coordinator.cancelActiveTurn("","user_interaction",new Capture());
        assertFalse(robot.pendingGuard.runIfAllowed(() -> fail("Cancelled camera may not execute")));
        assertEquals(0,count("camera.captured"));
    }

    @Test public void headPressCancellationRevokesPassiveAttentionAndQueuedCameraAuthority() throws Exception {
        FakeHardware lifecycle = new FakeHardware(); coordinator.setHardwareLifecycle(lifecycle);
        startText(); robot.queue = true;
        coordinator.onDeviceToolCall(tool("capture_camera", object()));
        lifecycle.attending = true;
        coordinator.publishRobotEvent("HeadPress", object());
        assertFalse(lifecycle.attending);
        assertEquals(1,lifecycle.stops);
        assertFalse(robot.pendingGuard.runIfAllowed(() -> fail("A cancelled capture may not resume")));
    }
    @Test public void lastRendererLossCancelsTurnAndSuspendsCameraEvenWithoutActiveTurn() throws Exception {
        FakeHardware lifecycle = new FakeHardware(); coordinator.setHardwareLifecycle(lifecycle);
        String turn = startText();
        coordinator.onRendererDisconnected();
        assertFalse(lifecycle.cameraEnabled);
        assertEquals(1,lifecycle.suspends);
        assertEquals(1,count("turn.cancelled"));
        coordinator.onRunEvent("run-remote","run.cancelled",object());
        lifecycle.cameraEnabled = true; lifecycle.attending = true;
        coordinator.onRendererDisconnected();
        assertFalse(lifecycle.cameraEnabled);
        assertFalse(lifecycle.attending);
        assertEquals(2,lifecycle.suspends);
        assertNull(coordinator.getActiveTurnId());
    }
    @Test public void rendererReplacementAndDuplicateCloseDoNotCauseFalseLastDisconnect() {
        Set<String> connected = new HashSet<>(Arrays.asList("old","replacement"));
        assertFalse(LocalRuntimeServer.removeLastRenderer(connected,"old"));
        assertFalse(LocalRuntimeServer.removeLastRenderer(connected,"old"));
        assertTrue(LocalRuntimeServer.removeLastRenderer(connected,"replacement"));
        assertFalse(LocalRuntimeServer.removeLastRenderer(connected,"replacement"));
    }
    @Test public void nativeFailurePreservesActionableCameraCodeAndBoundsMessage() throws Exception {
        JSONObject error = RemoteSessionCoordinator.nativeToolError(object("code","CAMERA_DISABLED","message","x".repeat(600)));
        assertEquals("CAMERA_DISABLED",error.getString("code"));
        assertEquals(512,error.getString("message").length());
        assertEquals("EXECUTION_FAILED", RemoteSessionCoordinator.nativeToolError(object("code","not a code")).getString("code"));
    }
    private static final class FakeHardware implements RemoteSessionCoordinator.HardwareLifecycle {
        boolean attending, cameraEnabled = true; int stops,suspends;
        public void setMotionAllowed(boolean allowed) { }
        public void stop() { stops++; attending = false; }
        public void suspend() { suspends++; attending = false; cameraEnabled = false; }
    }

    private JSONObject tool(String name, JSONObject arguments) {
        return object("callId", UUID.randomUUID().toString(), "runId", "run-remote", "toolName", name,
                "toolVersion", "1.0.0", "arguments", arguments, "timeoutMs", ToolManifestSpec.timeoutMs(name), "deadlineAt", future());
    }
    private int count(String type) {
        int count = 0;
        for (JSONObject event : events) if (type.equals(event.optString("type"))) count++;
        return count;
    }
    private JSONObject latest(String type) {
        JSONObject result = null;
        for (JSONObject event : events) if (type.equals(event.optString("type"))) result = event;
        assertNotNull(result);
        return result;
    }
    private static String future() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(System.currentTimeMillis() + 60_000));
    }
    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); }
        catch (JSONException error) { throw new AssertionError(error); }
        return result;
    }
    private static class Capture implements HermesTransport.ResultCallback {
        JSONObject result; String code;
        public void onSuccess(JSONObject result) { this.result = result; }
        public void onError(String code, String message) { this.code = code; }
    }
    private static class BinaryCapture implements HermesTransport.BinaryCallback {
        byte[] bytes; String code;
        public void onSuccess(byte[] bytes, String type, String digest, String expires) { this.bytes = bytes; }
        public void onError(String code, String message) { this.code = code; }
    }
    private static class FakeTransport implements HermesTransport {
        static final byte[] AUDIO = "audio fixture".getBytes(StandardCharsets.UTF_8);
        int submissions; int syntheses; int stops; int statusReads; int rotations;
        String state = "READY";
        String submittedText; String runId = "run-remote"; String runStatus = "running";
        boolean deferTranscription; boolean deferSubmission; boolean deferDownload; boolean deferToolTerminal;
        ResultCallback toolTerminal;
        ResultCallback transcription; ResultCallback submission; BinaryCallback download;
        byte[] downloadBytes = AUDIO;
        JSONArray artifacts = new JSONArray();
        List<JSONObject> toolUpdates = new ArrayList<>();
        public void start() { }
        public void reload() { }
        public NewSessionResult startNewSession() { rotations++; state = "CONNECTING"; return NewSessionResult.STARTED; }
        public void shutdown() { }
        public JSONObject getStatus() { return object("state", state); }
        public String getRemoteSessionId() { return "hermes-session"; }
        public void submitText(String id, String text, String language, ResultCallback callback) {
            submissions++; submittedText = text; submission = callback;
            if (!deferSubmission) callback.onSuccess(object("runId", runId, "status", "started"));
        }
        public void transcribe(JSONObject input, ResultCallback callback) {
            transcription = callback;
            if (!deferTranscription) callback.onSuccess(object("text", "recognized"));
        }
        public void synthesize(String run, String text, String language, ResultCallback callback) {
            syntheses++;
            artifacts = new JSONArray();
            for (int i = 0; i < 2; i++) artifacts.put(object("artifactId", UUID.randomUUID().toString(),
                    "mimeType", "audio/wav", "byteLength", AUDIO.length,
                    "sha256", AudioArtifactValidator.sha256(AUDIO), "expiresAt", future()));
            callback.onSuccess(object("segments", artifacts));
        }
        public void getRun(String run, ResultCallback callback) {
            statusReads++;
            callback.onSuccess(object("runId", run, "status", runStatus, "output", "Recovered response"));
        }
        public void stopRun(String run, ResultCallback callback) { stops++; callback.onSuccess(object("status", "stopping")); }
        public void downloadAudio(JSONObject metadata, BinaryCallback callback) {
            download = callback;
            if (!deferDownload) callback.onSuccess(downloadBytes, "audio/wav", "fixture", future());
        }
        public void reportToolResult(String id, JSONObject update, ResultCallback callback) {
            try { toolUpdates.add(new JSONObject(update.toString())); }
            catch (JSONException invalid) { throw new AssertionError(invalid); }
            if (deferToolTerminal && !"accepted".equals(update.optString("status"))) toolTerminal = callback;
            else callback.onSuccess(update);
        }
        public void reportPlayback(JSONObject update, ResultCallback callback) { callback.onSuccess(update); }
    }
    private static class FakeRobot implements RobotOperations {
        int stops; int executions; int physicalEffects; boolean queue; boolean moving;
        GuardedExecution.Guard pendingGuard;
        JSONObject nextOutput = object("accepted",true);
        public boolean isReady() { return true; }
        public boolean isMoving() { return moving; }
        public boolean emergencyStop() { stops++; boolean stopped = moving; moving = false; return stopped; }
        public Set<String> getAllowedTools() { return new HashSet<>(Arrays.asList(
                "get_system_status", "start_robot_following", "stop_robot_following", "look_at_user", "show_emotion", "go_to_sleep", "move_robot", "capture_camera")); }
        public boolean isNativeTool(String name) { return !"show_emotion".equals(name) && !"go_to_sleep".equals(name); }
        public boolean isPhysicalTool(String name) { return isNativeTool(name) && !"get_system_status".equals(name); }
        public void execute(String callId, String name, JSONObject arguments, GuardedExecution.Guard guard, ResultCallback callback) {
            executions++; pendingGuard = guard;
            if (queue) return;
            if (guard.runIfAllowed(() -> {
                if ("look_at_user".equals(name) || "start_robot_following".equals(name) || "move_robot".equals(name)) {
                    physicalEffects++;
                    moving = true;
                } else if ("stop_robot_following".equals(name)) moving = false;
            })) callback.onResult(object("status", "queued", "result", nextOutput));
        }
    }
}
