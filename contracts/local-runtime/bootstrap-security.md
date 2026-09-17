# Local renderer bootstrap and security

These requirements are normative for protocol `2.0`.

## Loopback boundary

- The Local Runtime MUST bind only to IPv4 loopback `127.0.0.1:8787`. It MUST
  NOT bind to `0.0.0.0`, a Wi-Fi/LAN address, or an externally reachable
  interface.
- The renderer origin is exactly `http://127.0.0.1:8787`. The runtime MUST NOT
  reflect arbitrary CORS origins or permit credentialed cross-origin access.
- State-changing requests and WebSocket upgrades MUST carry an exact matching
  `Origin`. A missing or different origin is rejected with
  `FORBIDDEN_ORIGIN` before parsing secrets or executing side effects.
- Host and forwarded headers do not widen the trust boundary. The runtime MUST
  ignore forwarded-client-address headers.

## Bootstrap cookie

`POST /api/v2/bootstrap` is the only operation that does not yet have a renderer
session cookie. It is still possession-authenticated by a native-generated
one-time bootstrap token:

1. On every renderer load, Native generates a fresh token with at least 256
   bits of randomness and a 60-second lifetime.
2. Native SHOULD inject it into the initial page URL fragment. It MUST NOT put
   the token in a query parameter or HTTP header. URL fragments are not sent to
   the server.
3. The renderer reads the fragment, immediately removes it from visible/history
   state, and submits it once as `bootstrapToken` in the JSON body of
   `POST /api/v2/bootstrap`.
4. Runtime verifies the token in constant time. Missing, malformed, expired, or
   replayed tokens return `INVALID_BOOTSTRAP_TOKEN`.
5. Every verification attempt consumes the token, whether it succeeds or
   fails. A retry requires a newly loaded, native-issued token.

The bootstrap token MUST NOT appear in responses, WebSocket messages, query
parameters, headers, analytics, or logs. A successful verification creates a
separate opaque renderer session with at least 256 bits of randomness and sets
a host-only cookie equivalent to:

```text
Set-Cookie: zenbo_local_session=<opaque>; Path=/api/v2; HttpOnly; SameSite=Strict; Max-Age=<bounded>
```

The cookie MUST omit `Domain`, MUST NOT be returned in JSON, and MUST NOT be
readable by renderer JavaScript. `Secure` is not asserted in protocol 2.0
because this endpoint is intentionally HTTP loopback; loopback binding and
strict origin validation are therefore mandatory. The lifetime MUST be no more
than 24 hours, and Android service restart or application-data reset invalidates
all prior sessions.

All other HTTP routes require the cookie. The native WebSocket handshake also
authenticates with the same HttpOnly cookie. Authentication failures use a
uniform JSON error for HTTP and reject the WebSocket upgrade with `401`.

Bootstrap is rate-limited and MUST NOT rotate or disclose the Hermes API credential.

## Settings lock

- Initial configuration occurs only through `POST /settings/setup` while
  `setupRequired` is true. The request includes the PIN and confirmation,
  Gateway URL, write-only Hermes API key, trust mode and runtime
  context. Runtime validates and persists the entire request atomically; on any
  failure it stores neither the PIN nor any Gateway setting.
- PIN verification occurs only through `POST /settings/unlock`; PIN values are
  never logged or returned.
- Unlock state is server-side and time-bounded. Updating settings requires a
  currently unlocked renderer session. Testing is allowed either while
  `setupRequired` is true or, after setup, while the renderer is unlocked.
- `GET /settings` returns redacted `hasApiKey` and
  `certificatePinConfigured` booleans plus the selected `trustMode`; it never
  returns the stored Hermes API key, certificate pin, or confirmation value.
- `PUT /settings` may accept a write-only `apiKey`. The native runtime
  stores it using the Android credential facility and excludes it from logs,
  events, status, crash messages, and subsequent responses.
- Selecting `CONFIRMED_SPKI_PIN` during setup or update requires both
  `certificatePin` and `confirmedFingerprint`; they MUST match exactly before
  any settings are persisted. Neither value substitutes for the Hermes API key.
- Underlying model-provider credentials are never accepted; apiKey authenticates only the selected Hermes profile.

The runtime applies retry throttling to setup/unlock attempts and returns
`RATE_LIMITED` with `Retry-After` when the limit is exceeded.

## Motion permission

`PUT /api/v2/motion` is a renderer interaction command, separate from locked
connection settings. It requires the existing renderer cookie and exact Origin,
but no PIN or settings unlock lease. It accepts only a boolean `enabled` field;
unknown fields and non-boolean values fail with `INVALID_REQUEST`.

Native persists this non-secret preference with a default of false. Off blocks
following and looking toward the user; stopping remains available. Credentials,
TLS trust and PIN state cannot be changed through this route. Enabling requires
a successful save; disabling revokes runtime permission before saving. If that
save fails, return `INTERNAL_ERROR` while keeping runtime permission off.

## Gateway test and trust modes

`POST /api/v2/settings/test` is non-persistent and accepts `gatewayUrl`,
`trustMode` and an optional write-only `apiKey`:

- `SYSTEM_TRUST` uses the platform trust store. If a transient Hermes API key is
  supplied, Runtime may authenticate and query gateway capabilities, but MUST
  erase the request value after the probe and MUST NOT save it.
- `CONFIRMED_SPKI_PIN` without a previously confirmed matching SPKI fingerprint
  performs only the TLS certificate probe. Runtime MUST NOT transmit the
  supplied Hermes API key. It returns `confirmationRequired: true`, the observed
  `fingerprint`, `authenticated: false`, and `capabilitiesReceived: false`.

Model-provider API keys are forbidden in this request and every other Local Runtime
request; the allowed apiKey is the Hermes profile access key. Unknown credential fields fail closed as `INVALID_REQUEST`.
