package com.robot.asus.kira;

/** SDK acknowledgement is not completion; long-running follow has an explicit start milestone. */
final class CommandProgress {
    final int serial;
    final boolean follow;
    private boolean active;
    private boolean found;
    private boolean complete;

    CommandProgress(int serial, boolean follow) { this.serial = serial; this.follow = follow; }

    Boolean state(int receivedSerial, String state) {
        if (complete || serial != receivedSerial) return null;
        if ("REJECTED".equals(state) || "FAILED".equals(state) || "PREEMPTED".equals(state)) {
            complete = true;
            return false;
        }
        if ("ACTIVE".equals(state)) active = true;
        if (!follow && "SUCCEED".equals(state) || follow && active && found) {
            complete = true;
            return true;
        }
        // A follow command that ends before the found+active milestone did not start following.
        if (follow && "SUCCEED".equals(state)) { complete = true; return false; }
        return null;
    }

    Boolean found(int receivedSerial) {
        if (complete || !follow || serial != receivedSerial) return null;
        found = true;
        if (active) { complete = true; return true; }
        return null;
    }
}
