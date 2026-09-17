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

import static org.junit.Assert.*;

public class RemoteSessionCoordinatorTest {
    private FakeTransport transport;
    private FakeRobot robot;
    private ScheduledExecutorService scheduler;
    private RemoteSessionCoordinator coordinator;
    private List<JSONObject> events;

    @Before public void setUp() {
        transport = new FakeTransport();
        robot = new FakeRobot();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        coordinator = new RemoteSessionCoordinator(transport, robot, scheduler);
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
        startText();
        JSONObject call = tool("look_at_user", object("doa", 10));
        coordinator.onDeviceToolCall(call);
        coordinator.onDeviceToolCall(call);
        assertEquals(1, robot.executions);
        assertEquals(1, robot.physicalEffects);
        assertEquals(2, transport.toolUpdates.size()); // accepted and one acknowledged terminal
        assertEquals("succeeded", transport.toolUpdates.get(1).getString("status"));
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
    private JSONObject tool(String name, JSONObject arguments) {
        return object("callId", UUID.randomUUID().toString(), "runId", "run-remote", "toolName", name,
                "toolVersion", "1.0.0", "arguments", arguments, "timeoutMs", 5_000, "deadlineAt", future());
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
        int submissions; int syntheses; int stops; int statusReads;
        String submittedText; String runId = "run-remote"; String runStatus = "running";
        boolean deferTranscription; boolean deferSubmission; boolean deferDownload; boolean deferToolTerminal;
        ResultCallback toolTerminal;
        ResultCallback transcription; ResultCallback submission; BinaryCallback download;
        byte[] downloadBytes = AUDIO;
        JSONArray artifacts = new JSONArray();
        List<JSONObject> toolUpdates = new ArrayList<>();
        public void start() { }
        public void reload() { }
        public void shutdown() { }
        public JSONObject getStatus() { return object("state", "READY"); }
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
            toolUpdates.add(update);
            if (deferToolTerminal && !"accepted".equals(update.optString("status"))) toolTerminal = callback;
            else callback.onSuccess(update);
        }
        public void reportPlayback(JSONObject update, ResultCallback callback) { callback.onSuccess(update); }
    }
    private static class FakeRobot implements RobotOperations {
        int stops; int executions; int physicalEffects; boolean queue;
        GuardedExecution.Guard pendingGuard;
        public boolean isReady() { return true; }
        public boolean isMoving() { return physicalEffects > 0; }
        public boolean emergencyStop() { stops++; return false; }
        public Set<String> getAllowedTools() { return new HashSet<>(Arrays.asList(
                "get_system_status", "start_robot_following", "stop_robot_following", "look_at_user", "show_emotion", "go_to_sleep")); }
        public boolean isNativeTool(String name) { return !"show_emotion".equals(name) && !"go_to_sleep".equals(name); }
        public boolean isPhysicalTool(String name) { return isNativeTool(name) && !"get_system_status".equals(name); }
        public void execute(String callId, String name, JSONObject arguments, GuardedExecution.Guard guard, ResultCallback callback) {
            executions++; pendingGuard = guard;
            if (queue) return;
            if (guard.runIfAllowed(() -> physicalEffects++)) callback.onResult(object("status", "queued", "result", object("accepted", true)));
        }
    }
}
