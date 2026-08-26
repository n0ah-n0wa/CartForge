# ADR 0009 — Transactional checkout

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — `OrderService` `@Transactional` checkout; cart `FOR UPDATE`; inventory via `InventoryService` (`FOR UPDATE` + `@Version`)

## Context

Checkout must create an order with line snapshots, decrement stock, clear the cart, and optionally record idempotency — without leaving partial durable state. Controllers must not own transactions; services do.

## Decision

Run checkout as **one PostgreSQL transaction** in `OrderService`:

1. Optional idempotency lock/lookup ([ADR 0007](0007-checkout-idempotency.md)).
2. Lock the user’s cart (`SELECT … FOR UPDATE`).
3. Validate active products and availability (fail-fast).
4. Allocate `order_number` from `order_number_seq`; create the order header.
5. For each cart line (product-id order): **lock + refresh** the product, **snapshot** name/sku/price from that locked row, then decrease stock (`InventoryService` — [ADR 0004](0004-optimistic-locking.md)).
6. Clear cart; save idempotency row when keyed.
7. Commit (or roll back entirely on failure).

Locking before snapshot ensures concurrent catalog price changes cannot charge a stale cart-graph copy while debiting the refreshed row.

Default isolation. No remote I/O required for correctness inside the transaction. Redis catalog eviction remains best-effort after stock changes (`@CacheEvict` on inventory mutations).

Customer cancel and inventory restore paths are also transactional.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Separate transactions per step | Partial failure (orphan orders, oversell, uncleared carts) |
| Saga / outbox across services | Microservice pattern; rejected by [ADR 0001](0001-modular-monolith.md) |
| Reserve stock when adding to cart | Spec: cart does not reserve; validation at checkout |
| Serializable isolation for all checkouts | Higher contention cost than cart lock + product row locks |

## Consequences

- Either a full successful checkout commits or nothing durable from that attempt remains.
- Concurrent checkouts for the same user serialize on the cart row; cross-user stock races serialize on product rows and surface as conflicts (409) when versions diverge.
- Controllers stay free of `@Transactional`.
