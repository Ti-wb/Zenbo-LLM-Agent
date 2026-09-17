# Third-party software notices

This document records the direct third-party dependencies declared by this
repository. It does not replace the license text shipped by an upstream
project, and the repository's Apache-2.0 license does not relicense any
third-party component.

## Web application

The exact resolved dependency tree and complete license texts are recorded in
[`WEB_THIRD_PARTY_LICENSES.txt`](WEB_THIRD_PARTY_LICENSES.txt). The direct
runtime dependencies currently declared in `package.json` are:

| Component | Resolved version | License |
| --- | --- | --- |
| [VAD Web](https://github.com/ricky0123/vad) (`@ricky0123/vad-web`) | 0.0.30 | ISC |
| [Pinia](https://github.com/vuejs/pinia) | 3.0.4 | MIT |
| [Vue](https://github.com/vuejs/core) | 3.5.25 | MIT |

Transitive production dependencies are listed in the generated web inventory
rather than duplicated here. Development-only tooling is not included because
it is not shipped in the web application; its license metadata remains in
`package-lock.json` and in each installed package.

VAD Web loads the Silero VAD model and ONNX Runtime Web. The generated web
inventory includes the VAD/Silero and ONNX Runtime license texts. ONNX Runtime
also publishes its version-specific bundled dependency notices here:
<https://github.com/microsoft/onnxruntime/blob/v1.23.2/ThirdPartyNotices.txt>.

## Android runtime

### AndroidX AppCompat

- Coordinates: `androidx.appcompat:appcompat:1.7.1` and
  `androidx.appcompat:appcompat:1.6.1`
- License: Apache License 2.0
- Source: <https://github.com/androidx/androidx>
- License text: <https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt>

The two versions are direct declarations in separate Android modules. Gradle
normally resolves the application runtime to a single compatible version.

### Mozilla GeckoView

- Coordinate: `org.mozilla.geckoview:geckoview:143.0.20250929153833`
- License: Mozilla Public License 2.0 (MPL-2.0)
- Project and source: <https://firefox-source-docs.mozilla.org/mobile/android/geckoview/>
- MPL-2.0 text: <https://www.mozilla.org/MPL/2.0/>
- Mozilla release source archive:
  <https://archive.mozilla.org/pub/firefox/releases/143.0.3/source/>

GeckoView is an MPL-covered component of a larger application; the MPL does
not change the license of this repository's separate source files. When an APK
containing GeckoView is distributed, recipients must be informed where the
corresponding MPL-covered source can be obtained. If GeckoView itself is
modified, the modified MPL-covered source must also be made available under
MPL-2.0. Preserve Mozilla and embedded third-party notices supplied with the
GeckoView distribution.

### AndroidAsync

- Coordinate: `com.koushikdutta.async:androidasync:3.1.0`
- License: Apache License 2.0
- Source: <https://github.com/koush/AndroidAsync>

```text
Copyright 2013 Koushik Dutta (2013)
```

The Apache License 2.0 text is available in this repository's
[`LICENSE`](LICENSE) file.

### OkHttp, Okio, and Kotlin runtime

The native Agent Gateway client adds OkHttp and its runtime dependencies. The
versions below are the versions resolved in the Android debug runtime graph;
release builds should re-check the resolved graph before distribution.

| Component | Resolved version | License | Source |
| --- | --- | --- | --- |
| OkHttp (`com.squareup.okhttp3:okhttp`) | 4.12.0 | Apache-2.0 | <https://github.com/square/okhttp> |
| Okio (`com.squareup.okio:okio-jvm`) | 3.6.0 | Apache-2.0 | <https://github.com/square/okio> |
| Kotlin Standard Library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.2.10 | Apache-2.0 | <https://github.com/JetBrains/kotlin> |

OkHttp carries Copyright 2019 Square, Inc. Okio carries Copyright 2013
Square, Inc. Kotlin carries Copyright 2010-2025 JetBrains s.r.o. and Kotlin
Programming Language contributors. The Apache License 2.0 text is available
in this repository's [`LICENSE`](LICENSE) file. Kotlin standard-library JDK 7
and JDK 8 compatibility artifacts also appear in the graph, but their classes
resolve to the Kotlin 2.2.10 standard library used by the APK.

### ISRG Root X1 public certificate

The Native Hermes client bundles the unchanged public [ISRG Root X1
certificate](https://letsencrypt.org/certs/isrgrootx1.pem) for Android 6 trust
compatibility. It is owned by Internet Security Research Group. [ISRG CP/CPS
section 9.5](https://letsencrypt.org/documents/isrg-cp-cps-v6.2/#95-intellectual-property-rights)
permits complete reproduction and redistribution on a non-exclusive,
royalty-free basis; this certificate is not licensed under the project's
Apache-2.0 license. Its source and fingerprint are recorded beside the bundled
certificate in `android/KiraZenbo/src/main/resources/certificates/README.md`.

### ASUS Zenbo SDK

The ASUS Zenbo SDK is a proprietary vendor dependency and is intentionally not
distributed by this repository. The repository's Apache-2.0 license grants no
rights to the SDK. Developers must obtain a robot- and firmware-compatible SDK
directly from ASUS after accepting the applicable end-user license agreement,
then install it locally as described in
[`android/ZenboSDK/README.md`](android/ZenboSDK/README.md).

ASUS download guidance:
<https://zenbo.asus.com/developer/documents/overview/Developer-Website-Tutorial>

Redistributors of an APK containing the ASUS SDK are responsible for checking
that the ASUS agreement permits that distribution.

### Gson bundled inside the ASUS SDK

The previously bundled ASUS SDK JAR contains Gson 2.8.9 classes. Gson is not a
standalone Gradle dependency of this project, so this notice applies only to an
APK or other distribution that includes an ASUS SDK containing Gson.
A separately downloaded SDK version may contain a different dependency set and
must be audited before its binary is redistributed.

- Component: [Gson](https://github.com/google/gson), version 2.8.9
- License: Apache License 2.0

```text
Copyright 2008 Google Inc.
```

The Apache License 2.0 text is available in this repository's
[`LICENSE`](LICENSE) file.

## Android build and test dependencies

These dependencies are used to build or test the project and are not intended
to be packaged into the application runtime:

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.1 | Apache-2.0 | <https://android.googlesource.com/platform/tools/base/> |
| Foojay toolchain resolver convention plugin | 0.8.0 | Apache-2.0 | <https://github.com/gradle/foojay-toolchains> |
| Gradle Wrapper / Gradle Build Tool | 8.13 | Apache-2.0 | <https://github.com/gradle/gradle> |
| JUnit 4 | 4.13.2 and 4.12 | EPL-1.0 | <https://github.com/junit-team/junit4> |
| JSON-java | 20240303 | Public Domain | <https://github.com/stleary/JSON-java> |

The committed Gradle Wrapper JAR contains its own `META-INF/LICENSE`. JUnit and
JSON-java are declared with `testImplementation` and are not included in
production APKs.
