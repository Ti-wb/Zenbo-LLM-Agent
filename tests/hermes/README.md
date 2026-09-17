# Hermes API and plugin boundary tests

`npm run test:hermes` runs dependency-free offline tests. They do not connect to
Hermes, run tools, spend model tokens, or load secrets.

- `fixtures/profile-models.json` and `profile-capabilities.json` are
  **sanitized-recorded** discovery response shapes. Deployment URLs, profile and
  model labels are replaced with generic examples. They describe protocol shape,
  not a currently deployed service or a required model.
- `fixtures/profile-run.json` is a **sanitized-recorded** echo session/run,
  SSE completion, authoritative status, same-key idempotent replay and session
  deletion. Identifiers are anonymized and the request has no model override.
  This fixture proves neither speech nor physical tools.
- Plugin channel and audio cases are **synthetic** contract examples. They test
  strict tool arguments, correlation shape, allowed result vocabulary and audio
  metadata. They do not prove a plugin deployment or robot action succeeded.
- Native JVM tests and plugin Python tests exercise actual request handling,
  lifecycle, cancellation, duplicate rejection and storage behavior. See the
  [main README](../../README.md) and [plugin tests](../../integrations/hermes-zenbo/README.md#tests)
  for commands.

Do not add production keys, deployment addresses or conversational content to
recorded fixtures. Mark recordings as sanitized when values have been replaced,
and keep synthetic scenarios distinct from recorded protocol shapes.
