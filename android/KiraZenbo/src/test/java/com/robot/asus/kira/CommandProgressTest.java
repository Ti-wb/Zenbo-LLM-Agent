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
