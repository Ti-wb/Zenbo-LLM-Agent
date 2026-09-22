# Native camera race and buffer regression

Run with JDK 17 and Python 3:

```sh
JAVA_HOME=/path/to/jdk-17 python3 tests/native-camera/run.py
```

This compiles the actual NativeCameraController, DeviceHardware, frame store and
access policy against synthetic Camera1, graphics and deterministic Looper stubs.
The runner writes all generated stubs/classes to a temporary directory and removes
it afterward. No new dependency or camera device is required.

The checks exercise deferred release acknowledgement and exclusive ownership,
release failure quarantine/retry, rapid off/on and background cancellation, stale
callbacks, and fixed NV21 buffer reuse across throttled and invalid frames. Stub
methods deliberately omit the allocating setPreviewCallback API so regressions
back to that API fail compilation.

This is a JVM scheduling/resource regression, not evidence of Android 6 camera HAL,
firmware, image orientation, memory pressure or physical followFace compatibility.
The Android build still compiles against the real SDK; those device gates remain
open until tested on the identified robot.
