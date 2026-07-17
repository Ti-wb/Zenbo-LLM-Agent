# Agent Gateway contract checks

Run the dependency-free validation from the repository root:

```sh
node tests/contracts/validate-contracts.mjs
```

The script parses the OpenAPI document, resolves every local contract `$ref`,
checks the required route/auth/header surface, validates positive and negative
fixtures against the supported JSON Schema 2020-12 keywords, and applies the
tool-name uniqueness rule that JSON Schema cannot express directly.

Files under `fixtures/valid/` must pass. Files under `fixtures/invalid/` must
fail for at least one schema or semantic reason.
