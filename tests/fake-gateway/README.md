# Executable Agent Gateway 1.0 fake

This directory contains a dependency-free, in-memory Agent Gateway used to
exercise the Zenbo native client against the contract in
[`contracts/agent-gateway`](../../contracts/agent-gateway). It is a test
harness, not a production gateway. It binds to IPv4 loopback by default and
can serve either cleartext HTTP/WebSocket for deterministic contract tests or
HTTPS/WSS for a real Zenbo Native client.

The fixture identity is public test data and must never be reused outside this
fake:

```text
Authorization: Bearer fake-device-token-for-tests-only
X-Zenbo-Device-Id: zenbo-k-fixture-device
X-Zenbo-Protocol: 1.0
```

## Run

Node.js 20.19 or newer is sufficient; no npm install or third-party WebSocket
package is required.

```sh
node tests/fake-gateway/server.mjs
```

The default endpoint is `http://127.0.0.1:8788/agent/v1`. Select another port
with `--port`:

```sh
node tests/fake-gateway/server.mjs --port 9876
```

Cleartext mode is only for Node contract tests. Zenbo Native rejects HTTP
Gateway URLs, so device testing MUST enable TLS by providing both PEM file
paths:

```sh
node tests/fake-gateway/server.mjs \
  --tls-cert /secure/outside-repo/zenbo-test-cert.pem \
  --tls-key /secure/outside-repo/zenbo-test-key.pem
```

This serves `https://127.0.0.1:8788/agent/v1` and the corresponding `wss://`
session streams. Supplying only one TLS flag fails before the server starts.
Keep the private key outside this repository, restrict its filesystem
permissions, and never commit it. The certificate file may contain the leaf
certificate followed by its intermediate chain.

The fake enforces device-token binding. Its default device ID is the public
fixture shown above; when testing Native, pass the exact stable device ID sent
by that installation:

```sh
node tests/fake-gateway/server.mjs \
  --device-id <native-device-id> \
  --tls-cert /secure/outside-repo/zenbo-test-cert.pem \
  --tls-key /secure/outside-repo/zenbo-test-key.pem
```

### Connecting a Zenbo

For a USB-connected robot, prefer ADB reverse so the fake remains bound to
loopback:

```sh
adb reverse tcp:8788 tcp:8788
```

Configure Native with `https://127.0.0.1:8788/agent/v1`. The certificate MUST
contain `IP:127.0.0.1` in its Subject Alternative Name; the Common Name alone
is insufficient.

For an isolated test LAN, explicitly bind the fake and use a certificate whose
SAN contains the DNS name or LAN IP used in the Native URL:

```sh
node tests/fake-gateway/server.mjs \
  --host 0.0.0.0 \
  --tls-cert /secure/outside-repo/lan-test-cert.pem \
  --tls-key /secure/outside-repo/lan-test-key.pem
```

Do not put the public fixture token on an untrusted network. Restrict the host
firewall to the test robot and never use `0.0.0.0` as the configured Gateway
hostname.

Trust-mode setup differs as follows:

- `SYSTEM_TRUST`: the certificate chain must terminate at a CA trusted by the
  Zenbo Android platform, and its SAN must match the configured host. For a
  private test CA, install that CA into the test device's applicable trust
  store before running Settings Test.
- `CONFIRMED_SPKI_PIN`: the Native pin-only test path may use a self-signed or
  private-CA certificate, but hostname/SAN matching still applies. Run Settings
  Test without sending the device token, verify the displayed
  `sha256/<base64>` SPKI fingerprint out of band, then submit that exact value
  as both `certificatePin` and `confirmedFingerprint`. The fake fixture token
  is supplied only for the later authenticated capabilities/session flow.

Run the deterministic integration suite with Node's built-in test runner:

```sh
node --test tests/fake-gateway/server.test.mjs
```

The independent schema checks remain available as:

```sh
node tests/contracts/validate-contracts.mjs
```

## Simulated lifecycle

- Authenticated capabilities and session create/get/delete routes.
- Idempotent session, turn, cancel, and playback requests.
- JSON text turns and binary-safe multipart `audio/wav` turns returning `202`
  with a correlated `turnId`.
- A retained, ordered WebSocket stream with `session.ready`, replay from
  `after`, ahead-cursor rejection, and stale-cursor `session.snapshot`
  recovery.
- One allowlisted `tool.call` per turn, monotonic HTTP tool results, and
  duplicate-terminal suppression.
- A protected WAV TTS artifact whose MIME type, byte count, SHA-256 metadata,
  RFC-style digest header, and expiry are verified by tests.
- Playback start/terminal reporting and idempotent turn cancellation.

All state is memory-only and is discarded when the process exits. The server
never accepts or stores provider credentials.
