# ADR 0001 — Modular monolith instead of microservices

- **Status:** Accepted
- **Date:** 2026-08-22
- **Implementation:** complete — single Spring Boot app under `com.example.ecommerce.*`

## Context

CartForge covers authentication, catalog, cart, checkout, orders, and inventory. That surface could be split into independently deployed services, but the specification forbids microservice distribution and related ceremony (message buses, service mesh). The team still needs maintainable domain boundaries.

## Decision

Ship **one** Spring Boot process. Domains are Java packages (`auth`, `user`, `category`, `product`, `cart`, `order`, `inventory`, `common`), not separate deployables.

Administration is a role and route set on owning features — not a separate package or service.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Microservices per domain | Spec non-goal; distributed transactions, duplicate auth, and ops overhead without scale need |
| Modular monolith with separate JARs / classloaders | Extra packaging complexity; package + layering rules already enforce boundaries |
| Modular monolith + async event bus between packages | Unnecessary indirection for in-process calls |

## Consequences

- One artifact, one Flyway schema, one `./mvnw verify` gate.
- Boundaries are package and layering rules, not network contracts.
- Scale-out is Deployment replica count, not per-domain services.
- No Kafka choreography or other distributed-system ceremony as a substitute for transactions.
