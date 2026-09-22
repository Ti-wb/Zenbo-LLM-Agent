package com.robot.asus.kira;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Synthetic scheduling/HAL probes; production classes are compiled by run.py. */
public final class NativeCameraRegression {
    private static int passed;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    private static Looper worker() { return HandlerThread.latest; }
    private static Looper main() { return Looper.getMainLooper(); }
    private static void reset() {
        main().queue.clear(); Camera.latest = null; Camera.opens = 0; Camera.orientation = 0; Camera.duringOpen = null;
        YuvImage.encoded = 0; YuvImage.fail = false; YuvImage.throwError = false; YuvImage.duringEncode = null;
        BitmapFactory.fail = false; Bitmap.failCompress = false; Bitmap.recycled = 0;
        SystemClock.now = 10_000; androidx.core.content.ContextCompat.permission = 0;
    }
    private static NativeCameraController opened() {
        NativeCameraController controller = new NativeCameraController(new Context());
        List<String> result = new ArrayList<>(); controller.enable(result::add); worker().drain();
        equal(1, result.size(), "Enable acknowledged once"); equal(null, result.get(0), "Enable succeeded");
        return controller;
    }
    private static void expectVisualBlocked(boolean owned) {
        check(owned, "Camera still owns its resource");
        for (String name : new String[]{"start_robot_following", "look_at_user", "attention"})
            check(HardwareAccessPolicy.cameraConflicts(name, owned), name + " must remain blocked");
        check(!HardwareAccessPolicy.cameraConflicts("move_robot", owned), "Bounded movement remains allowed");
    }
    private static void deferredRelease() {
        NativeCameraController controller = opened(); Camera source = Camera.latest;
        source.frame(); check(controller.jpeg("") != null, "Frame exists before off");
        List<String> result = new ArrayList<>(); controller.disable(result::add);
        check(!controller.isEnabled(), "Desired enabled revoked immediately");
        equal("releasing", controller.state(), "Off exposes pending release");
        expectVisualBlocked(controller.ownsResource()); check(!source.released, "Release has not run");
        equal(0, result.size(), "Off cannot acknowledge ahead of release");
        equal(null, controller.jpeg(""), "Off immediately revokes images");
        worker().drain(); check(source.released, "Actual HAL release ran");
        equal(null, source.preview, "Buffered callback cleared"); equal(null, source.error, "Error callback cleared");
        check(!controller.ownsResource(), "Ownership ends after release"); equal("disabled", controller.state(), "Disabled confirmed");
        equal(1, result.size(), "Off acknowledged once"); equal(null, result.get(0), "Off succeeded");
    }
    private static void rapidReopenAndStaleFrames() {
        NativeCameraController controller = opened(); Camera old = Camera.latest;
        byte[] data = old.buffers.peek(); Camera.PreviewCallback oldPreview = old.savedPreview; Camera.ErrorCallback oldError = old.savedError;
        List<String> off = new ArrayList<>(), on = new ArrayList<>(); controller.disable(off::add); controller.enable(on::add);
        expectVisualBlocked(controller.ownsResource()); worker().drainOne();
        check(old.released, "Old HAL released before re-open"); equal("starting", controller.state(), "Old off cannot overwrite new starting state");
        equal("CAMERA_CANCELLED", off.get(0), "Superseded off is cancelled"); expectVisualBlocked(controller.ownsResource());
        worker().drain(); Camera current = Camera.latest; check(current != old, "A new HAL was opened");
        current.frame(); equal("ready", controller.state(), "Current frame ready");
        int returned = old.returned; oldPreview.onPreviewFrame(data, old); oldError.onError(1, old);
        equal(returned, old.returned, "Stale buffer never sent to released camera");
        equal("ready", controller.state(), "Stale callbacks cannot change new state");
        check(!current.released, "Stale callbacks cannot release current camera"); equal(null, on.get(0), "New on succeeds");
    }
    private static void disableDuringOpen() {
        NativeCameraController controller = new NativeCameraController(new Context());
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        Camera.duringOpen = () -> {
            controller.disable(off::add); expectVisualBlocked(controller.ownsResource());
            check(!Camera.latest.released, "Open is still in progress");
        };
        controller.enable(on::add); worker().drain();
        equal("CAMERA_CANCELLED", on.get(0), "Opening operation was cancelled"); equal(null, off.get(0), "Off succeeded after cleanup");
        check(Camera.latest.released && !controller.ownsResource(), "Cancelled opening released actual HAL");
        equal("disabled", controller.state(), "Cancelled opening cannot restore ready");
    }
    private static void failedReleaseQuarantine() {
        NativeCameraController controller = opened(); Camera source = Camera.latest; source.failRelease = true;
        List<String> off = new ArrayList<>(); controller.disable(off::add); worker().drain();
        equal("CAMERA_RELEASE_FAILED", off.get(0), "Failed release is not successful off");
        equal("error", controller.state(), "Release failure visible"); expectVisualBlocked(controller.ownsResource());
        List<String> on = new ArrayList<>(); controller.enable(on::add); worker().drain();
        equal(1, Camera.opens, "Never open a second HAL over failed ownership");
        equal("CAMERA_RELEASE_FAILED", on.get(0), "Retry-on respects quarantine");
        source.failRelease = false; controller.disable(off::add); worker().drain();
        check(source.released && !controller.ownsResource(), "Successful release retry clears quarantine");
        equal(null, off.get(1), "Retry-off succeeds");
    }
    private static void fixedBuffersAndDroppedFrames() {
        NativeCameraController controller = opened(); Camera source = Camera.latest;
        equal(3, source.buffers.size(), "Small fixed pool");
        for (byte[] buffer : source.buffers) equal(640 * 480 * 3 / 2, buffer.length, "NV21 buffer size");
        for (int index = 0; index < 20; index++) { source.frame(); SystemClock.now += 20; }
        equal(1, YuvImage.encoded, "HAL frames are throttled before JPEG encoding");
        equal(3, source.buffers.size(), "All skipped frames returned"); equal(3, source.identities.size(), "No per-frame raw buffer allocation");
        SystemClock.now += 500; YuvImage.fail = true; source.frame();
        equal(3, source.buffers.size(), "Failed compression returns raw buffer");
        SystemClock.now += 500; YuvImage.fail = false; YuvImage.throwError = true; source.frame();
        equal(3, source.buffers.size(), "Exception returns raw buffer"); equal("CAMERA_FRAME_INVALID", controller.error(), "Frame error is bounded");
        SystemClock.now += 500; YuvImage.throwError = false; source.frame(); equal("", controller.error(), "Next valid frame recovers");
        equal(3, source.identities.size(), "Only original three arrays have been handed to HAL");
    }
    private static void rotatedFrameErrors() {
        Camera.orientation = 90; NativeCameraController controller = opened(); Camera source = Camera.latest;
        BitmapFactory.fail = true; source.frame(); equal(3, source.buffers.size(), "Decode failure returns buffer");
        SystemClock.now += 500; BitmapFactory.fail = false; Bitmap.failCompress = true; source.frame();
        equal(2, Bitmap.recycled, "Both bitmaps recycled after compression failure");
        equal(3, source.buffers.size(), "Rotated encode failure returns buffer");
        SystemClock.now += 500; Bitmap.failCompress = false; source.frame(); equal("ready", controller.state(), "Rotation error recovers");
    }
    private static void disableDuringFrameAndHalError() {
        NativeCameraController controller = opened(); Camera source = Camera.latest;
        int returned = source.returned; YuvImage.duringEncode = controller::disable; source.frame();
        equal(returned, source.returned, "Cancelled frame is not returned to obsolete generation");
        equal(null, controller.jpeg(""), "Cancelled encode never publishes image");
        expectVisualBlocked(controller.ownsResource()); worker().drain(); check(source.released, "Queued release runs");
        controller.enable(ignored -> {}); worker().drain(); Camera current = Camera.latest;
        current.error.onError(1, current); check(current.released, "HAL failure releases resources"); equal("error", controller.state(), "HAL failure state");
    }
    private static void bufferReturnFailure() {
        NativeCameraController controller = opened(); Camera source = Camera.latest; source.failReturn = true;
        source.frame(); check(source.released, "Buffer return failure releases camera");
        equal("error", controller.state(), "Buffer return failure exposed"); equal("CAMERA_UNAVAILABLE", controller.error(), "Buffer return error code");
    }
    private static DeviceHardware hardware(RobotGateway gateway) {
        DeviceHardware hardware = new DeviceHardware(new Context(), gateway); hardware.setForeground(true); return hardware;
    }
    private static void facadeOffAcknowledgement() {
        DeviceHardware hardware = hardware(new RobotGateway()); List<JSONObject> results = new ArrayList<>();
        hardware.setCameraEnabled(true, results::add); main().drain(); worker().drain(); main().drain();
        results.clear(); Camera source = Camera.latest;
        hardware.setCameraEnabled(false, results::add); main().drain();
        expectVisualBlocked(hardware.cameraOwnsResource()); equal(0, results.size(), "Facade off waits for HAL");
        equal("releasing", hardware.status().optString("cameraState"), "Facade publishes releasing");
        check(!hardware.status().optBoolean("cameraEnabled"), "Facade separates desired state");
        worker().drain(); check(source.released, "HAL released before callback dispatch"); main().drain();
        equal("success", results.get(0).optString("status"), "Facade off succeeds after release");
        check(!hardware.cameraOwnsResource(), "Facade no longer owns after confirmed release");
    }
    private static void facadeLateEnableCannotClearNewRequest() {
        DeviceHardware hardware = hardware(new RobotGateway()); List<JSONObject> old = new ArrayList<>(), off = new ArrayList<>(), current = new ArrayList<>();
        hardware.setCameraEnabled(true, old::add); main().drain(); // First native enable is queued.
        hardware.setCameraEnabled(false, off::add); main().drain();
        hardware.setCameraEnabled(true, current::add); main().drain();
        worker().drain(); main().drain();
        equal("CAMERA_CANCELLED", old.get(0).optString("code"), "Old enable is cancelled");
        equal("CAMERA_CANCELLED", off.get(0).optString("code"), "Superseded off is cancelled");
        equal("success", current.get(0).optString("status"), "New enable survives old completion");
        check(hardware.status().optBoolean("cameraEnabled"), "New desired state preserved");
        Camera.latest.frame(); List<JSONObject> captured = new ArrayList<>();
        hardware.capture(captured::add); main().drain(); worker().drain(); main().drain();
        equal("success", captured.get(0).optString("status"), "Late old callback cannot clear cameraRequested and break capture");
    }
    private static void facadeBackgroundDuringOpen() {
        DeviceHardware hardware = hardware(new RobotGateway()); List<JSONObject> results = new ArrayList<>();
        Camera.duringOpen = () -> { hardware.setForeground(false); expectVisualBlocked(hardware.cameraOwnsResource()); };
        hardware.setCameraEnabled(true, results::add); main().drain(); worker().drain(); main().drain();
        equal("CAMERA_CANCELLED", results.get(0).optString("code"), "Background cancels opening");
        check(Camera.latest.released && !hardware.cameraOwnsResource(), "Background closes actual camera");
        equal("disabled", hardware.status().optString("cameraState"), "Late open cannot revive background camera");
    }
    private static void facadeSuspendBeforeAttentionCompletes() {
        RobotGateway gateway = new RobotGateway(); gateway.deferAttention = true;
        DeviceHardware hardware = hardware(gateway); List<JSONObject> results = new ArrayList<>();
        hardware.setCameraEnabled(true, results::add); main().drain(); expectVisualBlocked(hardware.cameraOwnsResource());
        hardware.suspend(); gateway.attentionCallback.onResult(RobotGateway.success("", "", new JSONObject()));
        main().drain(); worker().drain(); main().drain();
        equal(0, Camera.opens, "Suspend cancels queued attention-to-camera handoff");
        equal("CAMERA_CANCELLED", results.get(0).optString("code"), "Suspended request cancelled");
        check(!hardware.cameraOwnsResource(), "Cancelled pre-open reservation clears");
    }
    private static void closeRejectsEnable() {
        NativeCameraController controller = opened(); Camera source = Camera.latest; controller.close();
        expectVisualBlocked(controller.ownsResource()); worker().drain();
        List<String> result = new ArrayList<>(); controller.enable(result::add);
        equal("CAMERA_DISABLED", result.get(0), "Closed controller cannot enqueue new opening"); check(source.released, "Close released actual HAL");
        equal(1, Camera.opens, "Closed controller did not re-open");
    }
    private static void run(String name, Runnable test) {
        reset(); test.run(); passed++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        run("release ownership and acknowledgement", NativeCameraRegression::deferredRelease);
        run("rapid off/on and stale callbacks", NativeCameraRegression::rapidReopenAndStaleFrames);
        run("disable while Camera.open is in flight", NativeCameraRegression::disableDuringOpen);
        run("release failure quarantine and retry", NativeCameraRegression::failedReleaseQuarantine);
        run("fixed buffer pool and dropped/error frame reuse", NativeCameraRegression::fixedBuffersAndDroppedFrames);
        run("rotated frame error cleanup", NativeCameraRegression::rotatedFrameErrors);
        run("cancelled encode and HAL failure", NativeCameraRegression::disableDuringFrameAndHalError);
        run("buffer return failure cleanup", NativeCameraRegression::bufferReturnFailure);
        run("facade off acknowledgement", NativeCameraRegression::facadeOffAcknowledgement);
        run("facade late completion generation", NativeCameraRegression::facadeLateEnableCannotClearNewRequest);
        run("facade background during open", NativeCameraRegression::facadeBackgroundDuringOpen);
        run("facade suspend before attention completion", NativeCameraRegression::facadeSuspendBeforeAttentionCompletes);
        run("close rejects subsequent enable", NativeCameraRegression::closeRejectsEnable);
        System.out.println(passed + " native camera regression scenarios passed");
    }
}
