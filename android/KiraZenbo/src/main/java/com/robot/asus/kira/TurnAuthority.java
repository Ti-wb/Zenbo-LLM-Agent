package com.robot.asus.kira;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Pure-Java guard that permanently revokes local execution authority for a cancelled turn. */
final class TurnAuthority {
    private final Set<String> revokedTurnLeases = new HashSet<>();

    synchronized void revoke(String sessionId, long epoch, String turnId) {
        if (turnId != null && !turnId.isEmpty()) {
            revokedTurnLeases.add(key(sessionId, epoch, turnId));
        }
    }

    synchronized boolean isRevoked(String sessionId, long epoch, String turnId) {
        return turnId != null
                && revokedTurnLeases.contains(key(sessionId, epoch, turnId));
    }

    synchronized boolean runIfAuthorized(
            String sessionId,
            long epoch,
            String turnId,
            Runnable action
    ) {
        if (isRevoked(sessionId, epoch, turnId)) return false;
        action.run();
        return true;
    }

    synchronized void forget(String sessionId, long epoch, String turnId) {
        revokedTurnLeases.remove(key(sessionId, epoch, turnId));
    }

    synchronized void clearSession(String sessionId, long epoch) {
        String prefix = (sessionId != null ? sessionId : "")
                + "\n" + epoch + "\n";
        Iterator<String> iterator = revokedTurnLeases.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) iterator.remove();
        }
    }

    private static String key(String sessionId, long epoch, String turnId) {
        return (sessionId != null ? sessionId : "")
                + "\n" + epoch + "\n" + (turnId != null ? turnId : "");
    }
}
