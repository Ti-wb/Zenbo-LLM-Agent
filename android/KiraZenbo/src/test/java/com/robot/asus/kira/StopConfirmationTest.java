package com.robot.asus.kira;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class StopConfirmationTest {
    @Test public void everyOwnedSerialMustConfirmBeforeCameraCanAcquireResource() {
        StopConfirmation stop = new StopConfirmation(new HashSet<>(Arrays.asList(7, 8)));
        assertFalse(stop.confirmed(99));
        assertFalse(stop.confirmed(7));
        assertFalse(stop.confirmed(7));
        assertTrue(stop.confirmed(8));
        assertFalse(stop.failed());
    }
    @Test public void supersededDisconnectedOrTimedOutStopCannotOpenCameraFromLateCallback() {
        for (String reason : Arrays.asList("superseded", "disconnected", "timeout")) {
            StopConfirmation stop = new StopConfirmation(new HashSet<>(Arrays.asList(7)));
            assertTrue(reason, stop.failed());
            assertFalse(reason, stop.confirmed(7));
            assertFalse(reason, stop.failed());
        }
    }
}
