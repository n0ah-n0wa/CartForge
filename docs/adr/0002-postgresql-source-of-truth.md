# ADR 0002 — PostgreSQL as the source of truth

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — Flyway `V1`–`V10`, Hibernate `ddl-auto=validate`, Testcontainers PostgreSQL

## Context

The API must persist users, catalog, carts, orders, and inventory with relational constraints, ACID transactions, and reviewable migrations. Redis is required for caching but must not become durable business state.

## Decision

**PostgreSQL** is the only durable business store. Flyway owns schema change. Hibernate never creates production tables (`ddl-auto=validate`).

Critical invariants (non-negative price/stock, positive quantities, unique email/SKU/slug/order number, idempotency uniqueness) are enforced in the database as well as in application code.

Order numbers (`order_number_seq`) and checkout idempotency rows live in PostgreSQL inside the checkout transaction. See [ADR 0007](0007-checkout-idempotency.md) and [ADR 0009](0009-transactional-checkout.md).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Redis as primary store | Spec forbids; data loss on flush/outage; weak relational constraints |
| Multiple databases per module | Microservice-shaped complexity inside a monolith |
| Hibernate `ddl-auto=update` / create | Undocumented schema drift; Flyway is the mandated path |
| H2 for integration tests | Would not prove PostgreSQL constraints, sequences, or advisory locks |

## Consequences

- Correctness does not depend on Redis availability.
- Schema review is migration files only.
- Integration tests use PostgreSQL (Testcontainers).
- In-cluster Postgres in Helm/`k8s` is demo infrastructure, not a production DB claim.
