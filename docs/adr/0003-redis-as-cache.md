# ADR 0003 — Redis as a cache

- **Status:** Accepted (mandated by `SPECIFICATIONS.md` v1.0.0)
- **Date:** 2026-08-22
- **Implementation:** complete (catalog product/category reads)

## Context

Catalog reads are expected to be read-heavy. The specification requires Redis and also requires the API to remain correct if Redis is unavailable. Using Redis as the system of record would violate that rule.

## Decision

Use Spring Cache with Redis for product and category reads first. Cache keys are deterministic (`product:{id}`, `category:{id}`, `products:{query-hash}`). Write paths invalidate affected entries.

On cache failure: log a warning and read PostgreSQL. Redis must not be the source of truth.

Redis may also back authentication rate limiting. That use fails open if Redis is down. See [ADR 0008](0008-auth-rate-limiting.md).

Redis must not be the sole store for checkout idempotency. See [ADR 0002](0002-postgresql-source-of-truth.md) and [ADR 0007](0007-checkout-idempotency.md).

## Consequences

- Local Compose and test environments include a Redis container where cache behavior is tested.
- Cache invalidation for list keys (`products:{query-hash}`) must be designed so writes do not leave stale catalog pages.
- Operators can treat Redis as disposable. Losing it degrades latency, not consistency.
