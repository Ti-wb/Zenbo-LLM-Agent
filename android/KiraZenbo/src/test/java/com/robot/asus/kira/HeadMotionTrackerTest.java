package com.robot.asus.kira;

import com.asus.robotframework.API.RobotCmdState;
import org.junit.Test;
import static org.junit.Assert.*;

public class HeadMotionTrackerTest {
    @Test public void onlyOwnTerminalSerialsReleaseHeadMotion() {
        RobotGateway.HeadMotionTracker tracker = new RobotGateway.HeadMotionTracker();
        tracker.started(10);
        tracker.started(11);
        tracker.onStateChanged(99, RobotCmdState.SUCCEED);
        tracker.onStateChanged(10, RobotCmdState.SUCCEED);
        tracker.onStateChanged(11, RobotCmdState.ACTIVE);
        assertTrue(tracker.isMoving());
        tracker.onStateChanged(11, RobotCmdState.PREEMPTED);
        assertFalse(tracker.isMoving());
        tracker.started(12);
        tracker.clear();
        assertFalse(tracker.isMoving());
    }
}
