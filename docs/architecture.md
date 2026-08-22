# Architecture

**Status:** planned. This document describes the architecture required by `SPECIFICATIONS.md`. No application modules have been implemented yet.

## Purpose

CartForge is specified as a single Spring Boot application that exposes a versioned REST API for an online store. The design is a modular monolith: business domains are Java packages in one process, not independently deployed services.

The architecture prioritizes maintainability, correctness, testability, and realistic backend practice over artificial distribution.

## Runtime topology

```text
                         ┌─────────────────────┐
                         │       Client        │
                         │ Browser / Postman   │
                         │ Swagger / API Tests │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ Ingress / HTTP      │
                         └──────────┬──────────┘
                                    │
                                    ▼
                  ┌──────────────────────────────────┐
                  │          Spring Boot API         │
                  │                                  │
                  │  Authentication                  │
                  │  Users                           │
                  │  Products                        │
                  │  Categories                      │
                  │  Cart                            │
                  │  Orders                          │
                  │  Inventory                       │
                  │  Administration                 │
                  └───────────────┬──────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                    ▼                           ▼
             ┌─────────────┐             ┌─────────────┐
             │ PostgreSQL  │             │    Redis    │
             │ Persistent  │             │ Cache       │
             │ Data        │             │             │
             └─────────────┘             └─────────────┘
```

- Clients talk HTTP only.
- Kubernetes Ingress is the production HTTP entry (when deployed).
- One application process owns every business domain.
- PostgreSQL is the durable store.
- Redis is a performance optimization for catalog reads.

## Package structure

The specification requires package-by-feature organization:

```text
src/main/java/com/example/ecommerce/
├── auth/
├── user/
├── category/
├── product/
├── cart/
├── order/
├── inventory/
└── common/
```

Typical contents of a feature package: `controller`, `service`, `repository`, `entity`, `dto`, `mapper`.

Inventory is the exception: it has a service and DTOs only. Stock quantity lives on `Product`. There is no inventory HTTP API.

`common` holds configuration, exception handling, security filters, validation, pagination, and logging. It is not a business domain.

Administration appears on the runtime diagram as a capability. The prescribed package tree has no `admin` package. Administrative endpoints belong in the feature that owns the resource (catalog writes on product/category; `/api/v1/admin/orders` on order).

## Layering rules

- Controllers must not contain business logic and must not manage transactions.
- Repositories must not contain business rules.
- Entities must not be exposed through the REST API.
- DTOs are used at API boundaries. Java records are preferred where appropriate.
- Mapping is isolated from controllers. Manual mappers are the default. MapStruct is not introduced unless it is used consistently and provides clear value.
- Business services define transaction boundaries.

## Bounded modules

| Package | Responsibility |
|---|---|
| `auth` | Registration, login, password hashing, JWT issue and validation |
| `user` | User persistence, roles, enabled flag, own-profile access |
| `category` | Catalog grouping, unique name and slug, active flag |
| `product` | SKU, money, stock quantity, optimistic locking, catalog reads |
| `inventory` | Increase, decrease, and restore stock; reject negative stock |
| `cart` | One cart per customer; line items; no inventory reservation |
| `order` | Checkout, snapshots, status transitions, cancellation, admin status |
| `common` | Cross-cutting infrastructure |

## Domain relationships

```text
User 1 ─── 1 Cart
Cart 1 ─── N CartItem
Product 1 ─── N CartItem

User 1 ─── N Order
Order 1 ─── N OrderItem
Product 1 ─── N OrderItem

Category 1 ─── N Product
```

Each customer has at most one cart. The planned interpretation of the 1–1 relationship and “at most one active cart” is a single cart row per user.

Order items store a commercial snapshot (product name, SKU, unit price) so historical orders stay correct after later catalog changes.

## API shape

- All business endpoints use `/api/v1`.
- Public: product and category reads, registration, login.
- Authenticated customers: own cart, own orders, own profile.
- Administrators: catalog writes, order listing, order status changes.
- Ownership is derived from the authenticated principal, never from a client-supplied `userId`.
- JSON fields use camelCase. Dates use ISO-8601. Currency is explicit.
- Errors use a single JSON envelope via `@RestControllerAdvice`. Stack traces are never returned.

## Consistency and caching

Checkout, cancellation, inventory changes, and necessary administrative status updates run in explicit database transactions.

Checkout must: load the cart, reject an empty cart, verify active products and stock, calculate current prices, create the order and items, decrement inventory, clear the cart, and commit atomically. Optimistic locking on products prevents negative stock under concurrent checkouts.

Redis cache keys must be deterministic (`product:{id}`, `category:{id}`, `products:{query-hash}`). Writes invalidate affected keys. If Redis is unavailable, the application logs a warning and reads PostgreSQL.

## Cross-cutting concerns (specified, not built)

- JWT Bearer authentication and role checks.
- Jakarta Bean Validation at the API edge.
- Allowlisted sort fields and enforced maximum page size.
- Request correlation ID (`X-Correlation-ID` or generated).
- Rate limiting on authentication endpoints.
- Idempotency key on order creation.
- Actuator health, readiness, and liveness for Kubernetes.
- Structured logging without passwords, hashes, JWTs, or authorization headers.

## Non-goals

The application must not become a microservice mesh, a payment processor, or a search-engine deployment. See `SPECIFICATIONS.md` section 4.

## Related documents

- [database.md](database.md)
- [security.md](security.md)
- [deployment.md](deployment.md)
- [adr/](adr/)
