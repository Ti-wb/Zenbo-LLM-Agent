package com.robot.asus.kira;

import java.util.HashSet;
import java.util.Set;

/** Pure-Java guard that permanently revokes local execution authority for a cancelled turn. */
final class TurnAuthority {
    private final Set<String> revokedTurnIds = new HashSet<>();

    synchronized void revoke(String turnId) {
        if (turnId != null && !turnId.isEmpty()) revokedTurnIds.add(turnId);
    }

    synchronized boolean isRevoked(String turnId) {
        return turnId != null && revokedTurnIds.contains(turnId);
    }

    synchronized boolean runIfAuthorized(String turnId, Runnable action) {
        if (isRevoked(turnId)) return false;
        action.run();
        return true;
    }

}
