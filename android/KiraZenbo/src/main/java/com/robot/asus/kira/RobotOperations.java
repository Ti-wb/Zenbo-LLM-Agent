package com.robot.asus.kira;

import org.json.JSONObject;
import java.util.Set;

/** Injectable hardware boundary. Tests must never invoke the physical RobotAPI. */
interface RobotOperations {
    interface ResultCallback { void onResult(JSONObject result); }
    boolean isReady();
    boolean isMoving();
    boolean emergencyStop();
    Set<String> getAllowedTools();
    boolean isNativeTool(String name);
    boolean isPhysicalTool(String name);
    void execute(String callId, String name, JSONObject arguments,
                 GuardedExecution.Guard guard, ResultCallback callback);
}
