# ADR 0004 — Optimistic locking (with checkout row locks)

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — `VersionedEntity` on `User`, `Product`, `Order`; inventory mutations also take `SELECT … FOR UPDATE`

## Context

Checkout decrements `Product.stockQuantity`. Concurrent buyers, catalog edits, cancellations, and admin stock updates can race. The specification requires optimistic locking on products and forbids `stockQuantity < 0`. Holding long locks on every catalog read would hurt latency; holding no locks on checkout would allow oversell under contention.

## Decision

Use a `version` column and JPA `@Version` (via `VersionedEntity`) on **`Product`**, **`Order`**, and **`User`**.

For inventory mutations (`InventoryService`), acquire a PostgreSQL **row lock** (`findByIdForUpdate`) then flush so concurrent catalog edits still surface as optimistic conflicts when appropriate. Checkout also locks the cart with `FOR UPDATE` ([ADR 0009](0009-transactional-checkout.md)).

A failed optimistic-lock update maps to HTTP **409**. The PostgreSQL check `stock_quantity >= 0` remains the last guard. No automatic server-side retry of a lost race.

Cart / cart-item / category / order-item entities are not versioned.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Optimistic `@Version` only (no row lock) | Higher oversell window under concurrent checkouts of the same SKU |
| Pessimistic locks on every product read | Over-locks public catalog traffic |
| Serializable isolation globally | Throughput cost across unrelated endpoints |
| Automatic retry loops in the service | Spec does not require; amplifies load under contention |

## Consequences

- Last-item races cannot produce negative stock when row locks, `@Version`, the checkout transaction, and the check constraint work together.
- Clients should treat 409 as retryable for checkout/inventory conflicts.
- Documentation must describe both mechanisms — removing `FOR UPDATE` as an “optimistic-only cleanup” would be an unsafe silent change.
