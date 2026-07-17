# Repository Guidelines

This document guides contributors and AI agents working on the Zenbo K thin
client. The deployable Agent Gateway is a separate system and is not contained
in this repository.

## Architecture and Trust Boundaries

- The Vue Web Renderer is a device UI only. It renders `PixelFace`, owns Pinia
  interaction state, captures speech with VAD Web, and plays validated audio.
- The Renderer talks only to the Android Local Runtime at
  `http://127.0.0.1:8787`. HTTP endpoints live under `/api/v1`; the local event
  stream is `ws://127.0.0.1:8787/api/v1/events` on the same unified server.
- `MainActivity` hosts the bundled Renderer in GeckoView and uses a one-time
  URL-fragment bootstrap token to establish an `HttpOnly` Local Runtime
  session. Do not add a second local port or expose the Local Runtime to LAN.
- `RobotApiService` owns the ASUS `RobotAPI`, `LocalRuntimeServer`,
  `RemoteSessionCoordinator`, and the native `AgentGatewayClient` lifecycle.
- Only the native `AgentGatewayClient` connects to the external Agent Gateway,
  using HTTPS and WSS with protocol version `1.0`. The Renderer must never
  connect to a Gateway, model provider, STT service, or TTS service directly.
- Provider credentials belong only in the separately deployed Gateway. Device
  tokens, TLS trust state, and settings authorization remain native concerns
  and must not be placed in Pinia, `localStorage`, Web assets, or the APK as
  build-time provider configuration.
- `contracts/agent-gateway/` defines the remote boundary and
  `contracts/local-runtime/` defines the Renderer-to-Native boundary. Treat
  both as normative interfaces, not informal documentation.

## Project Structure and Module Organization

- `src/App.vue` - top-level Renderer and interaction-state presentation.
- `src/components/PixelFace.vue` - web-rendered Zenbo face and emotion states.
- `src/components/SettingsOverlay.vue` - Native-backed onboarding and settings.
- `src/composables/` - orchestration hooks for Local Runtime, VAD, and playback.
- `src/services/` - transports, timers, interaction policy, settings shaping,
  and the web-owned tool registry.
- `src/stores/` - Pinia runtime state; secrets must never be stored here.
- `src/utils/` - focused helpers such as WAV/audio processing.
- `public/vad/` - VAD and ONNX Runtime assets copied by Vite.
- `android/KiraZenbo/` - GeckoView launcher, Local Runtime, remote Gateway
  client, credential storage, and robot-tool mediation.
- `android/RobotActivityLibrary/` - adapter around the locally supplied ASUS
  Zenbo SDK.
- `android/ZenboSDK/` - proprietary vendor SDK setup instructions; no SDK
  binary is distributed by this repository.
- `contracts/agent-gateway/` - remote OpenAPI, WebSocket, and tool-manifest
  contracts.
- `contracts/local-runtime/` - loopback OpenAPI, bootstrap security, and event
  contracts.
- `tests/contracts/` - deterministic contract and cross-surface consistency
  checks.
- `tests/fake-gateway/` - dependency-free in-memory Agent Gateway 1.0 test
  harness; it is not production infrastructure.

## Conversation and Event Flow

1. `MainActivity` starts the native service and loads the bundled Renderer from
   the loopback Local Runtime.
2. `useRuntimeController` coordinates Pinia, VAD capture, playback, settings,
   and `runtimeTransport`; it does not perform provider calls.
3. `LocalRuntimeServer` validates the Renderer session and relays conversation
   requests to `RemoteSessionCoordinator`.
4. The native `AgentGatewayClient` uploads turns and maintains the remote
   Gateway event stream. Native normalizes and relays permitted events over the
   unified local WebSocket.
5. Tool calls are validated against the fixed manifest, routed to the native
   robot boundary or the web registry by ownership, and correlated results are
   returned through the Local Runtime.

Preserve event ordering, cursor resume behavior, terminal-event uniqueness,
turn correlation, timeout limits, and playback reporting defined by the
contracts. Do not create an ad-hoc event envelope or bypass Native validation.
If the renderer's cursor predates Native's retained local history, Native emits
only normalized `local.gateway.state` and `local.robot.state` controls; the
Renderer refreshes `/api/v1/status` and `/api/v1/conversation` and resumes from
the returned `lastSequence`. Never synthesize remote `session.ready` or
`session.snapshot` envelopes for local-history recovery.

## Fixed Device Tool Registry

Protocol 1.0 exposes exactly six allowlisted device tools:

| Tool | Owner | Implementation boundary |
| --- | --- | --- |
| `get_system_status` | Native | `RobotGateway` |
| `start_robot_following` | Native | `RobotGateway` |
| `stop_robot_following` | Native | `RobotGateway` |
| `look_at_user` | Native | `RobotGateway` |
| `show_emotion` | Web | `DeviceToolRegistry` / `useRuntimeController` |
| `go_to_sleep` | Web | `DeviceToolRegistry` / `useRuntimeController` |

The fixed set and its schemas are mirrored across `ToolManifestSpec`,
`RobotGateway`, native Gateway validation, `src/services/toolOwnership.js`,
the web registry, Agent Gateway schemas, contract fixtures, and Fake Gateway.
When intentionally changing a tool, update every affected surface and its tests
in the same change. Unknown tools, wrong owners, invalid arguments, expired
deadlines, and duplicate terminal results must remain rejected.

## Build, Test, and Development Commands

From the repository root:

```sh
npm ci
npm run dev
npm run build
npm run android
```

- `npm run dev` starts the Vite development server.
- `npm run build` creates the production Web build in `dist/`.
- `npm run android` builds Web assets into
  `android/KiraZenbo/src/main/assets/app`.
- `npm run android:watch` continuously rebuilds the Android Web assets.
- `npm run licenses:web` regenerates the production Web dependency license
  inventory after dependency or lockfile changes.

The complete repository-level automated checks are:

```sh
npm test
npm run test:contracts
npm run test:gateway
cd android
./gradlew :RobotActivityLibrary:testDebugUnitTest :KiraZenbo:testDebugUnitTest
./gradlew :KiraZenbo:assembleDebug
```

Android builds require JDK 17, Android SDK Platforms 34 and 36, and a locally
obtained robot- and firmware-compatible ASUS Zenbo SDK JAR. Follow
`android/ZenboSDK/README.md`; do not commit or redistribute the vendor JAR.

## Coding Style and Design Rules

- Use 2-space indentation and ES modules for JavaScript and Vue files. Follow
  the existing Java formatting in Android sources.
- Vue components use PascalCase filenames. Composables use `useXxx.js` names.
- Keep presentation in components, orchestration in composables, protocol and
  policy logic in services, and shared non-secret state in Pinia stores.
- Make side effects explicit. Robot motion must pass through the Native-owned
  tool boundary and its safety checks.
- Keep Renderer-to-Native calls inside `runtimeTransport`; do not scatter
  loopback requests across components.
- Keep protocol payloads strict and versioned. Prefer schema changes plus
  fixtures before or alongside implementation changes.
- Do not add provider SDKs, provider `.env` files, or provider API keys to the
  client. New model capabilities belong behind the external Agent Gateway.

## Testing Guidelines

- Vitest tests are colocated as `*.test.js` beside Web code. Add focused tests
  for state transitions, VAD/playback coordination, transports, settings, and
  tool validation when those areas change.
- Run `npm run test:contracts` for any API, event, settings, tool, or security
  boundary change.
- Run `npm run test:gateway` for remote lifecycle, WebSocket, artifact, or tool
  flow changes.
- Run the narrowest relevant Android unit tests for Native changes, then the
  full Android test command above before handoff.
- Hardware-dependent work must include explicit Zenbo K manual test steps and
  name the device/firmware tested. Do not infer physical-device compatibility
  from a successful desktop, JVM, or APK build.

## License and Dependency Hygiene

- Project source is Apache-2.0; preserve `LICENSE`, required notices, and
  prominent modification notices when redistributing modified work.
- The ASUS Zenbo SDK is proprietary and outside the project license. Never
  commit its JAR.
- When runtime dependencies change, update `THIRD_PARTY_NOTICES.md`, regenerate
  `WEB_THIRD_PARTY_LICENSES.txt` when applicable, and verify Android notice
  obligations before distribution.

## Commit and Pull Request Guidelines

- Use Conventional Commits, for example `feat: add gateway reconnect state`,
  `fix: reject expired tool calls`, `docs: update launcher recovery`, or
  `test: cover playback cancellation`.
- Keep commits focused and logically grouped. Do not mix generated artifacts,
  unrelated cleanup, and behavior changes without a clear reason.
- PRs should state purpose, key changes, tests performed, target platforms, and
  hardware validation status. Include screenshots or recordings for visible UI
  changes and call out protocol or compatibility breaks explicitly.
