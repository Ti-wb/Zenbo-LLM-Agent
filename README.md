# KIRA – Zenbo Robot Web UI & Agent Runtime

This project is a complete rewrite of the ASUS Zenbo dialog system. It replaces the stock face/home UI with a modern, web‑based assistant powered by OpenAI agents, running inside GeckoView on the robot itself.

At a high level:

- A Vue 3 single‑page app provides the conversational UI, tools, and agent logic.
- GeckoView embeds that SPA as the robot’s primary UI (KIRA can be set as the device launcher).
- A foreground Android `Service` exposes the Zenbo native `RobotAPI` via a lightweight HTTP + WebSocket API.
- The web app uses that API to remote‑control the robot (speech, expressions, tracking, etc.) and to react to real‑time robot events.

The goal is to decouple the dialog/agent logic from the native UI and implement it in JavaScript, while still having full access to the robot’s capabilities through a clean Android bridge.

## Architecture Overview

<img width="1872" height="1246" alt="image" src="https://github.com/user-attachments/assets/5d9c9855-577d-47e9-80f0-158ec9158936" />

### Frontend (Vue 3 + OpenAI Agents)

- The web UI lives under `src/`:
  - `App.vue` bootstraps the page and wires up the core modules.
  - `src/composables/` contains reusable hooks:
    - `useAgent.js` – builds an OpenAI agent using `@openai/agents`, auto‑discovering `<tool>` and `<context>` elements from the DOM.
    - `useVoiceAgent.js` – orchestrates transcription (VAD + Whisper), agent turns, and TTS for a voice‑first experience.
    - `useRobotAPI.js` – provides a typed wrapper around the robot HTTP API (dialog, face, utility endpoints).
    - `useRobotEvents.js` – subscribes to robot events over WebSocket (`ws://<host>:8790/events`) and exposes `onEvent` / `onEventType`.
    - `useSleepMode.js` – global sleep/wake state used to mute/unmute the VAD microphone.
  - `src/modules/` defines feature modules:
    - `modules/robot/Robot.vue` – robot‑specific tools and context (e.g. follow user, expression presets).
    - `modules/misc/*.vue` – domain tools like `Assist`, `Finance`, `Weather`, `ImageGen`, `Search`, plus `Sleep` (agent sleep tool).
    - `modules/event/EventDisplay.vue` – debug view for incoming robot events.
    - `modules/event/SleepWake.vue` – listens for `onVoiceDetect` events to wake the agent from sleep.

The agent itself is described declaratively:

- `<context name="...">` blocks give the agent additional contextual instructions (current time, robot persona, etc.).
- `<tool name="...">` elements define callable tools with `<prop>` children describing parameters.
- `useAgent` scans the DOM for these tags and turns them into real tools for the OpenAI agent.

### Android Runtime (GeckoView + AsyncHttpServer + WebSocket)

The Android portion lives under `android/KiraZenbo/` and consists of:

- `MainActivity`:
  - Hosts a `GeckoView` and loads the SPA from `http://127.0.0.1:8787/`.
  - Implements `GeckoSession.PermissionDelegate` to grant microphone access for in‑browser audio capture.
  - Declares both a standard launcher intent and a `HOME` intent:
    - KIRA can be set as the *default home screen / launcher* on the device.
    - When selected, the GeckoView UI effectively replaces the default Zenbo home view.

- `RobotApiService` (foreground `Service`):
  - Owns a `RobotAPI` instance and registers callbacks for:
    - Robot state changes, tracking, face/gesture detection…
    - Dialog events such as `onVoiceDetect`, `onEventUserUtterance`, `onSpeakComplete`, etc.
  - Starts and manages two servers:
    - `AsyncRobotApiServer` (HTTP) on `http://127.0.0.1:8787`:
      - Serves the SPA (`assets/app/index.html`) at `/`.
      - Exposes REST endpoints under `/api/...` for:
        - Dialog actions (`/api/dialog/speak`, `/api/dialog/start_speak_animation`, `/api/dialog/voice_trigger`, …).
        - Face actions (`/api/face/expression`, `/api/face/expression_and_speak`).
        - Utility actions (`/api/utility/follow_face`, `/follow_object`, `/track_face`, `/look_at_user`, `/play_action`, `/play_emotional_action`, blue‑light filter controls).
      - This is what `useRobotAPI` calls from the web app.
    - `AndroidAsyncEventServer` (WebSocket) on `ws://127.0.0.1:8790/events`:
      - Broadcasts JSON event messages `{ "type": "<eventName>", "data": { ... } }` to all connected clients.
      - This is what `useRobotEvents` connects to.
  - Bridges native events into the WebSocket:
    - Each `RobotAPI` callback builds a JSON payload and calls `sendEvent("onVoiceDetect", data)`, `sendEvent("onStateChange", data)`, etc.
    - The web UI can subscribe to these (e.g. `onEventType('onVoiceDetect', ...)`) to update expressions, show debug info, or wake the sleep mode.
  - Brings the UI to the foreground when needed:
    - On `onVoiceDetect`, `RobotApiService` calls a helper that launches `MainActivity` (in the HOME task) so the GeckoView UI is visible when the user starts speaking to the robot.

## Sleep Mode & Voice Flow

The project implements an explicit “sleep mode” for the agent:

- `Sleep.vue` defines `<tool name="go_to_sleep">`:
  - When the agent calls this tool, `handleGoToSleep` sets the global `sleeping` flag and returns `{ ok: true, sleeping: true }`.
- `useSleepMode` exposes a shared `sleeping` ref and `goToSleep` / `wakeUp` helpers.
- `useVoiceAgent` watches `sleeping` and controls the **VAD mic mute**:
  - When `sleeping` is `true`:
    - Calls `muteMic()` in `useTranscription` so VAD segments are discarded and never sent to Whisper or the agent.
  - When `sleeping` is `false` and TTS is idle:
    - Calls `unmuteMic()` so the agent can hear the user again.
  - TTS always mutes the mic while speaking and only un‑mutes it when not in sleep mode, to avoid feedback loops.
- `SleepWake.vue` listens on the robot event stream:
  - When an `onVoiceDetect` event is received and `sleeping` is `true`, it calls `wakeUp()` so the agent wakes, unmutes the mic, and can handle the next utterance.

This gives the agent an explicit “go to sleep” tool and a natural wake‑up trigger based on the robot’s native voice detection.

## Launcher / Home Integration

KIRA is designed to replace the default Zenbo home view:

- `MainActivity` declares an intent filter with `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT`.
- When the app is installed, pressing Home or finishing boot may prompt the user to choose a default HOME app.
- If “Kira” is selected and set to “Always”, then:
  - KIRA’s GeckoView UI becomes the robot’s home screen.
  - After boot, `RobotApiService` starts in the background and `MainActivity` is the visible front‑end.
  - The system “face” activity can be hidden or replaced by the Vue UI as needed (e.g. via robot expressions such as hiding the face).

To revert back to the original launcher, clear KIRA’s default HOME association in Android’s “Home app” / “Default apps” settings and choose the original Zenbo launcher again.

## Developing & Building

### Prerequisites

- Install a current Node.js release and npm for the Vue frontend.
- Obtain a Zenbo SDK version compatible with the target robot directly from
  ASUS after accepting the vendor EULA. SDK binaries are not distributed by
  this repository. Follow [android/ZenboSDK/README.md](android/ZenboSDK/README.md)
  for the expected local path and Zenbo-K compatibility notes.
- Install Android Studio and configure the Android SDK required by the Gradle
  project.

### Environment configuration

Create a local environment file from the committed template:

```sh
cp .env.example .env
```

Fill only the integrations you intend to use. OpenAI-compatible features use
`VITE_OPENAI_API_KEY`; stock data uses `VITE_FINNHUB_API_KEY`; and web search
uses `VITE_FIRECRAWL_API_KEY` (or its legacy `VITE_FIRECRAWL_API_TOKEN` alias).
The corresponding API base URLs and model names have usable examples in the
template.

Do not commit `.env`. Every variable prefixed with `VITE_` is compiled into the
browser bundle and must be treated as public. A key placed there can be read
from the web UI, generated Android assets, or a distributed APK. For anything
beyond local development, route authenticated provider calls through a trusted
backend or native credential bridge, or require the device owner to supply a
revocable key at runtime.

### Frontend (Vue)

- Install dependencies: `npm install`
- Run dev server: `npm run dev`
  - This serves the SPA for browser‑based development (without GeckoView).
- Build/watch for Android/WebView: `npm run android`
  - Runs `vite build --watch --outDir android/KiraZenbo/src/main/assets/app`.
  - On each change, Vite rebuilds the SPA and writes a new `index.html` bundle directly into the Android assets folder used by GeckoView.

### Android

- Open the `android/` directory in Android Studio.
- Let Gradle sync, then run the `KiraZenbo` app on the device.
- While `npm run android` is running, any changes to the Vue app will rebuild and update `android/KiraZenbo/src/main/assets/app/index.html` automatically.
  - To see updated UI in GeckoView, rebuild/relaunch the Android app from Android Studio (or use “Apply Changes” if available).
- Once installed and set as the HOME/launcher, KIRA will start as the main UI; `RobotApiService` starts automatically to provide the HTTP and WebSocket APIs.

## License and third-party software

The original project source is provided under the
[Apache License 2.0](LICENSE). That license does not automatically cover vendor
SDKs or all third-party dependencies used by the Android and web applications.

- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) records bundled and runtime
  third-party components and their attribution requirements.
- [WEB_THIRD_PARTY_LICENSES.txt](WEB_THIRD_PARTY_LICENSES.txt) contains the
  generated license inventory for web dependencies.
- [android/NOTICE](android/NOTICE) contains Android-side notices retained from
  the upstream project.
- [android/ZenboSDK/README.md](android/ZenboSDK/README.md) explains how to
  obtain the proprietary ASUS SDK locally without redistributing it.

When distributing an APK or another binary build, include the applicable
third-party notices with the release.
