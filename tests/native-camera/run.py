#!/usr/bin/env python3
"""Compile production camera/facade code against deterministic, synthetic Android stubs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/os/Looper.java': '''package android.os;
import java.util.ArrayDeque;
public class Looper {
    private static final Looper MAIN = new Looper();
    public final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    public static Looper getMainLooper() { return MAIN; }
    public void drainOne() { queue.remove().run(); }
    public void drain() { int limit = 1000; while (!queue.isEmpty()) {
        if (--limit == 0) throw new AssertionError("Unbounded worker queue"); drainOne();
    } }
}''',
    'android/os/Handler.java': '''package android.os;
public class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public boolean post(Runnable task) { looper.queue.add(task); return true; }
    public boolean postDelayed(Runnable task, long delay) { return post(() -> { SystemClock.now += delay; task.run(); }); }
}''',
    'android/os/HandlerThread.java': '''package android.os;
public class HandlerThread extends Thread {
    public static Looper latest;
    private final Looper looper = new Looper();
    public HandlerThread(String name) { super(name); latest = looper; }
    @Override public void start() { }
    public Looper getLooper() { return looper; }
    public boolean quitSafely() { return true; }
}''',
    'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long now = 10000; public static long elapsedRealtime() { return now; } }''',
    'android/content/Context.java': '''package android.content;
public class Context {
    public static final String WINDOW_SERVICE = "window";
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return new android.view.WindowManager(); }
}''',
    'android/view/WindowManager.java': '''package android.view;
public class WindowManager { public Display getDefaultDisplay() { return new Display(); } }''',
    'android/view/Display.java': '''package android.view;
public class Display { public int getRotation() { return 0; } }''',
    'android/view/Surface.java': '''package android.view;
public class Surface { public static final int ROTATION_90 = 1, ROTATION_180 = 2, ROTATION_270 = 3; }''',
    'android/graphics/SurfaceTexture.java': '''package android.graphics;
public class SurfaceTexture { public boolean released; public SurfaceTexture(int id) {} public void release() { released = true; } }''',
    'android/graphics/ImageFormat.java': '''package android.graphics;
public class ImageFormat { public static final int NV21 = 17; public static int getBitsPerPixel(int format) { return 12; } }''',
    'android/graphics/Rect.java': '''package android.graphics;
public class Rect { public Rect(int left, int top, int right, int bottom) {} }''',
    'android/graphics/Matrix.java': '''package android.graphics;
public class Matrix { public void postRotate(float rotation) {} }''',
    'android/graphics/YuvImage.java': '''package android.graphics;
import java.io.ByteArrayOutputStream;
public class YuvImage {
    public static int encoded; public static boolean fail, throwError; public static Runnable duringEncode;
    public YuvImage(byte[] data, int format, int width, int height, int[] strides) {}
    public boolean compressToJpeg(Rect rect, int quality, ByteArrayOutputStream output) {
        encoded++; if (duringEncode != null) { Runnable hook = duringEncode; duringEncode = null; hook.run(); }
        if (throwError) throw new IllegalArgumentException("synthetic encode failure");
        if (fail) return false;
        output.write(255); output.write(216); output.write(255); output.write(217); return true;
    }
}''',
    'android/graphics/Bitmap.java': '''package android.graphics;
import java.io.ByteArrayOutputStream;
public class Bitmap {
    public enum CompressFormat { JPEG }
    public static int recycled; public static boolean failCompress;
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height, Matrix matrix, boolean filter) { return new Bitmap(); }
    public boolean compress(CompressFormat format, int quality, ByteArrayOutputStream output) {
        if (failCompress) return false; output.write(255); output.write(216); output.write(255); output.write(217); return true;
    }
    public int getWidth() { return 480; } public int getHeight() { return 640; }
    public void recycle() { recycled++; }
}''',
    'android/graphics/BitmapFactory.java': '''package android.graphics;
public class BitmapFactory { public static boolean fail; public static Bitmap decodeByteArray(byte[] data, int offset, int length) { return fail ? null : new Bitmap(); } }''',
    'android/hardware/Camera.java': '''package android.hardware;
import java.util.*;
import android.graphics.SurfaceTexture;
public class Camera {
    public interface PreviewCallback { void onPreviewFrame(byte[] data, Camera source); }
    public interface ErrorCallback { void onError(int code, Camera source); }
    public static class Size { public int width = 640, height = 480; }
    public static class CameraInfo { public static final int CAMERA_FACING_FRONT = 1; public int orientation, facing; }
    public static class Parameters {
        public List<Integer> getSupportedPreviewFormats() { return Arrays.asList(17); }
        public List<Size> getSupportedPreviewSizes() { return Arrays.asList(new Size()); }
        public void setPreviewSize(int width, int height) {} public void setPreviewFormat(int format) {}
    }
    public static Camera latest; public static int opens, orientation;
    public static Runnable duringOpen;
    public final ArrayDeque<byte[]> buffers = new ArrayDeque<>();
    public final Set<byte[]> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    public PreviewCallback preview, savedPreview; public ErrorCallback error, savedError;
    public boolean released, failRelease, failReturn; public int returned, releaseAttempts;
    public static int getNumberOfCameras() { return 1; }
    public static Camera open(int id) {
        opens++; latest = new Camera();
        if (duringOpen != null) { Runnable hook = duringOpen; duringOpen = null; hook.run(); }
        return latest;
    }
    public static void getCameraInfo(int id, CameraInfo info) { info.orientation = orientation; }
    public Parameters getParameters() { return new Parameters(); }
    public void setParameters(Parameters parameters) {} public void setPreviewTexture(SurfaceTexture texture) {}
    public void setErrorCallback(ErrorCallback callback) { error = callback; if (callback != null) savedError = callback; }
    public void setPreviewCallbackWithBuffer(PreviewCallback callback) { preview = callback; if (callback != null) savedPreview = callback; else buffers.clear(); }
    public void addCallbackBuffer(byte[] data) {
        if (released || failReturn) throw new IllegalStateException("Cannot return buffer");
        returned++; identities.add(data); buffers.add(data);
    }
    public byte[] frame() { byte[] data = buffers.remove(); preview.onPreviewFrame(data, this); return data; }
    public void startPreview() {} public void stopPreview() {}
    public void release() { releaseAttempts++; if (failRelease) throw new IllegalStateException("synthetic release failure"); released = true; }
}''',
    'android/Manifest.java': '''package android;
public class Manifest { public static class permission { public static final String CAMERA = "camera"; } }''',
    'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager { public static final int PERMISSION_GRANTED = 0; }''',
    'androidx/core/content/ContextCompat.java': '''package androidx.core.content;
public class ContextCompat { public static int permission; public static int checkSelfPermission(android.content.Context context, String name) { return permission; } }''',
    'android/util/Base64.java': '''package android.util;
public class Base64 { public static final int NO_WRAP = 2; public static String encodeToString(byte[] bytes, int flags) { return java.util.Base64.getEncoder().encodeToString(bytes); } }''',
    'org/json/JSONException.java': '''package org.json;
public class JSONException extends Exception { public JSONException(String message) { super(message); } }''',
    'org/json/JSONObject.java': '''package org.json;
import java.util.HashMap;
public class JSONObject {
    private final HashMap<String, Object> values = new HashMap<>();
    public JSONObject put(String key, Object value) throws JSONException { values.put(key, value); return this; }
    public Object opt(String key) { return values.get(key); }
    public String optString(String key) { Object value = opt(key); return value == null ? "" : value.toString(); }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(opt(key)); }
    public JSONObject optJSONObject(String key) { Object value = opt(key); return value instanceof JSONObject ? (JSONObject) value : null; }
}''',
    'com/robot/asus/kira/RobotOperations.java': '''package com.robot.asus.kira;
public interface RobotOperations { interface ResultCallback { void onResult(org.json.JSONObject result); } }''',
    'com/robot/asus/kira/MainActivity.java': '''package com.robot.asus.kira;
public class MainActivity { public static boolean requestNativeCameraPermission() { return true; } }''',
    'com/robot/asus/kira/RobotGateway.java': '''package com.robot.asus.kira;
import org.json.*;
public class RobotGateway {
    public boolean deferAttention; public RobotOperations.ResultCallback attentionCallback;
    public void setHardware(DeviceHardware hardware) {} public boolean isFollowing() { return false; }
    public boolean isReady() { return true; } public boolean isMoving() { return false; }
    public boolean isAttentionActive() { return false; } public String attentionState() { return "idle"; }
    public void setMotionAllowed(boolean allowed) {} public void setForeground(boolean foreground) {}
    public boolean busyExceptAttention() { return false; }
    public void stopAttention(RobotOperations.ResultCallback callback) { if (deferAttention) attentionCallback = callback; else callback.onResult(success("", "", new JSONObject())); }
    public void attend(double direction, boolean face) {} public void emergencyStop() {}
    public void execute(String id, String name, JSONObject args, GuardedExecution.Guard guard, RobotOperations.ResultCallback callback) {}
    public static JSONObject accepted() { return new JSONObject(); }
    public static JSONObject success(String id, String name, JSONObject output) {
        try { return new JSONObject().put("status", "success").put("output", output); } catch (JSONException error) { throw new AssertionError(error); }
    }
    public static JSONObject error(String id, String name, String code, String message) {
        try { return new JSONObject().put("status", "error").put("code", code); } catch (JSONException error) { throw new AssertionError(error); }
    }
}''',
}


def main():
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('A JDK is required; set JAVA_HOME to JDK 17.')
    with tempfile.TemporaryDirectory(prefix='zenbo-camera-regression-') as directory:
        temporary = Path(directory)
        sources = []
        for name, content in STUBS.items():
            file = temporary / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content + '\n')
            sources.append(str(file))
        for name in ('NativeCameraController', 'CameraFrameStore', 'DeviceHardware', 'HardwareAccessPolicy', 'SpeakerDirection', 'GuardedExecution'):
            sources.append(str(ROOT / 'android/KiraZenbo/src/main/java/com/robot/asus/kira' / (name + '.java')))
        sources.append(str(Path(__file__).with_name('NativeCameraRegression.java')))
        classes = temporary / 'classes'
        subprocess.run([javac, '--release', '11', '-d', str(classes), *sources], check=True)
        subprocess.run([java, '-ea', '-cp', str(classes), 'com.robot.asus.kira.NativeCameraRegression'], check=True)


if __name__ == '__main__':
    main()
