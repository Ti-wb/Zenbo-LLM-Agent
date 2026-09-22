# Zenbo Local Runtime contract 2.0

The bundled GeckoView renderer talks only to the Android Native service on
`127.0.0.1:8787`. HTTP and WebSocket routes share `/api/v2`; neither is a LAN API.
Native calls Hermes and owns the local UUIDs, state and sequence. Hermes session
and run IDs, SSE details, credentials and provider settings never reach the Web.

- [`openapi.json`](openapi.json): HTTP, settings, recovery, tool-result and
  playback operations.
- [`bootstrap-security.md`](bootstrap-security.md): one-use bootstrap token,
  HttpOnly cookie, origin checks, PIN authorization and credential storage.
- [`events.md`](events.md): Native-owned events, local replay and recovery.
- [`schemas/event.schema.json`](schemas/event.schema.json): control and
  conversation envelopes.

All JSON responses have exactly `ok`, `requestId`, `data`, `error`. Successful
binary audio and JPEG downloads are the exceptions. API key is write-only in settings;
responses expose `hasApiKey` instead. `gatewayUrl` retains the selected Hermes
profile API base. Model and speech configuration are managed exclusively by that
Hermes profile; the device has no model setting.

Motion permission is persisted by Native and defaults to **off**. The renderer
uses `PUT /api/v2/motion` with only `{ "enabled": true|false }`, authenticated by
its cookie and exact Origin; no settings PIN is required. The standard response
contains `data: {motionEnabled,moving}`. Both `/status` and every
`local.robot.state` event (including initial/recovery controls) carry the required
`motionEnabled` boolean. While off, `start_robot_following`, `look_at_user` and `move_robot`
return rejected tool results with `MOTION_DISABLED`; `stop_robot_following`
remains available, and a rejected motion tool does not fail the speech turn.
The eight allowlisted tool schemas are shared with the Hermes plugin. Disabling immediately blocks new
and queued motion and stops app-owned active/pending motion; idle disable does
not call the SDK. Enabling requires a successful persistent save. Storage failure
returns HTTP 500 `INTERNAL_ERROR`; failed persistence of off still leaves runtime
permission off, and the renderer refreshes status instead of restoring permission.

`/status` and every `local.robot.state` carry `battery: {percentage, charging}`:
percentage is an integer from 0 to 100 or null; charging is boolean or null.
Null means unavailable, never zero battery. These are UI status fields, not
additions to the fixed `get_system_status` tool schema.

`POST /api/v2/conversation/new-session` accepts only `{}` with the renderer cookie,
exact Origin and UUID `Idempotency-Key`. It returns 202 `ConversationResponse`
with cleared text, no active turn, the same local session UUID and a continuing
cursor. Native enters CONNECTING before the response and becomes READY only
after the fresh remote device binding. `/status.turnBusy` remains true for active,
unsettled or rotating work. Busy requests return 409 `TURN_BUSY`; a non-ready
Gateway returns 503 `GATEWAY_OFFLINE`. Reusing an accepted key returns its original
response without rotating again. A failed persistent reset returns 500
`INTERNAL_ERROR` and preserves the old conversation/connection. This command
changes no provider or device settings and never deletes old remote history.

Protocol 2.0 is installed together with its renderer in one APK. Old `/api/v1`
and Agent Gateway settings do not silently fall back or migrate credentials to
another profile; an operator completes Hermes setup explicitly.

Run `npm run test:contracts` to validate schemas, fixtures and boundary invariants.

## Device controls and camera

`GET /device/status` and `PUT /device/settings` expose the Native-owned camera and
automatic speaker-attention switches. `POST /device/action` accepts only an
allowlisted action; directional actions are fixed short movement steps, while
follow/stop use the SDK behavior and app-owned cancellation. `PUT /device/attention`
publishes `idle|listening|speaking` for Native attention; it supplies no invented
speaker direction. The explicit `look_at_user {doa}` tool remains distinct from
Native SDK-derived speaker attention. Fresh SDK direction takes priority; otherwise
attention falls back to face tracking, which cannot identify the speaker among
multiple people. Camera preview suspends SDK visual tracking to avoid sharing the
camera. Every route is under `/api/v2`.

`GET /device/camera/frame` provides a low-rate JPEG preview. `POST
/device/camera/capture` captures a local image without sending it to Hermes and
returns metadata; `GET /device/camera/{artifactId}` serves its temporary bytes.
Camera access requires the local switch and Android permission. The successful
remote `capture_camera` tool emits `camera.captured` with metadata only and a local
UUID turn ID. `ConversationData.cameraCaptures` is optional recovery metadata for
recent captures. No renderer event or snapshot contains `imageBase64`; expired
artifact URLs must be rendered as unavailable rather than indefinitely retried.

`PUT /device/remote {enabled}` requires the admin unlock lease. It controls a
separate, opt-in Android LAN listener on port 8788. That listener exposes no
loopback settings or Hermes credentials; its narrow pairing/control contract is
documented in [remote-control.md](remote-control.md).
