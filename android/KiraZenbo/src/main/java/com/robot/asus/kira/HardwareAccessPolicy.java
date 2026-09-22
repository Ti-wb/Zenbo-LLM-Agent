package com.robot.asus.kira;

/** RGB preview may coexist with chassis steps; SDK visual behaviors need exclusive camera access. */
final class HardwareAccessPolicy {
    static boolean cameraConflicts(String tool, boolean cameraOwned) {
        return cameraOwned && ("start_robot_following".equals(tool) || "look_at_user".equals(tool) || "attention".equals(tool));
    }
}
