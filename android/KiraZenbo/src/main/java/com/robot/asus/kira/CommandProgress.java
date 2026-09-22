package com.robot.asus.kira;

/** SDK acknowledgement is not completion; long-running follow has an explicit start milestone. */
final class CommandProgress {
    final int serial;
    final boolean follow;
    private boolean active;
    private boolean found;
    private boolean complete;
    private final long startedAt;
    private long searchStartedAt = -1;

    CommandProgress(int serial, boolean follow) { this(serial, follow, 0); }
    CommandProgress(int serial, boolean follow, long startedAt) {
        this.serial = serial; this.follow = follow; this.startedAt = startedAt;
    }

    boolean searchStarted(int receivedSerial, long now) {
        if (complete || !follow || serial != receivedSerial || searchStartedAt >= 0 || now >= startedAt + 5_000) return false;
        searchStartedAt = now;
        return true;
    }

    long followRemainingMillis(long now) {
        long deadline = searchStartedAt < 0 ? startedAt + 5_000 : Math.min(startedAt + 8_000, searchStartedAt + 3_000);
        return Math.max(0, deadline - now);
    }

    String followTimeoutCode() { return searchStartedAt < 0 ? "FOLLOW_START_TIMEOUT" : "FOLLOW_TARGET_NOT_FOUND"; }

    Boolean state(int receivedSerial, String state) {
        return state(receivedSerial, state, "NO_ERROR");
    }

    Boolean state(int receivedSerial, String state, String errorCode) {
        if (complete || serial != receivedSerial) return null;
        if (!"NO_ERROR".equals(errorCode) || "REJECTED".equals(state) || "FAILED".equals(state) || "PREEMPTED".equals(state)) {
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
