package com.robot.asus.kira;

import android.os.Handler;
import android.os.Looper;
import android.os.Build;

import com.asus.robotframework.API.RobotAPI;
import com.asus.robotframework.API.RobotFace;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Narrow native boundary between untrusted agent messages and the ASUS RobotAPI. */
public final class RobotGateway {
    public interface ResultCallback {
        void onResult(JSONObject result);
    }

    private static final Set<String> ALLOWED_TOOLS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "get_system_status",
            "start_robot_following",
            "stop_robot_following",
            "look_at_user",
            "show_emotion",
            "go_to_sleep"
    )));
    private static final Set<String> NATIVE_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "get_system_status", "start_robot_following", "stop_robot_following", "look_at_user"
    )));

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile RobotAPI robotAPI;
    private volatile boolean moving;

    public void attach(RobotAPI robotAPI) {
        this.robotAPI = robotAPI;
    }

    public void detach() {
        this.robotAPI = null;
        moving = false;
    }

    public boolean isReady() {
        return robotAPI != null;
    }

    public boolean isMoving() {
        return moving;
    }

    public Set<String> getAllowedTools() {
        return ALLOWED_TOOLS;
    }

    public boolean isNativeTool(String name) {
        return NATIVE_TOOLS.contains(name);
    }

    public boolean isPhysicalTool(String name) {
        return "start_robot_following".equals(name)
                || "stop_robot_following".equals(name)
                || "look_at_user".equals(name);
    }

    public boolean emergencyStop() {
        RobotAPI api = robotAPI;
        boolean stopped = api != null && moving;
        moving = false;
        if (api != null) mainHandler.post(api::cancelCommandAll);
        return stopped;
    }

    public void execute(String callId, String name, JSONObject arguments, ResultCallback callback) {
        execute(callId, name, arguments, action -> {
            action.run();
            return true;
        }, callback);
    }

    public void execute(
            String callId,
            String name,
            JSONObject arguments,
            GuardedExecution.Guard executionGuard,
            ResultCallback callback
    ) {
        if (callId == null || callId.trim().isEmpty()) {
            callback.onResult(error(callId, name, "invalid_call", "callId is required"));
            return;
        }
        if (!ALLOWED_TOOLS.contains(name)) {
            callback.onResult(error(callId, name, "tool_not_allowed", "Tool is not in the native allowlist"));
            return;
        }
        if (!isNativeTool(name)) {
            callback.onResult(error(callId, name, "web_owned_tool", "Tool must be executed by the renderer"));
            return;
        }
        RobotAPI api = robotAPI;
        if (api == null && !"get_system_status".equals(name)) {
            callback.onResult(error(callId, name, "robot_unavailable", "RobotAPI has not initialized"));
            return;
        }
        JSONObject safeArguments = arguments != null ? arguments : new JSONObject();
        mainHandler.post(() -> {
            try {
                boolean executed = GuardedExecution.runIfAllowed(executionGuard, () -> {
                    switch (name) {
                        case "get_system_status":
                            break;
                        case "start_robot_following":
                            api.utility.followFace(
                                    safeArguments.optBoolean("enablePreview", false),
                                    safeArguments.optBoolean("largePreview", false)
                            );
                            moving = true;
                            break;
                        case "stop_robot_following":
                            api.cancelCommandAll();
                            moving = false;
                            break;
                        case "look_at_user":
                            double doa = safeArguments.optDouble("doa", Double.NaN);
                            if (Double.isNaN(doa) || doa < -180.0 || doa > 180.0) {
                                throw new IllegalArgumentException("doa must be between -180 and 180");
                            }
                            api.utility.lookAtUser((float) doa);
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported tool");
                    }
                });
                if (!executed) {
                    callback.onResult(error(callId, name, "turn_cancelled", "Turn authority was cancelled"));
                    return;
                }
                JSONObject output = new JSONObject();
                output.put("accepted", true);
                if ("get_system_status".equals(name)) {
                    output.put("robotReady", robotAPI != null);
                    output.put("moving", moving);
                    output.put("androidSdk", Build.VERSION.SDK_INT);
                    output.put("robotModel", "zenbo-k");
                }
                callback.onResult(success(callId, name, output));
            } catch (Exception error) {
                callback.onResult(error(callId, name, "execution_failed", error.getMessage()));
            }
        });
    }

    private static String requireString(JSONObject input, String name) {
        String value = input.optString(name, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static JSONObject success(String callId, String name, JSONObject output) {
        JSONObject result = new JSONObject();
        try {
            result.put("type", "agent.tool_result");
            result.put("callId", callId);
            result.put("name", name);
            result.put("status", "queued");
            result.put("result", output);
        } catch (JSONException ignored) {
        }
        return result;
    }

    private static JSONObject error(String callId, String name, String code, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("type", "agent.tool_result");
            result.put("callId", callId != null ? callId : JSONObject.NULL);
            result.put("name", name != null ? name : JSONObject.NULL);
            result.put("status", "error");
            result.put("error", new JSONObject().put("code", code).put("message", message != null ? message : code));
        } catch (JSONException ignored) {
        }
        return result;
    }

    public JSONObject getToolManifest() {
        return buildToolManifest();
    }

    static JSONObject buildToolManifest() {
        org.json.JSONArray tools = new org.json.JSONArray();
        for (ToolManifestSpec.Definition definition : ToolManifestSpec.definitions()) {
            JSONObject tool = new JSONObject();
            try {
                tool.put("name", definition.name);
                tool.put("version", "1.0.0");
                tool.put("owner", definition.owner);
                tool.put("description", "Zenbo device capability: " + definition.name);
                tool.put("inputSchema", objectSchema(definition.inputProperties, definition.requiredInputs));
                tool.put("resultSchema", objectSchema(definition.resultProperties, definition.requiredResults));
                tool.put("sideEffect", definition.sideEffect);
                tool.put("idempotent", definition.idempotent);
                tool.put("requiresConfirmation", false);
                tool.put("timeoutMs", definition.timeoutMs);
                tools.put(tool);
            } catch (JSONException ignored) {
            }
        }
        JSONObject manifest = new JSONObject();
        try {
            manifest.put("protocolVersion", "1.0");
            manifest.put("manifestVersion", "native-1");
            manifest.put("tools", tools);
        } catch (JSONException ignored) {
        }
        return manifest;
    }

    private static JSONObject objectSchema(
            java.util.List<ToolManifestSpec.Property> propertySpecs,
            java.util.List<String> requiredNames
    ) throws JSONException {
        JSONObject properties = new JSONObject();
        org.json.JSONArray required = new org.json.JSONArray();
        for (ToolManifestSpec.Property propertySpec : propertySpecs) {
            JSONObject property = new JSONObject().put("type", propertySpec.type);
            if (propertySpec.minimum != null) property.put("minimum", propertySpec.minimum);
            if (propertySpec.maximum != null) property.put("maximum", propertySpec.maximum);
            if (!propertySpec.allowedValues.isEmpty()) {
                org.json.JSONArray values = new org.json.JSONArray();
                for (String value : propertySpec.allowedValues) values.put(value);
                property.put("enum", values);
            }
            properties.put(propertySpec.name, property);
        }
        for (String requiredName : requiredNames) required.put(requiredName);
        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false);
        if (required.length() > 0) schema.put("required", required);
        return schema;
    }
}
