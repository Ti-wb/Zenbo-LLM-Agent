package com.robot.asus.kira;

/** Executes a queued side effect only if authority is still valid at run time. */
final class GuardedExecution {
    interface Guard {
        boolean runIfAllowed(Runnable action);
    }

    private GuardedExecution() { }

    static boolean runIfAllowed(Guard guard, Runnable action) {
        return guard != null && guard.runIfAllowed(action);
    }
}
