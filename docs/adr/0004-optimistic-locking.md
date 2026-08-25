# ADR 0004 — Optimistic locking

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — `VersionedEntity` on `User`, `Product`, `Order`; HTTP 409 on conflict

## Context

Checkout decrements `Product.stockQuantity`. Concurrent buyers, catalog edits, cancellations, and admin stock updates can race. Holding long pessimistic locks on every read path would increase latency and contention. The specification requires optimistic locking on products and forbids `stockQuantity < 0`.

## Decision

Use a `version` column and JPA `@Version` (via `VersionedEntity`) on **`Product`**, **`Order`**, and **`User`**.

Inventory and order mutations run inside database transactions ([ADR 0009](0009-transactional-checkout.md)). A failed optimistic-lock update maps to HTTP **409**. The PostgreSQL check `stock_quantity >= 0` remains the last guard.

No automatic server-side retry of a lost race; the client may retry.

Cart / cart-item / category / order-item entities are not versioned.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Pessimistic `SELECT … FOR UPDATE` on every product read | Over-locks; cart already uses `FOR UPDATE` only where needed at checkout |
| Serializable isolation globally | Throughput cost; harder to reason about for all endpoints |
| Application-only checks without `@Version` | TOCTOU races under concurrent writers |
| Automatic retry loops in the service | Spec does not require; can amplify load under contention |

## Consequences

- Last-item races cannot produce negative stock when locking, the checkout transaction, and the check constraint work together.
- Clients should treat 409 as retryable for checkout/inventory conflicts.
- Concurrent cart edits rely on `uq_cart_items_cart_id_product_id` and transactional updates, not a cart `version`.
