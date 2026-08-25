# ADR 0003 — Redis as a cache

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — Spring Cache + Redis for catalog; fail-open; auth rate limit also uses Redis ([ADR 0008](0008-auth-rate-limiting.md))

## Context

Catalog reads are read-heavy. The specification requires Redis and requires the API to remain correct when Redis is unavailable. Using Redis as the system of record would violate [ADR 0002](0002-postgresql-source-of-truth.md).

## Decision

Use Spring Cache backed by Redis for product and category reads. Cache names (`CatalogCaches`): `product`, `category`, `products`, `categories` (keys such as `product:{id}`, `categories:active`).

Write paths `@CacheEvict` affected entries (including list caches). On Redis failure: log a warning and read PostgreSQL. Redis is not in the readiness probe group.

Redis must not store checkout idempotency as the sole record. See [ADR 0007](0007-checkout-idempotency.md).

## Alternatives considered

| Alternative | Why not |
|---|---|
| No cache | Acceptable for correctness, but misses a mandated capability and latency goal |
| Redis as write-through source of truth | Violates PostgreSQL-as-SoT; fails under Redis outage |
| In-process Caffeine only | Not shared across replicas; still useful later as L1, not a substitute for the Redis requirement |
| Fail-closed on Redis errors | Would take catalog offline; specification requires fail-open behavior |

## Consequences

- Compose and tests include Redis where cache behavior is exercised.
- Losing Redis degrades latency (and auth burst shielding), not consistency.
- List-cache eviction must stay complete so writes do not leave stale pages.
- Operators may treat Redis as disposable infrastructure.
