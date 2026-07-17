package com.robot.asus.kira;

import java.util.HashMap;
import java.util.Map;

/** Pure ordering guard for Gateway acceptance, renderer dispatch, and terminal tool results. */
final class ToolCallLifecycle {
    private enum State { ACCEPTING, DISPATCHED, TERMINAL }

    private final Map<String, State> states = new HashMap<>();

    synchronized boolean beginAcceptance(String callId) {
        if (states.containsKey(callId)) return false;
        states.put(callId, State.ACCEPTING);
        return true;
    }

    synchronized boolean markAccepted(String callId) {
        if (states.get(callId) != State.ACCEPTING) return false;
        states.put(callId, State.DISPATCHED);
        return true;
    }

    synchronized boolean isDispatched(String callId) {
        return states.get(callId) == State.DISPATCHED;
    }

    synchronized void failAcceptance(String callId) {
        if (states.get(callId) == State.ACCEPTING) states.remove(callId);
    }

    synchronized void markTerminal(String callId) {
        states.put(callId, State.TERMINAL);
    }

    synchronized void forget(String callId) {
        states.remove(callId);
    }
}
