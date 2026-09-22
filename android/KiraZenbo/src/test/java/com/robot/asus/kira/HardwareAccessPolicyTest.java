package com.robot.asus.kira;

import org.junit.Test;
import static org.junit.Assert.*;

public class HardwareAccessPolicyTest {
    @Test public void lanCameraPreviewAndBoundedChassisControlCanRunTogether() {
        assertFalse(HardwareAccessPolicy.cameraConflicts("move_robot", true));
        assertFalse(HardwareAccessPolicy.cameraConflicts("capture_camera", true));
        assertFalse(HardwareAccessPolicy.cameraConflicts("stop_robot_following", true));
    }
    @Test public void sdkVisualBehaviorsRemainExclusiveWithCameraPreview() {
        for (String tool : new String[]{"start_robot_following", "look_at_user", "attention"}) {
            assertTrue(HardwareAccessPolicy.cameraConflicts(tool, true));
            assertFalse(HardwareAccessPolicy.cameraConflicts(tool, false));
        }
    }
}
