# ADR 0007 — Checkout idempotency strategy

- **Status:** Accepted
- **Date:** 2026-08-24
- **Implementation:** complete — `checkout_idempotency_keys` (Flyway `V10`), `Idempotency-Key` on `POST /api/v1/orders`

## Context

Clients may retry checkout after timeouts. Without idempotency, retries create duplicate orders and double stock decrements. The specification requires honoring `Idempotency-Key` for authenticated order creation and allows PostgreSQL or Redis. Omitting the header may remain non-idempotent.

Idempotency success must be visible **if and only if** the checkout transaction commits ([ADR 0009](0009-transactional-checkout.md)).

## Decision

Store idempotency in **PostgreSQL**, not Redis.

| Rule | Behavior |
|---|---|
| Header absent | Existing non-idempotent checkout |
| Header present, first success | Insert row with `user_id`, key, SHA-256 body fingerprint, `order_id` in the same transaction |
| Same user + key + fingerprint | Return original order (HTTP 200), no second stock debit |
| Same user + key, different body | HTTP 409 `IDEMPOTENCY_KEY_REUSED` |
| Concurrent same user + key | `pg_advisory_xact_lock` before lookup/insert; unique constraint is last guard |
| Failed checkout | No row inserted → safe retry |

Scope is per authenticated user (`UNIQUE (user_id, idempotency_key)`).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Redis-only idempotency | Can acknowledge success without a committed order, or lose keys on flush; violates SoT under outage |
| Always-on idempotency without header | Spec allows omitting the key; changes client contract |
| Global (not per-user) keys | Cross-user collisions; weaker isolation |
| Store key before business work | Failed attempts would block legitimate retries |

## Consequences

- Duplicate submits survive Redis outages and multi-replica deploys.
- Idempotency applies to customer checkout, not admin APIs.
- Clients reusing a key for a different shipping address get a conflict, not a second order.
