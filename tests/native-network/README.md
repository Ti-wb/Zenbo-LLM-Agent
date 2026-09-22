# LAN request boundary regression

Run with the existing JDK 17, Android SDK 36 and cached project dependencies:

```sh
JAVA_HOME=/path/to/jdk-17 ANDROID_SDK_ROOT=/path/to/android-sdk python3 tests/native-network/run.py
```

If the dependency cache is missing, first resolve the existing Android project's
Gradle dependencies with the configured JDK and Android SDK. When the optional
local helper is present, `.local/tools/dev-android-env.sh python3 tests/native-network/run.py`
can supply those environment variables instead.

The runner compiles the actual LAN server, bounded body parser, LAN session policy
and asset streamer with the installed AndroidAsync 3.1.0 and org.json libraries.
Synthetic Android, hardware and socket/timer stubs are isolated in a temporary
directory. It does not change the Gradle test classpath or download dependencies.

The positive control completes a real library request showing that its default
GET JSON parser accepts a body above 2 KiB. Production route tests then cover every
route, known content types, unknown methods/targets, decoded newlines, streaming
and chunked overflow, the five-second deadline, exact-limit JSON, and gzip/deflate
rejection before any body decoder dispatch (including headers/body in one buffer).
An additional positive control demonstrates that AndroidAsync's `100-continue`
negotiation resumes pending compressed bytes before its early request hook; LAN
requests carrying `Expect` are therefore rejected before that negotiation.
Ordinary page, pairing, authenticated status/frame, heartbeat, action and logout
handlers complete their real HTTP responses; origin, CSRF and single-controller
checks are also preserved.

These are parser/routing and resource-limit checks on the JVM. They do not prove
Android 6 networking, device firmware, camera imagery or physical robot movement.
