package com.robot.asus.kira;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Keeps native connection state inside the Local Runtime protocol vocabulary. */
final class GatewayStateMapper {
    private static final Set<String> STATES = new HashSet<>(Arrays.asList(
            "UNCONFIGURED", "CONNECTING", "READY", "DEGRADED",
            "AUTH_ERROR", "TLS_ERROR", "INCOMPATIBLE", "OFFLINE"
    ));

    private GatewayStateMapper() {
    }

    static String normalize(String state) {
        return STATES.contains(state) ? state : "OFFLINE";
    }
}
