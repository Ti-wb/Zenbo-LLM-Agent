# Local Runtime event stream

The bundled renderer connects to:

```text
ws://127.0.0.1:8787/api/v1/events?after=<last-sequence>
```

The browser sends the HttpOnly `zenbo_local_session` cookie automatically. The
handshake MUST also carry `Origin: http://127.0.0.1:8787`; no token is accepted
in the query string. The server is loopback-only and rejects unauthenticated or
cross-origin upgrades.

Each text frame conforms to
[`schemas/event.schema.json`](schemas/event.schema.json): either an unchanged
Agent Gateway envelope or one normalized local control envelope. The Local
Runtime validates remote frames before relay and preserves `protocolVersion`,
`eventId`, `sequence`, `sessionId`, `turnId`, `type`, `timestamp`, and `data`
unchanged. It does not forward malformed or unknown events.

The stream is native-to-renderer only. Protocol 1.0 defines no renderer
application frames; renderer-initiated operations use only the HTTP routes
enumerated by the Local Runtime OpenAPI contract.

Two correlated renderer callbacks are part of that HTTP surface:

- A Web-owned `tool.call` reports `accepted` or its terminal result through
  `PUT /api/v1/conversation/tool-calls/{callId}`. Native verifies that the call
  is current, Web-owned, and allowlisted before forwarding it remotely.
- Audio playback reports `started`, `completed`, or `interrupted` through
  `POST /api/v1/conversation/playback`, correlated by `turnId` and
  `artifactId`.

There is no renderer tool-registration route. Native constructs the fixed six
tool manifest entries and remains authoritative for ownership and safety.

## Local control events

In addition to unchanged remote envelopes, Native emits normalized ephemeral
local controls using exactly
`protocolVersion,eventId,type,timestamp,data`. Local controls have no
`sequence`, `sessionId`, or `turnId` and never participate in remote cursor
replay:

- `local.gateway.state`: exact state `UNCONFIGURED`, `CONNECTING`, `READY`,
  `DEGRADED`, `AUTH_ERROR`, `TLS_ERROR`, `INCOMPATIBLE`, or `OFFLINE`. Its data
  may also include a non-secret `detail` of at most 512 characters and a
  canonical Local Runtime `errorCode` for recovery UI. It MUST NOT include a
  device token, remote URL, Authorization value, or other credential material.
- `local.robot.state`: `ready` and `moving` booleans.
- `local.screen.state`: exact state `ON` or `OFF`.
- `local.interaction`: exact interaction `HEAD_PRESS`.

Screen-off and head-press handling can request local cancellation, but the
event itself is never replayed as a remote conversation event.

## Safety cancellation

`POST /api/v1/conversation/cancel` accepts an optional `turnId`; omission means
the current authoritative turn, including the case where no turn is active.
The required reason is `barge_in`, `user_interaction`, `screen_off`, or `sleep`.
Native first performs a local emergency stop when the robot is moving, then
idempotently cancels the matching remote active turn. No active remote turn is
still a successful response with `data.remoteCancelled` and
`data.robotStopped` describing what occurred.

## Resume and bootstrap

- The renderer supplies the last event sequence it has durably handled in
  `after`; omission means `0`.
- The Local Runtime sends remote events in ascending sequence and suppresses
  duplicate `eventId` values. Ephemeral local controls may interleave without
  advancing or resetting that remote cursor.
- An `after` value ahead of native state rejects the upgrade with `409`.
- When the requested cursor is older than retained local history, the upgrade
  succeeds and recovery follows the Agent Gateway rule: `session.ready`, then
  an authoritative `session.snapshot`; the renderer resets its cursor from
  `snapshot.data.lastSequence` before handling later events.
- A renderer-observed remote sequence gap closes processing and reconnects from
  the last durable cursor. It never guesses across a gap or repeats a physical
  side effect.

Cookie expiry, invalid envelopes, and service shutdown close the stream. The
renderer keeps launcher recovery/settings access available while disconnected.
