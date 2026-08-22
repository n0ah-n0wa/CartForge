# ADR 0002 — PostgreSQL as the source of truth

- **Status:** Accepted (mandated by `SPECIFICATIONS.md` v1.0.0)
- **Date:** 2026-08-22
- **Implementation:** not started

## Context

The API must persist users, catalog, carts, orders, and inventory with relational constraints, transactions, and migrations. Alternative stores (multiple databases, shards, or cache-as-source-of-truth) would add complexity the specification rejects.

## Decision

PostgreSQL is the only durable business store. Flyway owns schema change. Production Hibernate is validation-only (`ddl-auto=validate` or equivalent). Hibernate must not create production tables.

Critical invariants (non-negative price and stock, positive quantities, unique email/SKU/slug/order number) are enforced in the database as well as in the application.

Order numbers and checkout idempotency records that must survive a Redis outage are planned as PostgreSQL data, allocated or written inside the checkout transaction.

## Consequences

- Application correctness does not depend on Redis availability.
- Schema review happens through immutable migration files.
- Integration tests must use PostgreSQL (Testcontainers), not a substitute such as H2, for migration and constraint proof.
- In-cluster PostgreSQL on Kubernetes, if present, is demo infrastructure, not a claim of a managed production database.
