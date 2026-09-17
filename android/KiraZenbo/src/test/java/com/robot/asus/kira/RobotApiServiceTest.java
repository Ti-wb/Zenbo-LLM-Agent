package com.robot.asus.kira;

import com.asus.robotframework.API.Utility;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RobotApiServiceTest {
    @Test public void firmwareQuickTapAndSdkShortAndMediumTouchesEnterInteraction() {
        assertTrue(RobotApiService.isHeadInteraction(1f));
        assertTrue(RobotApiService.isHeadInteraction(Utility.CapEventType.CAP_EVENT_SHORT));
        assertTrue(RobotApiService.isHeadInteraction(Utility.CapEventType.CAP_EVENT_MEDIUM));
    }

    @Test public void longAndUnclassifiedTouchEventsDoNotEnterInteraction() {
        assertFalse(RobotApiService.isHeadInteraction(Utility.CapEventType.CAP_EVENT_LONG));
        assertFalse(RobotApiService.isHeadInteraction(0f));
        assertFalse(RobotApiService.isHeadInteraction(2.4f));
        assertFalse(RobotApiService.isHeadInteraction(Float.NaN));
    }
}
