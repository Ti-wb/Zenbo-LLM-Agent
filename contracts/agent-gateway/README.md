# Zenbo Agent Gateway contract v0.1

This directory defines the network boundary between the Zenbo-K Android client
and an externally operated Agent Gateway. It is a contract only; this
repository does not contain a production gateway implementation.

The normative files are:

- [`openapi.json`](openapi.json): OpenAPI 3.1 HTTP API under `/agent/v1`.
- [`schemas/ws-envelope.schema.json`](schemas/ws-envelope.schema.json): JSON
  Schema for gateway-to-device WebSocket events.
- [`schemas/tool-manifest.schema.json`](schemas/tool-manifest.schema.json): JSON
  Schema for the device tool manifest supplied when a session is created.
- [`ws-protocol.md`](ws-protocol.md): WebSocket authentication, ordering,
  replay, and tool-call lifecycle rules that are not expressible in JSON
  Schema alone.

Protocol version `1.0` uses one device-scoped bearer token for both HTTP and
the native OkHttp WebSocket handshake. Every request also carries
`X-Zenbo-Device-Id` and `X-Zenbo-Protocol: 1.0`. Provider credentials never
cross this boundary and must not be stored on the robot.

The deterministic contract checks can be run without installing additional
packages:

```sh
node tests/contracts/validate-contracts.mjs
```
