# Local Runtime 2.0 and Hermes Zenbo contract checks

Run `npm run test:contracts` from the repository root. No running service,
credentials, device or installed Python package is required. All fixtures here
are synthetic contract examples, not recorded device or service observations.

The validator checks the loopback-only v2 route, cookie and origin boundary,
write-only Hermes settings, Native-owned event sequence, ordered audio metadata,
six strict tool schemas, and positive/negative HTTP/event fixtures. It resolves
local schema references and separately checks PIN/fingerprint confirmation and
one-use bootstrap token rejection.

`schema-validator.mjs` implements the JSON Schema keywords used here; it is a
small fixture validator, not a production request validator or a general-purpose
JSON Schema implementation. The separate `npm run test:hermes` covers recorded
Hermes response shapes and plugin channel/audio boundaries. Plugin and Native
runtime tests exercise actual lifecycle and safety implementation.
