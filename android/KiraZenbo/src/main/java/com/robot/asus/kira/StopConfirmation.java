package com.robot.asus.kira;

import java.util.HashSet;
import java.util.Set;

/** Exactly one result for a stop request; supersession and timeouts are never physical confirmations. */
final class StopConfirmation {
    private final Set<Integer> remaining;
    private boolean finished;
    StopConfirmation(Set<Integer> targets) { remaining = new HashSet<>(targets); }
    boolean confirmed(int serial) {
        if (finished || !remaining.remove(serial) || !remaining.isEmpty()) return false;
        finished = true; return true;
    }
    boolean failed() { if (finished) return false; finished = true; return true; }
}
