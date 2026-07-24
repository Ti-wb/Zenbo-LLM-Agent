package com.robot.asus.kira;

import org.json.JSONObject;

/** Narrow transport boundary used by the remote-session state machine. */
interface GatewayTransport {
    void start();

    void prepareReload();

    void reload();

    void shutdown();

    JSONObject getStatus();

    String getRemoteSessionId();

    void uploadTurn(
            String expectedSessionId,
            JSONObject input,
            AgentGatewayClient.ResultCallback callback
    );

    void cancelTurn(
            String expectedSessionId,
            String turnId,
            String reason,
            AgentGatewayClient.ResultCallback callback
    );

    void reportToolResult(
            String expectedSessionId,
            String callId,
            JSONObject update,
            AgentGatewayClient.ResultCallback callback
    );

    void reportPlayback(
            String expectedSessionId,
            JSONObject update,
            AgentGatewayClient.ResultCallback callback
    );

    void downloadAudio(
            String expectedSessionId,
            String artifactId,
            JSONObject expected,
            AgentGatewayClient.BinaryCallback callback
    );
}
