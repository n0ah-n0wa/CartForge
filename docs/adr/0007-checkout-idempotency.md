# ADR 0007 — Checkout idempotency in PostgreSQL

- **Status:** Accepted
- **Date:** 2026-08-24
- **Implementation:** `checkout_idempotency_keys` (Flyway `V10`), `OrderService` checkout, `Idempotency-Key` on `POST /api/v1/orders`

## Context

`SPECIFICATIONS.md` requires order creation to honor `Idempotency-Key` so the same authenticated user cannot create duplicate orders by retrying. The specification allows PostgreSQL or Redis. Clients may omit the header; checkout without a key stays non-idempotent.

Checkout already mutates orders, order lines, inventory, and the cart in one PostgreSQL transaction. A successful idempotency result must be visible if and only if that transaction commits. A failed checkout must not reserve a later replay of a successful order. Concurrent retries with the same user and key must not create two orders.

## Decision

Store idempotency records in PostgreSQL, not Redis.

A row is written in the same checkout transaction as the order, with `UNIQUE (user_id, idempotency_key)` and a non-null `order_id`. Failed checkouts insert nothing, so a later retry with the same key can proceed.

Concurrent retries for the same user and key take a transaction-scoped advisory lock (`pg_advisory_xact_lock`) before lookup or insert. The unique constraint remains the last guard. Equivalent replays (same SHA-256 fingerprint of the checkout body) return the original order without decrementing stock again. A reused key with a different body is rejected with HTTP 409.

Different users may present the same key; the unique key includes `user_id`.

Redis remains a catalog cache. It is not the source of truth for idempotency, because a cache outage or a write outside the checkout transaction could acknowledge success without an order, or allow a second order after a rollback.

## Consequences

- Duplicate submits survive Redis unavailability and multi-replica deploys.
- Idempotency is scoped to authenticated checkout, not to anonymous or administrative APIs.
- Omitting `Idempotency-Key` does not change existing checkout behavior.
- Clients that reuse a key for a different shipping address receive `IDEMPOTENCY_KEY_REUSED` rather than a second order.
