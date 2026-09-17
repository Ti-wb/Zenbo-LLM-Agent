# Native-owned Local Runtime events

The renderer connects to `ws://127.0.0.1:8787/api/v2/events?after=<lastSequence>`
with its HttpOnly `zenbo_local_session` cookie and exact Origin
`http://127.0.0.1:8787`. Native binds loopback only. HTTP APIs handle renderer
commands; WebSocket application frames flow Native → renderer only.

All frames use `protocolVersion: "2.0"`, Native-generated UUID `eventId`,
monotonically increasing Native `sequence`, `type`, ISO `timestamp`, and `data`.
Conversation events additionally contain Native UUID `sessionId` and nullable
Native UUID `turnId`. Control events have neither ID. No frame is an unchanged
Hermes SSE event; no local sequence is a remote resume cursor.

## Conversation and controls

Existing conversation type names remain local UI vocabulary: `session.ready`,
`session.snapshot`, `turn.accepted`, `stt.final`, `agent.thinking`, `tool.call`,
`agent.text.final`, `tts.ready`, `turn.completed`, `turn.error`, `turn.cancelled`,
`session.expired`, and `session.closed`. Native translates Hermes lifecycle into
these types. `session.ready`'s legacy `gatewayTime` field means Native's current
ISO timestamp, and `resumedAfter` is a local sequence.

`tts.ready.data.artifacts` is a nonempty ordered array. Each artifact has UUID
`artifactId`, MIME, byte length, SHA-256 and expiry. Native validates all downloaded
bytes before exposing them locally. Web plays in order and reports each artifact
via `POST /api/v2/conversation/playback`; only completion of the final artifact
emits `turn.completed`. Playback receipts are never forwarded to Hermes.

The four controls are `local.gateway.state`, `local.robot.state`,
`local.screen.state`, and `local.interaction`. They also advance the local
sequence. Their strict data schemas exclude keys and authorization values.

Web-owned tool calls use `PUT /api/v2/conversation/tool-calls/{callId}`. Native
validates the allowlist, owner, correlation and deadline before returning the
result on the Hermes plugin channel. There is no renderer tool registration.

## Cancel and recovery

`POST /api/v2/conversation/cancel` accepts optional `turnId` and required reason
`barge_in`, `user_interaction`, `screen_off`, or `sleep`. Native stops local
playback and physical motion, revokes tool authority, emits `turn.cancelled`,
then stops the Hermes run. Cancellation is local immediately; remote run state
must reach terminal before another run starts. During that interval a turn
submission returns HTTP 409 with `TURN_BUSY`.

The renderer reads `/api/v2/status` and `/api/v2/conversation` before opening the
stream, using the authoritative `lastSequence`. Every delivered event advances
that local cursor, including controls. Gaps reconnect from the last applied
sequence; unknown/malformed frames fail closed. If local retained history is too
old, Native sends current state controls, and the renderer refreshes both HTTP
resources before resuming from their authoritative sequence. Neither a recovery
snapshot nor a replay triggers a physical action twice.

Hermes SSE disconnection is independent of renderer stream recovery. Native
queries the run status instead of replaying remote tool progress as executable
commands. After app restart, saved remote run state is checked before authorizing
new work. Stale/cancelled run tools and audio are ignored. Closing SSE never
counts as a successful remote cancellation.
