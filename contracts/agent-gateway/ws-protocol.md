# WebSocket session event protocol 1.0

The session stream carries ordered Agent Gateway events to the native Android
client. It is independent from the loopback Local Runtime event stream at
`ws://127.0.0.1:8787/api/v1/events`; protocol 1.0 does not use port 8790.

Normative terms such as **MUST**, **MUST NOT**, and **SHOULD** use the meanings
defined by RFC 2119.

## Connection

The native OkHttp client connects to:

```text
wss://<gateway>/agent/v1/sessions/{sessionId}/events?after={sequence}
```

The handshake MUST include all three headers used by the HTTP API:

```text
Authorization: Bearer <device-scoped-token>
X-Zenbo-Device-Id: <stable-device-id>
X-Zenbo-Protocol: 1.0
```

The bearer token is scoped to one device and this gateway. It MUST NOT be a
provider credential, appear in the URL, or be logged. The gateway MUST reject a
token whose device binding differs from `X-Zenbo-Device-Id`.

The server sends text frames containing one JSON object conforming to
[`schemas/ws-envelope.schema.json`](schemas/ws-envelope.schema.json). Protocol
1.0 defines no device-to-gateway application frames; tool and playback updates
use authenticated HTTP routes. Either peer may use standard WebSocket ping and
pong control frames.

Every application envelope has exactly these top-level fields:

```text
protocolVersion, eventId, sequence, sessionId, turnId, type, timestamp, data
```

`turnId` is a UUID for turn-scoped events and `null` for session-scoped events.
Event-specific fields live under `data`; they are not duplicated at the top
level.

## Ordering and replay

- Retained-event `sequence` is scoped to a session, starts at `1`, and
  increases by exactly one for every retained event.
- `eventId` is globally unique and immutable across replay.
- The device advances its durable cursor only after the event is validated and
  its local side effect or durable handoff has completed.
- On first connection the device omits `after` (equivalent to `after=0`). On
  reconnect it supplies its last durable sequence; the gateway replays all
  retained events whose sequence is greater than `after` in ascending order.
- A replayed `tool.call` MUST retain the same `callId`. The device MUST use that
  ID for duplicate suppression and MUST NOT execute an already-terminal call a
  second time.
- If `after` is ahead of the session cursor, the handshake fails with `409`.
- If `after` is older than retained history, the handshake still succeeds. The
  gateway sends `session.ready` followed by a complete, authoritative
  `session.snapshot`. The snapshot envelope `sequence` and
  `data.lastSequence` identify the same current cursor. After atomically
  replacing its local session state, the client resets its durable cursor to
  `data.lastSequence`; subsequent retained events start after that sequence.
- A sequence gap or invalid envelope is a protocol error. The client MUST stop
  dispatching events, preserve its last good cursor, and reconnect; it MUST NOT
  guess past the gap.

The first frame on every successful connection is the non-retained
`session.ready` control event. Its `sequence` and `data.resumedAfter` both
equal the cursor accepted by the gateway (including `0` on first connection).
It does not advance the durable cursor. Normal replay begins in the next frame,
except that an out-of-retention cursor first receives the authoritative
`session.snapshot` recovery frame described above. WebSocket ping/pong, rather
than application heartbeat events, detects a dead connection.

## Session and turn lifecycle

The session lifecycle is `active -> closing -> closed`; expiry produces the
terminal `expired` state. Only an active session accepts turns and tool-call
updates. Session creation registers the selected agent profile, the typed robot
name/language context, and the complete device tool manifest.

A voice turn follows this normal path:

```text
HTTP 202 -> turn.accepted -> stt.final -> agent.thinking
          -> zero or more tool.call -> agent.text.final
          -> optional tts.ready -> turn.completed
```

`turn.cancelled` and `turn.error` are terminal alternatives. Events received
for an already-terminal turn are ignored after validation and recorded as a
gateway protocol fault; they never trigger a robot action.

The HTTP upload accepts only `audio/wav`, at most 30 seconds and 2 MiB. Output
audio metadata is carried in `tts.ready`; the
client downloads it through the authenticated audio route. It MUST reject an
artifact over 10 MiB or whose byte count, content type, or SHA-256 digest does
not match the event metadata.

## Tool calls

The device registers its complete tool manifest while creating the session.
Only the following approved tools may be present or requested:

```text
get_system_status
start_robot_following
stop_robot_following
look_at_user
show_emotion
go_to_sleep
```

Each manifest entry declares its executor as `owner: native` or `owner: web`
and its side-effect class as `none`, `ui`, or `physical`. Provider-backed tools
execute inside the gateway and are never registered as device tools.

For each `tool.call`, the client MUST:

1. Validate the envelope, tool name/version, argument schema, deadline, and
   local safety policy before dispatch.
2. Reject unknown, expired, malformed, or locally disallowed calls without
   invoking robot APIs.
3. Correlate every HTTP update with `callId` and send monotonic states through
   `PUT /sessions/{sessionId}/tool-calls/{callId}`.
4. Treat `succeeded`, `failed`, and `rejected` as terminal. Timeout and turn
   cancellation are reported as `failed` with error code `TIMEOUT` or
   `TURN_CANCELLED`. Replaying a terminal call returns the stored result instead
   of running it again.

Additional physical-tool safety rules are normative:

- A `physical` tool is allowed only for the current user-initiated active turn;
  stale, background, synthetic, or already-terminal turns MUST be rejected.
- At most one `physical` tool may be executing at a time. A second request is
  rejected without invoking a robot API.
- The client and gateway MUST retain each terminal `callId` and its terminal
  result for at least five minutes for duplicate suppression and replay.
- A local stop action is always available and executable, including while
  disconnected or while another physical tool is running. Remote session,
  tool, or playback state MUST NOT block it.

The serialized UTF-8 JSON representation of `ToolCallUpdate.output` MUST NOT
exceed 16 KiB (16,384 bytes). The client must reject or reduce larger local
results before upload, and the gateway rejects oversized updates with `413`.

The gateway MUST NOT infer success merely because the WebSocket stayed open.
Missing results time out at the call's required `data.timeoutMs`, bounded by
the registered manifest timeout, or the earlier `data.deadlineAt`.

## Playback updates

`tts.ready` identifies an artifact using `data.artifactId`, `data.mimeType`, and
`data.expiresAt`. The client reports `started`, followed by exactly one of
`completed` or `interrupted`, to
`POST /sessions/{sessionId}/playback`, correlating with the envelope `turnId`
and artifact ID. An `interrupted` update includes one of `barge_in`,
`screen_off`, `playback_error`, or `client_cancelled` as its reason. A replayed
artifact already terminal for that turn MUST NOT start audio again.

## Failure behavior

- Reconnect uses exponential backoff with jitter and a bounded maximum delay.
- The device MUST NOT queue stale voice turns indefinitely or replay a turn
  upload without the same idempotency key and `clientTurnId`.
- Gateway unavailability MUST NOT block local launcher controls, a local stop
  action, or recovery to Android settings/original launcher.
- Protocol frames, transcripts, tool arguments, and errors may contain personal
  data. Production logs MUST redact authorization and minimize audio/transcript
  retention.

Suggested WebSocket close codes are `1000` for a normal session close, `1002`
for an invalid protocol message, `1008` for authentication/policy rejection,
and `1011` for a transient gateway failure.
