# Repository Guidelines

## Operating model

The main agent owns product decisions, task boundaries, integration and final
acceptance. Delegate implementation and focused tests to specialized sub-agents
with non-overlapping ownership. Develop new features on an isolated `codex/`
branch. Use Conventional Commits when committing or publishing.

## Architecture and trust boundaries

This project connects Zenbo to a configured Hermes profile. The only server
addition is one plugin in the Hermes process. Do not add another server,
database or provider fallback without an explicit design decision.

- Vue renders PixelFace, captures WAV with VAD, manages interaction state and
  plays Native-validated audio. It only connects to `127.0.0.1:8787/api/v2`.
- Android owns the loopback server, one-use fragment bootstrap, HttpOnly session,
  PIN authorization, Keystore credentials, Hermes lifecycle and ASUS SDK safety.
- Native connects to an HTTPS base ending in `/p/{profile}/v1`, retaining the
  profile and any reverse-proxy prefix for all session/run operations. For example,
  `https://hermes.example.com/hermes-api/p/robot/v1` uses sample profile `robot`
  and optional proxy prefix `/hermes-api`; replace both for the installation.
  The profile owns the model and STT/TTS configuration; omit `model` from
  Runs requests.
- `apiKey` is write-only; public settings expose `hasApiKey` and `gatewayUrl`. There is
  no model selection. Underlying provider credentials stay in Hermes. Never put keys in
  Pinia, localStorage, build assets, APK, logs, fixtures or committed files.
- The Hermes Zenbo plugin mounts `/zenbo/{profile}/v1` under the same optional
  proxy prefix, using HTTPS/WSS on the configured origin.
  It reuses Hermes STT/TTS and tool execution context; no second process/port.
- Android 6 / API 23 is the hard device compatibility baseline.

## Normative interfaces

`contracts/hermes-zenbo/README.md` defines the plugin channel and profile routing.
`contracts/hermes-zenbo/device-tools.json` is the shared six-tool schema.
`contracts/local-runtime/` defines renderer bootstrap, HTTP and Native-owned
protocol 2.0 events. Change implementation, schema, fixtures and tests together.

Local events use Native UUIDs and an increasing sequence, including controls.
Never forward Hermes SSE frames as local envelopes. On remote SSE loss, query
Hermes run status; do not assume WebSocket replay or execute tools from progress
notifications. Local renderer recovery reads status/conversation and resumes
Native's local sequence. Do not expose opaque remote run IDs to Web state.

Cancellation stops local playback/motion and revokes tool authority first.
The remote run must become terminal before starting another; new turns receive
409 `TURN_BUSY` meanwhile. Keep terminal events unique and ignore late cancelled
run audio/tools. Playback completion is local and only the last ordered artifact
completes the turn; Hermes has no plugin playback API.

## Six fixed device tools

| Tool | Owner |
| --- | --- |
| `get_system_status` | Native |
| `start_robot_following` | Native |
| `stop_robot_following` | Native |
| `look_at_user` | Native |
| `show_emotion` | Web through Native |
| `go_to_sleep` | Web through Native |

Preserve strict arguments, ownership, deadlines, movement safety and duplicate
terminal rejection. Disconnected or uncertain physical tool calls are never
replayed. Bind profile/session/run from authenticated Hermes context, not model
arguments. The plugin waits for terminal device results, not just acceptance.

`show_emotion` is staged until first answer audio starts. Keep the five existing
emotions, actual-audio mouth animation, cancellation/reset behavior and ordered
multi-artifact playback. Do not claim hands-free barge-in while playback pauses
VAD.

## Code and test organization

- `src/components/`: presentation; `src/composables/`: orchestration;
  `src/services/`: transport/policy; `src/stores/`: non-secret state.
- Keep loopback requests inside `runtimeTransport`. Use 2-space ES modules and
  existing Java style.
- `android/KiraZenbo/`: Native integration; `android/RobotActivityLibrary/`:
  vendor SDK boundary; `integrations/hermes-zenbo/`: one in-process Hermes extension.
- `tests/contracts/`: v2 schema and boundary checks; `tests/hermes/`: recorded
  discovery/lifecycle fixtures and offline failure scenarios. Fixtures must say
  whether they are recorded or synthetic and must not carry real secrets.

```sh
npm test
npm run test:contracts
npm run test:hermes
npm run build
npm run android
cd android
./gradlew :RobotActivityLibrary:testDebugUnitTest :KiraZenbo:testDebugUnitTest
./gradlew :KiraZenbo:assembleDebug
```

Run the plugin's documented Python tests for plugin changes. Run focused checks
first, then the relevant complete suites before handoff. New runtime dependencies
require notices/license updates; regenerate Web licenses when applicable.

JDK 17, Android SDK Platforms 34 and 36, and a compatible locally obtained ASUS
SDK JAR are required. Read `android/ZenboSDK/README.md`; do not distribute its JAR.
Downloaded SDK/ADB tools, `.local` outputs, secret files, raw recordings and APKs
must be Git-ignored.

## Device testing and delivery

Record exact model, firmware, API and ABI before tests. Preserve existing app data
and original Launcher. A successful desktop/JVM/build test does not establish
TLS, GeckoView, microphone, playback, movement or firmware compatibility.

Final delivery uses concise Taiwan Traditional Chinese with changed behavior,
actual tests, device/firmware identity and unverified gates. Do not invent
successful deployments, STT/TTS readiness or hardware results. Include screenshots
for visible UI changes. PRs state protocol breaks and hardware test status.
