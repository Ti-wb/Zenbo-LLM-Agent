#!/usr/bin/env python3
"""Exercise production LAN routes with installed AndroidAsync and synthetic Android IO."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    "android/os/Looper.java": """package android.os;
public class Looper { public static Looper getMainLooper() { return null; }
public static Looper myLooper() { return null; } public Thread getThread() { return Thread.currentThread(); } }""",
    "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static long elapsedRealtime() { return 100; } }""",
    "android/text/TextUtils.java": """package android.text;
public class TextUtils {
public static boolean equals(CharSequence a, CharSequence b) { return a == b || a != null && b != null && a.toString().equals(b.toString()); }
public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; } }""",
    "android/util/Log.java": """package android.util;
public class Log { public static int i(String tag, String message) { return 0; }
public static int e(String tag, String message, Throwable error) { throw new AssertionError(message, error); } }""",
    "android/content/Context.java": """package android.content;
public class Context { public Context getApplicationContext() { return this; }
public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); } }""",
    "android/content/res/AssetManager.java": """package android.content.res;
public class AssetManager { public java.io.InputStream open(String path) {
if (!path.equals("app/remote-control.html")) throw new AssertionError(path);
return new java.io.ByteArrayInputStream("<html>synthetic remote page</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }""",
    "com/robot/asus/kira/DeviceHardware.java": """package com.robot.asus.kira;
import org.json.JSONObject;
public class DeviceHardware {
public int actions, frames, statusCalls;
interface Guard { boolean run(Runnable task); } interface Result { void onResult(JSONObject value); }
public JSONObject status() { statusCalls++; return new JSONObject().put("robotReady", true); }
public byte[] cameraJpeg(String id) { frames++; return new byte[]{(byte)255, (byte)216, (byte)255, (byte)217}; }
public void stop() { }
public void action(String action, Guard guard, Result callback) {
if (!guard.run(() -> actions++)) throw new AssertionError("Guard rejected normal control");
callback.onResult(new JSONObject().put("status", "success")); } }""",
    "com/robot/asus/kira/RemoteSessionCoordinator.java": """package com.robot.asus.kira;
public class RemoteSessionCoordinator { public boolean isMotionEnabled() { return true; }
public boolean manualDeviceActionAllowed() { return true; } }""",
    "com/robot/asus/kira/LocalRuntimeServer.java": """package com.robot.asus.kira;
public class LocalRuntimeServer {
static String isoTime(long time) { return java.time.Instant.ofEpochMilli(time).toString(); }
static boolean validDeviceAction(String action) { return java.util.Arrays.asList("follow", "stop", "forward", "backward", "left", "right").contains(action); } }""",
}


def cached_artifact(group, name, version, suffix):
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    matches = sorted((cache / "caches/modules-2/files-2.1" / group / name / version).glob(f"*/{name}-{version}.{suffix}"))
    if not matches:
        raise SystemExit(f"Missing cached {name} {version}; resolve the existing Android project's dependencies first.")
    return matches[0]


def main():
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not javac or not java or not sdk:
        raise SystemExit("Run with JDK 17 JAVA_HOME and ANDROID_SDK_ROOT, or .local/tools/dev-android-env.sh python3 tests/native-network/run.py")
    android_jar = Path(sdk) / "platforms/android-36/android.jar"
    if not android_jar.is_file():
        raise SystemExit(f"Missing {android_jar}")
    async_aar = cached_artifact("com.koushikdutta.async", "androidasync", "3.1.0", "aar")
    json_jar = cached_artifact("org.json", "json", "20240303", "jar")
    with tempfile.TemporaryDirectory(prefix="zenbo-lan-regression-") as temporary:
        work = Path(temporary)
        sources = []
        for relative, source in STUBS.items():
            target = work / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source)
            sources.append(str(target))
        with zipfile.ZipFile(async_aar) as archive:
            (work / "androidasync.jar").write_bytes(archive.read("classes.jar"))
        classpath = os.pathsep.join(map(str, [work, work / "androidasync.jar", json_jar, android_jar]))
        production = ROOT / "android/KiraZenbo/src/main/java/com/robot/asus/kira"
        sources += [str(production / f"{name}.java") for name in [
            "BoundedJsonBody", "LanHttpServer", "LanRemoteServer", "LanRemoteSecurity", "AppAssetStream",
        ]]
        sources += [str(ROOT / "tests/native-network" / name) for name in ["ControlledSocket.java", "LanRequestsRegression.java"]]
        subprocess.run([javac, "--release", "11", "-cp", classpath, "-d", str(work), *sources], check=True)
        subprocess.run([java, "-ea", "-cp", classpath, "com.robot.asus.kira.LanRequestsRegression"], check=True)


if __name__ == "__main__":
    main()
