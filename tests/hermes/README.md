# Hermes API and plugin boundary tests

`npm run test:hermes` runs dependency-free offline tests. They do not connect to
Hermes, run tools, spend model tokens, or load secrets.

- `fixtures/grok-models.json` and `grok-capabilities.json` are actual authenticated
  read-only responses from the configured grok profile. Each records source and
  UTC timestamp, without request Authorization headers. Top-level capability
  `model` may remain the server label `hermes-agent`; the profile model list
  advertises `grok`.
- `fixtures/grok-run.json` records a real echo session/run, SSE completion,
  authoritative status, same-key idempotent replay and successful deletion of
  the test session. Identifiers are consistently anonymized; the request has
  no model override. This fixture proves neither speech nor physical tools.
- Plugin channel and audio cases are **synthetic** contract examples. They test
  strict tool arguments, correlation shape, allowed result vocabulary and audio
  metadata. They do not prove a plugin deployment or robot action succeeded.
- Native JVM tests and plugin Python tests exercise actual request handling,
  lifecycle, cancellation, duplicate rejection and storage behavior. See the
  implementation and device acceptance documentation for those commands.

The old Agent Gateway test server is intentionally removed. These fixtures do
not define or deploy another Gateway. Do not add production keys or conversational
content to recorded fixtures. Live smoke commands must use an explicitly selected
key file, create only their own test resources, and remove those resources after
terminal status.
