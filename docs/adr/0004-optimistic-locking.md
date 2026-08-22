# ADR 0004 — Optimistic locking

- **Status:** Accepted (mandated by `SPECIFICATIONS.md` v1.0.0)
- **Date:** 2026-08-22
- **Implementation:** not started

## Context

Checkout decrements `Product.stockQuantity`. Concurrent buyers, concurrent product edits, simultaneous cancellation, and administrative stock updates can race. Pessimistic locking or serializable isolation on every request would increase complexity and hold locks longer than necessary. The specification requires optimistic locking on products and forbids `stockQuantity < 0`.

## Decision

Use a `version` column and JPA optimistic locking on `Product` (and on `Order` / `User` where the specification includes `version` or says locking is appropriate).

Checkout and inventory updates run in a database transaction. A failed optimistic-lock operation becomes a controlled API error (HTTP 409). The database check constraint on `stock_quantity >= 0` remains the last guard.

The specification does not require automatic retry of a lost race. The loser receives an application-level conflict.

`@Version` and conflict mapping belong with checkout implementation. A later concurrency phase adds race tests; locking is not deferred until those tests exist.

## Consequences

- Last-item races cannot produce negative stock if locking, transactions, and the check constraint are implemented together.
- Clients may need to retry checkout after a 409.
- Cart rows have no specified `version`. Concurrent cart edits rely on the unique `(cart_id, product_id)` constraint and transactional updates unless a later change adds a cart version.
