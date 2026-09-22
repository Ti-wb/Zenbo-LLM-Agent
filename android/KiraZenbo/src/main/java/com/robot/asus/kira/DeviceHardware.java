package com.robot.asus.kira;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Shared hardware facade for local renderer, Hermes tools and authenticated LAN controls. */
public final class DeviceHardware {
    private static final GuardedExecution.Guard ALWAYS = action -> { action.run(); return true; };
    private final Context context;
    private final RobotGateway gateway;
    private final NativeCameraController camera;
    private final SpeakerDirection direction = new SpeakerDirection();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean foreground, motionAllowed, attentionEnabled, cameraRequested, permissionPending;
    private volatile String phase = "idle";
    private volatile long generation, cameraGeneration;
    private long lastAttention;

    public DeviceHardware(Context context, RobotGateway gateway) {
        this.context = context.getApplicationContext(); this.gateway = gateway;
        camera = new NativeCameraController(context);
        gateway.setHardware(this);
    }
    public JSONObject status() {
        JSONObject result = new JSONObject();
        String attentionState = !attentionEnabled ? "disabled" : cameraOwnsResource() ? "paused_for_camera"
                : gateway.isFollowing() ? "paused_for_following" : !foreground || !motionAllowed ? "motion_disabled"
                : "idle".equals(phase) ? "idle" : gateway.isAttentionActive() || "unavailable".equals(gateway.attentionState()) ? gateway.attentionState()
                : Double.isNaN(direction.fresh(SystemClock.elapsedRealtime())) ? "waiting_for_face" : "waiting_for_direction";
        try {
            result.put("robotReady", gateway.isReady()).put("moving", gateway.isMoving()).put("following", gateway.isFollowing())
                    .put("attentionEnabled", attentionEnabled).put("attentionState", attentionState)
                    .put("attentionSource", Double.isNaN(direction.fresh(SystemClock.elapsedRealtime())) ? "face_tracking" : "sdk_doa")
                    .put("cameraEnabled", camera.isEnabled()).put("cameraState", permissionPending ? "permission_pending" : camera.state())
                    .put("cameraPermission", hasCameraPermission() ? "granted" : "required")
                    .put("cameraError", camera.error());
        } catch (JSONException ignored) { }
        return result;
    }
    public void setMotionAllowed(boolean allowed) { motionAllowed = allowed; gateway.setMotionAllowed(allowed); }
    public synchronized void setForeground(boolean visible) {
        foreground = visible; gateway.setForeground(visible);
        if (!visible) {
            ++generation; ++cameraGeneration; phase = "idle"; direction.clear(); cameraRequested = false; permissionPending = false; camera.disable();
        }
    }
    public void setAttentionEnabled(boolean enabled) {
        attentionEnabled = enabled;
        if (!enabled) gateway.stopAttention(ignored -> { }); else updateAttention();
    }
    public void setInteractionPhase(String phase) {
        if (!("idle".equals(phase) || "listening".equals(phase) || "speaking".equals(phase))) throw new IllegalArgumentException("Invalid interaction phase");
        this.phase = phase;
        if ("idle".equals(phase)) gateway.stopAttention(ignored -> { }); else updateAttention();
    }
    void onVoiceEvent(JSONObject event) {
        if (!foreground || !direction.observe(event, SystemClock.elapsedRealtime())) return;
        if (gateway.isAttentionActive() && "tracking_face".equals(gateway.attentionState())) {
            gateway.stopAttention(result -> { if (!"error".equals(result.optString("status"))) updateAttention(); });
        } else updateAttention();
    }
    boolean wantsAttention() { return foreground && motionAllowed && attentionEnabled && !"idle".equals(phase) && !cameraOwnsResource(); }
    private void updateAttention() {
        main.post(() -> {
            if (!wantsAttention() || gateway.isMoving() || SystemClock.elapsedRealtime() - lastAttention < 1_000) return;
            lastAttention = SystemClock.elapsedRealtime();
            double doa = direction.fresh(lastAttention);
            gateway.attend(doa, Double.isNaN(doa));
        });
    }
    boolean cameraOwnsResource() { return cameraRequested || camera.ownsResource(); }
    public void setCameraEnabled(boolean enabled, RobotOperations.ResultCallback callback) {
        main.post(() -> setCameraEnabledOnMain(enabled, callback));
    }
    private synchronized void setCameraEnabledOnMain(boolean enabled, RobotOperations.ResultCallback callback) {
        if (!enabled) {
            ++generation;
            final long token = ++cameraGeneration;
            cameraRequested = false; permissionPending = false;
            camera.disable(code -> main.post(() -> completeCameraChange(token, false, code, callback)));
            return;
        }
        if (!foreground) { fail(callback, "CAMERA_FOREGROUND_REQUIRED", "Open the Zenbo app before enabling its camera"); return; }
        if (gateway.busyExceptAttention()) { fail(callback, "CAMERA_BUSY", "Stop following or movement before opening the camera"); return; }
        final long token = ++cameraGeneration;
        if (!hasCameraPermission()) {
            permissionPending = true;
            if (!MainActivity.requestNativeCameraPermission()) {
                permissionPending = false; fail(callback, "CAMERA_PERMISSION_REQUIRED", "Open the Zenbo app to grant camera permission"); return;
            }
            callback.onResult(RobotGateway.success("local", "camera", status())); return;
        }
        permissionPending = false;
        cameraRequested = true;
        gateway.stopAttention(stopped -> main.post(() -> startCameraAfterAttention(token, stopped, callback)));
    }
    private synchronized void startCameraAfterAttention(long token, JSONObject stopped, RobotOperations.ResultCallback callback) {
        if (token != cameraGeneration || !foreground || !cameraRequested) { fail(callback, "CAMERA_CANCELLED", "Camera request was cancelled"); return; }
        if ("error".equals(stopped.optString("status"))) { cameraRequested = false; callback.onResult(stopped); return; }
        camera.enable(code -> main.post(() -> completeCameraChange(token, true, code, callback)));
    }
    private synchronized void completeCameraChange(long token, boolean opening, String code, RobotOperations.ResultCallback callback) {
        if (token != cameraGeneration || (opening && (!foreground || !cameraRequested))) {
            fail(callback, "CAMERA_CANCELLED", "Camera request was cancelled"); return;
        }
        if (code != null) {
            if (opening) cameraRequested = false;
            fail(callback, code, opening ? "Camera could not be opened" : "Camera could not be released"); return;
        }
        callback.onResult(RobotGateway.success("local", "camera", status()));
        if (!opening) updateAttention();
    }
    synchronized void onCameraPermissionResult(boolean granted) {
        boolean requested = permissionPending; permissionPending = false;
        if (requested && granted && foreground) setCameraEnabled(true, ignored -> { });
    }
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    public void action(String action, RobotOperations.ResultCallback callback) { action(action, ALWAYS, callback); }
    public void action(String action, GuardedExecution.Guard guard, RobotOperations.ResultCallback callback) {
        String name;
        JSONObject args = new JSONObject();
        if ("follow_start".equals(action)) name = "start_robot_following";
        else if ("follow_stop".equals(action) || "stop".equals(action)) name = "stop_robot_following";
        else if ("forward".equals(action) || "backward".equals(action) || "left".equals(action) || "right".equals(action)) {
            name = "move_robot"; try { args.put("direction", action); } catch (JSONException ignored) { }
        } else { fail(callback, "INVALID_ACTION", "Unsupported robot action"); return; }
        gateway.execute(UUID.randomUUID().toString(), name, args, guard, callback);
    }
    public void capture(RobotOperations.ResultCallback callback) { capture(ALWAYS, callback); }
    public void capture(GuardedExecution.Guard guard, RobotOperations.ResultCallback callback) {
        final long token = generation;
        main.post(() -> {
            if (!foreground || !camera.isEnabled() || !cameraRequested) { fail(callback, "CAMERA_DISABLED", "Enable the camera on Zenbo before capturing"); return; }
            boolean allowed = GuardedExecution.runIfAllowed(guard, () -> camera.capture((frame, code) -> {
                if (token != generation || !foreground || !camera.isEnabled()) { fail(callback, "CAMERA_CANCELLED", "Camera request was cancelled"); return; }
                if (code != null) { fail(callback, code, "A current camera image is unavailable"); return; }
                boolean current = GuardedExecution.runIfAllowed(guard, () -> {
                    try {
                        JSONObject output = RobotGateway.accepted().put("artifactId", frame.id).put("mimeType", "image/jpeg")
                                .put("byteLength", frame.jpeg.length).put("sha256", frame.sha256).put("width", frame.width)
                                .put("height", frame.height).put("capturedAt", frame.capturedAt())
                                .put("imageBase64", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP));
                        callback.onResult(RobotGateway.success("local", "capture_camera", output));
                    } catch (JSONException error) { fail(callback, "CAMERA_FRAME_INVALID", "Camera image metadata is invalid"); }
                });
                if (!current) fail(callback, "TURN_CANCELLED", "Capture authority expired");
            }));
            if (!allowed) fail(callback, "TURN_CANCELLED", "Capture authority expired");
        });
    }
    public byte[] cameraJpeg(String artifactIdOrEmpty) { return camera.jpeg(artifactIdOrEmpty); }
    public void stop() { ++generation; phase = "idle"; direction.clear(); gateway.emergencyStop(); }
    /** Sleep/background is stronger than a motion stop and revokes camera access and pending capture. */
    public synchronized void suspend() { stop(); ++cameraGeneration; cameraRequested = false; permissionPending = false; camera.disable(); }
    public synchronized void close() { suspend(); camera.close(); }
    private static void fail(RobotOperations.ResultCallback callback, String code, String message) {
        callback.onResult(RobotGateway.error("local", "hardware", code, message));
    }
}
