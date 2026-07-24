package com.robot.asus.kira;

import org.json.JSONObject;

import java.util.List;

/** Durable, non-secret execution journal for at-most-once device tool handling. */
interface ToolCallJournal {
    enum DeliveryState {
        NONE,
        PENDING,
        ACKNOWLEDGED,
        ABANDONED
    }

    final class Entry {
        final String sessionId;
        final String callId;
        final String turnId;
        final String owner;
        final String name;
        final long dispatchedAt;
        final JSONObject terminalResult;
        final long terminalAt;
        final DeliveryState deliveryState;

        Entry(
                String sessionId,
                String callId,
                String turnId,
                String owner,
                String name,
                long dispatchedAt,
                JSONObject terminalResult,
                long terminalAt,
                DeliveryState deliveryState
        ) {
            this.sessionId = sessionId;
            this.callId = callId;
            this.turnId = turnId;
            this.owner = owner;
            this.name = name;
            this.dispatchedAt = dispatchedAt;
            this.terminalResult = copy(terminalResult);
            this.terminalAt = terminalAt;
            this.deliveryState = deliveryState;
        }

        boolean isTerminal() {
            return terminalResult != null;
        }

        Entry withTerminal(JSONObject result, long timestamp) {
            return new Entry(
                    sessionId,
                    callId,
                    turnId,
                    owner,
                    name,
                    dispatchedAt,
                    result,
                    timestamp,
                    DeliveryState.PENDING
            );
        }

        Entry withDeliveryState(DeliveryState state) {
            return new Entry(
                    sessionId,
                    callId,
                    turnId,
                    owner,
                    name,
                    dispatchedAt,
                    terminalResult,
                    terminalAt,
                    state
            );
        }

        Entry withAbandonedAt(long retainedAt) {
            return new Entry(
                    sessionId,
                    callId,
                    turnId,
                    owner,
                    name,
                    dispatchedAt,
                    terminalResult,
                    Math.max(terminalAt, retainedAt),
                    DeliveryState.ABANDONED
            );
        }

        private static JSONObject copy(JSONObject value) {
            if (value == null) return null;
            try {
                return new JSONObject(value.toString());
            } catch (Exception error) {
                throw new IllegalArgumentException("Tool journal JSON is invalid", error);
            }
        }
    }

    boolean recordDispatched(Entry entry);

    boolean recordTerminal(Entry entry, JSONObject terminalResult, long terminalAt);

    boolean markAcknowledged(String sessionId, String callId);

    boolean markAbandoned(String sessionId, String callId);

    boolean markAbandoned(String sessionId, String callId, long retainedAt);

    Entry get(String sessionId, String callId);

    /** Returns null when persisted journal data cannot be decoded safely. */
    List<Entry> loadSession(String sessionId);

    /** Returns null if any persisted journal record is corrupt or internally inconsistent. */
    List<Entry> loadAll();

    boolean prune(long now, long minimumRetentionMillis);
}
