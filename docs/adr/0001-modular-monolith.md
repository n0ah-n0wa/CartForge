# ADR 0001 — Modular monolith instead of microservices

- **Status:** Accepted (mandated by `SPECIFICATIONS.md` v1.0.0)
- **Date:** 2026-08-22
- **Implementation:** not started

## Context

The system is a production-style e-commerce API covering authentication, catalog, cart, checkout, orders, and inventory. That surface could be split into independently deployed services. The specification forbids that split.

## Decision

Implement one Spring Boot application. Business domains are Java packages (`auth`, `user`, `category`, `product`, `cart`, `order`, `inventory`, `common`), not separate processes.

Administration is a capability (role and routes), not a microservice and not a required top-level package.

## Consequences

- One deployable artifact, one database schema owned by Flyway, one CI quality gate (`./mvnw verify`).
- Module boundaries are package and layering rules, not network contracts.
- Horizontal scale is replica count on a single Deployment, not per-domain services.
- The project must not introduce Kafka-based microservice choreography or other distributed-system ceremony that the specification lists as a non-goal.
