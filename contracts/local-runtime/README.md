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
binary audio downloads are the sole exception. API key is write-only in settings;
responses expose `hasApiKey` instead. `gatewayUrl` retains the selected Hermes
profile API base. Model and speech configuration are managed exclusively by that
Hermes profile; the device has no model setting.

Motion permission is persisted by Native and defaults to **off**. The renderer
uses `PUT /api/v2/motion` with only `{ "enabled": true|false }`, authenticated by
its cookie and exact Origin; no settings PIN is required. The standard response
contains `data: {motionEnabled,moving}`. Both `/status` and every
`local.robot.state` event (including initial/recovery controls) carry the required
`motionEnabled` boolean. While off, `start_robot_following` and `look_at_user`
return rejected tool results with `MOTION_DISABLED`; `stop_robot_following`
remains available, and a rejected motion tool does not fail the speech turn.
The six device tool schemas remain unchanged. Disabling immediately blocks new
and queued motion and stops app-owned active/pending motion; idle disable does
not call the SDK. Enabling requires a successful persistent save. Storage failure
returns HTTP 500 `INTERNAL_ERROR`; failed persistence of off still leaves runtime
permission off, and the renderer refreshes status instead of restoring permission.

Protocol 2.0 is installed together with its renderer in one APK. Old `/api/v1`
and Agent Gateway settings do not silently fall back or migrate credentials to
another profile; an operator completes Hermes setup explicitly.

Run `npm run test:contracts` to validate schemas, fixtures and boundary invariants.
