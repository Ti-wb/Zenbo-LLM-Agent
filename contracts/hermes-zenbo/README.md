# Hermes Zenbo integration contract

Native calls the existing Hermes API directly for sessions and runs. The single
in-process Zenbo plugin supplies only device tools and speech. These are different
interfaces: plugin protocol `1.0`, Local Runtime protocol `2.0`, and Hermes' native
API version must not be compared as if they were the same protocol.

## Profile routing and authentication

The configured HTTPS profile API base ends in `/p/{profile}/v1`, for example
`https://hermes.example.com/hermes-api/p/robot/v1`. The host and `robot` profile
are placeholders. The selected profile must have its model and STT/TTS
configured; Native must omit `model` entirely from run requests.
Native retains the entire reverse-proxy and profile prefix. There is no fallback
to another profile.

| Capability | Route relative to any reverse-proxy prefix |
| --- | --- |
| Hermes discovery | `GET /p/{profile}/v1/capabilities` |
| Hermes sessions | `/p/{profile}/api/sessions` |
| Hermes runs | `/p/{profile}/v1/runs` |
| Plugin root | `/zenbo/{profile}/v1` |

All routes require `Authorization: Bearer <profile API key>`. Every plugin HTTP
request and WebSocket upgrade additionally requires `X-Zenbo-Device-Id`.
The plugin mounts `/zenbo/{profile}/v1` in the existing Hermes process.
An optional reverse-proxy prefix such as `/hermes-api` precedes every route
above. All remote traffic uses HTTPS/WSS on the configured origin; port 443 is
the default, and explicitly configured HTTPS ports are supported.
Unknown profiles and wrong keys fail; they never fall back to another profile.

`GET /capabilities` at the plugin root returns:

```json
{"pluginVersion":"1.0","tools":["get_system_status","start_robot_following","stop_robot_following","look_at_user","show_emotion","go_to_sleep"],"speech":{"sttConfigured":true,"ttsConfigured":true}}
```

Configured speech is discovery metadata, not proof that synthesis/recognition or
hardware playback has succeeded.

## Device channel

`WSS /device-channel` uses JSON text frames described by
[`schemas/device-channel.schema.json`](schemas/device-channel.schema.json).
Authentication is performed on the upgrade; credentials never occur in frames.
Transport liveness uses WebSocket ping/pong, without an application heartbeat.

1. Native sends `device.bind` with the Hermes `sessionId`; plugin acknowledges
   `device.bound` with authenticated `deviceId` and `sessionId`.
2. Native submits the Hermes run and obtains `run_id`, then sends `run.activate`
   with `sessionId`, `runId`, and Native UUID `turnId`. Plugin returns `run.active`.
3. Tool handlers derive profile/session/run from Hermes execution context, never
   from model-supplied arguments or device IDs. A handler may wait at most five
   seconds for activation, included in its total deadline. It cannot dispatch
   before activation.
4. Plugin sends `tool.call` with a fresh UUID `callId`, all three correlation IDs,
   fixed `toolName`, `toolVersion: "1.0.0"`, strict `arguments`, `timeoutMs: 5000`,
   and ISO `deadlineAt`.
5. Native sends `tool.result`, echoing IDs, with `updatedAt` and `status` of
   `accepted`, `succeeded`, `failed`, or `rejected`; success uses `output`, failure
   uses `error: {code,message}`. Plugin acknowledges with `tool.ack` containing
   `callId` and `status`. The Hermes tool handler waits for a terminal result;
   an `accepted` receipt is not completion.
6. Native sends `run.deactivate` with the three IDs and a reason, receiving
   `run.inactive`. Deactivation fails all pending calls and revokes authority.

A fast run may already be `completed` when its first `run.activate` arrives.
After verifying ownership, the plugin may establish this initial binding for
speech only, with internal authority `enabled=false` and reason `completed`.
It still acknowledges `run.active`; that acknowledgement does not grant tool
authority for a completed run. Runs that are `failed`, `cancelled`, or `stopping`,
and any previously revoked binding, cannot be activated or reactivated.

A duplicate active device/session binding is rejected without replacing the
existing connection. Disconnect, cancellation, expiry, or uncertain results
never replay tools, including idempotent physical tools. Unknown calls, wrong
owners, mismatched correlation IDs, duplicate terminal results, and invalid
arguments are rejected. Errors use `{type:"error",code,message}`.

The exact six tools and their input/result schemas are defined in
[`device-tools.json`](device-tools.json). Native validates ownership and safety;
Web-owned `show_emotion` and `go_to_sleep` still pass through Native mediation.

## Speech and audio

- `POST /audio/transcriptions`: multipart `audio` (16 kHz, mono PCM WAV, at most
  2 MiB and 30 seconds), `language`, `sessionId`, Native UUID `turnId`, and
  `durationMs`; returns `{text,language}`. Raw input is deleted in a `finally`
  path after processing, including failures.
- `POST /audio/speech`: JSON `{text,language,sessionId,runId,turnId}`; returns
  `{artifacts:[...]}`. The array is nonempty and ordered. Each item contains
  UUID `artifactId`, `mimeType` (`audio/mpeg` or `audio/wav`), `byteLength`
  (1–10 MiB), lowercase hexadecimal `sha256`, and ISO `expiresAt`.
- `GET /audio/{artifactId}`: authorization matches profile, key and device.
  Returns the declared MIME, `Content-Length`, and `Digest: sha-256=<base64>`.
  Artifacts expire after at most 30 minutes. Native checks size/hash/MIME before
  exposing them through its loopback audio route.

Speech calls reuse the selected Hermes profile's existing STT/TTS configuration.
The plugin does not add providers, a database, a second server, or a fallback
speech service. REST failures use `{error:{code,message}}` with HTTP 4xx/5xx,
without private paths, credentials, or provider tracebacks. Playback receipts
are local Native state only; no remote playback endpoint is defined.

## Native Hermes API use

Session creation returns `{object:"hermes.session",session:{id,...}}`.
Run creation sends `{input,session_id,instructions}` (no `model` field) and returns
`{run_id,status}`. Progress uses the run SSE endpoint and Hermes events such as
`message.delta`, `run.completed`, `run.failed`, and `run.cancelled`. Polling a
run's authoritative status handles SSE loss; SSE disconnect alone is not a
terminal result. Native uses the stop endpoint for cancellation and blocks a
new run while cancellation remains unresolved.

Hermes session/run IDs are opaque strings. They remain Native/plugin concerns;
Local Runtime exposes its own UUID session/turn IDs and its own event sequence.
Tests must distinguish recorded Hermes fixtures from synthetic fault scenarios.
