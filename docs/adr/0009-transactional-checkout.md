# ADR 0009 — Transactional checkout

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — `OrderService` `@Transactional` checkout; cart `FOR UPDATE`; inventory via `InventoryService`

## Context

Checkout must create an order with line snapshots, decrement stock, clear the cart, and optionally record idempotency — without leaving partial durable state (order without stock debit, or stock debit without order). Controllers must not own transactions; services do.

## Decision

Run checkout as **one PostgreSQL transaction** in `OrderService`:

1. Optional idempotency lock/lookup ([ADR 0007](0007-checkout-idempotency.md)).
2. Lock the user’s cart (`SELECT … FOR UPDATE`).
3. Validate active products and availability.
4. Create order + snapshot line prices/names/SKUs; allocate `order_number` from `order_number_seq`.
5. Decrease stock through `InventoryService` (optimistic `@Version` on `Product` — [ADR 0004](0004-optimistic-locking.md)).
6. Clear cart; save idempotency row when keyed.
7. Commit (or roll back entirely on failure).

Default isolation. No remote I/O (HTTP, Redis writes required for correctness) inside the transaction. Redis cache eviction remains best-effort outside durable correctness.

Customer cancel and inventory restore paths are also transactional.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Separate transactions per step | Partial failure (orphan orders, oversell, uncleared carts) |
| Saga / outbox across services | Microservice pattern; rejected by [ADR 0001](0001-modular-monolith.md) |
| Reserve stock when adding to cart | Spec: cart does not reserve; validation at checkout |
| Serializable isolation for all checkouts | Higher contention cost than optimistic locking + cart lock |

## Consequences

- Either a full successful checkout commits or nothing durable from that attempt remains.
- Concurrent checkouts for the same user serialize on the cart row; cross-user stock races surface as optimistic-lock / stock conflicts (409).
- Controllers stay free of `@Transactional`.
