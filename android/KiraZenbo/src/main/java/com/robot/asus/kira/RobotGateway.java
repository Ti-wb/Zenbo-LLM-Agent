package com.robot.asus.kira;

import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Bundle;
import com.asus.robotframework.API.RobotAPI;
import com.asus.robotframework.API.RobotCmdState;
import com.asus.robotframework.API.MotionControl;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One native owner for SDK commands. Every hardware result is correlated with its own serial. */
public final class RobotGateway implements RobotOperations {
    private static final Set<String> ALLOWED_TOOLS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "get_system_status", "start_robot_following", "stop_robot_following", "look_at_user",
            "show_emotion", "go_to_sleep", "move_robot", "capture_camera")));
    private static final Set<String> NATIVE_TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "get_system_status", "start_robot_following", "stop_robot_following", "look_at_user", "move_robot", "capture_camera")));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile RobotAPI robotAPI;
    private volatile DeviceHardware hardware;
    private volatile boolean moving, following, attention, motionAllowed, foreground;
    private volatile String attentionState = "idle";
    private volatile long epoch;
    private final HeadMotionTracker headMotion = new HeadMotionTracker();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Set<Integer> owned = new HashSet<>();
    private final Map<Integer, ResultCallback> stopWaiters = new HashMap<>();
    private Integer followSerial, attentionSerial;

    static final class HeadMotionTracker {
        private final Set<Integer> activeSerials = new HashSet<>();
        synchronized void started(int serial) { activeSerials.add(serial); }
        synchronized boolean isMoving() { return !activeSerials.isEmpty(); }
        synchronized void clear() { activeSerials.clear(); }
        synchronized void onStateChanged(int serial, RobotCmdState state) { if (terminal(state)) activeSerials.remove(serial); }
    }
    private final class Pending {
        final String callId, name;
        final CommandProgress progress;
        final ResultCallback callback;
        final Runnable timeout;
        Pending(String callId, String name, int serial, boolean follow, long deadline, ResultCallback callback) {
            this.callId = callId; this.name = name; this.callback = callback;
            progress = new CommandProgress(serial, follow);
            timeout = () -> {
                if (pending.remove(serial) != this) return;
                RobotAPI api = robotAPI;
                if (api != null) { cancelSdk(api, serial); stopSdkMotion(api); }
                if (Integer.valueOf(serial).equals(attentionSerial)) attentionState = "stop_unconfirmed";
                callback.onResult(error(callId, name, "TIMEOUT", "SDK did not confirm the command before its deadline"));
                // Keep ownership until the SDK reports terminal: a timed-out physical action must not be replayed.
            };
            mainHandler.postDelayed(timeout, deadline);
        }
    }
    static boolean terminal(RobotCmdState state) {
        return state == RobotCmdState.SUCCEED || state == RobotCmdState.FAILED || state == RobotCmdState.REJECTED || state == RobotCmdState.PREEMPTED;
    }
    void setHardware(DeviceHardware hardware) { this.hardware = hardware; }
    public void attach(RobotAPI api) { robotAPI = api; }
    public void detach() {
        robotAPI = null; ++epoch;
        mainHandler.post(() -> {
            for (Pending request : new java.util.ArrayList<>(pending.values())) finish(request, false, "ROBOT_UNAVAILABLE");
            pending.clear(); owned.clear(); followSerial = null; attentionSerial = null;
            moving = false; following = false; attention = false; headMotion.clear();
            for (ResultCallback callback : stopWaiters.values()) callback.onResult(error("local", "stop", "ROBOT_UNAVAILABLE", "Robot disconnected"));
            stopWaiters.clear();
        });
    }
    public boolean isReady() { return robotAPI != null; }
    public boolean isMoving() { return moving || headMotion.isMoving(); }
    public boolean isFollowing() { return following; }
    boolean isAttentionActive() { return attention; }
    String attentionState() { return attentionState; }
    boolean busyExceptAttention() { return isMoving() && !attention; }
    public Set<String> getAllowedTools() { return ALLOWED_TOOLS; }
    public boolean isNativeTool(String name) { return NATIVE_TOOLS.contains(name); }
    public boolean isPhysicalTool(String name) {
        return "start_robot_following".equals(name) || "stop_robot_following".equals(name)
                || "look_at_user".equals(name) || "move_robot".equals(name);
    }
    void setMotionAllowed(boolean enabled) { motionAllowed = enabled; if (!enabled) emergencyStop(); }
    void setForeground(boolean enabled) { foreground = enabled; if (!enabled) emergencyStop(); }
    public boolean emergencyStop() {
        boolean wasMoving = isMoving();
        ++epoch;
        Runnable stop = () -> stopOwned("local", "stop", ignored -> { }, false);
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run(); else mainHandler.post(stop);
        return wasMoving;
    }
    public void onCommandStateChanged(int serial, RobotCmdState state) {
        headMotion.onStateChanged(serial, state);
        Pending request = pending.get(serial);
        if (request != null) {
            Boolean result = request.progress.state(serial, state.name());
            if (result != null) finish(request, result, "SDK_" + state.name());
        }
        if (terminal(state)) {
            owned.remove(serial);
            if (Integer.valueOf(serial).equals(followSerial)) { followSerial = null; following = false; }
            if (Integer.valueOf(serial).equals(attentionSerial)) { attentionSerial = null; attention = false; attentionState = "idle"; }
            moving = !owned.isEmpty();
            ResultCallback waiter = stopWaiters.remove(serial);
            if (waiter != null) waiter.onResult(success("local", "stop", accepted()));
        }
    }
    void onCommandResult(int serial, Bundle result) {
        if (result == null) return;
        boolean found = false;
        for (String key : result.keySet()) {
            Object value = result.get(key);
            if (value instanceof String && (((String) value).contains("FOLLOW_FACE_FOUND_USER")
                    || ((String) value).contains("TRACK_FACE_FOUND_USER"))) found = true;
        }
        Pending request = pending.get(serial);
        if (request != null && found) {
            Boolean complete = request.progress.found(serial);
            if (complete != null) finish(request, complete, "SDK_FAILED");
        }
    }
    private void finish(Pending request, boolean succeeded, String code) {
        if (pending.remove(request.progress.serial) != request) return;
        mainHandler.removeCallbacks(request.timeout);
        if (succeeded && request.progress.follow) {
            if ("attention".equals(request.name)) attentionState = "tracking_face";
            else following = true;
        }
        request.callback.onResult(succeeded ? success(request.callId, request.name, accepted())
                : error(request.callId, request.name, code, "SDK did not complete the requested action"));
    }
    private void track(String callId, String name, int serial, boolean follow, long deadline, ResultCallback callback) {
        if (serial < 0) { callback.onResult(error(callId, name, "SDK_REJECTED", "SDK rejected the command")); return; }
        owned.add(serial); moving = true;
        pending.put(serial, new Pending(callId, name, serial, follow, deadline, callback));
    }
    public void execute(String callId, String name, JSONObject args, ResultCallback callback) {
        execute(callId, name, args, action -> { action.run(); return true; }, callback);
    }
    @Override public void execute(String callId, String name, JSONObject args, GuardedExecution.Guard guard, ResultCallback callback) {
        if (callId == null || callId.trim().isEmpty() || !ALLOWED_TOOLS.contains(name)) {
            callback.onResult(error(callId, name, "INVALID_TOOL", "Invalid tool name or call identifier")); return;
        }
        if (!isNativeTool(name)) { callback.onResult(error(callId, name, "WEB_OWNED_TOOL", "Tool belongs to renderer")); return; }
        final long requestEpoch = epoch;
        final JSONObject arguments = args == null ? new JSONObject() : args;
        mainHandler.post(() -> {
            if ("get_system_status".equals(name)) {
                JSONObject output = accepted();
                try { output.put("robotReady", isReady()).put("moving", isMoving()).put("androidSdk", Build.VERSION.SDK_INT).put("robotModel", Build.MODEL); }
                catch (JSONException ignored) { }
                callback.onResult(success(callId, name, output)); return;
            }
            if (requestEpoch != epoch && !"stop_robot_following".equals(name)) { callback.onResult(error(callId, name, "TURN_CANCELLED", "Action authority was revoked")); return; }
            try {
                boolean permitted = GuardedExecution.runIfAllowed(guard, () -> dispatch(callId, name, arguments, guard, callback));
                if (!permitted) callback.onResult(error(callId, name, "TURN_CANCELLED", "Action authority was revoked"));
            } catch (RuntimeException | LinkageError failure) { callback.onResult(error(callId, name, "EXECUTION_FAILED", "Robot could not execute the action")); }
        });
    }
    private void dispatch(String callId, String name, JSONObject args, GuardedExecution.Guard guard, ResultCallback callback) {
        if ("capture_camera".equals(name)) {
            if (hardware == null) callback.onResult(error(callId, name, "CAMERA_UNAVAILABLE", "Camera is unavailable"));
            else hardware.capture(guard, callback);
            return;
        }
        RobotAPI api = robotAPI; // Read the live attachment only on the actual dispatch thread.
        if (api == null) { callback.onResult(error(callId, name, "ROBOT_UNAVAILABLE", "RobotAPI has not initialized")); return; }
        if ("stop_robot_following".equals(name)) { ++epoch; stopOwned(callId, name, callback, false); return; }
        if (!motionAllowed || !foreground) { callback.onResult(error(callId, name, "MOTION_DISABLED", "Robot movement is disabled")); return; }
        if (attention) {
            final long token = epoch;
            stopOwned(callId, name, stopped -> {
                if ("error".equals(stopped.optString("status"))) { callback.onResult(stopped); return; }
                if (token != epoch) { callback.onResult(error(callId, name, "TURN_CANCELLED", "Action authority was revoked")); return; }
                boolean current = GuardedExecution.runIfAllowed(guard, () -> dispatch(callId, name, args, guard, callback));
                if (!current) callback.onResult(error(callId, name, "TURN_CANCELLED", "Action authority was revoked"));
            }, true);
            return;
        }
        if (isMoving() || HardwareAccessPolicy.cameraConflicts(name, hardware != null && hardware.cameraOwnsResource())) {
            callback.onResult(error(callId, name, "ROBOT_BUSY", "Camera or another robot action owns the hardware")); return;
        }
        if ("look_at_user".equals(name)) {
            Object value = args.opt("doa");
            double doa = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
            if (Double.isNaN(doa) || Double.isInfinite(doa) || doa < -180 || doa > 180) {
                callback.onResult(error(callId, name, "INVALID_ARGUMENT", "A measured direction is required")); return;
            }
            int serial = api.utility.lookAtUser((float) doa); if (serial >= 0) headMotion.started(serial);
            track(callId, name, serial, false, 2_000, callback); return;
        }
        if ("move_robot".equals(name)) {
            String direction = args.optString("direction");
            if (!Arrays.asList("forward", "backward", "left", "right").contains(direction)) {
                callback.onResult(error(callId, name, "INVALID_ARGUMENT", "Unsupported direction")); return;
            }
        }
        final long actionEpoch = epoch;
        int avoidance = api.motion.setAvoidanceStatus(true);
        track(callId, "avoidance", avoidance, false, 1_500, result -> {
            if ("error".equals(result.optString("status"))) { callback.onResult(result); return; }
            // onStateChange removes the previous serial after invoking this callback.
            owned.remove(avoidance); moving = !owned.isEmpty();
            if (actionEpoch != epoch || !motionAllowed || !foreground || robotAPI != api) {
                callback.onResult(error(callId, name, "TURN_CANCELLED", "Action authority was revoked")); return;
            }
            boolean allowed;
            try { allowed = GuardedExecution.runIfAllowed(guard, () -> {
                if ("start_robot_following".equals(name)) {
                    int serial = api.utility.followFace(false, false); followSerial = serial;
                    track(callId, name, serial, true, 3_000, callback);
                } else {
                    String direction = args.optString("direction");
                    float x = "forward".equals(direction) ? 0.15f : "backward".equals(direction) ? -0.15f : 0f;
                    int theta = "left".equals(direction) ? -15 : "right".equals(direction) ? 15 : 0;
                    track(callId, name, api.motion.moveBody(x, 0f, theta, MotionControl.SpeedLevel.Body.L1), false, 2_000, callback);
                }
            }); } catch (RuntimeException | LinkageError unsupported) {
                callback.onResult(error(callId, name, "EXECUTION_FAILED", "SDK could not start the action")); return;
            }
            if (!allowed) callback.onResult(error(callId, name, "TURN_CANCELLED", "Action authority was revoked"));
        });
    }
    void attend(double doa, boolean faceFallback) {
        final long token = epoch;
        mainHandler.post(() -> {
            if (token != epoch || !motionAllowed || !foreground || robotAPI == null || isMoving()
                    || hardware != null && !hardware.wantsAttention()) return;
            int serial;
            try { serial = faceFallback ? robotAPI.utility.trackFace(false, false) : robotAPI.utility.lookAtUser((float) doa); }
            catch (RuntimeException | LinkageError unsupported) { attentionState = "unavailable"; return; }
            if (serial < 0) { attentionState = "unavailable"; return; }
            attention = true;
            attentionState = faceFallback ? "finding_face" : "looking_at_speaker";
            attentionSerial = serial;
            track("attention", "attention", serial, faceFallback, faceFallback ? 5_000 : 2_000, ignored -> { });
        });
    }
    void stopAttention(ResultCallback callback) {
        mainHandler.post(() -> stopOwned("attention", "stop", callback, true));
    }
    private void stopOwned(String callId, String name, ResultCallback callback, boolean attentionOnly) {
        RobotAPI api = robotAPI;
        Set<Integer> targets = new HashSet<>();
        if (attentionOnly) { if (attentionSerial != null) targets.add(attentionSerial); }
        else targets.addAll(owned);
        if (targets.isEmpty()) { callback.onResult(success(callId, name, accepted())); return; }
        if (api == null) { callback.onResult(error(callId, name, "ROBOT_UNAVAILABLE", "Robot disconnected")); return; }
        final StopConfirmation confirmation = new StopConfirmation(targets);
        Runnable timeout = () -> {
            if (!confirmation.failed()) return;
            for (Integer serial : targets) stopWaiters.remove(serial);
            callback.onResult(error(callId, name, "STOP_UNCONFIRMED", "SDK has not confirmed stopping"));
        };
        mainHandler.postDelayed(timeout, 2_000);
        for (Integer serial : targets) {
            Pending request = pending.get(serial);
            if (request != null) finish(request, false, "TURN_CANCELLED");
            ResultCallback prior = stopWaiters.put(serial, result -> {
                if ("error".equals(result.optString("status"))) {
                    if (confirmation.failed()) { mainHandler.removeCallbacks(timeout); callback.onResult(result); }
                    return;
                }
                if (confirmation.confirmed(serial)) { mainHandler.removeCallbacks(timeout); callback.onResult(success(callId, name, accepted())); }
            });
            if (prior != null) prior.onResult(error(callId, name, "TURN_CANCELLED", "Stop request superseded"));
            cancelSdk(api, serial);
        }
        if (!attentionOnly) stopSdkMotion(api);
    }
    private static void cancelSdk(RobotAPI api, int serial) {
        try { api.cancelCommandBySerial(serial); } catch (RuntimeException | LinkageError ignored) { }
        // Lack of a terminal callback remains STOP_UNCONFIRMED; exceptions never imply success.
    }
    private static void stopSdkMotion(RobotAPI api) {
        try { api.motion.stopMoving(); } catch (RuntimeException | LinkageError ignored) { }
    }
    static JSONObject accepted() { JSONObject result = new JSONObject(); try { result.put("accepted", true); } catch (JSONException ignored) { } return result; }
    static JSONObject success(String callId, String name, JSONObject output) {
        JSONObject result = new JSONObject();
        try { result.put("type", "agent.tool_result").put("callId", callId).put("name", name).put("status", "succeeded").put("result", output); }
        catch (JSONException ignored) { }
        return result;
    }
    static JSONObject error(String callId, String name, String code, String message) {
        JSONObject result = new JSONObject();
        try { result.put("type", "agent.tool_result").put("callId", callId == null ? JSONObject.NULL : callId)
                .put("name", name == null ? JSONObject.NULL : name).put("status", "error")
                .put("error", new JSONObject().put("code", code).put("message", message)); }
        catch (JSONException ignored) { }
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
            manifest.put("protocolVersion", "2.0");
            manifest.put("manifestVersion", "hermes-zenbo-2");
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
            if (propertySpec.minLength != null) property.put("minLength", propertySpec.minLength);
            if (propertySpec.maxLength != null) property.put("maxLength", propertySpec.maxLength);
            if (propertySpec.pattern != null) property.put("pattern", propertySpec.pattern);
            if (propertySpec.format != null) property.put("format", propertySpec.format);
            if (propertySpec.constant != null) property.put("const", propertySpec.constant);
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
