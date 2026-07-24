package com.robot.asus.kira;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RemoteSessionCoordinatorTest {
    private static final String TURN_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION_A = "22222222-2222-4222-8222-222222222222";
    private static final String SESSION_B = "33333333-3333-4333-8333-333333333333";
    private static final String TURN_B = "44444444-4444-4444-8444-444444444444";

    @Test
    public void webToolRemainsReplayableAcrossRendererReloadUntilTerminalResultIsCaptured()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            List<JSONObject> firstRenderer = new ArrayList<>();
            fixture.coordinator.setLocalPublisher(firstRenderer::add);
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            JSONObject call = toolCall("show_emotion", new JSONObject().put("emotion", "HAPPY"));

            fixture.coordinator.onGatewayMessage(call, eventCommit);

            assertEquals(0, eventCommit.commits);
            assertEquals(0, eventCommit.retries);
            assertTrue(containsEvent(fixture.coordinator.getConversation(), call.getString("eventId")));
            assertTrue(containsEvent(new JSONArray(firstRenderer), call.getString("eventId")));

            List<JSONObject> reloadedRenderer = new ArrayList<>();
            fixture.coordinator.setLocalPublisher(reloadedRenderer::add);
            for (JSONObject retained : jsonObjects(fixture.coordinator.getConversation())) {
                reloadedRenderer.add(retained);
            }
            assertTrue(containsEvent(new JSONArray(reloadedRenderer), call.getString("eventId")));
            assertEquals(0, eventCommit.commits);

            RecordingResultCallback terminalCallback = new RecordingResultCallback();
            fixture.coordinator.reportToolResult(
                    call.getJSONObject("data").getString("callId"),
                    succeededEmotionUpdate(),
                    terminalCallback
            );

            assertEquals(1, eventCommit.commits);
            assertEquals(1, terminalCallback.successes);
            assertEquals(0, fixture.robot.executeCount);
        }
    }

    @Test
    public void failedWebTerminalUploadResendsCachedResultWithoutRequiringExecutionAgain()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.gateway.terminalFailuresRemaining = 1;
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            fixture.coordinator.onGatewayMessage(call, eventCommit);
            String callId = call.getJSONObject("data").getString("callId");
            JSONObject terminal = succeededUpdate();

            RecordingResultCallback failedAttempt = new RecordingResultCallback();
            fixture.coordinator.reportToolResult(callId, terminal, failedAttempt);
            assertEquals(1, failedAttempt.errors);
            assertEquals(1, eventCommit.commits);

            RecordingResultCallback cachedRetry = new RecordingResultCallback();
            fixture.coordinator.reportToolResult(callId, terminal, cachedRetry);

            assertEquals(2, fixture.gateway.terminalReports);
            assertEquals(1, cachedRetry.successes);
            assertEquals(1, eventCommit.commits);
            assertEquals(0, fixture.robot.executeCount);
        }
    }

    @Test
    public void failedNativeTerminalUploadResendsCacheWithoutRepeatingPhysicalAction()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.gateway.terminalFailuresRemaining = 1;
            JSONObject call = toolCall("look_at_user", new JSONObject().put("doa", 12));
            RecordingEventCommit initialCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(call, initialCommit);

            assertEquals(1, initialCommit.commits);
            assertEquals(1, fixture.robot.executeCount);
            assertEquals(1, fixture.gateway.terminalReports);

            RecordingEventCommit replayCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, replayCommit);

            assertEquals(1, fixture.robot.executeCount);
            assertEquals(2, fixture.gateway.terminalReports);
            assertEquals(1, replayCommit.commits);
        }
    }

    @Test
    public void retainsEveryTerminalToolResultForAtLeastFiveMinutesEvenAboveOldCap()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            for (int index = 0; index < 129; index++) {
                JSONObject call = toolCall("go_to_sleep", new JSONObject());
                fixture.coordinator.onGatewayMessage(call, new RecordingEventCommit());
                fixture.coordinator.reportToolResult(
                        call.getJSONObject("data").getString("callId"),
                        succeededUpdate(),
                        new RecordingResultCallback()
                );
            }

            assertEquals(129, fixture.coordinator.retainedTerminalToolCount());
            fixture.clock.addAndGet(5L * 60L * 1000L);
            assertEquals(129, fixture.coordinator.retainedTerminalToolCount());
            fixture.clock.incrementAndGet();
            assertEquals(0, fixture.coordinator.retainedTerminalToolCount());
        }
    }

    @Test
    public void cancellationTerminalizesPendingWebToolAndResolvesItsCommitCallback()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(
                    toolCall("show_emotion", new JSONObject().put("emotion", "HAPPY")),
                    eventCommit
            );
            RecordingResultCallback cancelCallback = new RecordingResultCallback();

            fixture.coordinator.cancelActiveTurn(TURN_ID, "user_interaction", cancelCallback);

            assertEquals(1, eventCommit.commits);
            assertEquals(1, cancelCallback.successes);
            assertEquals("failed", fixture.gateway.lastTerminalStatus);
        }
    }

    @Test
    public void localCancelTerminalizesQueuedNativeToolEvenWhenCancelHttpFails()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            fixture.gateway.cancelFailuresRemaining = 1;
            JSONObject call = toolCall("look_at_user", new JSONObject().put("doa", 12));
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(call, new RecordingEventCommit());
            RecordingResultCallback cancelCallback = new RecordingResultCallback();

            fixture.coordinator.cancelActiveTurn(
                    TURN_ID,
                    "user_interaction",
                    cancelCallback
            );

            assertEquals(1, cancelCallback.errors);
            assertEquals(1, fixture.gateway.terminalReports);
            ToolCallJournal.Entry terminal = fixture.journal.get(
                    fixture.gateway.remoteSessionId,
                    callId
            );
            assertTrue(terminal.isTerminal());
            assertEquals(
                    "TURN_CANCELLED",
                    terminal.terminalResult.getJSONObject("error").getString("code")
            );
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    terminal.deliveryState
            );
            assertTrue(!fixture.robot.runDelayedAction());
            fixture.robot.completeDelayedSuccess();
            assertEquals(0, fixture.robot.executeCount);
            assertEquals(1, fixture.gateway.terminalReports);

            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void localCancelTreatsTerminalToolConflictAsDurablyAbandoned()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            fixture.gateway.terminalConflictsRemaining = 1;
            JSONObject call = toolCall("look_at_user", new JSONObject().put("doa", 12));
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(call, new RecordingEventCommit());

            fixture.coordinator.cancelActiveTurn(
                    TURN_ID,
                    "user_interaction",
                    new RecordingResultCallback()
            );

            ToolCallJournal.Entry terminal = fixture.journal.get(
                    fixture.gateway.remoteSessionId,
                    callId
            );
            assertTrue(terminal.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    terminal.deliveryState
            );
            assertTrue(!fixture.robot.runDelayedAction());
            fixture.robot.completeDelayedSuccess();
            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void receivedTurnTerminalAbandonsQueuedNativeToolAndFencesCallback()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            JSONObject call = toolCall(
                    "start_robot_following",
                    new JSONObject().put("enablePreview", true)
            );
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(call, new RecordingEventCommit());
            RecordingEventCommit terminalCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    turnCancelled(fixture.gateway),
                    terminalCommit
            );

            assertEquals(1, terminalCommit.commits);
            assertTrue(fixture.coordinator.getActiveTurnId() == null);
            ToolCallJournal.Entry terminal = fixture.journal.get(
                    fixture.gateway.remoteSessionId,
                    callId
            );
            assertTrue(terminal.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    terminal.deliveryState
            );
            assertTrue(!fixture.robot.runDelayedAction());
            fixture.robot.completeDelayedSuccess();
            assertEquals(0, fixture.robot.executeCount);
            assertEquals(0, fixture.gateway.terminalReports);

            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            assertEquals(0, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void terminalSessionAbandonsOldDeliveryButRetainsDuplicateSuppressionForFiveMinutes()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.gateway.holdTerminalReports = true;
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            RecordingEventCommit toolCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, toolCommit);
            RecordingResultCallback resultCallback = new RecordingResultCallback();
            fixture.coordinator.reportToolResult(
                    call.getJSONObject("data").getString("callId"),
                    succeededUpdate(),
                    resultCallback
            );
            assertEquals(1, toolCommit.commits);
            assertEquals(0, resultCallback.errors);

            RecordingEventCommit sessionCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(
                    new JSONObject()
                            .put("type", "session.closed")
                            .put("eventId", UUID.randomUUID().toString())
                            .put("sequence", 3)
                            .put("data", new JSONObject().put("reason", "policy")),
                    sessionCommit
            );

            assertEquals(1, sessionCommit.commits);
            assertEquals(1, resultCallback.errors);
            assertEquals(1, fixture.coordinator.retainedTerminalToolCount());
            fixture.gateway.completeHeldTerminalReport();
            assertEquals(1, fixture.coordinator.retainedTerminalToolCount());
            fixture.clock.addAndGet(5L * 60L * 1000L + 1L);
            assertEquals(0, fixture.coordinator.retainedTerminalToolCount());
        }
    }

    @Test
    public void stoppingCoordinatorRetriesAnyUncommittedWebToolFrame() throws Exception {
        Fixture fixture = new Fixture();
        fixture.activateTurn();
        RecordingEventCommit eventCommit = new RecordingEventCommit();
        fixture.coordinator.onGatewayMessage(
                toolCall("go_to_sleep", new JSONObject()),
                eventCommit
        );

        fixture.close();

        assertEquals(0, eventCommit.commits);
        assertEquals(1, eventCommit.retries);
    }

    @Test
    public void dispatchedToolRecoversAsExecutionUncertainAndIsNeverExecutedAgain()
            throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        InMemoryToolCallJournal journal = new InMemoryToolCallJournal();
        FakeGateway firstGateway = new FakeGateway();
        FakeRobot firstRobot = new FakeRobot();
        firstRobot.delayExecution = true;
        JSONObject call = toolCall("look_at_user", new JSONObject().put("doa", 12));
        String callId = call.getJSONObject("data").getString("callId");

        Fixture first = new Fixture(clock, firstGateway, firstRobot, journal);
        first.activateTurn();
        RecordingEventCommit firstCommit = new RecordingEventCommit();
        first.coordinator.onGatewayMessage(call, firstCommit);
        assertEquals(1, firstCommit.commits);
        assertEquals(0, firstRobot.executeCount);
        assertTrue(journal.get(firstGateway.remoteSessionId, callId) != null);
        first.close();

        FakeGateway recoveredGateway = new FakeGateway();
        FakeRobot recoveredRobot = new FakeRobot();
        try (Fixture recovered = new Fixture(
                clock,
                recoveredGateway,
                recoveredRobot,
                journal
        )) {
            RecordingEventCommit readyCommit = new RecordingEventCommit();
            recovered.coordinator.onGatewayMessage(sessionReady(recoveredGateway), readyCommit);

            assertEquals(1, readyCommit.commits);
            assertEquals(1, recoveredGateway.terminalReports);
            assertEquals(
                    "EXECUTION_UNCERTAIN",
                    recoveredGateway.lastTerminalUpdate
                            .getJSONObject("error")
                            .getString("code")
            );
            ToolCallJournal.Entry recoveredEntry =
                    journal.get(recoveredGateway.remoteSessionId, callId);
            assertTrue(recoveredEntry.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    recoveredEntry.deliveryState
            );

            RecordingEventCommit replayCommit = new RecordingEventCommit();
            recovered.coordinator.onGatewayMessage(call, replayCommit);
            assertEquals(1, replayCommit.commits);
            assertEquals(0, recoveredRobot.executeCount);
            assertEquals(1, recoveredGateway.terminalReports);
        }
    }

    @Test
    public void persistedTerminalResultIsResentAndAcknowledgedAfterRestart()
            throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        InMemoryToolCallJournal journal = new InMemoryToolCallJournal();
        FakeGateway firstGateway = new FakeGateway();
        firstGateway.holdTerminalReports = true;
        JSONObject call = toolCall("go_to_sleep", new JSONObject());
        String callId = call.getJSONObject("data").getString("callId");

        Fixture first = new Fixture(clock, firstGateway, new FakeRobot(), journal);
        first.activateTurn();
        first.coordinator.onGatewayMessage(call, new RecordingEventCommit());
        first.coordinator.reportToolResult(
                callId,
                succeededUpdate(),
                new RecordingResultCallback()
        );
        ToolCallJournal.Entry pending =
                journal.get(firstGateway.remoteSessionId, callId);
        assertEquals(ToolCallJournal.DeliveryState.PENDING, pending.deliveryState);
        first.close();

        FakeGateway recoveredGateway = new FakeGateway();
        try (Fixture recovered = new Fixture(
                clock,
                recoveredGateway,
                new FakeRobot(),
                journal
        )) {
            recovered.coordinator.onGatewayMessage(
                    sessionReady(recoveredGateway),
                    new RecordingEventCommit()
            );

            assertEquals(1, recoveredGateway.terminalReports);
            assertEquals("succeeded", recoveredGateway.lastTerminalStatus);
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    journal.get(recoveredGateway.remoteSessionId, callId).deliveryState
            );

            List<JSONObject> rendererEvents = new ArrayList<>();
            recovered.coordinator.setLocalPublisher(rendererEvents::add);
            RecordingEventCommit replayCommit = new RecordingEventCommit();
            recovered.coordinator.onGatewayMessage(call, replayCommit);
            assertEquals(1, replayCommit.commits);
            assertEquals(0, rendererEvents.size());
            assertEquals(1, recoveredGateway.terminalReports);
        }
    }

    @Test
    public void lateUploadFailureAfterStopLeavesTerminalResultPendingForRestart()
            throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        InMemoryToolCallJournal journal = new InMemoryToolCallJournal();
        FakeGateway firstGateway = new FakeGateway();
        firstGateway.holdTerminalReports = true;
        JSONObject call = toolCall("go_to_sleep", new JSONObject());
        String callId = call.getJSONObject("data").getString("callId");

        Fixture first = new Fixture(clock, firstGateway, new FakeRobot(), journal);
        first.activateTurn();
        first.coordinator.onGatewayMessage(call, new RecordingEventCommit());
        first.coordinator.reportToolResult(
                callId,
                succeededUpdate(),
                new RecordingResultCallback()
        );
        first.close();
        firstGateway.failHeldTerminalReport();

        assertEquals(
                ToolCallJournal.DeliveryState.PENDING,
                journal.get(SESSION_A, callId).deliveryState
        );

        FakeGateway recoveredGateway = new FakeGateway();
        try (Fixture recovered = new Fixture(
                clock,
                recoveredGateway,
                new FakeRobot(),
                journal
        )) {
            recovered.coordinator.onGatewayMessage(
                    sessionReady(recoveredGateway),
                    new RecordingEventCommit()
            );

            assertEquals(1, recoveredGateway.terminalReports);
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    journal.get(recoveredGateway.remoteSessionId, callId).deliveryState
            );
        }
    }

    @Test
    public void websocketReconnectKeepsLiveWebToolPending() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall(
                    "show_emotion",
                    new JSONObject().put("emotion", "HAPPY")
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit toolCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, toolCommit);

            RecordingEventCommit readyCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    readyCommit
            );

            ToolCallJournal.Entry live =
                    fixture.journal.get(fixture.gateway.remoteSessionId, callId);
            assertTrue(!live.isTerminal());
            assertEquals(0, fixture.gateway.terminalReports);
            assertEquals(1, readyCommit.commits);
            assertEquals(0, toolCommit.commits);

            fixture.coordinator.reportToolResult(
                    callId,
                    succeededEmotionUpdate(),
                    new RecordingResultCallback()
            );
            assertEquals(1, toolCommit.commits);
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void websocketReconnectKeepsQueuedNativeToolLive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            JSONObject call = toolCall("look_at_user", new JSONObject().put("doa", 12));
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(call, new RecordingEventCommit());

            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );

            ToolCallJournal.Entry live =
                    fixture.journal.get(fixture.gateway.remoteSessionId, callId);
            assertTrue(!live.isTerminal());
            assertEquals(0, fixture.gateway.terminalReports);
            assertTrue(fixture.robot.runDelayedAction());
            fixture.robot.completeDelayedSuccess();
            assertEquals(1, fixture.robot.executeCount);
            assertEquals(1, fixture.gateway.terminalReports);
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    fixture.journal.get(
                            fixture.gateway.remoteSessionId,
                            callId
                    ).deliveryState
            );
        }
    }

    @Test
    public void terminalSessionFencesQueuedNativeActionAndLateCallback()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            JSONObject call = toolCall(
                    "start_robot_following",
                    new JSONObject().put("enablePreview", true)
            );
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(call, new RecordingEventCommit());

            RecordingEventCommit terminalCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(
                    new JSONObject()
                            .put("sessionId", fixture.gateway.remoteSessionId)
                            .put("type", "session.closed")
                            .put("eventId", UUID.randomUUID().toString())
                            .put("sequence", 3)
                            .put("data", new JSONObject().put("reason", "policy")),
                    terminalCommit
            );

            assertEquals(1, terminalCommit.commits);
            assertTrue(!fixture.robot.runDelayedAction());
            fixture.robot.completeDelayedSuccess();
            assertEquals(0, fixture.robot.executeCount);
            assertEquals(0, fixture.gateway.terminalReports);
            ToolCallJournal.Entry entry =
                    fixture.journal.get(fixture.gateway.remoteSessionId, callId);
            assertTrue(entry.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    entry.deliveryState
            );
        }
    }

    @Test
    public void journalFailurePreventsNativeDispatchAndRetriesGatewayFrame()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.journal.failNextMutation = true;
            RecordingEventCommit eventCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    toolCall("look_at_user", new JSONObject().put("doa", 12)),
                    eventCommit
            );

            assertEquals(0, fixture.robot.executeCount);
            assertEquals(0, eventCommit.commits);
            assertEquals(1, eventCommit.retries);
        }
    }

    @Test
    public void failedReloadPreflightLeavesLiveTurnAndJournalUntouched()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit toolCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, toolCommit);
            fixture.gateway.failReloadPreparation = true;

            assertThrows(
                    IllegalStateException.class,
                    fixture.coordinator::reloadGateway
            );

            assertEquals(TURN_ID, fixture.coordinator.getActiveTurnId());
            assertEquals(0, fixture.robot.emergencyStopCount);
            assertEquals(0, fixture.gateway.reloads);
            assertEquals(0, toolCommit.commits);
            assertEquals(0, toolCommit.retries);
            assertTrue(!fixture.journal.get(
                    fixture.gateway.remoteSessionId,
                    callId
            ).isTerminal());

            fixture.coordinator.reportToolResult(
                    callId,
                    succeededUpdate(),
                    new RecordingResultCallback()
            );
            assertEquals(1, toolCommit.commits);
        }
    }

    @Test
    public void reloadBetweenWebAcceptanceAndRegistrationNeverPublishesToolCall()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            List<JSONObject> published = new ArrayList<>();
            fixture.coordinator.setLocalPublisher(published::add);
            fixture.coordinator.setBeforeWebToolRegistrationHookForTest(
                    fixture.coordinator::reloadGateway
            );
            JSONObject call = toolCall(
                    "show_emotion",
                    new JSONObject().put("emotion", "HAPPY")
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(call, eventCommit);

            assertEquals(0, countEventsOfType(published, "tool.call"));
            assertEquals(0, eventCommit.commits);
            assertEquals(1, eventCommit.retries);
            ToolCallJournal.Entry entry =
                    fixture.journal.get(SESSION_A, callId);
            assertTrue(entry != null && entry.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    entry.deliveryState
            );
        }
    }

    @Test
    public void rendererReloadReentrantFromToolPublicationDoesNotDeadlock()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            AtomicBoolean reloaded = new AtomicBoolean();
            fixture.coordinator.setLocalPublisher(event -> {
                if ("tool.call".equals(event.optString("type"))
                        && reloaded.compareAndSet(false, true)) {
                    fixture.coordinator.reloadGateway();
                }
            });
            JSONObject call = toolCall(
                    "show_emotion",
                    new JSONObject().put("emotion", "HAPPY")
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> dispatch = executor.submit(
                        () -> fixture.coordinator.onGatewayMessage(call, eventCommit)
                );
                dispatch.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertTrue(reloaded.get());
            assertEquals(1, fixture.gateway.reloads);
            assertEquals(0, eventCommit.commits);
            assertEquals(1, eventCommit.retries);
            ToolCallJournal.Entry entry =
                    fixture.journal.get(SESSION_A, callId);
            assertTrue(entry != null && entry.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    entry.deliveryState
            );
        }
    }

    @Test
    public void lateUploadSuccessFromFencedSessionCannotCancelReplacementSession()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.gateway.holdUploads = true;
            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            String clientTurnId = "44444444-4444-4444-8444-444444444444";
            fixture.coordinator.submitTurn(new JSONObject()
                    .put("clientTurnId", clientTurnId)
                    .put("text", "old"));
            RecordingResultCallback oldCancel = new RecordingResultCallback();
            fixture.coordinator.cancelActiveTurn(
                    clientTurnId,
                    "user_interaction",
                    oldCancel
            );

            fixture.coordinator.reloadGateway();
            assertEquals(1, oldCancel.errors);
            assertEquals("SESSION_EXPIRED", oldCancel.errorCode);

            fixture.gateway.remoteSessionId = SESSION_B;
            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            fixture.coordinator.submitTurn(new JSONObject()
                    .put("clientTurnId", clientTurnId)
                    .put("text", "new"));

            fixture.gateway.completeHeldUpload(0, TURN_ID);

            assertEquals(0, fixture.gateway.cancelCalls);
            assertTrue(fixture.coordinator.getActiveTurnId() == null);

            fixture.gateway.completeHeldUpload(0, TURN_ID);

            assertEquals(TURN_ID, fixture.coordinator.getActiveTurnId());
            assertEquals(0, fixture.gateway.cancelCalls);
        }
    }

    @Test
    public void bindingNewSessionAbandonsEveryStaleJournalEntryWithFreshRetention()
            throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        InMemoryToolCallJournal journal = new InMemoryToolCallJournal();
        String callId = "55555555-5555-4555-8555-555555555555";
        assertTrue(journal.recordDispatched(new ToolCallJournal.Entry(
                SESSION_A,
                callId,
                TURN_ID,
                "native",
                "look_at_user",
                clock.get() - 60_000L,
                null,
                0L,
                ToolCallJournal.DeliveryState.NONE
        )));
        FakeGateway gateway = new FakeGateway();
        gateway.remoteSessionId = SESSION_B;
        try (Fixture fixture = new Fixture(
                clock,
                gateway,
                new FakeRobot(),
                journal
        )) {
            RecordingEventCommit readyCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    sessionReady(gateway),
                    readyCommit
            );

            assertEquals(1, readyCommit.commits);
            ToolCallJournal.Entry abandoned = journal.get(SESSION_A, callId);
            assertTrue(abandoned != null && abandoned.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    abandoned.deliveryState
            );
            assertEquals(clock.get(), abandoned.terminalAt);

            clock.addAndGet(5L * 60L * 1000L);
            assertTrue(journal.prune(clock.get(), 5L * 60L * 1000L));
            assertTrue(journal.get(SESSION_A, callId) != null);
            clock.incrementAndGet();
            assertTrue(journal.prune(clock.get(), 5L * 60L * 1000L));
            assertTrue(journal.get(SESSION_A, callId) == null);
        }
    }

    @Test
    public void corruptGlobalJournalFailsSessionRecoveryClosed() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        InMemoryToolCallJournal journal = new InMemoryToolCallJournal();
        String callId = "66666666-6666-4666-8666-666666666666";
        assertTrue(journal.recordDispatched(new ToolCallJournal.Entry(
                SESSION_A,
                callId,
                TURN_ID,
                "web",
                "go_to_sleep",
                clock.get(),
                null,
                0L,
                ToolCallJournal.DeliveryState.NONE
        )));
        journal.failLoadAll = true;
        FakeGateway gateway = new FakeGateway();
        gateway.remoteSessionId = SESSION_B;
        try (Fixture fixture = new Fixture(
                clock,
                gateway,
                new FakeRobot(),
                journal
        )) {
            RecordingEventCommit readyCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    sessionReady(gateway),
                    readyCommit
            );

            assertEquals(0, readyCommit.commits);
            assertEquals(1, readyCommit.retries);
            assertThrows(
                    org.json.JSONException.class,
                    () -> fixture.coordinator.submitTurn(new JSONObject()
                            .put("clientTurnId", UUID.randomUUID().toString())
                            .put("text", "must stay blocked"))
            );
            assertEquals(0, gateway.uploadCalls);
            ToolCallJournal.Entry unchanged = journal.get(SESSION_A, callId);
            assertTrue(unchanged != null && !unchanged.isTerminal());
            assertEquals(0, gateway.terminalReports);
        }
    }

    @Test
    public void staleTerminalFromOldSessionCannotRevokeSameTurnIdInReplacementSession()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();

            fixture.gateway.remoteSessionId = SESSION_B;
            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            JSONObject call = toolCall(
                    SESSION_B,
                    TURN_ID,
                    "look_at_user",
                    new JSONObject().put("doa", 12)
            );
            fixture.coordinator.onGatewayMessage(
                    call,
                    new RecordingEventCommit()
            );
            RecordingEventCommit staleCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    turnCancelled(SESSION_A, TURN_ID),
                    staleCommit
            );

            assertEquals(0, staleCommit.commits);
            assertEquals(1, staleCommit.retries);
            assertEquals(TURN_ID, fixture.coordinator.getActiveTurnId());
            assertTrue(fixture.robot.runDelayedAction());
            fixture.robot.completeDelayedSuccess();
            assertEquals(1, fixture.robot.executeCount);
            assertEquals(SESSION_B, fixture.gateway.lastReportedSessionId);
        }
    }

    @Test
    public void remoteTurnTerminalDrainsPendingWebToolWithoutTerminalUpload()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit toolCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, toolCommit);
            RecordingEventCommit terminalCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    turnCancelled(fixture.gateway),
                    terminalCommit
            );

            assertEquals(1, terminalCommit.commits);
            assertEquals(1, toolCommit.commits);
            assertEquals(0, toolCommit.retries);
            ToolCallJournal.Entry entry =
                    fixture.journal.get(SESSION_A, callId);
            assertTrue(entry != null && entry.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    entry.deliveryState
            );
            assertEquals(0, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void conflictingWebTerminalUploadAbandonsButReportsConflictToLocalWaiter()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(
                    call,
                    new RecordingEventCommit()
            );
            fixture.gateway.terminalConflictsRemaining = 1;
            RecordingResultCallback result = new RecordingResultCallback();

            fixture.coordinator.reportToolResult(
                    callId,
                    succeededUpdate(),
                    result
            );

            assertEquals(0, result.successes);
            assertEquals(1, result.errors);
            assertEquals("CONFLICT", result.errorCode);
            ToolCallJournal.Entry entry =
                    fixture.journal.get(SESSION_A, callId);
            assertTrue(entry != null && entry.isTerminal());
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    entry.deliveryState
            );
            assertEquals(1, fixture.gateway.terminalReports);

            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void staleCancelCannotAttachToDifferentPendingUpload() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.gateway.holdUploads = true;
            fixture.coordinator.onGatewayMessage(
                    sessionReady(fixture.gateway),
                    new RecordingEventCommit()
            );
            String newClientTurnId =
                    "77777777-7777-4777-8777-777777777777";
            fixture.coordinator.submitTurn(new JSONObject()
                    .put("clientTurnId", newClientTurnId)
                    .put("text", "new upload"));
            RecordingResultCallback staleCancel =
                    new RecordingResultCallback();

            fixture.coordinator.cancelActiveTurn(
                    "88888888-8888-4888-8888-888888888888",
                    "client_request",
                    staleCancel
            );

            assertEquals(1, staleCancel.successes);
            assertEquals(false, staleCancel.result.getBoolean(
                    "remoteCancelled"
            ));
            assertEquals(false, staleCancel.result.getBoolean(
                    "robotStopped"
            ));
            assertEquals(0, fixture.gateway.cancelCalls);
            assertEquals(0, fixture.robot.emergencyStopCount);

            fixture.gateway.completeHeldUpload(0, TURN_ID);
            assertEquals(TURN_ID, fixture.coordinator.getActiveTurnId());
        }
    }

    @Test
    public void corruptCurrentSessionJournalKeepsTurnSubmissionBlocked()
            throws Exception {
        InMemoryToolCallJournal journal = new InMemoryToolCallJournal();
        journal.failLoadSessionId = SESSION_A;
        FakeGateway gateway = new FakeGateway();
        try (Fixture fixture = new Fixture(
                new AtomicLong(System.currentTimeMillis()),
                gateway,
                new FakeRobot(),
                journal
        )) {
            RecordingEventCommit readyCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    sessionReady(gateway),
                    readyCommit
            );

            assertEquals(0, readyCommit.commits);
            assertEquals(1, readyCommit.retries);
            assertThrows(
                    org.json.JSONException.class,
                    () -> fixture.coordinator.submitTurn(new JSONObject()
                            .put("clientTurnId", UUID.randomUUID().toString())
                            .put("text", "must stay blocked"))
            );
            assertEquals(0, gateway.uploadCalls);
        }
    }

    @Test
    public void acknowledgedWebResultStaysAcknowledgedAfterTurnCompletes()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(
                    call,
                    new RecordingEventCommit()
            );
            fixture.coordinator.reportToolResult(
                    callId,
                    succeededUpdate(),
                    new RecordingResultCallback()
            );
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    fixture.journal.get(SESSION_A, callId).deliveryState
            );
            RecordingEventCommit completedCommit =
                    new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(
                    new JSONObject()
                            .put("protocolVersion", "1.0")
                            .put("eventId", UUID.randomUUID().toString())
                            .put("sequence", 3)
                            .put("sessionId", SESSION_A)
                            .put("turnId", TURN_ID)
                            .put("type", "turn.completed")
                            .put("timestamp", iso(System.currentTimeMillis()))
                            .put("data", new JSONObject()),
                    completedCommit
            );

            assertEquals(1, completedCommit.commits);
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    fixture.journal.get(SESSION_A, callId).deliveryState
            );
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void cancelBeforeWebRegistrationKeepsPendingTerminalDelivery()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.gateway.holdTerminalReports = true;
            RecordingResultCallback cancelResult =
                    new RecordingResultCallback();
            fixture.coordinator.setBeforeWebToolRegistrationHookForTest(
                    () -> fixture.coordinator.cancelActiveTurn(
                            TURN_ID,
                            "client_request",
                            cancelResult
                    )
            );
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit toolCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(call, toolCommit);

            assertEquals(1, toolCommit.commits);
            assertEquals(0, toolCommit.retries);
            assertEquals(1, cancelResult.successes);
            assertEquals(1, fixture.gateway.terminalReports);
            assertEquals(
                    ToolCallJournal.DeliveryState.PENDING,
                    fixture.journal.get(SESSION_A, callId).deliveryState
            );

            fixture.gateway.failHeldTerminalReport();
            assertEquals(
                    ToolCallJournal.DeliveryState.PENDING,
                    fixture.journal.get(SESSION_A, callId).deliveryState
            );
        }
    }

    @Test
    public void expiredWebDeadlineSettlesWithoutRendererPublication()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            List<JSONObject> published = new ArrayList<>();
            fixture.coordinator.setLocalPublisher(published::add);
            fixture.coordinator.setBeforeWebToolRegistrationHookForTest(
                    () -> fixture.clock.addAndGet(2L)
            );
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            call.getJSONObject("data").put(
                    "deadlineAt",
                    iso(fixture.clock.get() + 1L)
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(call, eventCommit);

            assertEquals(1, eventCommit.commits);
            assertEquals(0, eventCommit.retries);
            assertEquals(0, countEventsOfType(published, "tool.call"));
            assertEquals(
                    "TIMEOUT",
                    fixture.journal.get(SESSION_A, callId)
                            .terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void nativeDeadlineExpiringDuringPublicationPreventsExecution()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.coordinator.setLocalPublisher(event -> {
                if ("tool.call".equals(event.optString("type"))) {
                    fixture.clock.addAndGet(2L);
                }
            });
            JSONObject call = toolCall(
                    "look_at_user",
                    new JSONObject().put("doa", 12)
            );
            call.getJSONObject("data").put(
                    "deadlineAt",
                    iso(fixture.clock.get() + 1L)
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(call, eventCommit);

            assertEquals(1, eventCommit.commits);
            assertEquals(0, eventCommit.retries);
            assertEquals(0, fixture.robot.executeCount);
            assertEquals(
                    "TIMEOUT",
                    fixture.journal.get(SESSION_A, callId)
                            .terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
        }
    }

    @Test
    public void failedPredispatchTerminalWriteCanReplayAndSettle()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            AtomicBoolean firstAttempt = new AtomicBoolean(true);
            fixture.coordinator.setBeforeWebToolRegistrationHookForTest(() -> {
                if (firstAttempt.compareAndSet(true, false)) {
                    fixture.clock.addAndGet(2L);
                    fixture.journal.failNextMutation = true;
                }
            });
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            call.getJSONObject("data").put(
                    "deadlineAt",
                    iso(fixture.clock.get() + 1L)
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit firstCommit = new RecordingEventCommit();

            fixture.coordinator.onGatewayMessage(call, firstCommit);

            assertEquals(0, firstCommit.commits);
            assertEquals(1, firstCommit.retries);
            RecordingEventCommit replayCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, replayCommit);
            assertEquals(1, replayCommit.commits);
            assertEquals(0, replayCommit.retries);
            assertEquals(
                    "TIMEOUT",
                    fixture.journal.get(SESSION_A, callId)
                            .terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
            assertEquals(0, fixture.robot.executeCount);
        }
    }

    @Test
    public void terminalBetweenJournalRecordAndLifecycleMarkSettlesEveryOwner()
            throws Exception {
        for (String toolName : Arrays.asList(
                "go_to_sleep",
                "look_at_user"
        )) {
            try (Fixture fixture = new Fixture()) {
                fixture.activateTurn();
                AtomicBoolean cancelOnce = new AtomicBoolean(true);
                fixture.coordinator.setAfterLiveDispatchJournalHookForTest(
                        () -> {
                            if (cancelOnce.compareAndSet(true, false)) {
                                fixture.coordinator.cancelActiveTurn(
                                        TURN_ID,
                                        "client_request",
                                        new RecordingResultCallback()
                                );
                            }
                        }
                );
                JSONObject arguments = "look_at_user".equals(toolName)
                        ? new JSONObject().put("doa", 12)
                        : new JSONObject();
                JSONObject call = toolCall(toolName, arguments);
                String callId = call.getJSONObject("data")
                        .getString("callId");
                RecordingEventCommit eventCommit =
                        new RecordingEventCommit();

                fixture.coordinator.onGatewayMessage(call, eventCommit);

                assertEquals(1, eventCommit.commits);
                assertEquals(0, eventCommit.retries);
                assertTrue(fixture.journal.get(
                        SESSION_A,
                        callId
                ).isTerminal());
                assertEquals(0, fixture.robot.executeCount);
            }
        }
    }

    @Test
    public void failedWebTerminalWriteKeepsTimeoutUntilDurableSettlement()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            call.getJSONObject("data").put("timeoutMs", 500);
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, eventCommit);
            fixture.journal.failNextMutation = true;
            RecordingResultCallback result = new RecordingResultCallback();

            fixture.coordinator.reportToolResult(
                    callId,
                    succeededUpdate(),
                    result
            );

            assertEquals(1, result.errors);
            ToolCallJournal.Entry terminal = waitForTerminal(
                    fixture.journal,
                    SESSION_A,
                    callId
            );
            assertEquals(
                    "TIMEOUT",
                    terminal.terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
            assertEquals(1, eventCommit.commits);
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void failedNativeTerminalWriteIsRetriedByExistingTimeout()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            JSONObject call = toolCall(
                    "look_at_user",
                    new JSONObject().put("doa", 12)
            );
            call.getJSONObject("data").put("timeoutMs", 500);
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, eventCommit);
            assertTrue(fixture.robot.runDelayedAction());
            fixture.journal.failNextMutation = true;

            fixture.robot.completeDelayedSuccess();

            ToolCallJournal.Entry terminal = waitForTerminal(
                    fixture.journal,
                    SESSION_A,
                    callId
            );
            assertEquals(
                    "TIMEOUT",
                    terminal.terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
            assertEquals(1, eventCommit.commits);
            assertEquals(1, fixture.robot.executeCount);
            assertEquals(1, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void callbackBlockedBeforeFenceCannotRebindReloadedSession()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean blockOnce = new AtomicBoolean(true);
            fixture.coordinator.setBeforeGatewayMessageFenceHookForTest(() -> {
                if (!blockOnce.compareAndSet(true, false)) return;
                blocked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            RecordingEventCommit staleCommit = new RecordingEventCommit();
            JSONObject staleMessage = turnCancelled(SESSION_A, TURN_ID);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> staleFrame = executor.submit(() ->
                        fixture.coordinator.onGatewayMessage(
                                staleMessage,
                                staleCommit
                        )
                );
                assertTrue(blocked.await(2, TimeUnit.SECONDS));

                fixture.coordinator.reloadGateway();
                release.countDown();
                staleFrame.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }

            assertEquals(0, staleCommit.commits);
            assertEquals(1, staleCommit.retries);
            assertTrue(fixture.coordinator.getActiveTurnId() == null);
            assertTrue(fixture.gateway.remoteSessionId == null);
        }
    }

    @Test
    public void webTerminalCallbackBlockedBeforeCancelCannotWin()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, eventCommit);
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.coordinator.setBeforeToolTerminalFenceHookForTest(() -> {
                blocked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            RecordingResultCallback localResult =
                    new RecordingResultCallback();
            JSONObject succeeded = succeededUpdate();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> resultTask = executor.submit(() ->
                        fixture.coordinator.reportToolResult(
                                callId,
                                succeeded,
                                localResult
                        )
                );
                assertTrue(blocked.await(2, TimeUnit.SECONDS));
                RecordingResultCallback cancel =
                        new RecordingResultCallback();
                fixture.coordinator.cancelActiveTurn(
                        TURN_ID,
                        "client_request",
                        cancel
                );
                release.countDown();
                resultTask.get(2, TimeUnit.SECONDS);

                ToolCallJournal.Entry terminal =
                        fixture.journal.get(SESSION_A, callId);
                assertEquals(
                        "TURN_CANCELLED",
                        terminal.terminalResult
                                .getJSONObject("error")
                                .getString("code")
                );
                assertEquals(
                        ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                        terminal.deliveryState
                );
                assertEquals(1, eventCommit.commits);
                assertEquals(1, fixture.gateway.terminalReports);
                assertEquals(1, cancel.successes);
                assertEquals(1, localResult.successes);
                assertEquals(
                        "failed",
                        localResult.result.getString("status")
                );
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void nativeTerminalCallbackBlockedBeforeCancelCannotWin()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.robot.delayExecution = true;
            JSONObject call = toolCall(
                    "look_at_user",
                    new JSONObject().put("doa", 12)
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, eventCommit);
            assertTrue(fixture.robot.runDelayedAction());
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.coordinator.setBeforeToolTerminalFenceHookForTest(() -> {
                blocked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> resultTask = executor.submit(() -> {
                    try {
                        fixture.robot.completeDelayedSuccess();
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
                assertTrue(blocked.await(2, TimeUnit.SECONDS));
                fixture.coordinator.cancelActiveTurn(
                        TURN_ID,
                        "client_request",
                        new RecordingResultCallback()
                );
                release.countDown();
                resultTask.get(2, TimeUnit.SECONDS);

                ToolCallJournal.Entry terminal =
                        fixture.journal.get(SESSION_A, callId);
                assertEquals(
                        "TURN_CANCELLED",
                        terminal.terminalResult
                                .getJSONObject("error")
                                .getString("code")
                );
                assertEquals(
                        ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                        terminal.deliveryState
                );
                assertEquals(1, fixture.robot.executeCount);
                assertEquals(1, fixture.gateway.terminalReports);
                assertEquals(1, eventCommit.commits);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void stalePlaybackCallbackCannotMutateReplacementSession()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            String artifactId = UUID.randomUUID().toString();
            fixture.coordinator.onGatewayMessage(
                    ttsReady(SESSION_A, TURN_ID, artifactId),
                    new RecordingEventCommit()
            );
            fixture.gateway.holdPlaybackReports = true;
            RecordingResultCallback stale = new RecordingResultCallback();
            fixture.coordinator.reportPlayback(
                    playbackUpdate(TURN_ID, artifactId, "completed"),
                    stale
            );

            activateReplacementTurn(fixture);
            fixture.coordinator.onGatewayMessage(
                    ttsReady(SESSION_B, TURN_B, artifactId),
                    new RecordingEventCommit()
            );
            fixture.gateway.completeHeldPlayback();

            assertEquals(0, stale.successes);
            assertEquals(1, stale.errors);
            assertEquals("SESSION_EXPIRED", stale.errorCode);
            assertEquals(
                    "SYNTHESIZING",
                    fixture.coordinator.getConversationSnapshot(0L)
                            .getString("turnState")
            );
            fixture.gateway.holdPlaybackReports = false;
            RecordingResultCallback current = new RecordingResultCallback();
            fixture.coordinator.reportPlayback(
                    playbackUpdate(TURN_B, artifactId, "completed"),
                    current
            );
            assertEquals(
                    Arrays.asList(SESSION_A, SESSION_B),
                    fixture.gateway.playbackSessionIds
            );
            assertEquals(1, current.successes);
            assertEquals(
                    "IDLE",
                    fixture.coordinator.getConversationSnapshot(0L)
                            .getString("turnState")
            );
        }
    }

    @Test
    public void staleAudioDownloadCannotEscapeOrConsumeReplacementArtifact()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            String artifactId = UUID.randomUUID().toString();
            fixture.coordinator.onGatewayMessage(
                    ttsReady(SESSION_A, TURN_ID, artifactId),
                    new RecordingEventCommit()
            );
            fixture.gateway.holdDownloads = true;
            RecordingBinaryCallback stale = new RecordingBinaryCallback();
            fixture.coordinator.downloadAudio(artifactId, stale);

            activateReplacementTurn(fixture);
            fixture.coordinator.onGatewayMessage(
                    ttsReady(SESSION_B, TURN_B, artifactId),
                    new RecordingEventCommit()
            );
            byte[] staleBytes = new byte[]{1, 2, 3};
            fixture.gateway.completeHeldDownload(
                    staleBytes,
                    "audio/mpeg",
                    "sha-256=:stale:",
                    iso(System.currentTimeMillis() + 60_000L)
            );
            assertEquals(0, stale.successes);
            assertEquals(1, stale.errors);
            assertEquals("SESSION_EXPIRED", stale.errorCode);

            RecordingBinaryCallback current =
                    new RecordingBinaryCallback();
            fixture.coordinator.downloadAudio(artifactId, current);
            byte[] currentBytes = new byte[]{4, 5, 6};
            fixture.gateway.completeHeldDownload(
                    currentBytes,
                    "audio/mpeg",
                    "sha-256=:current:",
                    iso(System.currentTimeMillis() + 60_000L)
            );
            assertEquals(
                    Arrays.asList(SESSION_A, SESSION_B),
                    fixture.gateway.downloadSessionIds
            );
            assertEquals(1, current.successes);
            assertTrue(Arrays.equals(currentBytes, current.bytes));
        }
    }

    @Test
    public void acceptedConflictIsAbandonedAndDoesNotBlockReplay()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.gateway.acceptedConflictsRemaining = 1;
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit first = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, first);

            ToolCallJournal.Entry terminal =
                    fixture.journal.get(SESSION_A, callId);
            assertEquals(1, first.commits);
            assertEquals(0, first.retries);
            assertEquals(0, fixture.robot.executeCount);
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    terminal.deliveryState
            );
            assertEquals(
                    "CONFLICT",
                    terminal.terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
            RecordingEventCommit replay = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, replay);
            assertEquals(1, replay.commits);
            assertEquals(0, replay.retries);
            assertEquals(0, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void gatewayTerminalAcceptancePreventsDeviceExecution()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            fixture.gateway.acceptedResponseOverride =
                    failedUpdate("TIMEOUT");
            JSONObject call = toolCall(
                    "look_at_user",
                    new JSONObject().put("doa", 12)
            );
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, eventCommit);

            ToolCallJournal.Entry terminal =
                    fixture.journal.get(SESSION_A, callId);
            assertEquals(1, eventCommit.commits);
            assertEquals(0, fixture.robot.executeCount);
            assertEquals(
                    "TIMEOUT",
                    terminal.terminalResult
                            .getJSONObject("error")
                            .getString("code")
            );
            assertEquals(
                    ToolCallJournal.DeliveryState.ACKNOWLEDGED,
                    terminal.deliveryState
            );
            assertEquals(0, fixture.gateway.terminalReports);
        }
    }

    @Test
    public void mismatchedTerminalAcknowledgementIsNotReportedAsSuccess()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            fixture.coordinator.onGatewayMessage(
                    call,
                    new RecordingEventCommit()
            );
            fixture.gateway.terminalResponseOverride =
                    failedUpdate("TIMEOUT");
            RecordingResultCallback result = new RecordingResultCallback();
            fixture.coordinator.reportToolResult(
                    callId,
                    succeededUpdate(),
                    result
            );

            assertEquals(0, result.successes);
            assertEquals(1, result.errors);
            assertEquals("CONFLICT", result.errorCode);
            assertEquals(
                    ToolCallJournal.DeliveryState.ABANDONED,
                    fixture.journal.get(
                            SESSION_A,
                            callId
                    ).deliveryState
            );
        }
    }

    @Test
    public void rendererToolResultMustMatchFixedManifestBeforePersistence()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.activateTurn();
            JSONObject call = toolCall("go_to_sleep", new JSONObject());
            String callId = call.getJSONObject("data").getString("callId");
            RecordingEventCommit eventCommit = new RecordingEventCommit();
            fixture.coordinator.onGatewayMessage(call, eventCommit);
            RecordingResultCallback invalid =
                    new RecordingResultCallback();

            fixture.coordinator.reportToolResult(
                    callId,
                    new JSONObject()
                            .put("status", "succeeded")
                            .put(
                                    "updatedAt",
                                    iso(System.currentTimeMillis())
                            )
                            .put(
                                    "output",
                                    new JSONObject().put("ok", true)
                            ),
                    invalid
            );

            assertEquals(0, invalid.successes);
            assertEquals(1, invalid.errors);
            assertEquals("TOOL_REJECTED", invalid.errorCode);
            assertEquals(0, eventCommit.commits);
            assertTrue(!fixture.journal.get(
                    SESSION_A,
                    callId
            ).isTerminal());

            RecordingResultCallback valid =
                    new RecordingResultCallback();
            fixture.coordinator.reportToolResult(
                    callId,
                    succeededUpdate(),
                    valid
            );
            assertEquals(1, valid.successes);
            assertEquals(1, eventCommit.commits);
        }
    }

    @Test
    public void staleActiveTurnCancelDoesNotStopCurrentRobotWork()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.coordinator.onGatewayMessage(
                    turnAccepted(SESSION_A, TURN_B),
                    new RecordingEventCommit()
            );
            fixture.robot.delayExecution = true;
            fixture.coordinator.onGatewayMessage(
                    toolCall(
                            SESSION_A,
                            TURN_B,
                            "look_at_user",
                            new JSONObject().put("doa", 12)
                    ),
                    new RecordingEventCommit()
            );
            int stopsBefore = fixture.robot.emergencyStopCount;
            RecordingResultCallback stale =
                    new RecordingResultCallback();

            fixture.coordinator.cancelActiveTurn(
                    TURN_ID,
                    "client_request",
                    stale
            );

            assertEquals(1, stale.successes);
            assertEquals(
                    false,
                    stale.result.getBoolean("remoteCancelled")
            );
            assertEquals(
                    false,
                    stale.result.getBoolean("robotStopped")
            );
            assertEquals(stopsBefore, fixture.robot.emergencyStopCount);
            assertEquals(0, fixture.gateway.cancelCalls);
            assertTrue(fixture.robot.runDelayedAction());

            fixture.coordinator.onGatewayMessage(
                    turnCancelled(SESSION_A, TURN_B),
                    new RecordingEventCommit()
            );
            int stopsAfterTerminal = fixture.robot.emergencyStopCount;
            RecordingResultCallback idleStale =
                    new RecordingResultCallback();
            fixture.coordinator.cancelActiveTurn(
                    TURN_B,
                    "client_request",
                    idleStale
            );
            assertEquals(1, idleStale.successes);
            assertEquals(stopsAfterTerminal, fixture.robot.emergencyStopCount);
            assertEquals(0, fixture.gateway.cancelCalls);
        }
    }

    @Test
    public void concurrentActiveTurnCancelsShareTheRemoteOutcome()
            throws Exception {
        for (boolean succeeds : Arrays.asList(true, false)) {
            try (Fixture fixture = new Fixture()) {
                fixture.activateTurn();
                fixture.gateway.holdCancels = true;
                RecordingResultCallback first =
                        new RecordingResultCallback();
                RecordingResultCallback second =
                        new RecordingResultCallback();

                fixture.coordinator.cancelActiveTurn(
                        TURN_ID,
                        "client_request",
                        first
                );
                fixture.coordinator.cancelActiveTurn(
                        TURN_ID,
                        "client_request",
                        second
                );

                assertEquals(1, fixture.gateway.cancelCalls);
                assertEquals(0, first.successes + first.errors);
                assertEquals(0, second.successes + second.errors);
                if (succeeds) {
                    fixture.gateway.completeHeldCancel();
                    assertEquals(1, first.successes);
                    assertEquals(1, second.successes);
                } else {
                    fixture.gateway.failHeldCancel();
                    assertEquals(1, first.errors);
                    assertEquals(1, second.errors);
                    assertEquals("GATEWAY_OFFLINE", first.errorCode);
                    assertEquals("GATEWAY_OFFLINE", second.errorCode);
                }
            }
        }
    }

    @Test
    public void protocolLengthsUseUnicodeCodePointsAndNeverSplitSurrogatePairs() throws Exception {
        String emoji = "\uD83E\uDD16";
        String valid = emoji.repeat(16_000);
        String invalid = valid + emoji;

        assertEquals(16_000, ProtocolStrings.length(valid));
        AgentGatewayClient.requireLength(valid, 1, 16_000, "agent text");
        LocalRuntimeServer.validateTextTurnLength(valid);
        assertThrows(
                org.json.JSONException.class,
                () -> AgentGatewayClient.requireLength(invalid, 1, 16_000, "agent text")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalRuntimeServer.validateTextTurnLength(invalid)
        );

        String truncated = ProtocolStrings.truncate(emoji.repeat(513), 512);
        assertEquals(512, ProtocolStrings.length(truncated));
        assertTrue(Character.isLowSurrogate(truncated.charAt(truncated.length() - 1)));
    }

    private static JSONObject toolCall(String name, JSONObject arguments) throws Exception {
        return toolCall(SESSION_A, TURN_ID, name, arguments);
    }

    private static JSONObject toolCall(
            String sessionId,
            String turnId,
            String name,
            JSONObject arguments
    ) throws Exception {
        String callId = UUID.randomUUID().toString();
        return new JSONObject()
                .put("protocolVersion", "1.0")
                .put("eventId", UUID.randomUUID().toString())
                .put("sequence", 2)
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("type", "tool.call")
                .put("timestamp", iso(System.currentTimeMillis()))
                .put("data", new JSONObject()
                        .put("callId", callId)
                        .put("toolName", name)
                        .put("toolVersion", "1.0.0")
                        .put("arguments", arguments)
                        .put("timeoutMs", 5_000)
                        .put("deadlineAt", iso(System.currentTimeMillis() + 60_000L)));
    }

    private static JSONObject sessionReady(FakeGateway gateway) throws Exception {
        return new JSONObject()
                .put("protocolVersion", "1.0")
                .put("eventId", UUID.randomUUID().toString())
                .put("sequence", 0)
                .put("sessionId", gateway.remoteSessionId)
                .put("type", "session.ready")
                .put("timestamp", iso(System.currentTimeMillis()))
                .put("data", new JSONObject());
    }

    private static JSONObject turnAccepted(
            String sessionId,
            String turnId
    ) throws Exception {
        return new JSONObject()
                .put("protocolVersion", "1.0")
                .put("eventId", UUID.randomUUID().toString())
                .put("sequence", 1)
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("type", "turn.accepted")
                .put("timestamp", iso(System.currentTimeMillis()))
                .put("data", new JSONObject());
    }

    private static JSONObject ttsReady(
            String sessionId,
            String turnId,
            String artifactId
    ) throws Exception {
        return new JSONObject()
                .put("protocolVersion", "1.0")
                .put("eventId", UUID.randomUUID().toString())
                .put("sequence", 2)
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("type", "tts.ready")
                .put("timestamp", iso(System.currentTimeMillis()))
                .put("data", new JSONObject()
                        .put("artifactId", artifactId)
                        .put("mimeType", "audio/mpeg")
                        .put("byteLength", 3)
                        .put("sha256", "a".repeat(64))
                        .put(
                                "expiresAt",
                                iso(System.currentTimeMillis() + 60_000L)
                        ));
    }

    private static JSONObject playbackUpdate(
            String turnId,
            String artifactId,
            String status
    ) throws Exception {
        return new JSONObject()
                .put("turnId", turnId)
                .put("artifactId", artifactId)
                .put("status", status)
                .put("timestamp", iso(System.currentTimeMillis()));
    }

    private static void activateReplacementTurn(Fixture fixture)
            throws Exception {
        fixture.coordinator.reloadGateway();
        fixture.gateway.remoteSessionId = SESSION_B;
        fixture.coordinator.onGatewayMessage(
                sessionReady(fixture.gateway),
                new RecordingEventCommit()
        );
        fixture.coordinator.onGatewayMessage(
                turnAccepted(SESSION_B, TURN_B),
                new RecordingEventCommit()
        );
    }

    private static JSONObject turnCancelled(FakeGateway gateway) throws Exception {
        return turnCancelled(gateway.remoteSessionId, TURN_ID);
    }

    private static JSONObject turnCancelled(String sessionId, String turnId) throws Exception {
        return new JSONObject()
                .put("protocolVersion", "1.0")
                .put("eventId", UUID.randomUUID().toString())
                .put("sequence", 3)
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("type", "turn.cancelled")
                .put("timestamp", iso(System.currentTimeMillis()))
                .put("data", new JSONObject().put("reason", "client_request"));
    }

    private static JSONObject succeededUpdate() throws Exception {
        return new JSONObject()
                .put("status", "succeeded")
                .put("updatedAt", iso(System.currentTimeMillis()))
                .put("output", new JSONObject()
                        .put("ok", true)
                        .put("sleeping", true));
    }

    private static JSONObject succeededEmotionUpdate() throws Exception {
        return new JSONObject()
                .put("status", "succeeded")
                .put("updatedAt", iso(System.currentTimeMillis()))
                .put("output", new JSONObject()
                        .put("ok", true)
                        .put("emotion", "HAPPY")
                        .put("durationMs", 0));
    }

    private static JSONObject failedUpdate(String code) throws Exception {
        return new JSONObject()
                .put("status", "failed")
                .put("updatedAt", iso(System.currentTimeMillis()))
                .put("error", new JSONObject()
                        .put("code", code)
                        .put("message", "Gateway terminalized the tool call")
                        .put("retryable", false));
    }

    private static String iso(long time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(time);
    }

    private static boolean containsEvent(JSONArray events, String eventId) {
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.optJSONObject(index);
            if (event != null && eventId.equals(event.optString("eventId"))) return true;
        }
        return false;
    }

    private static int countEventsOfType(List<JSONObject> events, String type) {
        int count = 0;
        for (JSONObject event : events) {
            if (type.equals(event.optString("type"))) count++;
        }
        return count;
    }

    private static ToolCallJournal.Entry waitForTerminal(
            InMemoryToolCallJournal journal,
            String sessionId,
            String callId
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            ToolCallJournal.Entry entry = journal.get(sessionId, callId);
            if (entry != null && entry.isTerminal()) return entry;
            Thread.sleep(10L);
        }
        throw new AssertionError("Tool result did not become terminal");
    }

    private static List<JSONObject> jsonObjects(JSONArray events) {
        List<JSONObject> result = new ArrayList<>();
        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.optJSONObject(index);
            if (event != null) result.add(event);
        }
        return result;
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong clock;
        final ScheduledExecutorService scheduler;
        final FakeGateway gateway;
        final FakeRobot robot;
        final InMemoryToolCallJournal journal;
        final RemoteSessionCoordinator coordinator;
        boolean closed;

        Fixture() {
            this(
                    new AtomicLong(System.currentTimeMillis()),
                    new FakeGateway(),
                    new FakeRobot(),
                    new InMemoryToolCallJournal()
            );
        }

        Fixture(
                AtomicLong clock,
                FakeGateway gateway,
                FakeRobot robot,
                InMemoryToolCallJournal journal
        ) {
            this.clock = clock;
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
            this.gateway = gateway;
            this.robot = robot;
            this.journal = journal;
            this.coordinator = new RemoteSessionCoordinator(
                    robot,
                    gateway,
                    journal,
                    scheduler,
                    clock::get
            );
        }

        void activateTurn() throws Exception {
            coordinator.onGatewayMessage(
                    new JSONObject()
                            .put("sessionId", gateway.remoteSessionId)
                            .put("turnId", TURN_ID)
                            .put("type", "turn.accepted")
                            .put("eventId", UUID.randomUUID().toString())
                            .put("sequence", 1)
                            .put("data", new JSONObject()),
                    new RecordingEventCommit()
            );
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            coordinator.stop();
        }
    }

    private static final class FakeGateway implements GatewayTransport {
        int terminalFailuresRemaining;
        int terminalConflictsRemaining;
        int terminalReports;
        String lastTerminalStatus = "";
        JSONObject lastTerminalUpdate;
        boolean holdTerminalReports;
        AgentGatewayClient.ResultCallback heldTerminalCallback;
        JSONObject heldTerminalResponse;
        int acceptedConflictsRemaining;
        JSONObject acceptedResponseOverride;
        JSONObject terminalResponseOverride;
        String remoteSessionId = SESSION_A;
        boolean failReloadPreparation;
        int reloads;
        int cancelFailuresRemaining;
        String cancelFailureCode = "GATEWAY_OFFLINE";
        int cancelCalls;
        String lastCancelledSessionId;
        String lastReportedSessionId;
        boolean holdUploads;
        int uploadCalls;
        final List<AgentGatewayClient.ResultCallback> heldUploadCallbacks =
                new ArrayList<>();
        boolean holdCancels;
        AgentGatewayClient.ResultCallback heldCancelCallback;
        boolean holdPlaybackReports;
        int playbackReports;
        String lastPlaybackSessionId;
        final List<String> playbackSessionIds = new ArrayList<>();
        AgentGatewayClient.ResultCallback heldPlaybackCallback;
        boolean holdDownloads;
        int downloadRequests;
        String lastDownloadSessionId;
        final List<String> downloadSessionIds = new ArrayList<>();
        AgentGatewayClient.BinaryCallback heldDownloadCallback;

        @Override public void start() {
        }

        @Override public void prepareReload() {
            if (failReloadPreparation) {
                throw new IllegalStateException("durable reload preflight failed");
            }
        }

        @Override public void reload() {
            reloads++;
            remoteSessionId = null;
        }

        @Override public void shutdown() {
            remoteSessionId = null;
        }

        @Override public JSONObject getStatus() {
            return new JSONObject();
        }

        @Override public String getRemoteSessionId() {
            return remoteSessionId;
        }

        @Override public void uploadTurn(
                String expectedSessionId,
                JSONObject input,
                AgentGatewayClient.ResultCallback callback
        ) {
            uploadCalls++;
            if (holdUploads) {
                heldUploadCallbacks.add(callback);
                return;
            }
            callback.onSuccess(new JSONObject());
        }

        @Override public void cancelTurn(
                String expectedSessionId,
                String turnId,
                String reason,
                AgentGatewayClient.ResultCallback callback
        ) {
            cancelCalls++;
            lastCancelledSessionId = expectedSessionId;
            if (holdCancels) {
                heldCancelCallback = callback;
                return;
            }
            if (cancelFailuresRemaining > 0) {
                cancelFailuresRemaining--;
                callback.onError(cancelFailureCode, "cancel failed");
                return;
            }
            callback.onSuccess(new JSONObject());
        }

        @Override public void reportToolResult(
                String expectedSessionId,
                String callId,
                JSONObject update,
                AgentGatewayClient.ResultCallback callback
        ) {
            lastReportedSessionId = expectedSessionId;
            String status = update.optString("status");
            if ("accepted".equals(status)) {
                if (acceptedConflictsRemaining > 0) {
                    acceptedConflictsRemaining--;
                    callback.onError(
                            "CONFLICT",
                            "turn is already terminal"
                    );
                    return;
                }
                callback.onSuccess(
                        acceptedResponseOverride != null
                                ? acceptedResponseOverride
                                : update
                );
                return;
            }
            if (!"accepted".equals(status)) {
                terminalReports++;
                lastTerminalStatus = status;
                lastTerminalUpdate = update;
                if (terminalConflictsRemaining > 0) {
                    terminalConflictsRemaining--;
                    callback.onError("CONFLICT", "turn is already terminal");
                    return;
                }
                if (terminalFailuresRemaining > 0) {
                    terminalFailuresRemaining--;
                    callback.onError("GATEWAY_OFFLINE", "temporary failure");
                    return;
                }
                if (holdTerminalReports) {
                    heldTerminalCallback = callback;
                    heldTerminalResponse =
                            terminalResponseOverride != null
                                    ? terminalResponseOverride
                                    : update;
                    return;
                }
            }
            callback.onSuccess(
                    terminalResponseOverride != null
                            ? terminalResponseOverride
                            : update
            );
        }

        void completeHeldUpload(int index, String remoteTurnId) throws Exception {
            AgentGatewayClient.ResultCallback callback =
                    heldUploadCallbacks.remove(index);
            callback.onSuccess(new JSONObject().put("turnId", remoteTurnId));
        }

        void completeHeldTerminalReport() {
            AgentGatewayClient.ResultCallback callback = heldTerminalCallback;
            JSONObject response = heldTerminalResponse;
            heldTerminalCallback = null;
            heldTerminalResponse = null;
            if (callback != null) callback.onSuccess(response);
        }

        void failHeldTerminalReport() {
            AgentGatewayClient.ResultCallback callback = heldTerminalCallback;
            heldTerminalCallback = null;
            heldTerminalResponse = null;
            if (callback != null) {
                callback.onError("GATEWAY_OFFLINE", "late failure");
            }
        }

        @Override public void reportPlayback(
                String expectedSessionId,
                JSONObject update,
                AgentGatewayClient.ResultCallback callback
        ) {
            playbackReports++;
            lastPlaybackSessionId = expectedSessionId;
            playbackSessionIds.add(expectedSessionId);
            if (holdPlaybackReports) {
                heldPlaybackCallback = callback;
                return;
            }
            callback.onSuccess(new JSONObject());
        }

        @Override public void downloadAudio(
                String expectedSessionId,
                String artifactId,
                JSONObject expected,
                AgentGatewayClient.BinaryCallback callback
        ) {
            downloadRequests++;
            lastDownloadSessionId = expectedSessionId;
            downloadSessionIds.add(expectedSessionId);
            if (holdDownloads) {
                heldDownloadCallback = callback;
                return;
            }
            callback.onError("NOT_FOUND", "not used");
        }

        void completeHeldCancel() {
            AgentGatewayClient.ResultCallback callback = heldCancelCallback;
            heldCancelCallback = null;
            if (callback != null) callback.onSuccess(new JSONObject());
        }

        void failHeldCancel() {
            AgentGatewayClient.ResultCallback callback = heldCancelCallback;
            heldCancelCallback = null;
            if (callback != null) {
                callback.onError("GATEWAY_OFFLINE", "late cancel failure");
            }
        }

        void completeHeldPlayback() {
            AgentGatewayClient.ResultCallback callback = heldPlaybackCallback;
            heldPlaybackCallback = null;
            if (callback != null) callback.onSuccess(new JSONObject());
        }

        void completeHeldDownload(
                byte[] bytes,
                String contentType,
                String digest,
                String expiresAt
        ) {
            AgentGatewayClient.BinaryCallback callback = heldDownloadCallback;
            heldDownloadCallback = null;
            if (callback != null) {
                callback.onSuccess(
                        bytes,
                        contentType,
                        digest,
                        expiresAt
                );
            }
        }
    }

    private static final class FakeRobot implements RobotOperations {
        private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
                "get_system_status",
                "start_robot_following",
                "stop_robot_following",
                "look_at_user",
                "show_emotion",
                "go_to_sleep"
        ));
        int executeCount;
        int emergencyStopCount;
        boolean delayExecution;
        GuardedExecution.Guard delayedGuard;
        RobotGateway.ResultCallback delayedCallback;

        @Override public boolean isReady() {
            return true;
        }

        @Override public boolean isMoving() {
            return false;
        }

        @Override public Set<String> getAllowedTools() {
            return ALLOWED;
        }

        @Override public boolean isNativeTool(String name) {
            return !("show_emotion".equals(name) || "go_to_sleep".equals(name));
        }

        @Override public boolean isPhysicalTool(String name) {
            return "start_robot_following".equals(name)
                    || "stop_robot_following".equals(name)
                    || "look_at_user".equals(name);
        }

        @Override public boolean emergencyStop() {
            emergencyStopCount++;
            return false;
        }

        @Override public void execute(
                String callId,
                String name,
                JSONObject arguments,
                GuardedExecution.Guard executionGuard,
                RobotGateway.ResultCallback callback
        ) {
            if (delayExecution) {
                delayedGuard = executionGuard;
                delayedCallback = callback;
                return;
            }
            boolean executed = executionGuard.runIfAllowed(() -> executeCount++);
            assertTrue(executed);
            try {
                callback.onResult(new JSONObject()
                        .put("status", "queued")
                        .put("result", new JSONObject().put("accepted", true)));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        boolean runDelayedAction() {
            GuardedExecution.Guard guard = delayedGuard;
            delayedGuard = null;
            return guard != null && guard.runIfAllowed(() -> executeCount++);
        }

        void completeDelayedSuccess() throws Exception {
            RobotGateway.ResultCallback callback = delayedCallback;
            delayedCallback = null;
            if (callback != null) {
                callback.onResult(new JSONObject()
                        .put("status", "queued")
                        .put("result", new JSONObject().put("accepted", true)));
            }
        }
    }

    private static final class InMemoryToolCallJournal implements ToolCallJournal {
        final Map<String, Entry> entries = new HashMap<>();
        boolean failNextMutation;
        boolean failLoadAll;
        String failLoadSessionId;

        @Override public synchronized boolean recordDispatched(Entry entry) {
            if (consumeFailure()) return false;
            String key = key(entry.sessionId, entry.callId);
            Entry existing = entries.get(key);
            if (existing != null) return sameIdentity(existing, entry);
            entries.put(key, copy(entry));
            return true;
        }

        @Override public synchronized boolean recordTerminal(
                Entry entry,
                JSONObject terminalResult,
                long terminalAt
        ) {
            if (consumeFailure()) return false;
            String key = key(entry.sessionId, entry.callId);
            Entry existing = entries.get(key);
            Entry base = existing != null ? existing : entry;
            if (!sameIdentity(base, entry)) return false;
            if (base.isTerminal()) {
                return base.terminalResult.toString().equals(terminalResult.toString());
            }
            entries.put(key, base.withTerminal(terminalResult, terminalAt));
            return true;
        }

        @Override public synchronized boolean markAcknowledged(String sessionId, String callId) {
            return updateState(sessionId, callId, DeliveryState.ACKNOWLEDGED);
        }

        @Override public synchronized boolean markAbandoned(String sessionId, String callId) {
            Entry entry = entries.get(key(sessionId, callId));
            if (entry == null || !entry.isTerminal()) return false;
            if (entry.deliveryState == DeliveryState.ACKNOWLEDGED
                    || entry.deliveryState == DeliveryState.ABANDONED) {
                return true;
            }
            return updateState(sessionId, callId, DeliveryState.ABANDONED);
        }

        @Override public synchronized boolean markAbandoned(
                String sessionId,
                String callId,
                long retainedAt
        ) {
            if (consumeFailure()) return false;
            String key = key(sessionId, callId);
            Entry entry = entries.get(key);
            if (entry == null || !entry.isTerminal()) return false;
            entries.put(key, entry.withAbandonedAt(retainedAt));
            return true;
        }

        @Override public synchronized Entry get(String sessionId, String callId) {
            Entry entry = entries.get(key(sessionId, callId));
            return entry == null ? null : copy(entry);
        }

        @Override public synchronized List<Entry> loadSession(String sessionId) {
            if (sessionId.equals(failLoadSessionId)) return null;
            List<Entry> result = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (sessionId.equals(entry.sessionId)) result.add(copy(entry));
            }
            result.sort(Comparator
                    .comparingLong((Entry entry) -> entry.dispatchedAt)
                    .thenComparing(entry -> entry.callId));
            return result;
        }

        @Override public synchronized List<Entry> loadAll() {
            if (failLoadAll) return null;
            List<Entry> result = new ArrayList<>();
            for (Entry entry : entries.values()) result.add(copy(entry));
            result.sort(Comparator
                    .comparing((Entry entry) -> entry.sessionId)
                    .thenComparingLong(entry -> entry.dispatchedAt)
                    .thenComparing(entry -> entry.callId));
            return result;
        }

        @Override public synchronized boolean prune(long now, long minimumRetentionMillis) {
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.isTerminal()
                        && entry.terminalAt + minimumRetentionMillis < now
                        && (entry.deliveryState == DeliveryState.ACKNOWLEDGED
                        || entry.deliveryState == DeliveryState.ABANDONED)) {
                    iterator.remove();
                }
            }
            return true;
        }

        private boolean updateState(String sessionId, String callId, DeliveryState state) {
            if (consumeFailure()) return false;
            String key = key(sessionId, callId);
            Entry entry = entries.get(key);
            if (entry == null || !entry.isTerminal()) return false;
            entries.put(key, entry.withDeliveryState(state));
            return true;
        }

        private boolean consumeFailure() {
            if (!failNextMutation) return false;
            failNextMutation = false;
            return true;
        }

        private static String key(String sessionId, String callId) {
            return sessionId + "\n" + callId;
        }

        private static Entry copy(Entry entry) {
            return new Entry(
                    entry.sessionId,
                    entry.callId,
                    entry.turnId,
                    entry.owner,
                    entry.name,
                    entry.dispatchedAt,
                    entry.terminalResult,
                    entry.terminalAt,
                    entry.deliveryState
            );
        }

        private static boolean sameIdentity(Entry left, Entry right) {
            return left.sessionId.equals(right.sessionId)
                    && left.callId.equals(right.callId)
                    && left.turnId.equals(right.turnId)
                    && left.owner.equals(right.owner)
                    && left.name.equals(right.name);
        }
    }

    private static final class RecordingEventCommit
            implements AgentGatewayClient.EventCommitCallback {
        int commits;
        int retries;

        @Override public void commit() {
            commits++;
        }

        @Override public void retry() {
            retries++;
        }
    }

    private static final class RecordingResultCallback
            implements AgentGatewayClient.ResultCallback {
        int successes;
        int errors;
        JSONObject result;
        String errorCode;

        @Override public void onSuccess(JSONObject result) {
            successes++;
            this.result = result;
        }

        @Override public void onError(String code, String message) {
            errors++;
            errorCode = code;
        }
    }

    private static final class RecordingBinaryCallback
            implements AgentGatewayClient.BinaryCallback {
        int successes;
        int errors;
        byte[] bytes;
        String errorCode;

        @Override public void onSuccess(
                byte[] bytes,
                String contentType,
                String digest,
                String expiresAt
        ) {
            successes++;
            this.bytes = bytes;
        }

        @Override public void onError(String code, String message) {
            errors++;
            errorCode = code;
        }
    }
}
