# Zenbo Local Runtime contract 1.0

The Local Runtime is the native Android control plane exposed only to the
bundled GeckoView renderer. It serves HTTP and WebSocket traffic on
`127.0.0.1:8787`; it is not a LAN API and is not the external Agent Gateway.

Normative files:

- [`openapi.json`](openapi.json): OpenAPI 3.1 HTTP and WebSocket handshake
  surface under `/api/v1`.
- [`bootstrap-security.md`](bootstrap-security.md): renderer bootstrap cookie,
  origin validation, settings lock, and loopback boundary.
- [`events.md`](events.md): local WebSocket relay, ordering, resume, and
  canonical event-envelope rules.
- [`schemas/event.schema.json`](schemas/event.schema.json): remote envelopes or
  normalized ephemeral local gateway/robot/screen/interaction events.

All JSON responses, including errors, use the same four top-level fields:

```json
{
  "ok": true,
  "requestId": "00000000-0000-4000-8000-000000000000",
  "data": {},
  "error": null
}
```

Binary audio downloads are the sole successful-response exception. Their
error responses still use the JSON envelope.

Web-owned tool results and renderer playback lifecycle updates use the
correlated HTTP routes in `openapi.json`; the Local Runtime validates them and
forwards them to the external Agent Gateway.

Validate both Agent Gateway and Local Runtime contracts with:

```sh
node tests/contracts/validate-contracts.mjs
```
