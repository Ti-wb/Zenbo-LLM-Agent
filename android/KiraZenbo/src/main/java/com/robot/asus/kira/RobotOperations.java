package com.robot.asus.kira;

import org.json.JSONObject;

import java.util.Set;

/** Robot capabilities consumed by the remote-session state machine. */
interface RobotOperations {
    boolean isReady();

    boolean isMoving();

    Set<String> getAllowedTools();

    boolean isNativeTool(String name);

    boolean isPhysicalTool(String name);

    boolean emergencyStop();

    void execute(
            String callId,
            String name,
            JSONObject arguments,
            GuardedExecution.Guard executionGuard,
            RobotGateway.ResultCallback callback
    );
}
