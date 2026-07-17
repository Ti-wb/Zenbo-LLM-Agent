# Agent Gateway contract checks

Run the dependency-free validation from the repository root:

```sh
node tests/contracts/validate-contracts.mjs
```

The script parses both Agent Gateway and Local Runtime OpenAPI documents,
resolves contract `$ref` targets, checks each required route/auth/header/cookie
surface, validates positive and negative fixtures against the supported JSON
Schema 2020-12 keywords, and applies semantic checks such as tool-name
uniqueness, one-time bootstrap-token replay rejection, PIN/fingerprint
confirmation equality, redacted settings responses, and canonical local
Gateway/turn state vocabularies.

Files under `fixtures/valid/` must pass. Files under `fixtures/invalid/` must
fail for at least one schema or semantic reason.
