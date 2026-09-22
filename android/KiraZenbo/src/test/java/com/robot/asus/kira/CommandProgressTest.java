package com.robot.asus.kira;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommandProgressTest {
    @Test public void finiteMovementRequiresItsOwnTerminalState() {
        CommandProgress command = new CommandProgress(42, false);
        assertNull(command.state(41, "SUCCEED"));
        assertNull(command.state(42, "PENDING"));
        assertNull(command.state(42, "ACTIVE"));
        assertEquals(Boolean.TRUE, command.state(42, "SUCCEED"));
        assertNull(command.state(42, "FAILED"));
        for (String sdkError : new String[]{"COORDINATOR_APP_CANCELED", "MOTION_FAILED", "UNKNOWN"}) {
            CommandProgress rejected = new CommandProgress(43, false);
            assertEquals(Boolean.FALSE, rejected.state(43, "SUCCEED", sdkError));
            assertNull(rejected.state(43, "SUCCEED", "NO_ERROR"));
        }
    }
    @Test public void coldStartAndFaceSearchHaveSeparateBoundedDeadlines() {
        CommandProgress command = new CommandProgress(37, true, 1_000);
        assertNull(command.state(37, "ACTIVE"));
        assertEquals(1_720, command.followRemainingMillis(4_280)); // Device SDK began search 3.28 s after ACTIVE.
        assertTrue(command.searchStarted(37, 4_280));
        assertEquals("FOLLOW_TARGET_NOT_FOUND", command.followTimeoutCode());
        assertEquals(3_000, command.followRemainingMillis(4_280));
        assertFalse(command.searchStarted(37, 5_000)); // Repeated milestones cannot extend authority.
        assertEquals(0, command.followRemainingMillis(7_280));
        CommandProgress cold = new CommandProgress(38, true, 1_000);
        assertEquals("FOLLOW_START_TIMEOUT", cold.followTimeoutCode());
        assertFalse(cold.searchStarted(38, 6_000));
        assertEquals(0, cold.followRemainingMillis(6_000));
        CommandProgress latest = new CommandProgress(39, true, 1_000);
        assertTrue(latest.searchStarted(39, 5_999));
        assertEquals(0, latest.followRemainingMillis(9_000));
    }
    @Test public void followMustBeActiveAndFindUserInEitherCallbackOrder() {
        CommandProgress first = new CommandProgress(7, true);
        assertNull(first.found(8));
        assertNull(first.found(7));
        assertEquals(Boolean.TRUE, first.state(7, "ACTIVE"));
        CommandProgress second = new CommandProgress(9, true);
        assertNull(second.state(9, "ACTIVE"));
        assertEquals(Boolean.TRUE, second.found(9));
    }
    @Test public void followEndingBeforeFindingUserFailsAndLateFoundCannotResurrectIt() {
        for (String terminal : new String[]{"SUCCEED", "FAILED", "REJECTED", "PREEMPTED"}) {
            CommandProgress command = new CommandProgress(7, true);
            assertNull(command.state(7, "ACTIVE"));
            assertEquals(Boolean.FALSE, command.state(7, terminal));
            assertNull(command.found(7));
        }
    }
}
