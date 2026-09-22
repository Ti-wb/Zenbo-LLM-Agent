package com.robot.asus.kira;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Camera1 is intentionally used for the legacy API 23 robot camera HAL. No raw images leave memory. */
@SuppressWarnings("deprecation")
final class NativeCameraController {
    interface Callback { void done(String error); }
    interface CaptureCallback { void done(CameraFrameStore.Frame frame, String error); }
    private static final int PREVIEW_BUFFERS = 3;
    private final Context context;
    private final HandlerThread thread = new HandlerThread("zenbo-camera");
    private final Handler worker;
    private final CameraFrameStore frames = new CameraFrameStore();
    // Only the camera worker touches the HAL and its surface.
    private Camera camera;
    private SurfaceTexture surface;
    private volatile long generation;
    private volatile boolean enabled, resourceOwned;
    private boolean closed;
    private volatile String state = "disabled";
    private volatile String error = "";
    private long lastEncoded;

    NativeCameraController(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
    }
    boolean isEnabled() { return enabled; }
    // Desired state is revoked immediately; ownership lasts through confirmed HAL release.
    boolean ownsResource() { return enabled || resourceOwned; }
    String state() { return state; }
    String error() { return error; }

    synchronized void enable(Callback callback) {
        if (closed) { callback.done("CAMERA_DISABLED"); return; }
        if (enabled && !"error".equals(state)) {
            final long token = generation;
            worker.post(() -> callback.done(!current(token) ? "CAMERA_CANCELLED" : "error".equals(state) ? error : null));
            return;
        }
        enabled = true;
        state = "starting"; error = "";
        long token = ++generation;
        worker.post(() -> {
            if (!current(token)) { callback.done("CAMERA_CANCELLED"); return; }
            // A failed release is quarantined. Never open another camera over that ownership.
            if (!release()) {
                synchronized (NativeCameraController.this) {
                    if (current(token)) { state = "error"; error = "CAMERA_RELEASE_FAILED"; frames.clear(); }
                }
                callback.done("CAMERA_RELEASE_FAILED"); return;
            }
            try {
                if (Camera.getNumberOfCameras() == 0) throw new IllegalStateException("CAMERA_UNAVAILABLE");
                synchronized (NativeCameraController.this) {
                    if (!current(token)) { callback.done("CAMERA_CANCELLED"); return; }
                    resourceOwned = true;
                }
                camera = Camera.open(0);
                if (!current(token)) { finishCancelled(callback); return; }
                Camera.Parameters parameters = camera.getParameters();
                List<Integer> formats = parameters.getSupportedPreviewFormats();
                if (formats == null || !formats.contains(ImageFormat.NV21)) throw new IllegalStateException("CAMERA_FORMAT_UNSUPPORTED");
                Camera.Size selected = null;
                for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
                    if (size.width < 1 || size.height < 1 || size.width > 1280 || size.height > 1280) continue;
                    if (selected == null || Math.abs(size.width * size.height - 640 * 480)
                            < Math.abs(selected.width * selected.height - 640 * 480)) selected = size;
                }
                if (selected == null) throw new IllegalStateException("CAMERA_FORMAT_UNSUPPORTED");
                final int width = selected.width, height = selected.height;
                parameters.setPreviewSize(width, height);
                parameters.setPreviewFormat(ImageFormat.NV21);
                camera.setParameters(parameters);
                surface = new SurfaceTexture(0);
                camera.setPreviewTexture(surface);
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(0, info);
                int displayRotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
                int degrees = displayRotation == Surface.ROTATION_90 ? 90 : displayRotation == Surface.ROTATION_180 ? 180
                        : displayRotation == Surface.ROTATION_270 ? 270 : 0;
                final int rotation = (info.orientation + (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? degrees : -degrees) + 360) % 360;
                lastEncoded = SystemClock.elapsedRealtime() - 500;
                camera.setErrorCallback((code, source) -> {
                    if (current(token) && source == camera) fail(token, "CAMERA_UNAVAILABLE");
                });
                camera.setPreviewCallbackWithBuffer((data, source) -> {
                    try {
                        if (!current(token) || source != camera || data == null || SystemClock.elapsedRealtime() - lastEncoded < 500) return;
                        lastEncoded = SystemClock.elapsedRealtime();
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        if (!new YuvImage(data, ImageFormat.NV21, width, height, null)
                                .compressToJpeg(new Rect(0, 0, width, height), 70, output)) return;
                        byte[] jpeg = output.toByteArray();
                        int resultWidth = width, resultHeight = height;
                        if (rotation != 0) {
                            Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                            if (decoded == null) return;
                            Bitmap oriented = null;
                            try {
                                Matrix matrix = new Matrix(); matrix.postRotate(rotation);
                                oriented = Bitmap.createBitmap(decoded, 0, 0, width, height, matrix, false);
                                output.reset();
                                if (!oriented.compress(Bitmap.CompressFormat.JPEG, 70, output)) return;
                                jpeg = output.toByteArray();
                                resultWidth = oriented.getWidth(); resultHeight = oriented.getHeight();
                            } finally {
                                if (oriented != null && oriented != decoded) oriented.recycle();
                                decoded.recycle();
                            }
                        }
                        synchronized (NativeCameraController.this) {
                            if (current(token) && source == camera) {
                                frames.update(jpeg, resultWidth, resultHeight, System.currentTimeMillis());
                                state = "ready"; error = "";
                            }
                        }
                    } catch (Exception ignored) {
                        synchronized (NativeCameraController.this) {
                            if (current(token) && source == camera) error = "CAMERA_FRAME_INVALID";
                        }
                    } finally {
                        // Dropped/throttled/invalid frames also return their buffer. Never touch a stale HAL.
                        if (data != null && current(token) && source == camera) {
                            try { source.addCallbackBuffer(data); }
                            catch (RuntimeException failure) { fail(token, "CAMERA_UNAVAILABLE"); }
                        }
                    }
                });
                int bufferBytes = (width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) + 7) / 8;
                for (int index = 0; index < PREVIEW_BUFFERS; index++) camera.addCallbackBuffer(new byte[bufferBytes]);
                camera.startPreview();
                if (!current(token)) { finishCancelled(callback); return; }
                callback.done("error".equals(state) ? error : null);
            } catch (Exception failure) {
                if (!current(token)) { finishCancelled(callback); return; }
                String code = failure instanceof SecurityException ? "CAMERA_PERMISSION_REQUIRED" : "CAMERA_UNAVAILABLE";
                if (failure.getMessage() != null && failure.getMessage().startsWith("CAMERA_")) code = failure.getMessage();
                fail(token, code);
                callback.done(error);
            }
        });
    }

    void disable() { disable(ignored -> { }); }
    synchronized void disable(Callback callback) {
        boolean owned = ownsResource();
        enabled = false;
        final long token = ++generation;
        state = owned ? "releasing" : "disabled"; error = ""; frames.clear();
        worker.post(() -> {
            boolean released = release();
            String result;
            synchronized (NativeCameraController.this) {
                if (generation != token) result = "CAMERA_CANCELLED";
                else {
                    state = released ? "disabled" : "error";
                    error = released ? "" : "CAMERA_RELEASE_FAILED";
                    result = released ? null : error;
                }
            }
            callback.done(result);
        });
    }
    void capture(CaptureCallback callback) {
        final long token = generation;
        final long deadline = SystemClock.elapsedRealtime() + 3_500;
        worker.post(new Runnable() {
            @Override public void run() {
                if (!current(token)) { callback.done(null, "CAMERA_DISABLED"); return; }
                CameraFrameStore.Frame frame = frames.capture(System.currentTimeMillis());
                if (frame != null) { callback.done(frame, null); return; }
                if ("error".equals(state) || SystemClock.elapsedRealtime() >= deadline) {
                    callback.done(null, error.isEmpty() ? "CAMERA_FRAME_UNAVAILABLE" : error); return;
                }
                worker.postDelayed(this, 100);
            }
        });
    }
    byte[] jpeg(String id) { return enabled ? frames.read(id, System.currentTimeMillis()) : null; }
    private boolean current(long token) { return enabled && generation == token; }
    private void finishCancelled(Callback callback) { release(); callback.done("CAMERA_CANCELLED"); }
    private void fail(long token, String code) {
        boolean released = release();
        synchronized (this) {
            if (!current(token)) return;
            state = "error"; error = released ? code : "CAMERA_RELEASE_FAILED"; frames.clear();
        }
    }
    private boolean release() {
        if (camera != null) {
            try { camera.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) { }
            try { camera.setErrorCallback(null); } catch (RuntimeException ignored) { }
            try { camera.stopPreview(); } catch (RuntimeException ignored) { }
            try { camera.release(); }
            catch (RuntimeException failure) { resourceOwned = true; return false; }
            camera = null;
        }
        resourceOwned = false;
        if (surface != null) {
            try { surface.release(); } catch (RuntimeException ignored) { }
            surface = null;
        }
        return true;
    }
    synchronized void close() {
        if (closed) return;
        closed = true; disable(); worker.post(thread::quitSafely);
    }
}
