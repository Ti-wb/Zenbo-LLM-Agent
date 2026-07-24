# SQL query generation

`health.sql` is part of the live PostgreSQL readiness path and is generated
into `internal/store/sqlcgen`. Regenerate it with:

```sh
make sqlc-generate
```

CI and local verification should run `make sqlc-check`; it uses sqlc's own
generated-file diff and fails when the committed output is stale. Both targets
pin sqlc rather than depending on whichever binary happens to be installed.

Add stable, independently executable queries here. Transaction-heavy state
machines remain beside their Go code because they intentionally combine
advisory locks, row locks, compare-and-set updates, event sequence allocation,
and job creation in one transaction. Moving fragments of those operations into
standalone generated methods would make their atomicity and lock ordering less
obvious.
